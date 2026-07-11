plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.farook.delightcrud"
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
