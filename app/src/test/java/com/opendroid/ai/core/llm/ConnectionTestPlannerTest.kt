package com.opendroid.ai.core.llm

import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.data.models.LLMConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTestPlannerTest {
    @Test
    fun `only JARVIS Cloud is listed as a connection-test provider`() {
        assertEquals(listOf("JARVIS Cloud"), ConnectionTestPlanner.cloudProviders())
        assertEquals(listOf("JARVIS Cloud"), ConnectionTestPlanner.configuredProviders(LLMConfig()))
    }

    @Test
    fun `legacy external providers are rejected without probing`() {
        listOf("OpenAI", "Groq", "OpenRouter", "Anthropic Claude", "Ollama", "Custom OpenAI Compatible")
            .forEach { provider ->
                val gap = ConnectionTestPlanner.configurationGap(
                    LLMConfig(apiKeys = mapOf(provider to "legacy-secret")), provider
                )
                assertEquals(LLMError.ProviderUnrecognized, gap?.reason)
            }
    }

    @Test
    fun `legacy provider credentials are never copied into a connection test`() {
        val saved = LLMConfig(apiKeys = mapOf("OpenAI" to "stored-secret"))
        val candidate = saved.copy(apiKeys = mapOf("OpenAI" to "new-secret"))
        assertEquals(saved, ConnectionTestPlanner.withCandidateConnection(saved, candidate, "OpenAI"))
    }

    @Test
    fun `JARVIS Cloud requires no user API key`() {
        assertNull(ConnectionTestPlanner.configurationGap(LLMConfig(), "JARVIS Cloud"))
    }

    @Test
    fun `success records the JARVIS Cloud response latency`() {
        val connected = ConnectionTestPlanner.success(
            provider = "JARVIS Cloud",
            model = "glm-flash",
            latencyMs = 342L,
            testedAtMillis = 1L
        )
        assertEquals(342L, connected.latencyMs)
        assertEquals("glm-flash", connected.model)
        assertTrue(connected.provider == "JARVIS Cloud")
    }
}
