import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.TextMappingsWriter
import java.io.IOException
import java.io.Writer

/**
 * Creates a new mappings writer, from the given [Writer].
 *
 * @param writer The output writer, to write to
 */
class TinyV2MappingsWriter (writer: Writer?, private val obfNamespace: String, private val deobfNamespace: String) : TextMappingsWriter(writer) {
    @Throws(IOException::class)
    override fun write(mappings: MappingSet) {
        // Write the header, below for example
        // tiny	2	0	official	intermediary
        writer.print("tiny")
        printTab()
        writer.print("2")
        printTab()
        writer.print("0")
        printTab()
        writer.print(deobfNamespace)
        printTab()
        writer.print(obfNamespace)

        // Write our first class entry
        iterateClasses(mappings) { classMapping ->
            writer.println() // Always NL before next class
            writer.print("c") // Class
            printTab()
            writer.print(classMapping.fullObfuscatedName) // Obf
            printTab()
            writer.print(classMapping.fullDeobfuscatedName) // Deobf

            // Methods
            for (methodMapping in classMapping.methodMappings) {
                writer.println() // Newline for method
                printTab() // Indent by 1
                writer.print("m") // Method
                printTab()
                writer.print(methodMapping.signature.descriptor.toString()) // Method descriptor
                printTab()
                writer.print(methodMapping.simpleObfuscatedName) // Obf
                printTab()
                writer.print(methodMapping.simpleDeobfuscatedName) // Deobf
            }

            for (fieldMapping in classMapping.fieldMappings) {
                writer.println() // Newline for field
                printTab() // Ident by 1
                writer.print("f") // Method
                printTab()

                // Write the field type. This is required
                writer.print(fieldMapping.type.orElseThrow<RuntimeException> {
                    RuntimeException(java.lang.String.format("Cannot write field \"%s -> %s\" in class \"%s -> %s\" since it has no field type",
                            fieldMapping.obfuscatedName, fieldMapping.deobfuscatedSignature, classMapping.getFullObfuscatedName(), classMapping.getFullDeobfuscatedName()))
                })

                printTab()
                writer.print(fieldMapping.obfuscatedName) // Obf
                printTab()
                writer.print(fieldMapping.deobfuscatedName) // Deobf
            }
        }
    }

    private fun printTab() {
        writer.print("\t")
    }
}