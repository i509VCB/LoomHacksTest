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
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.srg.tsrg.TSrgReader
import org.cadixdev.lorenz.model.ClassMapping
import org.cadixdev.lorenz.model.TopLevelClassMapping
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.jar.JarFile

// dependencies?
// binarypatcher - ConsoleTool

// TODO: Process
//  Generate official -> srg ---- Done
//  Generate intermediary -> srg via intermediary -> (official -> official) -> srg ---- Done
//  Create official -> srg jar ---- Done
//  Apply patches
//  Remap patched jar from srg -> named via srg -> (intermediary -> intermediary) -> named
//  Yeet old named jar with new one
class ForgeJarProcessor (private val project: Project, private val extension: (ForgeExtension).() -> Unit) : net.fabricmc.loom.processors.JarProcessor {
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

        var jarFile: JarFile? = null

        // We should only have one zip file here
        mcpConfig.forEach { file ->
            mcpConfigFile = file
            jarFile = JarFile(file)
            val config = jarFile!!.entries().toList().filter {
                return@filter !it.isDirectory && it.name.endsWith("config.json")
            }.first()

            mcpConfigSpec = readSpec(GSON.fromJson(InputStreamReader(jarFile!!.getInputStream(config)), JsonObject::class.java))
        }

        jarFile!!

        // TODO: resolve forge patches
    }

    // The file jar produced by this MUST be named
    override fun process(file: File?) {
        val loomExtension = project.extensions.getByType<LoomGradleExtension>()

        if (file == null) {
            TODO("Proper error")
        }

        project.logger.lifecycle(":generating forge jar")

        // Remap the client jar to srg
        val clientJar = loomExtension.minecraftProvider.mergedJar
        val mappings = loomExtension.mappingsProvider.mappings

        val tinyReader = net.fabricmc.lorenztiny.TinyMappingsReader(mappings, "official", "intermediary")
        val obfToIntermediary = tinyReader.read()

        val mcpConfig = JarFile(mcpConfigFile)
        val joinedMappingsEntry = mcpConfig.getJarEntry(mcpConfigSpec.joinedMappings)

        val obfToSrg = generateSrgMappings(mcpConfig.getInputStream(joinedMappingsEntry))
        // TODO: Apply parameter names?
        //  Patches rely on srg param names

        // Generate the inheritance provider so we have fully cascaded mappings
        val clientJarFile = JarFile(clientJar)
        val cascadingInheritanceProvider = CascadingInheritanceProvider()
        val providers = mutableListOf<ClassProvider>()
        providers.add(JarFileClassProvider(clientJarFile))
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

        // Iterate through obf -> intermediary and obf -> srg and complete field mappings
        iterateClasses(obfToIntermediary) {
            it.complete(cachingInheritanceProvider)
        }

        iterateClasses(obfToSrg) {
            it.complete(cachingInheritanceProvider)
        }

        // Srg mappings do not have field signatures included, we will generate those by using intermediary
        obfToSrg.addFieldTypeProvider {
            obfToIntermediary.getClassMapping(it.parent.fullObfuscatedName)
                    .flatMap { classMapping -> classMapping.getFieldMapping(it.obfuscatedName) }
                    .flatMap { fieldMapping -> fieldMapping.type }
        }

        clientJarFile.close()

        // FUCK
        // So apparently SRG has a class mapping that no longer exists
        // This does not cause an issue in 1.14.4 (Spunbric remapper v1) but it's an issue in 1.16.2 and 1.16.3
        // Reflection time it is
        val topLevelClassesField = obfToSrg.javaClass.getDeclaredField("topLevelClasses")
        topLevelClassesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val topLevelClasses: MutableMap<String, TopLevelClassMapping> = topLevelClassesField.get(obfToSrg) as MutableMap<String, TopLevelClassMapping>
        topLevelClasses.remove("afd")

        // Make srg -> intermediary mappings
        val srgToIntermediary = obfToSrg.reverse().merge(obfToIntermediary)

        val srgClientJar = clientJar.absoluteFile.parentFile.toPath().resolve("minecraft-$minecraftVersion-srg-mapped.jar")
        val tinyRemapper = TinyRemapper.newRemapper()
                .ignoreConflicts(true)
                .fixPackageAccess(true)
                .withMappings { acceptor ->
                    iterateClasses(obfToSrg) {
                        acceptor.acceptClass(it.fullObfuscatedName, it.fullDeobfuscatedName)

                        it.fieldMappings.forEach { field ->
                            acceptor.acceptField(IMappingProvider.Member(it.fullObfuscatedName, field.obfuscatedName, field.type.get().toString()), field.deobfuscatedName)
                        }

                        it.methodMappings.forEach { method ->
                            acceptor.acceptMethod(IMappingProvider.Member(it.fullObfuscatedName, method.obfuscatedName, method.obfuscatedDescriptor), method.deobfuscatedName)
                        }
                    }
                }.build()

        println(clientJar)
        val outputConsumer = OutputConsumerPath.Builder(srgClientJar).build()
        outputConsumer.addNonClassFiles(clientJar.toPath(), NonClassCopyMode.FIX_META_INF, tinyRemapper)

        tinyRemapper.readInputs(clientJar.toPath())
        // TODO: Classpath
        // tinyRemapper.readClassPath()

        tinyRemapper.apply(outputConsumer)
        outputConsumer.close()
        tinyRemapper.finish()

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

internal fun iterateClasses(mappings: MappingSet, action: (ClassMapping<*, *>) -> Unit) {
    mappings.topLevelClassMappings.forEach {
        iterateClass(it, action)
    }
}

internal fun iterateClass(classMapping: ClassMapping<*, *>, action: (ClassMapping<*, *>) -> Unit) {
    action.invoke(classMapping)

    classMapping.innerClassMappings.forEach {
        iterateClass(it, action)
    }
}

private fun generateSrgMappings(stream: InputStream): MappingSet = autoclose(InputStreamReader(stream)) { TSrgReader(it).read() }

private fun <T : AutoCloseable, R : Any> autoclose(closeable: T, tryWithResourcesBlock: (T) -> R): R {
    val value = tryWithResourcesBlock.invoke(closeable)
    closeable.close()
    return value
}

open class ForgeExtension(private val project: Project) {
    lateinit var forgeVersion: String
}

internal val GSON = Gson()
