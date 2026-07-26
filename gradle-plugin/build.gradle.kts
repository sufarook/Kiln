plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    alias(libs.plugins.vanniktech.publish)
}

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
        create("kiln") {
            id = "io.github.sufarook.kiln"
            implementationClass = "io.github.sufarook.kiln.gradle.KilnPlugin"
            displayName = "Kiln"
            description = "Compile-time CRUD generation for Kotlin Multiplatform SQLite"
        }
    }
}

tasks.test {
    useJUnit()
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    if (!System.getenv("SIGNING_KEY").isNullOrEmpty()) signAllPublications()

    coordinates(artifactId = "gradle-plugin")

    pom {
        name.set("Kiln Gradle Plugin")
        description.set("Gradle plugin for Kiln — one-line setup wires KSP, the processor, and generated sources into any KMP project.")
        url.set("https://github.com/sufarook/Kiln")
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set("sufarook")
                name.set("Syed Ummer Farook")
                email.set("syedfarook1798@gmail.com")
            }
        }
        scm {
            connection.set("scm:git:github.com/sufarook/Kiln.git")
            developerConnection.set("scm:git:ssh://github.com/sufarook/Kiln.git")
            url.set("https://github.com/sufarook/Kiln")
        }
    }
}
