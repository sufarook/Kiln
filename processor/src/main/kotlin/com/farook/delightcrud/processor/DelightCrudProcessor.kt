package com.farook.delightcrud.processor

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*

class DelightCrudProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    private val seenTableNames = mutableSetOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver
            .getSymbolsWithAnnotation("com.farook.delightcrud.annotations.DbEntity")
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        symbols.forEach { classDecl ->
            val metadata = EntityVisitor.extract(classDecl, logger) ?: return@forEach

            if (!seenTableNames.add(metadata.tableName)) {
                logger.error(
                    "DelightCRUD: duplicate table name '${metadata.tableName}' — " +
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

class DelightCrudProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        DelightCrudProcessor(environment.codeGenerator, environment.logger)
}
