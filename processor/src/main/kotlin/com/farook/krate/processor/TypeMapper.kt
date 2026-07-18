package com.farook.krate.processor

import com.google.devtools.ksp.symbol.KSType

data class SqliteMapping(
    val sqliteType: String,
    val bindMethod: String,
    val cursorMethod: String,
    val defaultValue: String,       // SQL literal for NOT NULL ALTER TABLE ADD COLUMN
    val isBoolean: Boolean = false,
    val isEnum: Boolean = false,
    val isInt: Boolean = false,
    val isFloat: Boolean = false
)

object TypeMapper {

    fun map(ksType: KSType): SqliteMapping {
        val fqn = ksType.declaration.qualifiedName?.asString() ?: ""
        return when (fqn) {
            "kotlin.String"  -> SqliteMapping("TEXT",    "bindString", "getString", "''")
            "kotlin.Int"     -> SqliteMapping("INTEGER", "bindLong",   "getLong",   "0",   isInt = true)
            "kotlin.Long"    -> SqliteMapping("INTEGER", "bindLong",   "getLong",   "0")
            "kotlin.Short"   -> SqliteMapping("INTEGER", "bindLong",   "getLong",   "0",   isInt = true)
            "kotlin.Byte"    -> SqliteMapping("INTEGER", "bindLong",   "getLong",   "0",   isInt = true)
            "kotlin.Float"   -> SqliteMapping("REAL",    "bindDouble", "getDouble", "0.0", isFloat = true)
            "kotlin.Double"  -> SqliteMapping("REAL",    "bindDouble", "getDouble", "0.0")
            "kotlin.Boolean" -> SqliteMapping("INTEGER", "bindLong",   "getLong",   "0",   isBoolean = true)
            "kotlin.ByteArray" -> SqliteMapping("BLOB",  "bindBytes",  "getBytes",  "X''")
            "kotlinx.datetime.LocalDate",
            "kotlinx.datetime.LocalDateTime",
            "kotlinx.datetime.Instant" -> SqliteMapping("TEXT", "bindString", "getString", "''")
            else -> {
                val classDecl = ksType.declaration as? com.google.devtools.ksp.symbol.KSClassDeclaration
                if (classDecl?.classKind == com.google.devtools.ksp.symbol.ClassKind.ENUM_CLASS) {
                    SqliteMapping("TEXT", "bindString", "getString", "''", isEnum = true)
                } else {
                    error(
                        "Krate: type '$fqn' is not supported. " +
                        "Use a primitive type, String, ByteArray, Boolean, an enum, " +
                        "kotlinx.datetime types, or annotate the property with @Ignore."
                    )
                }
            }
        }
    }
}
