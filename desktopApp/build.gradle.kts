import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
}

compose.desktop {
    application {
        mainClass = "pl.czak.learnlauncher.desktop.MainKt"
        nativeDistributions {
            targetFormats(
                TargetFormat.Deb,
                TargetFormat.Rpm
            )
            packageName = "manga-reader"
            packageVersion = "1.0.0"
        }
    }
}

// The host-Linux libapp7_domain.so is built by the shared module's
// `buildHostNativeLib` task (see :shared:build.gradle.kts).  It lands in
// shared/src/desktopMain/resources/META-INF/native/linux-x86_64/, which
// flows into this app's runtime classpath via the :shared dependency —
// no extra wiring needed here.
