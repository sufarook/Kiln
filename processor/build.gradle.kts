plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.vanniktech.publish)
}

dependencies {
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)
    implementation(project(":annotations"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinpoet)     // needed to construct TypeName in tests
}

kotlin {
    // Pinned so published class-file version is reproducible across build machines.
    jvmToolchain(17)
}

tasks.test {
    useJUnit()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
    }
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    if (!System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey").isNullOrEmpty()) signAllPublications()

    coordinates(artifactId = "processor")

    pom {
        name.set("Kiln Processor")
        description.set("KSP processor for Kiln — generates type-safe SQLite repositories from @DbEntity classes.")
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
