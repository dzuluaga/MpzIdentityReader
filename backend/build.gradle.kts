plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktor)
}

project.setProperty("mainClassName", "org.multipaz.identityreader.backend.Main")

kotlin {
    jvmToolchain(17)

    compilerOptions {
        allWarningsAsErrors = true
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    ksp(libs.multipaz.cbor.rpc)
    implementation(libs.multipaz)
    implementation(project(":libbackend"))

    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.io.bytestring)
    implementation(libs.hsqldb)
    implementation(libs.mysql)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.java)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.logging)
    implementation(libs.logback.classic)
}

ktor {
}