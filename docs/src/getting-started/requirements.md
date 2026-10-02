# Requirements

## Toolchain

| Tool | Minimum version | Notes |
|------|----------------|-------|
| Kotlin | **2.0.0** | KSP 2.x requires Kotlin 2.x |
| KSP | **2.0.0-1.0.21** | Applied automatically by the plugin |
| Gradle | **8.0** | |
| Android Gradle Plugin | **8.0** | |
| Java / JVM target | **17** | Kiln's artifacts are compiled to Java 17 bytecode. AGP 8.x already requires Gradle to run on JDK 17+, so Android consumers need no change. |

## Android

| Setting | Value |
|---------|-------|
| `compileSdk` | **36** |
| `minSdk` | **24** (Android 7.0) |
| `targetSdk` | **36** |

## Runtime dependencies

Kiln ships its own platform-specific SQLite drivers. You do not need to add any separate driver dependency — the `runtime` artifact includes drivers for Android, iOS, and the JVM.
