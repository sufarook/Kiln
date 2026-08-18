package io.github.sufarook.kiln.processor

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*

class KilnProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    private val seenTableNames = mutableSetOf<String>()
    private var schemaGenerated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver
            .getSymbolsWithAnnotation("io.github.sufarook.kiln.annotations.DbEntity")
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        val entities = mutableListOf<EntityMetadata>()
        val sourceFiles = linkedSetOf<KSFile>()

        symbols.forEach { classDecl ->
            val metadata = EntityVisitor.extract(classDecl, logger) ?: return@forEach

            if (!seenTableNames.add(metadata.tableName)) {
                logger.error(
                    "Kiln: duplicate table name '${metadata.tableName}' — " +
                        "set a distinct tableName in @DbEntity on '${metadata.entityClassName}'",
                    classDecl
                )
                return@forEach
            }

            RepositoryGenerator.generate(metadata, codeGenerator)
            entities += metadata
            classDecl.containingFile?.let { sourceFiles += it }
        }

        // @DbEntity classes are always hand-written, so they all resolve in the
        // first round — later rounds only see Kiln's own generated files, which
        // carry no annotations. Emitting the aggregate once here (rather than in
        // finish()) keeps the file inside a normal processing round.
        if (entities.isNotEmpty() && !schemaGenerated) {
            SchemaGenerator.generate(entities, sourceFiles.toList(), codeGenerator)
            schemaGenerated = true
        }

        return emptyList()
    }
}

class KilnProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = KilnProcessor(environment.codeGenerator, environment.logger)
}
