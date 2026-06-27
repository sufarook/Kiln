plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "com.farook.delightcrud"
version = "1.0.0-SNAPSHOT"

dependencies {
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)
    implementation(project(":annotations"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinpoet)     // needed to construct TypeName in tests
}

tasks.test {
    useJUnit()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
    }
}
