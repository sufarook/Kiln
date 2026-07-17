# Requirements

## Toolchain

| Tool | Minimum version | Notes |
|------|----------------|-------|
| Kotlin | **2.0.0** | KSP 2.x requires Kotlin 2.x |
| KSP | **2.0.0-1.0.21** | Applied automatically by the plugin |
| Gradle | **8.0** | |
| Android Gradle Plugin | **8.0** | |
| Java / JVM target | **11** | |

## Android

| Setting | Value |
|---------|-------|
| `compileSdk` | 34+ |
| `minSdk` | **21** (Android 5.0) |
| `targetSdk` | 34+ |

## Runtime dependencies

DelightCRUD's runtime layer sits on top of [SQLDelight](https://cashapp.github.io/sqldelight/) 2.x for the `SqlDriver` abstraction. You choose the driver for your platform — DelightCRUD does not bundle one.

| Platform | Driver dependency |
|----------|------------------|
| Android | `app.cash.sqldelight:android-driver:2.3.2` |
| iOS (KMP) | `app.cash.sqldelight:native-driver:2.3.2` |
| JVM tests | `app.cash.sqldelight:sqlite-driver:2.3.2` |

!!! note
    DelightCRUD does not require you to write any SQLDelight `.sq` files or configure a SQLDelight schema. The driver is used purely as a transport layer for raw SQL execution.
