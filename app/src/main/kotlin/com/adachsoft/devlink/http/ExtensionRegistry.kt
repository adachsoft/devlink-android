package com.adachsoft.devlink.http

import org.json.JSONArray
import org.json.JSONObject

class ExtensionRegistry(extensions: Collection<DevlinkExtension>) {
    val all: List<DevlinkExtension> = extensions.toList()
    val enabled: List<DevlinkExtension>
        get() = all.filter(DevlinkExtension::enabled)

    init {
        require(all.map(DevlinkExtension::id).toSet().size == all.size) {
            "Extension identifiers must be unique."
        }
    }

    fun findForPath(path: String): DevlinkExtension? = all.firstOrNull { extension ->
        path == extension.basePath || path.startsWith("${extension.basePath}/")
    }

    fun descriptors(): JSONArray = JSONArray().apply {
        all.forEach { extension ->
            put(
                JSONObject()
                    .put("id", extension.id)
                    .put("version", extension.version)
                    .put("enabled", extension.enabled)
                    .put("scope", extension.scope)
                    .put("basePath", extension.basePath),
            )
        }
    }

    fun statusSections(): JSONObject = JSONObject().apply {
        enabled.forEach { extension ->
            put(extension.id, extension.statusSection())
        }
    }
}
