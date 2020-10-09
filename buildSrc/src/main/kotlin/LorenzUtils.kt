import net.fabricmc.tinyremapper.IMappingProvider
import org.cadixdev.lorenz.MappingSet

internal fun MappingSet.apply(mappingAcceptor: IMappingProvider.MappingAcceptor) {
    apply(mappingAcceptor, true) {
        val exception: Exception = RuntimeException("Cannot apply mapping set to mapping acceptor due to missing field mappings:")

        it.forEach { entry ->
            exception.addSuppressed(RuntimeException("Field \"${entry.obfuscatedFieldName} -> ${entry.deobfuscatedFieldName}\" in class \"${entry.obfuscatedClassName} -> ${entry.deobfuscatedClassName}\""))
        }

        throw exception
    }
}

internal fun MappingSet.apply(mappingAcceptor: IMappingProvider.MappingAcceptor, strict: Boolean, invalidFieldHandler: (List<MissingFieldEntry>) -> Unit = {}) {
    // Validate all fields have field types per tiny spec.
    if (strict) {
        val fieldsWithMissingSignatures = findFieldsMissingSignature()

        if (fieldsWithMissingSignatures.isNotEmpty()) {
            invalidFieldHandler.invoke(fieldsWithMissingSignatures)
        }
    }

    iterateClasses(this) {
        mappingAcceptor.acceptClass(it.fullObfuscatedName, it.fullDeobfuscatedName)

        it.fieldMappings.forEach { field ->
            val fieldType = field.type
            val fieldDescriptor = when (strict) {
                true -> fieldType.get()
                false -> fieldType.orElse(null)
            }.toString()

            mappingAcceptor.acceptField(IMappingProvider.Member(it.fullObfuscatedName, field.obfuscatedName, fieldDescriptor), field.deobfuscatedName)
        }

        it.methodMappings.forEach { method ->
            mappingAcceptor.acceptMethod(IMappingProvider.Member(it.fullObfuscatedName, method.obfuscatedName, method.obfuscatedDescriptor), method.deobfuscatedName)
        }
    }
}

internal fun MappingSet.findFieldsMissingSignature() : List<MissingFieldEntry> {
    val missing = mutableListOf<MissingFieldEntry>()

    iterateClasses(this) { classMapping ->
        classMapping.fieldMappings.forEach { field ->
            if (!field.type.isPresent) {
                missing += MissingFieldEntry(field.obfuscatedName, field.deobfuscatedName, classMapping.fullObfuscatedName, classMapping.fullDeobfuscatedName)
            }
        }
    }

    return missing
}

internal data class MissingFieldEntry(val obfuscatedFieldName: String, val deobfuscatedFieldName: String, val obfuscatedClassName: String, val deobfuscatedClassName: String)
