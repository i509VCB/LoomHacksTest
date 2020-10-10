import com.google.gson.Gson
import com.google.gson.JsonObject
import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.tinyremapper.IMappingProvider
import net.fabricmc.tinyremapper.NonClassCopyMode
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import org.cadixdev.bombe.analysis.CachingInheritanceProvider
import org.cadixdev.bombe.analysis.CascadingInheritanceProvider
import org.cadixdev.bombe.analysis.ReflectionInheritanceProvider
import org.cadixdev.bombe.asm.analysis.ClassProviderInheritanceProvider
import org.cadixdev.bombe.asm.jar.ClassProvider
import org.cadixdev.bombe.asm.jar.JarFileClassProvider
import org.cadixdev.bombe.type.MethodDescriptor
import org.cadixdev.bombe.type.signature.MethodSignature
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.srg.tsrg.TSrgReader
import org.cadixdev.lorenz.model.TopLevelClassMapping
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.*
import java.util.jar.JarFile
import java.util.zip.ZipFile

// dependencies?
// binarypatcher - ConsoleTool

// TODO: Process
//  Generate official -> srg ---- Done
//  Generate intermediary -> srg via intermediary -> (official -> official) -> srg ---- Done
//  Create official -> srg jar ---- Done
//  Apply patches
//  Remap patched jar from srg -> named via srg -> (intermediary -> intermediary) -> named
//  Yeet old named jar with new one
class ForgeJarProcessor(private val project: Project, private val extension: (ForgeExtension).() -> Unit) : net.fabricmc.loom.processors.JarProcessor {
    private lateinit var mcpConfigFile: File
    private lateinit var mcpConfigSpec: McpConfigSpec
    private lateinit var minecraftVersion: String
    private lateinit var forgeDependencyNotation: String

    override fun setup() {
        val forgeExtension = ForgeExtension(project)
        val loomExtension = project.extensions.getByType<LoomGradleExtension>()

        project.logger.lifecycle(":setting up forge jar processor")
        extension.invoke(forgeExtension)

        // So some early setup, we need to figure out what version of Minecraft and the version of forge we want to use
        minecraftVersion = loomExtension.minecraftProvider.minecraftVersion
        forgeDependencyNotation = "$FORGE$minecraftVersion-${forgeExtension.forgeVersion}"

        // Resolve MCPConfig
        val dummyConfig = project.configurations.maybeCreate(MCP_CONFIG_CONFIGURATION)
        project.dependencies.add(MCP_CONFIG_CONFIGURATION, "$MCP_CONFIG$minecraftVersion")
        val mcpConfig = dummyConfig.resolve()

        if (mcpConfig.isEmpty() || mcpConfig.size > 1) {
            throw IllegalArgumentException("MCP Config could not be found");
        }

        // We should only have one zip file here
        mcpConfig.forEach { file ->
            mcpConfigFile = file
            JarFile(file).use {
                val config = it.entries().toList().filter { config ->
                    return@filter !config.isDirectory && config.name.endsWith("config.json")
                }.first()

                mcpConfigSpec = readSpec(GSON.fromJson(InputStreamReader(it.getInputStream(config)), JsonObject::class.java))
            }
        }

        // TODO: resolve forge patches
    }

    // The file jar produced by this MUST be named
    override fun process(file: File?) {
        val loomExtension = project.extensions.getByType<LoomGradleExtension>()

        if (file == null) {
            throw RuntimeException("Minecraft jar does not exist?")
        }

        project.logger.lifecycle(":generating forge jar")

        // Remap the client jar to srg
        val clientJar = loomExtension.minecraftProvider.mergedJar
        val mappings = loomExtension.mappingsProvider.mappings

        val tinyReader = net.fabricmc.lorenztiny.TinyMappingsReader(mappings, "official", "intermediary")
        val obfToIntermediary = tinyReader.read()

        val mcpConfig = ZipFile(mcpConfigFile) // TODO: .use {
        val joinedMappingsEntry = mcpConfig.getEntry(mcpConfigSpec.joinedMappings)

        val obfToSrg = createSrgMappings(mcpConfig.getInputStream(joinedMappingsEntry))
        val staticSrgMethods = mutableListOf<String>()
        val constructorIds = mutableMapOf<String, MutableList<ConstructorEntry>>()

        // Read static methods
        Scanner(mcpConfig.getInputStream(mcpConfig.getEntry(mcpConfigSpec.staticMethods))).use {
            while (it.hasNextLine()) {
                staticSrgMethods += it.nextLine()
            }
        }

        Scanner(mcpConfig.getInputStream(mcpConfig.getEntry(mcpConfigSpec.constructors))).use {
            var line = 0

            while (it.hasNextLine()) {
                val parts = it.nextLine().split("\\s+".toRegex())

                if (parts.size != 3) {
                    throw RuntimeException("Invalid constructor entry at line $line in constructors.txt")
                }

                if (constructorIds[parts[1]] == null) {
                    constructorIds[parts[1]] = mutableListOf()
                }

                constructorIds[parts[1]]?.plusAssign(ConstructorEntry(parts[1], parts[0].toInt(), parts[2]))
                line++
            }
        }

        // TODO: Apply parameter names?
        //  Patches rely on srg param names so we need these
        //  For later

        // Complete inheritance for mappings
        completeMappings(clientJar, obfToIntermediary, obfToSrg)

        // Srg mappings do not have field signatures included, we will generate those by using intermediary's field descriptors
        obfToSrg.addFieldTypeProvider {
            obfToIntermediary.getClassMapping(it.parent.fullObfuscatedName)
                    .flatMap { classMapping -> classMapping.getFieldMapping(it.obfuscatedName) }
                    .flatMap { fieldMapping -> fieldMapping.type }
        }

        val srgToObf = obfToSrg.reverse()

        // Example: this.func_777777_X(_)(_) -> p_777777_1_
        obfToSrg.iterateClasses { classMapping ->
            classMapping.methodMappings.forEach { methodMapping ->
                val deobfuscatedName = methodMapping.deobfuscatedName

                // Constructors handled seperately
                if (!deobfuscatedName.startsWith("func_")) {
                    return@forEach // Ignore weird cases for now
                }

                // Extract the method id
                val methodId = deobfuscatedName.replace("\\D+".toRegex(), "")

                for (index: Int in methodMapping.signature.descriptor.paramTypes.indices) {
                    methodMapping.createParameterMapping(index, "p_${methodId}_${index}_")
                }
            }
        }

        // SRG does not have constructor mappings, so we need to assemble these for their param names
        obfToSrg.iterateClasses { classMapping ->
            constructorIds[classMapping.fullDeobfuscatedName]?.forEach { ctor ->
                // Descriptor from `constructors.txt` is deobfuscated, we need to obfuscate it
                val obfuscatedDescriptor = srgToObf.deobfuscate(MethodDescriptor.of(ctor.descriptor))
                val ctorMapping = classMapping.createMethodMapping(MethodSignature.of("<init>", obfuscatedDescriptor.toString()))

                // Create parameter mappings
                for (index: Int in ctorMapping.signature.descriptor.paramTypes.indices) {
                    ctorMapping.createParameterMapping(index, "p_i${ctor.id}_${index}_")
                }
            }
        }

        applySrgMappingHacks(obfToSrg)

        // Make srg -> intermediary mappings
        val srgToIntermediary = srgToObf.merge(obfToIntermediary)

        val srgClientJar = clientJar.absoluteFile.parentFile.toPath().resolve("minecraft-$minecraftVersion-srg-mapped.jar")
        val tinyRemapper = TinyRemapper.newRemapper()
                .ignoreConflicts(true) // The mappings process isn't perfect, it requires a little slack with conflicts
                .fixPackageAccess(true)
                .withMappings {
                    obfToSrg.apply(it, staticSrgMethods)
                }
                .build()

        println(clientJar)

        // TR will fail if the srgClientJar exists - TODO: Make an issue?
        if (Files.exists(srgClientJar)) {
            Files.delete(srgClientJar)
        }

        project.logger.lifecycle(":remapping minecraft (TinyRemapper, official -> srg)")
        OutputConsumerPath.Builder(srgClientJar).build().use { outputConsumer ->
            outputConsumer.addNonClassFiles(clientJar.toPath(), NonClassCopyMode.FIX_META_INF, tinyRemapper)

            tinyRemapper.readInputs(clientJar.toPath())
            tinyRemapper.apply(outputConsumer)
        }.apply {
            tinyRemapper.finish()
        }

        // Write the mappings to a test file
        //println(file)
        //val writer = TinyV2MappingsWriter(FileWriter(File(file.parent, "test.tiny")), "srg", "intermediary")
        //writer.write(srgToIntermediary)
        /*
         * TODO: remap the client jar obf -> srg ---- Done
         * TODO: decompile srg jar
         * TODO: apply forge patches
         * TODO: compile forge jar
         * TODO: remap forge jar from srg -> named via intermediary
         * TODO: Place processed forge jar at file location of the jar
         */
    }

    override fun isInvalid(file: File?): Boolean {
        return true // TODO impl
    }
}

private fun completeMappings(clientJar: File?, vararg mappings: MappingSet) {
    JarFile(clientJar).use { clientJarFile ->
        val cascadingInheritanceProvider = CascadingInheritanceProvider()
        val providers = mutableListOf<ClassProvider>()
        providers.add(JarFileClassProvider(clientJarFile))

        // Use client jar for context when calculating inheritance
        cascadingInheritanceProvider.install(ClassProviderInheritanceProvider(ClassProvider { className ->
            providers.forEach {
                val bytes: ByteArray? = it.get(className)

                if (bytes != null) {
                    return@ClassProvider bytes
                }
            }

            return@ClassProvider null
        }))

        // Install JRE classes
        cascadingInheritanceProvider.install(ReflectionInheritanceProvider(ClassLoader.getSystemClassLoader()))

        val cachingInheritanceProvider = CachingInheritanceProvider(cascadingInheritanceProvider)

        // Iterate through mappings to complete inheritance
        mappings.forEach {
            it.iterateClasses { classMapping ->
                classMapping.complete(cachingInheritanceProvider)
            }
        }
    }
}

private fun applySrgMappingHacks(obfToSrg: MappingSet) {
    // So apparently SRG has a class mapping that no longer exists
    // Supposedly "intended" per this issue: https://github.com/MinecraftForge/MCPConfig/issues/125
    // So we use reflection to remove the entry
    val topLevelClassesField = obfToSrg.javaClass.getDeclaredField("topLevelClasses")
    topLevelClassesField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val topLevelClasses: MutableMap<String, TopLevelClassMapping> = topLevelClassesField.get(obfToSrg) as MutableMap<String, TopLevelClassMapping>
    topLevelClasses.remove("afd")
}

private fun createSrgMappings(stream: InputStream) : MappingSet = TSrgReader(InputStreamReader(stream)).use { it.read() }

open class ForgeExtension(private val project: Project) {
    lateinit var forgeVersion: String
}

internal val GSON = Gson()
