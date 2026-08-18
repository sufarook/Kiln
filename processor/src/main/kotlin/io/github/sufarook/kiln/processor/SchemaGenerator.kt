package io.github.sufarook.kiln.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSFile
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeSpec

/**
 * Generates a single `KilnSchema` object per compilation so callers can create
 * every table in one call instead of one `createTable()` per repository:
 *
 * ```kotlin
 * KilnSchema.createAll(driver)
 * ```
 *
 * Per-repository `createTable()` is still generated and still works — this is
 * purely additive, for the common case where you want the whole schema ready
 * at startup and don't want to edit an init block every time you add an entity.
 */
object SchemaGenerator {

    private val SQL_DRIVER = ClassName("app.cash.sqldelight.db", "SqlDriver")

    fun generate(
        entities: List<EntityMetadata>,
        sourceFiles: List<KSFile>,
        codeGenerator: CodeGenerator
    ) {
        if (entities.isEmpty()) return

        val packageName = commonPackagePrefix(entities.map { it.packageName })

        // Sorted for reproducible output. Creation order is irrelevant to SQLite
        // here because Kiln emits no FOREIGN KEY constraints — nothing in the
        // schema makes one table depend on another existing first.
        val ordered = entities.sortedBy { it.entityClassName }

        val createAll = FunSpec.builder("createAll")
            .addKdoc(
                "Creates every Kiln table in this module and runs auto-migration on each.\n" +
                    "\n" +
                    "Safe to call on every launch: each table is created only if missing, and\n" +
                    "the migrator no-ops when the schema already matches. Equivalent to calling\n" +
                    "`createTable()` on all %L generated repositories.\n",
                ordered.size
            )
            .addParameter("driver", SQL_DRIVER)

        ordered.forEach { meta ->
            val repository = ClassName(meta.packageName, "${meta.entityClassName}Repository")
            createAll.addStatement("%T(driver).createTable()", repository)
        }

        val schemaObject = TypeSpec.objectBuilder("KilnSchema")
            .addKdoc(
                "Every table Kiln generated for this module.\n" +
                    "\n" +
                    "Generated — do not edit. Adding a new `@DbEntity` updates this\n" +
                    "automatically on the next build, so startup code never has to change.\n"
            )
            .addFunction(createAll.build())
            .build()

        val file = FileSpec.builder(packageName, "KilnSchema")
            .addType(schemaObject)
            .build()

        // Aggregating: this one file is derived from *all* entity sources, so any
        // added/removed entity must invalidate and regenerate it.
        val output = codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = true, *sourceFiles.toTypedArray()),
            packageName = packageName,
            fileName = "KilnSchema"
        )
        output.writer().use { file.writeTo(it) }
    }

    /**
     * The deepest package shared by every entity, so `KilnSchema` lands somewhere
     * predictable and importable. Entities in `com.app.data` and `com.app.model`
     * yield `com.app`.
     *
     * Falls back to the first package alphabetically when there is no shared root
     * (e.g. `com.a` and `org.b`) — the default package is not a usable location.
     */
    internal fun commonPackagePrefix(packages: List<String>): String {
        if (packages.isEmpty()) return ""
        val segmentLists = packages.map { it.split(".") }
        val first = segmentLists.first()
        var shared = 0
        while (shared < first.size &&
            segmentLists.all { it.size > shared && it[shared] == first[shared] }
        ) {
            shared++
        }
        val prefix = first.take(shared).joinToString(".")
        return prefix.ifEmpty { packages.min() }
    }
}
