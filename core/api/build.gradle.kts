plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.openapi.generator)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Generated Retrofit client from the pinned Komga OpenAPI snapshot
// (api/openapi.json, hash pinned in api/openapi.json.sha256).
// The spec is the contract; regenerate deliberately, never edit
// generated output by hand.
openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set(rootProject.file("api/openapi.json").toURI().toString())
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    cleanupOutput.set(true)
    apiPackage.set("com.kmworks.kmreader.core.api")
    modelPackage.set("com.kmworks.kmreader.core.api.model")
    invokerPackage.set("com.kmworks.kmreader.core.api.infrastructure")
    library.set("jvm-retrofit2")
    configOptions.set(
        mapOf(
            "serializationLibrary" to "kotlinx_serialization",
            "dateLibrary" to "string",
            "collectionType" to "list",
            "enumPropertyNaming" to "UPPERCASE",
            "omitGradleWrapper" to "true",
            "useOneOfInterfaces" to "false",
        ),
    )
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/openapi/src/main/kotlin"))
}

tasks.register("patchGeneratedSources") {
    // Stock-output fixes for the kotlin generator:
    // 1. `@Serializable interface` for oneOf bodies is only legal on
    //    *sealed* interfaces in kotlinx-serialization.
    // 2. Properties typed `kotlin.Any` have no serializer and crash the
    //    serialization IR backend. On the wire they are arbitrary JSON,
    //    so JsonElement is the faithful representation.
    dependsOn("openApiGenerate")
    val modelDir = file("build/generated/openapi/src/main/kotlin/com/kmworks/kmreader/core/api/model")
    doLast {
        // 0. The spec's date-condition hierarchy is emitted as subclasses of a
        //    schema the generator maps to the date type alias: a file named
        //    `java.time.LocalDate.kt` declaring `sealed interface java.time.LocalDate`,
        //    which is not a legal Kotlin identifier. Rename the parent to
        //    LocalDateCondition and re-point every subclass.
        val bogusParent = File(modelDir, "java.time.LocalDate.kt")
        if (bogusParent.exists()) {
            val renamed = bogusParent.readText()
                .replace("sealed interface java.time.LocalDate", "sealed interface LocalDateCondition")
            File(modelDir, "LocalDateCondition.kt").writeText(renamed)
            bogusParent.delete()
        }
        modelDir.listFiles { f -> f.extension == "kt" }?.forEach { source ->
            val original = source.readText()
            if (original.contains("java.time.LocalDate")) {
                source.writeText(original.replace("java.time.LocalDate", "LocalDateCondition"))
            }
        }
        // 0b. Condition variants re-declare `operator` from their parent
        //     interface without `override`. Add it for any file whose class
        //     has a supertype and whose operator val lacks the modifier.
        // 0c. Same generator quirk for `value`: SearchOperatorIsNullT /
        //     SearchOperatorIsNotNullT re-declare `value` from
        //     SearchOperatorNumericNullableInteger without `override`.
        val supertypeRegex = Regex("""\)\s*:\s*[A-Z][A-Za-z0-9]*\s*\{""")
        modelDir.listFiles { f -> f.extension == "kt" }?.forEach { source ->
            val original = source.readText()
            if (supertypeRegex.containsMatchIn(original)) {
                val patched = original
                    .replace(
                        "    val `operator`: kotlin.String,",
                        "    override val `operator`: kotlin.String,",
                    )
                    .replace(
                        "    val `operator`: kotlin.String\n",
                        "    override val `operator`: kotlin.String\n",
                    )
                    .replace(
                        "    val `value`: ",
                        "    override val `value`: ",
                    )
                if (patched != original) source.writeText(patched)
            }
        }
        modelDir.listFiles { f -> f.extension == "kt" }?.forEach { source ->
            val lines = source.readLines()
            var changed = false
            val patched = lines.map { line ->
                val trimmed = line.trimStart()
                if ((trimmed.startsWith("val ") || trimmed.startsWith("override val ")) && line.contains("kotlin.Any")) {
                    changed = true
                    line.replace("kotlin.Any", "kotlinx.serialization.json.JsonElement")
                } else if (
                    line.startsWith("interface ") &&
                    source.readText().contains("@Serializable")
                ) {
                    changed = true
                    line.replace(Regex("""(?<!sealed )interface """), "sealed interface ")
                } else {
                    line
                }
            }
            if (changed) source.writeText(patched.joinToString(System.lineSeparator()))
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn("openApiGenerate")
    dependsOn("patchGeneratedSources")
}

dependencies {
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.retrofit.converter.scalars)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}
