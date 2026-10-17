plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Read .env (KEY=VALUE lines, # comments). Process env wins over .env so
// you can override on the command line: BACKEND_PROVIDER=supabase ./gradlew ...
val envValues: Map<String, String> = run {
    val out = mutableMapOf<String, String>()
    listOf(
        rootProject.file(".env"),
        file("../.env"),
    ).firstOrNull { it.exists() }?.readLines()?.forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        val key = line.substring(0, eq).trim()
        val value = line.substring(eq + 1).trim().removeSurrounding("\"").removeSurrounding("'")
        out[key] = value
    }
    out
}
fun envOrDotenv(key: String): String =
    System.getenv(key)?.takeIf { it.isNotEmpty() } ?: envValues[key].orEmpty()

// Legacy server-config.yaml fallback (kept so old setups don't break).
val serverConfigFile = file("server-config.yaml")
var backendUrl = envOrDotenv("BACKEND_URL")
var queueHost = envOrDotenv("QUEUE_HOST")
var queuePort = envOrDotenv("QUEUE_PORT").toIntOrNull() ?: 0
if ((backendUrl.isEmpty() || queueHost.isEmpty()) && serverConfigFile.exists()) {
    serverConfigFile.readLines().forEach { line ->
        Regex("""^\s*backend_url:\s*"(.+)"""").find(line)?.let {
            if (backendUrl.isEmpty()) backendUrl = it.groupValues[1]
        }
        Regex("""^\s*queue_host:\s*"(.+)"""").find(line)?.let {
            if (queueHost.isEmpty()) queueHost = it.groupValues[1]
        }
        Regex("""^\s*queue_port:\s*(\d+)\s*$""").find(line)?.let {
            if (queuePort == 0) queuePort = it.groupValues[1].toInt()
        }
    }
}

// ask-ip: LATE-BOUND gradle properties (-Paskip.urls=... -Paskip.pubkeys=...),
// falling back to env/.env. Binding real values later is a config change touching
// no Kotlin. urls: ';'-separated. pubkeys: ';'-separated "key_id:base64raw32".
// These are the ETERNAL bootstrap list + verify keys — the only baked network
// facts; the volatile backend address arrives via the signed document.
// ASKIP_PUBKEYS is a LIST so a second signing key (k2) is baked in this same
// rebuild: losing k1 then costs a re-sign, not a rebuild+reinstall.
val askipUrls = (project.findProperty("askip.urls") as String?)?.takeIf { it.isNotEmpty() }
    ?: envOrDotenv("ASKIP_URLS")
val askipPubkeys = (project.findProperty("askip.pubkeys") as String?)?.takeIf { it.isNotEmpty() }
    ?: envOrDotenv("ASKIP_PUBKEYS")
// ask-ip payload cipher key: 32 bytes base64, SECRET (symmetric, baked in APK
// but must NEVER be committed). Late-bound `-Paskip.cipherKey=...` / env
// ASKIP_CIPHER_KEY; empty default. Bernardo generates it on m1 and injects it at
// build time; an empty value keeps the client on baked (no document can decrypt).
val askipCipherKey = (project.findProperty("askip.cipherKey") as String?)?.takeIf { it.isNotEmpty() }
    ?: envOrDotenv("ASKIP_CIPHER_KEY")

val backendProvider = envOrDotenv("BACKEND_PROVIDER")
val tursoUrl = envOrDotenv("TURSO_URL")
val tursoAuthToken = envOrDotenv("TURSO_AUTH_TOKEN")
val supabaseUrl = envOrDotenv("SUPABASE_URL")
val supabaseAnonKey = envOrDotenv("SUPABASE_ANON_KEY")
// e2e only: extra login id(s) accepted by LocalAuthApi (comma-separated). Empty in
// prod builds — set via env for isolated-DB test builds so flows never log in as a
// real user id (see daily rule 11, 2026-08-05).
val e2eExtraUsers = envOrDotenv("E2E_EXTRA_USERS")

android {
    namespace = "pl.czak.learnlauncher.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    // NDK for libapp7_domain.so (JNI shim over domain-core/).  Builds
    // for all four Android ABIs (arm64-v8a, armeabi-v7a, x86_64, x86)
    // matching the pre-fork release lineage that historically installed
    // on every Motorola test device.  The .so files are <20 KB each so
    // the four-ABI matrix only adds ~60 KB to the APK and eliminates
    // "App not compatible" surprises on devices whose primary ABI we'd
    // otherwise have missed.
    ndkVersion = "26.1.10909125"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "me.bstearns.app7"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "BACKEND_URL", "\"${backendUrl}\"")
        buildConfigField("String", "QUEUE_HOST", "\"${queueHost}\"")
        buildConfigField("int", "QUEUE_PORT", "${queuePort}")
        buildConfigField("String", "BACKEND_PROVIDER", "\"${backendProvider}\"")
        buildConfigField("String", "TURSO_URL", "\"${tursoUrl}\"")
        buildConfigField("String", "TURSO_AUTH_TOKEN", "\"${tursoAuthToken}\"")
        buildConfigField("String", "SUPABASE_URL", "\"${supabaseUrl}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${supabaseAnonKey}\"")
        buildConfigField("String", "ASKIP_URLS", "\"${askipUrls}\"")
        buildConfigField("String", "ASKIP_PUBKEYS", "\"${askipPubkeys}\"")
        buildConfigField("String", "ASKIP_CIPHER_KEY", "\"${askipCipherKey}\"")
        buildConfigField("String", "E2E_EXTRA_USERS", "\"${e2eExtraUsers}\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
                cFlags += "-Wall"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Signing pulled from env vars (see .env.template). Defaults below are placeholders;
    // the release/debug keystore files in this folder are text stubs until regenerated.
    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getenv("ANDROID_DEBUG_KEYSTORE_PATH") ?: "debug-learnlauncher.keystore")
            storePassword = System.getenv("ANDROID_DEBUG_STORE_PASSWORD") ?: "PLACEHOLDER"
            keyAlias = System.getenv("ANDROID_DEBUG_KEY_ALIAS") ?: "learnlauncher"
            keyPassword = System.getenv("ANDROID_DEBUG_KEY_PASSWORD") ?: "PLACEHOLDER"
        }
        create("release") {
            storeFile = file(System.getenv("ANDROID_RELEASE_KEYSTORE_PATH") ?: "release-learnlauncher.keystore")
            storePassword = System.getenv("ANDROID_RELEASE_STORE_PASSWORD") ?: "PLACEHOLDER"
            keyAlias = System.getenv("ANDROID_RELEASE_KEY_ALIAS") ?: "learnlauncher_release"
            keyPassword = System.getenv("ANDROID_RELEASE_KEY_PASSWORD") ?: "PLACEHOLDER"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.play.services.auth)
}
