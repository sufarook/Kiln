package com.farook.krate.processor

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*

class KrateProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    private val seenTableNames = mutableSetOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver
            .getSymbolsWithAnnotation("com.farook.krate.annotations.DbEntity")
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        symbols.forEach { classDecl ->
            val metadata = EntityVisitor.extract(classDecl, logger) ?: return@forEach

            if (!seenTableNames.add(metadata.tableName)) {
                logger.error(
                    "Krate: duplicate table name '${metadata.tableName}' — " +
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

class KrateProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        KrateProcessor(environment.codeGenerator, environment.logger)
}
