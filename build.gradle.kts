plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

group = "me.kevin"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {

    linuxX64().apply {
        compilations.getByName("main") {
            cinterops {
                val liburing by creating
            }
        }
    }

    sourceSets {
        val linuxX64Main by getting {
            dependencies {
                api(libs.kotlinx.coroutines.core)
            }
        }
        val linuxX64Test by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
