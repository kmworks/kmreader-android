import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Release signing credentials.
 *
 * CI decodes the `KEYSTORE_BASE64` secret into a temporary keystore and exports
 * the four variables below; a local build can instead use an untracked
 * `keystore.properties` at the repository root. When neither is present the
 * release variant stays unsigned, and debug builds are unaffected.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingCredential(env: String, property: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(property)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingCredential("KEYSTORE_FILE", "storeFile")
val releaseStorePassword = signingCredential("KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingCredential("KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingCredential("KEY_PASSWORD", "keyPassword") ?: releaseStorePassword
val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null

/**
 * ncnn android-vulkan prebuilt SDK (for waifu2x upscaling, see docs/waifu2x-upscaling-plan.md).
 * The SDK is NOT committed to git; `fetchNcnnSdk` downloads + sha256-verifies + extracts it
 * into third_party/ncnn/ before any native build runs. Bump the version/hash together.
 */
val ncnnVersion = "20260113"
val ncnnSha256 = "41944630a7b305b190e7caea4b405bc6d103c6383dc4988e9e85616058e3cccd"
val ncnnSdkDir = rootProject.file("third_party/ncnn/ncnn-$ncnnVersion-android-vulkan")

val fetchNcnnSdk = tasks.register("fetchNcnnSdk") {
    val zipFile = rootProject.file("third_party/ncnn/ncnn-$ncnnVersion-android-vulkan.zip")
    val marker = File(ncnnSdkDir, ".extracted-v$ncnnVersion")
    outputs.upToDateWhen { marker.exists() }
    doLast {
        if (marker.exists()) return@doLast
        if (!zipFile.exists()) {
            zipFile.parentFile.mkdirs()
            logger.lifecycle("Downloading ncnn $ncnnVersion android-vulkan SDK...")
            URI("https://github.com/Tencent/ncnn/releases/download/$ncnnVersion/ncnn-$ncnnVersion-android-vulkan.zip")
                .toURL().openStream().use { input ->
                    zipFile.outputStream().use { input.copyTo(it) }
                }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        zipFile.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual == ncnnSha256) {
            zipFile.delete()
            "ncnn SDK sha256 mismatch: expected $ncnnSha256, got $actual (zip deleted, retry to re-download)"
        }
        copy {
            from(zipTree(zipFile))
            into(zipFile.parentFile)
        }
        marker.writeText("ok")
        zipFile.delete()
        logger.lifecycle("ncnn SDK ready at $ncnnSdkDir")
    }
}

android {
    namespace = "com.kmworks.kmreader"
    compileSdk = 37
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "com.kmworks.kmreader"
        minSdk = 28
        targetSdk = 36
        versionCode = 12
        versionName = "2.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Real devices only; x86 emulators have no reliable Vulkan (see docs/waifu2x-upscaling-plan.md §4.2)
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.named("preBuild") { dependsOn(fetchNcnnSdk) }

dependencies {
    implementation(project(":core:api"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.haze)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.retrofit.converter.scalars)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // PDF text layer (select/copy/search): official Jetpack PdfView + sandboxed document loading
    implementation("androidx.pdf:pdf-viewer:1.0.0-beta01")
    implementation("androidx.pdf:pdf-document-service:1.0.0-beta01")
    // PDF outline/TOC extraction only (androidx.pdf beta01 has no outline API; see
    // docs/pdf-native-reader-plan.md §3). Drop once androidx.pdf ships an outline API.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation(libs.junit)
    // JVM org.json for unit tests that exercise org.json.JSONObject based models (EpubOverlayPreferences)
    testImplementation("org.json:json:20250517")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
