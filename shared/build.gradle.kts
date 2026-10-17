import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
}

kotlin {
    // ── Android target (AGP 9 style) ──
    androidLibrary {
        namespace = "pl.czak.learnlauncher.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // ── Desktop (JVM) target ──
    jvm("desktop")

    // ── iOS targets ──
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    // ── Dependencies ──
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            api(libs.ktor.client.core) // expose HttpClient type through composition root
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.cio)
            }
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.room.runtime)
            implementation(libs.room.ktx)
            implementation(libs.gson)
            implementation(libs.play.services.auth)
            implementation(libs.ktor.client.okhttp)
            // ask-ip: RFC 8032 Ed25519 verify (java.security "Ed25519" is not
            // available on older Android). Same dep app11 uses; no code shared.
            implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
}

// ─────────────────────────────────────────────────────────────────────────
//  Native domain library (Phase 4/5 — desktop JVM).
//
//  Compiles domain-core/{src,jni}/*.c into a host-Linux libapp7_domain.so
//  and drops it at
//    src/desktopMain/resources/META-INF/native/linux-x86_64/libapp7_domain.so
//  This source set's resources flow into the desktop jar AND onto the
//  desktopTest runtime classpath, so:
//    - :desktopApp:run picks it up via the :shared dependency
//    - :shared:desktopTest can load it directly for ParityTest
//  One artifact, two consumers.  Skips CMake (host gcc is fine for 5 .c
//  files; CMake stays the build-of-record for Android only because AGP
//  requires it).
// ─────────────────────────────────────────────────────────────────────────

val javaHomeForJni: String =
    System.getenv("JAVA_HOME")
        ?: System.getProperty("java.home")
            ?.let { javaHome ->
                if (file("$javaHome/include/jni.h").exists()) javaHome
                else file(javaHome).parentFile?.absolutePath ?: javaHome
            }
        ?: error("JAVA_HOME unset and System.getProperty(\"java.home\") missing")

val nativeOutDir = layout.projectDirectory.dir("src/desktopMain/resources/META-INF/native/linux-x86_64")
val nativeOutFile = nativeOutDir.file("libapp7_domain.so")
val domainCoreDir = rootProject.layout.projectDirectory.dir("domain-core")
val domainRustDir = rootProject.layout.projectDirectory.dir("domain-rust")
val domainRustArchive = domainRustDir.file("target/release/libapp7_domain.a")

// The domain is Rust now (domain-rust/ is the algebraic source of truth);
// domain-core keeps only the frozen header + the JNI shim + the round_trip
// oracle.  cargo produces the staticlib, gcc links shim + archive into the
// same .so path as before.
val cargoBuildDomain by tasks.registering(Exec::class) {
    group = "build"
    description = "cargo build --release the Rust domain staticlib (no_std, dependency-free)."
    inputs.dir(domainRustDir.dir("src"))
    inputs.file(domainRustDir.file("Cargo.toml"))
    outputs.file(domainRustArchive)
    commandLine(
        "cargo", "build", "--release",
        "--manifest-path", domainRustDir.file("Cargo.toml").asFile.absolutePath,
    )
}

val buildHostNativeLib by tasks.registering(Exec::class) {
    group = "build"
    description = "Link JNI shim + Rust domain staticlib into libapp7_domain.so for the desktop JVM target."
    dependsOn(cargoBuildDomain)

    inputs.file(domainRustArchive)
    inputs.dir(domainCoreDir.dir("include"))
    inputs.dir(domainCoreDir.dir("jni"))
    outputs.file(nativeOutFile)

    doFirst { nativeOutDir.asFile.mkdirs() }

    val jniIncludeRoot = "$javaHomeForJni/include"
    val jniIncludePlatform = "$javaHomeForJni/include/linux"

    commandLine(
        "gcc",
        "-shared", "-fPIC", "-Os", "-Wall", "-Wextra", "-Werror", "-std=c11",
        "-fvisibility=hidden",
        "-I", domainCoreDir.dir("include").asFile.absolutePath,
        "-I", jniIncludeRoot,
        "-I", jniIncludePlatform,
        "-o", nativeOutFile.asFile.absolutePath,
        domainCoreDir.file("jni/app7_domain_jni.c").asFile.absolutePath,
        domainRustArchive.asFile.absolutePath,
    )
}

// Wire into every consumer that needs the .so on its classpath.
tasks.matching { it.name == "desktopProcessResources" || it.name == "desktopTestProcessResources" }
    .configureEach { dependsOn(buildHostNativeLib) }
// Also gate :desktopJar / :desktopTest directly in case the resources
// processor names diverge across plugin versions.
tasks.matching { it.name == "desktopJar" || it.name == "desktopTest" }
    .configureEach { dependsOn(buildHostNativeLib) }
