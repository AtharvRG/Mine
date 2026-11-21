// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

@Serializable
data class ImportedProviderConfig(
    val endpoint: String,
    val model: String,
    val key: String? = null,
    val fallbackEndpoint: String? = null,
    val fallbackModel: String? = null,
    val fallbackKey: String? = null
)

object ProviderConfigParser {
    private const val MAX_PAYLOAD_LENGTH = 65536 // 64 KB cap to prevent OOM
    private const val MAX_KEY_LENGTH = 512

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Parses a provider configuration payload from a JSON string or a URI query string (e.g. mine://provider?...).
     *
     * Invariants:
     * - Key is strictly optional. If absent or blank, returned key is null.
     * - Endpoint must use http or https scheme and have a valid host.
     * - Model must not be blank.
     */
    fun parse(rawPayload: String): Result<ImportedProviderConfig> = runCatching {
        val trimmed = rawPayload.trim()
        if (trimmed.length > MAX_PAYLOAD_LENGTH) {
            throw IllegalArgumentException("Payload exceeds maximum size of 64KB")
        }
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("Payload cannot be empty")
        }

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            parseJson(trimmed)
        } else if (trimmed.startsWith("mine://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            parseUri(trimmed)
        } else {
            // Attempt JSON parse first, fallback to URI parse if it contains '?'
            try {
                parseJson(trimmed)
            } catch (jsonEx: Exception) {
                if (trimmed.contains("?")) {
                    parseUri(trimmed)
                } else {
                    throw IllegalArgumentException("Unrecognized configuration format. Expected JSON or mine:// URI.", jsonEx)
                }
            }
        }
    }

    private fun parseJson(jsonString: String): ImportedProviderConfig {
        val element = json.parseToJsonElement(jsonString)
        if (element !is JsonObject) {
            throw IllegalArgumentException("Expected a JSON object")
        }

        val endpoint = element.findString("endpoint", "base_url", "baseUrl", "url")
            ?: throw IllegalArgumentException("Missing required provider endpoint / baseUrl")
        val model = element.findString("model", "model_name", "modelName")
            ?: throw IllegalArgumentException("Missing required provider model")
        val key = element.findString("key", "api_key", "apiKey")?.takeIf { it.isNotBlank() }
        val fallbackEndpoint = element.findString("fallback_endpoint", "fallbackEndpoint")
        val fallbackModel = element.findString("fallback_model", "fallbackModel")
        val fallbackKey = element.findString("fallback_key", "fallbackKey")?.takeIf { it.isNotBlank() }

        validateEndpoint(endpoint)
        validateModel(model)
        if (key != null && key.length > MAX_KEY_LENGTH) {
            throw IllegalArgumentException("API key exceeds maximum length of $MAX_KEY_LENGTH")
        }
        if (fallbackEndpoint != null) validateEndpoint(fallbackEndpoint)

        return ImportedProviderConfig(
            endpoint = endpoint.trim(),
            model = model.trim(),
            key = key?.trim(),
            fallbackEndpoint = fallbackEndpoint?.trim(),
            fallbackModel = fallbackModel?.trim(),
            fallbackKey = fallbackKey?.trim()
        )
    }

    private fun parseUri(uriString: String): ImportedProviderConfig {
        val queryPart = if (uriString.contains("?")) {
            uriString.substringAfter("?")
        } else {
            throw IllegalArgumentException("URI must contain query parameters with endpoint and model")
        }

        val params = mutableMapOf<String, String>()
        for (param in queryPart.split("&")) {
            if (param.isBlank()) continue
            val parts = param.split("=", limit = 2)
            val name = runCatching { URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()) }.getOrDefault(parts[0]).trim().lowercase(Locale.ROOT)
            val value = if (parts.size > 1) {
                runCatching { URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()) }.getOrDefault(parts[1]).trim()
            } else ""
            params[name] = value
        }

        val endpoint = params["endpoint"] ?: params["base_url"] ?: params["baseurl"] ?: params["url"]
            ?: throw IllegalArgumentException("Missing required provider endpoint in URI")
        val model = params["model"] ?: params["model_name"] ?: params["modelname"]
            ?: throw IllegalArgumentException("Missing required provider model in URI")
        val key = (params["key"] ?: params["api_key"] ?: params["apikey"])?.takeIf { it.isNotBlank() }
        val fallbackEndpoint = params["fallback_endpoint"] ?: params["fallbackendpoint"]
        val fallbackModel = params["fallback_model"] ?: params["fallbackmodel"]
        val fallbackKey = (params["fallback_key"] ?: params["fallbackkey"])?.takeIf { it.isNotBlank() }

        validateEndpoint(endpoint)
        validateModel(model)
        if (key != null && key.length > MAX_KEY_LENGTH) {
            throw IllegalArgumentException("API key exceeds maximum length of $MAX_KEY_LENGTH")
        }
        if (fallbackEndpoint != null) validateEndpoint(fallbackEndpoint)

        return ImportedProviderConfig(
            endpoint = endpoint.trim(),
            model = model.trim(),
            key = key?.trim(),
            fallbackEndpoint = fallbackEndpoint?.trim(),
            fallbackModel = fallbackModel?.trim(),
            fallbackKey = fallbackKey?.trim()
        )
    }

    private fun validateEndpoint(endpoint: String) {
        val trimmed = endpoint.trim()
        if (trimmed.isBlank()) {
            throw IllegalArgumentException("Endpoint cannot be blank")
        }
        val uri = runCatching { URI(trimmed) }.getOrElse {
            throw IllegalArgumentException("Invalid endpoint URL format: ${it.message}")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val isLocalhost = uri.host?.equals("localhost", ignoreCase = true) == true || uri.host == "127.0.0.1"
        if (scheme != "https" && !(scheme == "http" && isLocalhost)) {
            throw IllegalArgumentException("Endpoint must use https:// (or http:// on localhost)")
        }
        if (uri.host.isNullOrBlank()) {
            throw IllegalArgumentException("Endpoint URL must specify a valid host")
        }
    }

    private fun validateModel(model: String) {
        if (model.trim().isBlank()) {
            throw IllegalArgumentException("Model name cannot be blank")
        }
    }

    private fun JsonObject.findString(vararg keys: String): String? {
        for (k in keys) {
            val prim = this[k]?.jsonPrimitive
            if (prim != null && prim.isString) {
                val str = prim.content.trim()
                if (str.isNotEmpty()) return str
            }
        }
        return null
    }
}
