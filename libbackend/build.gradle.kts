plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("maven-publish")
}

kotlin {
    jvmToolchain(17)

    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.multipaz)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.io.bytestring)
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
