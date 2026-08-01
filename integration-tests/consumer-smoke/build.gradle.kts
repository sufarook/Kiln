plugins {
    kotlin("jvm") version "2.3.20"
    id("io.github.sufarook.kiln")
}

kotlin {
    // Deliberately pinned to Kiln's documented minimum. If a published module ever
    // regresses to a higher class-file version, this project fails to load it —
    // which is exactly the regression we want CI to catch.
    jvmToolchain(17)
}

// No Kiln dependencies declared here on purpose. The plugin is responsible for
// adding annotations, runtime, and the KSP processor at the correct coordinates.
// If it gets those wrong, this project fails to resolve — which is the whole point.
dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
}
