plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.aimalygin.xrayrust.kmp.sample"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.aimalygin.xrayrust.kmp.sample"
        minSdk = libs.versions.android.sample.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(project(":xray-rust-kmp"))
    implementation(libs.kotlinx.coroutines.android)
}
