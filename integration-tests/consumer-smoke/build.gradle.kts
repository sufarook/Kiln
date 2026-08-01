plugins {
    kotlin("jvm") version "2.3.20"
    id("io.github.sufarook.kiln")
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
