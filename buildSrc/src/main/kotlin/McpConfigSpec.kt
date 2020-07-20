import com.google.gson.JsonObject

internal fun readSpec(json: JsonObject) : McpConfigSpec = when (getSpecVersion(json)) {
    1 -> readV1Spec(json)
    else -> throw RuntimeException("Invalid \"config.json\" from MCPConfig, has no spec version")
}

private fun getSpecVersion(json: JsonObject) : Int {
    if (json.has("spec")) {
        return json.get("spec").asInt
    }

    throw RuntimeException("Invalid \"config.json\" from MCPConfig, has no spec version")
}

private fun readV1Spec(json: JsonObject) : McpConfigSpecV1 {
    if (!json.has("data")) {
        throw RuntimeException("Invalid \"config.json\" from MCPConfig, has no \"data\" object")
    }

    val data = json.getAsJsonObject("data")

    if (!data.has("mappings")) {
        throw RuntimeException("Invalid \"config.json\" from MCPConfig, \"data\" object does not contain a \"mappings\" entry")
    }

    val mappings = data.getAsJsonPrimitive("mappings").asString!!

    if (!data.has("patches")) {
        throw RuntimeException()
    }

    val patches = data.getAsJsonObject("patches")

    if (!patches.has("joined")) {
        throw RuntimeException()
    }

    return McpConfigSpecV1(mappings, patches.getAsJsonPrimitive("joined").asString!!)
}

internal interface McpConfigSpec {
    val version: Int
    val joinedMappings: String
    val joinedPatches: String
}

private data class McpConfigSpecV1(override val joinedMappings: String, override val joinedPatches: String) : McpConfigSpec {
    override val version: Int = 1
}
