plugins {
    kotlin("multiplatform") version "1.7.21"
}

group = "me.kevin"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {

    linuxX64()

    sourceSets {
        val linuxX64Main by getting
        val linuxX64Test by getting
    }
}
