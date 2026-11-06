import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

group = "io.github.aimalygin"
version = "0.1.0-SNAPSHOT"

val mobileEnv: Provider<Map<String, String>> = providers
    .fileContents(isolated.rootProject.projectDirectory.file("release/mobile.env"))
    .asText
    .map(::parseEnv)

fun parseEnv(text: String): Map<String, String> = text.lineSequence()
    .map(String::trim)
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .associate { it.substringBefore('=') to it.substringAfter('=') }

val xrayRustMobileVersion: String = mobileEnv.get().getValue("XRAY_RUST_MOBILE_VERSION")

kotlin {
    explicitApi()

    android {
        namespace = "io.github.aimalygin.xrayrust.kmp"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            // "!!" pins the version strictly to release/mobile.env.
            api("${libs.xray.rust.mobile.get().module}:$xrayRustMobileVersion!!")
        }
    }
}

val pinnedAar = configurations.detachedConfiguration(
    dependencies.create("io.github.aimalygin:xray-rust-mobile:$xrayRustMobileVersion@aar"),
)

val verifyXrayRustMobileAar = tasks.register("verifyXrayRustMobileAar") {
    description = "Checks the resolved xray-rust-mobile AAR against XRAY_RUST_MOBILE_AAR_SHA256 in release/mobile.env."
    group = "verification"
    val aarFiles = pinnedAar.incoming.artifacts.resolvedArtifacts.map { artifacts -> artifacts.map { it.file } }
    val expected = mobileEnv.map { it.getValue("XRAY_RUST_MOBILE_AAR_SHA256") }
    inputs.files(aarFiles)
    inputs.property("expectedSha256", expected)
    doLast {
        val aar = aarFiles.get().single()
        val actual = MessageDigest.getInstance("SHA-256").digest(aar.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(actual == expected.get()) { "${aar.name}: sha256 $actual, release/mobile.env pins ${expected.get()}" }
    }
}

tasks.named("check") { dependsOn(verifyXrayRustMobileAar) }
