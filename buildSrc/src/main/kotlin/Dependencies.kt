import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.dsl.RepositoryHandler
import java.net.URI

fun RepositoryHandler.fabric() = maven {
    this.name = "Fabric"
    this.url = URI("https://maven.fabricmc.net/")
}

fun DependencyHandler.minecraft(version: String): Dependency? = this.add(
    "minecraft",
    "com.mojang:minecraft:$version"
)

fun DependencyHandler.yarn(minecraftVersion: String, yarnBuild: String): Dependency? = this.add(
    "mappings",
    "net.fabricmc:yarn:$minecraftVersion+build.$yarnBuild:v2"
)

fun DependencyHandler.`fabric-loader`(version: String): Dependency? = modImplementation(
    "net.fabricmc:fabric-loader:$version"
)

fun DependencyHandler.`fabric-api`(version: String): Dependency? = modImplementation(
    "net.fabricmc.fabric-api:fabric-api:$version"
)

fun DependencyHandler.modImplementation(dependencyNotation: Any): Dependency? = this.add(
    "modImplementation",
    dependencyNotation
)

fun DependencyHandler.testmodImplementation(dependencyNotation: Any): Dependency? = this.add(
    "testmodImplementation",
    dependencyNotation
)
