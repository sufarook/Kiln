plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

group = "com.farook.delightcrud"
version = "1.0.0-SNAPSHOT"

dependencies {
    implementation(gradleApi())
    implementation(kotlin("gradle-plugin"))
    compileOnly(libs.ksp.api)

    testImplementation(gradleTestKit())
    testImplementation(libs.junit)
}

gradlePlugin {
    plugins {
        create("delightCrud") {
            id = "com.farook.delightcrud"
            implementationClass = "com.farook.delightcrud.gradle.DelightCrudPlugin"
            displayName = "DelightCRUD"
            description = "Compile-time CRUD generation for Kotlin Multiplatform SQLite"
        }
    }
}

tasks.test {
    useJUnit()
}
