package com.anchor.core.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigParserTest {

    @Test
    fun parseJson_ValidWithKey_ReturnsAllFields() {
        val json = """
            {
                "endpoint": "https://api.openai.com/v1",
                "model": "gpt-4o",
                "key": "sk-12345"
            }
        """.trimIndent()

        val result = ProviderConfigParser.parse(json)
        assertTrue("Expected success but was $result", result.isSuccess)
        val config = result.getOrThrow()
        assertEquals("https://api.openai.com/v1", config.endpoint)
        assertEquals("gpt-4o", config.model)
        assertEquals("sk-12345", config.key)
    }

    @Test
    fun parseJson_ValidWithoutKey_ReturnsNullKey() {
        val json = """
            {
                "endpoint": "https://api.openai.com/v1",
                "model": "gpt-4o"
            }
        """.trimIndent()

        val result = ProviderConfigParser.parse(json)
        assertTrue(result.isSuccess)
        val config = result.getOrThrow()
        assertEquals("https://api.openai.com/v1", config.endpoint)
        assertEquals("gpt-4o", config.model)
        assertNull(config.key)
    }

    @Test
    fun parseJson_WithSynonyms_ReturnsCanonicalFields() {
        val json = """
            {
                "base_url": "https://api.mistral.ai/v1",
                "model_name": "mistral-large",
                "api_key": "mis-123"
            }
        """.trimIndent()

        val result = ProviderConfigParser.parse(json)
        assertTrue(result.isSuccess)
        val config = result.getOrThrow()
        assertEquals("https://api.mistral.ai/v1", config.endpoint)
        assertEquals("mistral-large", config.model)
        assertEquals("mis-123", config.key)
    }

    @Test
    fun parseJson_CamelCaseSynonyms_ReturnsCanonicalFields() {
        val json = """
            {
                "baseUrl": "https://openrouter.ai/api/v1",
                "model": "anthropic/claude-3.5-sonnet",
                "apiKey": "sk-or-v1-xyz"
            }
        """.trimIndent()

        val result = ProviderConfigParser.parse(json)
        assertTrue(result.isSuccess)
        val config = result.getOrThrow()
        assertEquals("https://openrouter.ai/api/v1", config.endpoint)
        assertEquals("anthropic/claude-3.5-sonnet", config.model)
        assertEquals("sk-or-v1-xyz", config.key)
    }

    @Test
    fun parseUri_ValidMineScheme_ReturnsConfig() {
        val uri = "mine://provider?endpoint=https%3A%2F%2Fapi.openai.com%2Fv1&model=gpt-4o&key=sk-abc"

        val result = ProviderConfigParser.parse(uri)
        assertTrue(result.isSuccess)
        val config = result.getOrThrow()
        assertEquals("https://api.openai.com/v1", config.endpoint)
        assertEquals("gpt-4o", config.model)
        assertEquals("sk-abc", config.key)
    }

    @Test
    fun parseUri_ValidWithoutKey_ReturnsNullKey() {
        val uri = "mine://provider?endpoint=https%3A%2F%2Fapi.openai.com%2Fv1&model=gpt-4o-mini"

        val result = ProviderConfigParser.parse(uri)
        assertTrue(result.isSuccess)
        val config = result.getOrThrow()
        assertEquals("https://api.openai.com/v1", config.endpoint)
        assertEquals("gpt-4o-mini", config.model)
        assertNull(config.key)
    }

    @Test
    fun parseUri_UnencodedEndpoint_ParsesCorrectly() {
        val uri = "mine://provider?endpoint=https://api.groq.com/openai/v1&model=llama-3.3-70b-versatile"

        val result = ProviderConfigParser.parse(uri)
        assertTrue(result.isSuccess)
        val config = result.getOrThrow()
        assertEquals("https://api.groq.com/openai/v1", config.endpoint)
        assertEquals("llama-3.3-70b-versatile", config.model)
        assertNull(config.key)
    }

    @Test
    fun parse_InvalidScheme_Fails() {
        val json = """{"endpoint": "file:///data/local/tmp", "model": "test"}"""
        val result = ProviderConfigParser.parse(json)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("http", ignoreCase = true) == true)
    }

    @Test
    fun parse_NonHttpUri_Fails() {
        val uri = "mine://provider?endpoint=ftp://example.com/v1&model=test"
        val result = ProviderConfigParser.parse(uri)
        assertTrue(result.isFailure)
    }

    @Test
    fun parse_EmptyModel_Fails() {
        val json = """{"endpoint": "https://api.openai.com/v1", "model": "  "}"""
        val result = ProviderConfigParser.parse(json)
        assertTrue(result.isFailure)
    }

    @Test
    fun parse_MissingEndpoint_Fails() {
        val json = """{"model": "gpt-4o"}"""
        val result = ProviderConfigParser.parse(json)
        assertTrue(result.isFailure)
    }

    @Test
    fun parse_MalformedJsonOrGarbage_Fails() {
        val result = ProviderConfigParser.parse("this is random garbage text")
        assertTrue(result.isFailure)
    }

    @Test
    fun parse_OversizedPayload_Fails() {
        val oversized = "{\"endpoint\": \"https://api.com\", \"model\": \"m\", \"extra\": \"" + "a".repeat(70_000) + "\"}"
        val result = ProviderConfigParser.parse(oversized)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("large", ignoreCase = true) == true ||
                   result.exceptionOrNull()?.message?.contains("size", ignoreCase = true) == true)
    }

    @Test
    fun parse_WhitespaceTrimmed() {
        val json = """
            {
                "endpoint": "  https://api.openai.com/v1  ",
                "model": "  gpt-4o  ",
                "key": "  sk-test  "
            }
        """.trimIndent()

        val result = ProviderConfigParser.parse(json)
        assertTrue(result.isSuccess)
        val config = result.getOrThrow()
        assertEquals("https://api.openai.com/v1", config.endpoint)
        assertEquals("gpt-4o", config.model)
        assertEquals("sk-test", config.key)
    }
}
