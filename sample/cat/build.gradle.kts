plugins {
    alias(libs.plugins.kotlin.multiplatform)
}


kotlin {

    linuxX64 {
        binaries {
            executable {
                entryPoint = "com.kevincianfarini.iouring.cat.main"
            }
        }
    }

    sourceSets {
        val linuxX64Main by getting {
            dependencies {
                implementation(libs.clikt)
                implementation(project(":io-uring"))
            }
        }
    }
}
