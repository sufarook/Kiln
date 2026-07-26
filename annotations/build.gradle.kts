plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.publish)
}

android {
    namespace = "io.github.sufarook.kiln.annotations"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

kotlin {
    androidTarget {
        publishLibraryVariants("release")
    }
    jvm()
    iosArm64()
    iosX64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting
    }
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    if (!System.getenv("SIGNING_KEY").isNullOrEmpty()) signAllPublications()

    coordinates(artifactId = "annotations")

    pom {
        name.set("Kiln Annotations")
        description.set("Compile-time annotations for Kiln — @DbEntity, @PrimaryKey, @Column, @Ignore, @Relation.")
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
