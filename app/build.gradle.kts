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

android {
    namespace = "com.kmworks.kmreader"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.kmworks.kmreader"
        minSdk = 28
        targetSdk = 36
        versionCode = 23
        versionName = "2.6.6"
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
    lint {
        // The lint gate: errors fail the build, and CI runs lintDebug on every push and PR.
        // The baseline freezes the findings that predate the gate — fix them opportunistically
        // and lint itself shrinks the file; any NEW issue fails the build until it is fixed or
        // deliberately baselined (`./gradlew :app:lintDebug` regenerates the file).
        baseline = file("lint-baseline.xml")
        abortOnError = true
        disable += setOf(
            // The UI ships in English and Chinese only, and Chinese has no plural quantity forms,
            // so the "use a plurals element" suggestion is pure noise for this project.
            "PluralsCandidate",
            // Dependency-upgrade reminders: versions in the catalog are pinned deliberately and
            // bumped by hand, not flagged on every build.
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
            "UseTomlInstead",
            "OldTargetApi",
        )
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
    testImplementation(libs.junit)
    // JVM org.json for unit tests that exercise org.json.JSONObject based models (EpubOverlayPreferences)
    testImplementation("org.json:json:20250517")
}
