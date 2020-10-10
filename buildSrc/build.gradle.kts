plugins {
    `kotlin-dsl`
    kotlin("jvm") version "1.3.72"
}

repositories {
    jcenter()
    mavenCentral()
    gradlePluginPortal()
    mavenLocal()
    maven(url = "https://maven.fabricmc.net") {
        this.name = "Fabric"
    }
    maven(url = "https://files.minecraftforge.net/maven") {
        this.name = "Forge"
    }
}

dependencies {
    //implementation("net.fabricmc:fabric-loom:0.5-local")
    implementation("net.fabricmc:fabric-loom:0.5-SNAPSHOT")
    implementation("com.google.code.gson:gson:2.8.5")

    implementation("net.fabricmc:tiny-mappings-parser:0.2.2.14")
    implementation("net.fabricmc:tiny-remapper:0.3.0.70")

    implementation("org.cadixdev:lorenz:0.5.2")
    implementation("net.fabricmc:lorenz-tiny:2.0.0+build.2") {
        isTransitive = false
    }
    implementation("org.cadixdev:lorenz-asm:0.5.2")

    // Specific dependencies
    implementation("net.minecraftforge:binarypatcher:1.+:fatjar")
}
