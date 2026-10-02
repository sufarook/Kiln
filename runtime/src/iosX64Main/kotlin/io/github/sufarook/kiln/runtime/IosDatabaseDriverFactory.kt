package io.github.sufarook.kiln.runtime

import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

class IosDatabaseDriverFactory {
    fun create(dbName: String): KilnDriver {
        val documentsDir = NSFileManager.defaultManager.URLsForDirectory(
            NSDocumentDirectory,
            NSUserDomainMask
        ).first().toString()
        return IosKilnDriver("${documentsDir}$dbName")
    }
}
