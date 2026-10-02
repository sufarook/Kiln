package io.github.sufarook.kiln.runtime

actual fun createTestDriver(): KilnDriver = IosKilnDriver(":memory:")
