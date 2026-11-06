import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

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

fun mobileEnvValue(key: String): Provider<String> = mobileEnv.map { it.getValue(key) }

/**
 * Downloads [url] into [destination] and fails on a sha256 mismatch. Network access happens only
 * when the task runs; an unchanged url/hash with the file in place is up to date.
 */
abstract class DownloadVerifiedFile : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val destination: RegularFileProperty

    @TaskAction
    fun download() {
        val target = destination.get().asFile
        val partial = File(target.parentFile, "${target.name}.part")
        target.parentFile.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        URI(url.get()).toURL().openStream().use { input ->
            DigestInputStream(input, digest).use { hashed -> partial.outputStream().use(hashed::copyTo) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != sha256.get()) {
            partial.delete()
            throw GradleException("${url.get()}: sha256 $actual, release/mobile.env pins ${sha256.get()}")
        }
        check(partial.renameTo(target) || (target.delete() && partial.renameTo(target))) { "cannot move $partial" }
    }
}

val xrayMobileDir = layout.buildDirectory.dir("xray-rust-mobile/$xrayRustMobileVersion")
val ffiIncludeDir = xrayMobileDir.map { it.dir("include") }
val xcframeworkRoot = xrayMobileDir.map { it.dir("xcframework") }
val xcframeworkDir = xcframeworkRoot.map { it.dir("XrayRust.xcframework") }

fun rawSourceUrl(pathKey: String): Provider<String> = mobileEnv.map { env ->
    "${env.getValue("XRAY_RUST_MOBILE_RAW_BASE_URL")}/${env.getValue("XRAY_RUST_MOBILE_COMMIT")}/${env.getValue(pathKey)}"
}

val downloadXrayFfiHeader = tasks.register<DownloadVerifiedFile>("downloadXrayFfiHeader") {
    description = "Downloads xray_ffi.h of the pinned xray-rust-mobile commit and checks it against release/mobile.env."
    url = rawSourceUrl("XRAY_RUST_FFI_HEADER_PATH")
    sha256 = mobileEnvValue("XRAY_RUST_FFI_HEADER_SHA256")
    destination = ffiIncludeDir.map { it.file("xray_ffi.h") }
}

val downloadXrayModulemap = tasks.register<DownloadVerifiedFile>("downloadXrayModulemap") {
    description = "Downloads the XrayRust module.modulemap of the pinned commit and checks its sha256."
    url = rawSourceUrl("XRAY_RUST_MODULEMAP_PATH")
    sha256 = mobileEnvValue("XRAY_RUST_MODULEMAP_SHA256")
    destination = ffiIncludeDir.map { it.file("module.modulemap") }
}

val downloadXrayXcframework = tasks.register<DownloadVerifiedFile>("downloadXrayXcframework") {
    description = "Downloads XrayRust.xcframework.zip of the pinned release and checks the Package.swift checksum."
    url = mobileEnvValue("XRAY_RUST_MOBILE_XCFRAMEWORK_URL")
    sha256 = mobileEnvValue("XRAY_RUST_MOBILE_XCFRAMEWORK_ZIP_SHA256")
    destination = xrayMobileDir.map { it.file("XrayRust.xcframework.zip") }
}

val extractXrayXcframework = tasks.register<Sync>("extractXrayXcframework") {
    description = "Unpacks the verified XrayRust.xcframework (bare libxray_ffi.a slices)."
    from(zipTree(downloadXrayXcframework.flatMap { it.destination }))
    into(xcframeworkRoot)
}

// Kotlin/Native target -> slice directory of XrayRust.xcframework.
val xcframeworkSlices = mapOf(
    "iosArm64" to "ios-arm64",
    "iosSimulatorArm64" to "ios-arm64_x86_64-simulator",
)

fun KotlinNativeTarget.bindXrayFfi() {
    val slice = xcframeworkDir.get().dir(xcframeworkSlices.getValue(targetName)).asFile
    val interop = compilations.getByName("main").cinterops.create("xrayffi") {
        definitionFile = project.file("src/nativeInterop/cinterop/xrayffi.def")
        includeDirs(ffiIncludeDir.get().asFile)
        extraOpts("-libraryPath", slice.path)
    }
    tasks.named(interop.interopProcessingTaskName) {
        dependsOn(downloadXrayFfiHeader, downloadXrayModulemap, extractXrayXcframework)
    }
    binaries.framework {
        baseName = "XrayRustKmp"
    }
}

kotlin {
    explicitApi()

    android {
        namespace = "io.github.aimalygin.xrayrust.kmp"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
    }
    iosArm64().bindXrayFfi()
    iosSimulatorArm64().bindXrayFfi()

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
