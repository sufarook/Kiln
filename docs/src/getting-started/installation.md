# Installation

## Android (single-module)

### 1. Configure repositories

```kotlin title="settings.gradle.kts"
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

### 2. Apply the plugin and add dependencies

```kotlin title="app/build.gradle.kts"
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") version "2.3.20"
    id("com.farook.krate") version "1.0.0-alpha01"
}

dependencies {
    implementation("com.farook.krate:annotations:1.0.0-alpha01")
    implementation("com.farook.krate:runtime:1.0.0-alpha01")
    implementation("app.cash.sqldelight:android-driver:2.3.2")
}
```

!!! tip "Plugin handles KSP"
    You do **not** need to apply `com.google.devtools.ksp` manually. The Krate plugin detects your project type and applies KSP with the correct configuration.

---

## Kotlin Multiplatform

For KMP projects the plugin configures `kspCommonMainMetadata` automatically — entities defined in `commonMain` are processed and their repositories are available on all targets.

```kotlin title="shared/build.gradle.kts"
plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("com.farook.krate") version "1.0.0-alpha01"
}

kotlin {
    androidTarget()
    iosArm64()
    iosX64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("com.farook.krate:annotations:1.0.0-alpha01")
            implementation("com.farook.krate:runtime:1.0.0-alpha01")
        }
        androidMain.dependencies {
            implementation("app.cash.sqldelight:android-driver:2.3.2")
        }
        iosMain.dependencies {
            implementation("app.cash.sqldelight:native-driver:2.3.2")
        }
    }
}
```

### iOS driver factory

On iOS use `IosDatabaseDriverFactory` instead of the Android one:

```kotlin title="iosMain"
val driver = IosDatabaseDriverFactory().create("myapp.db")
val noteRepo = NoteRepository(driver).also { it.createTable() }
```

---

## Maven coordinates

| Artifact | Coordinate |
|----------|-----------|
| Annotations | `com.farook.krate:annotations:1.0.0-alpha01` |
| Runtime | `com.farook.krate:runtime:1.0.0-alpha01` |
| Gradle plugin | `com.farook.krate` (plugin id) |

!!! warning "Alpha release"
    Version `1.0.0-alpha01` is stable for Android production use. The public API is stable but minor breaking changes may occur before the `1.0.0` stable release. Watch the [GitHub releases](https://github.com/ummerfarook/Krate/releases) page for updates.
