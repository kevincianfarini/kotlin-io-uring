plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

group = "me.kevin"
version = "1.0-SNAPSHOT"


kotlin {

    explicitApi()

    linuxX64 {
        compilations.getByName("main") {
            cinterops {
                val liburing by creating
            }
        }
    }

    sourceSets {

        all {
            languageSettings {
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
            }
        }

        val linuxX64Main by getting {
            dependencies {
                api(libs.kotlinx.coroutines.core)
            }
        }
        val linuxX64Test by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

tasks.withType<AbstractTestTask> {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
        showStackTraces = true
    }
}
