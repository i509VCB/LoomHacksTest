import net.fabricmc.tinyremapper.IMappingProvider
import org.cadixdev.bombe.type.BaseType
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.model.ClassMapping

fun MappingSet.iterateClasses(action: (ClassMapping<*, *>) -> Unit) {
    topLevelClassMappings.forEach {
        iterateClass(it, action)
    }
}

private fun iterateClass(classMapping: ClassMapping<*, *>, action: (ClassMapping<*, *>) -> Unit) {
    action.invoke(classMapping)

    classMapping.innerClassMappings.forEach {
        iterateClass(it, action)
    }
}

internal fun MappingSet.apply(
        mappingAcceptor: IMappingProvider.MappingAcceptor,
        staticMethods: List<String>,
        strict: Boolean = true,
        invalidFieldHandler: (List<MissingFieldEntry>) -> Unit = {
            val exception: Exception = RuntimeException("Cannot apply mapping set to mapping acceptor due to missing field mappings:")

            it.forEach { entry ->
                exception.addSuppressed(RuntimeException("Field \"${entry.obfuscatedFieldName} -> ${entry.deobfuscatedFieldName}\" in class \"${entry.obfuscatedClassName} -> ${entry.deobfuscatedClassName}\""))
            }

            throw exception
        }
) {
    // Validate all fields have field types per tiny spec.
    if (strict) {
        val fieldsWithMissingSignatures = findFieldsMissingSignature()

        if (fieldsWithMissingSignatures.isNotEmpty()) {
            invalidFieldHandler.invoke(fieldsWithMissingSignatures)
        }
    }

    iterateClasses {
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

            var lvtIndex = 0

            if (!staticMethods.contains(method.deobfuscatedName)) {
                lvtIndex++
            }

            val paramTypes = method.descriptor.paramTypes
            method.parameterMappings.forEach { parameter ->
                mappingAcceptor.acceptMethodArg(IMappingProvider.Member(it.fullObfuscatedName, method.obfuscatedName, method.obfuscatedDescriptor), lvtIndex, parameter.deobfuscatedName)

                when (paramTypes[parameter.index]) {
                    // Consider the fact Longs and Doubles take up two spaces on LVT
                    BaseType.DOUBLE, BaseType.LONG -> lvtIndex += 2
                    else -> lvtIndex++
                }
            }
        }
    }
}

internal fun MappingSet.findFieldsMissingSignature() : List<MissingFieldEntry> {
    val missing = mutableListOf<MissingFieldEntry>()

    iterateClasses { classMapping ->
        classMapping.fieldMappings.forEach { field ->
            if (!field.type.isPresent) {
                missing += MissingFieldEntry(field.obfuscatedName, field.deobfuscatedName, classMapping.fullObfuscatedName, classMapping.fullDeobfuscatedName)
            }
        }
    }

    return missing
}

internal data class MissingFieldEntry(val obfuscatedFieldName: String, val deobfuscatedFieldName: String, val obfuscatedClassName: String, val deobfuscatedClassName: String)

internal data class ConstructorEntry(val owner: String, val id: Int, val descriptor: String)
