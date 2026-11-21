import groovy.json.JsonSlurper

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
        // 3. Tolerant decoding for response models.
        //    A Komga server older than this OpenAPI snapshot legitimately omits
        //    properties the snapshot marks required — `notableEntities`, for one,
        //    only exists from Komga 1.29 — and kotlinx-serialization throws
        //    MissingFieldException for a required property without a default.
        //    The app turns that failure into an empty screen. Giving every
        //    non-nullable response-model property a neutral default marks it
        //    optional in the generated descriptor, so an absent field decodes to
        //    an empty value, matching what the iOS client gets from its optional
        //    properties. Request-only schemas stay strict on purpose: the app
        //    always supplies their required properties, and kotlinx omits
        //    default-valued properties when serializing (encodeDefaults is off),
        //    so defaulting them would silently rewrite request bodies.
        //    Required object references stay strict too: no neutral value exists.
        val responseSchemas = responseReachableSchemas(rootProject.file("api/openapi.json"))
        check(responseSchemas.isNotEmpty()) {
            "no response models found in api/openapi.json — the spec layout changed"
        }
        var defaultedProperties = 0
        modelDir.listFiles { f -> f.extension == "kt" }?.forEach { source ->
            val schemaName = source.nameWithoutExtension
            if (schemaName !in responseSchemas) return@forEach
            val enumEntries = enumFirstEntries(source.readLines(), schemaName)
            var inConstructor = false
            var changed = false
            val patched = source.readLines().map { line ->
                val trimmed = line.trimEnd()
                if (!inConstructor && (trimmed == "data class $schemaName (" || trimmed == "data class $schemaName(")) {
                    inConstructor = true
                    line
                } else if (inConstructor && (trimmed == ")" || trimmed == ") {")) {
                    inConstructor = false
                    line
                } else if (inConstructor) {
                    val typedDefault = neutralDefault(trimmed, enumEntries)
                    if (typedDefault == null) {
                        line
                    } else {
                        changed = true
                        defaultedProperties++
                        typedDefault
                    }
                } else {
                    line
                }
            }
            if (changed) source.writeText(patched.joinToString(System.lineSeparator()))
        }
        if (defaultedProperties > 0) {
            println("patchGeneratedSources: defaulted $defaultedProperties response property/ies for tolerant decoding")
        }
    }
}

private val reflectedProperty = Regex("""^(\s+(?:override )?val [^:]+: )(.+?)(,?)$""")
private val reflectedEnumEntry = Regex("""^\s+(?:@SerialName\(value = "[^"]*"\)\s+)?([A-Za-z0-9_]+)\(""")

private val neutralScalars = mapOf(
    "kotlin.String" to "\"\"",
    "kotlin.Boolean" to "false",
    "kotlin.Int" to "0",
    "kotlin.Long" to "0L",
    "kotlin.Double" to "0.0",
    "kotlin.Float" to "0.0f",
    "kotlinx.serialization.json.JsonElement" to "kotlinx.serialization.json.JsonNull",
)

/** The same property line with a neutral default appended, or null to leave it strict. */
private fun neutralDefault(line: String, enumEntries: Map<String, String>): String? {
    val match = reflectedProperty.matchEntire(line) ?: return null
    val type = match.groupValues[2].trim()
    // Already nullable or already defaulted: the generator emitted no strictness.
    if (type.contains('?') || type.contains(" = ")) return null
    val literal = neutralScalars[type]
        ?: when {
            type.startsWith("kotlin.collections.List<") -> "emptyList()"
            type.startsWith("kotlin.collections.Set<") -> "emptySet()"
            type.startsWith("kotlin.collections.Map<") -> "emptyMap()"
            else -> enumEntries[type]?.let { "$type.$it" }
        }
        ?: return null
    return match.groupValues[1] + type + " = " + literal + match.groupValues[3]
}

/** First declared constant of each nested enum declared in a generated model file. */
private fun enumFirstEntries(lines: List<String>, owner: String): Map<String, String> {
    val entries = mutableMapOf<String, String>()
    var pendingEnum: String? = null
    lines.forEach { line ->
        val declaration = Regex("""^\s+enum class (\w+)\(val value:""").find(line)
        if (declaration != null) {
            pendingEnum = declaration.groupValues[1]
            return@forEach
        }
        val enumName = pendingEnum ?: return@forEach
        if (line.trimStart().startsWith("}")) {
            pendingEnum = null
            return@forEach
        }
        val entry = reflectedEnumEntry.find(line)
        if (entry != null) {
            entries["$owner.$enumName"] = entry.groupValues[1]
            pendingEnum = null
        }
    }
    return entries
}

/**
 * Schema names reachable from any API response body in the pinned spec. Only
 * those models are decoded from server payloads, so only those get the tolerant
 * treatment; request bodies are built locally and stay strict.
 */
private fun responseReachableSchemas(specFile: File): Set<String> {
    val spec = JsonSlurper().parse(specFile) as Map<*, *>
    val schemas = ((spec["components"] as? Map<*, *>)?.get("schemas") as? Map<*, *>) ?: return emptySet()
    val operationKeys = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")

    fun collectReferences(node: Any?, into: MutableSet<String>) {
        when (node) {
            is Map<*, *> -> {
                (node["\$ref"] as? String)?.let { into += it.substringAfterLast('/') }
                node.values.forEach { collectReferences(it, into) }
            }
            is List<*> -> node.forEach { collectReferences(it, into) }
        }
    }

    val roots = mutableSetOf<String>()
    (spec["paths"] as? Map<*, *>)?.values?.forEach { pathItem ->
        (pathItem as? Map<*, *>)?.forEach { (key, operation) ->
            if (key !in operationKeys) return@forEach
            val responses = (operation as? Map<*, *>)?.get("responses") ?: return@forEach
            collectReferences(responses, roots)
        }
    }

    val reachable = mutableSetOf<String>()
    val queue = ArrayDeque(roots)
    while (queue.isNotEmpty()) {
        val name = queue.removeFirst()
        if (!reachable.add(name)) continue
        val schema = schemas[name] ?: continue
        val referenced = mutableSetOf<String>()
        collectReferences(schema, referenced)
        referenced.forEach { if (it !in reachable) queue.addLast(it) }
    }
    return reachable
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

    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
