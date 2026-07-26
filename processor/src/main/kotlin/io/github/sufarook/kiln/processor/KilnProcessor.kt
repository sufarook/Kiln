package io.github.sufarook.kiln.processor

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*

class KilnProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    private val seenTableNames = mutableSetOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver
            .getSymbolsWithAnnotation("io.github.sufarook.kiln.annotations.DbEntity")
            .filterIsInstance<KSClassDeclaration>()
            .toList()

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
        }

        return emptyList()
    }
}

class KilnProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        KilnProcessor(environment.codeGenerator, environment.logger)
}
