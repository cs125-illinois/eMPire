plugins {
    id("java-library")
    kotlin("jvm") version "1.3.41"
    id("com.github.johnrengelman.shadow") version "5.1.0"
}

dependencies {
    compileOnly(gradleApi())
    setOf(
            "com.android.tools.build:gradle:3.4.2",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.3.41",
            "com.fasterxml.jackson.core:jackson-databind:2.9.9",
            "com.fasterxml.jackson.module:jackson-module-kotlin:2.9.8",
            "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.9.8",
            "org.ow2.asm:asm:7.1").forEach {
        implementation(it)
        shadow(it)
    }
}

repositories {
    mavenCentral()
    google()
}

tasks {
    compileKotlin {
        kotlinOptions {
            jvmTarget = "1.8"
        }
    }
}
