plugins {
    id("fabric-loom")
}

// Versioning stuff
val minecraftVersion: String by rootProject
val loaderVersion: String by rootProject
val yarnBuild: String by rootProject

val baseVersion: String by rootProject
val version: String = "$baseVersion+$minecraftVersion"

repositories {
    gradlePluginPortal()
    mavenCentral()
    jcenter()
    fabric()
    maven(url = "https://files.minecraftforge.net/maven") {
        this.name = "Forge"
    }
}

dependencies {
    minecraft(minecraftVersion)
    yarn(minecraftVersion, yarnBuild)
    `fabric-loader`(loaderVersion)
    implementation("org.jetbrains:annotations:19.0.0")
}

configure<JavaPluginConvention> {
    this.sourceCompatibility = JavaVersion.VERSION_1_8
    this.targetCompatibility = JavaVersion.VERSION_1_8
}

loom {
    addJarProcessor(ForgeJarProcessor(project) {
        forgeVersion = "33.0.3"
    })
}
