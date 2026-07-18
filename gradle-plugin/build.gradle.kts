plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.farook.krate"
version = "1.0.0-SNAPSHOT"

dependencies {
    implementation(gradleApi())
    // Ships KSP on the consumer's classpath so plugins.apply("com.google.devtools.ksp") works
    implementation(libs.ksp.gradle.plugin)
    // Consumer's build already has KGP — compile against it only
    compileOnly(kotlin("gradle-plugin"))

    testImplementation(gradleTestKit())
    testImplementation(libs.junit)
}

gradlePlugin {
    plugins {
        create("krate") {
            id = "com.farook.krate"
            implementationClass = "com.farook.krate.gradle.KratePlugin"
            displayName = "Krate"
            description = "Compile-time CRUD generation for Kotlin Multiplatform SQLite"
        }
    }
}

tasks.test {
    useJUnit()
}
