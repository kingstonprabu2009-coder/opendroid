package com.opendroid.ai.core.llm.providers

import com.google.gson.Gson
import com.opendroid.ai.BuildConfig
import com.opendroid.ai.core.cloud.JarvisCloudSession
import com.opendroid.ai.core.llm.LLMProvider
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.LLMResponse
import com.opendroid.ai.data.models.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** First-party JARVIS gateway. It never silently falls back to another provider. */
@Singleton
class JarvisCloudProvider @Inject constructor(
    private val session: JarvisCloudSession
) : LLMProvider {
    override val name = "JARVIS Cloud"
    override val availableModels = listOf("jarvis-cloud")
    private val gson = Gson()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override suspend fun complete(request: LLMRequest): LLMResponse = withContext(Dispatchers.IO) {
        val baseUrl = BuildConfig.JARVIS_CLOUD_BASE_URL.trim()
        if (!baseUrl.startsWith("https://")) {
            throw IOException("JARVIS Cloud is not configured yet. Cloud deployment URL is required.")
        }
        if (request.messages.any { it.imageBase64 != null }) {
            throw IOException("JARVIS Cloud image understanding is not enabled yet. Text chat is supported.")
        }
        val messages = request.messages.map { message ->
            mapOf(
                "role" to if (message.sender == ChatMessage.Sender.USER) "user" else "assistant",
                "content" to message.text
            )
        }
        if (messages.isEmpty()) throw IOException("Add a message before sending.")
        val root = baseUrl.trimEnd('/') + "/"
        val payload = gson.toJson(
            mapOf(
                "application_context" to request.systemPrompt.take(24000),
                "max_tokens" to request.maxTokens.coerceIn(1, 4096),
                "team_id" to request.teamId,
                "messages" to messages
            )
        )
        val httpRequest = Request.Builder().url(root + "v1/chat")
            .post(payload.toRequestBody(jsonType)).build()
        val started = System.currentTimeMillis()
        session.authenticatedClient().newCall(httpRequest).execute().use { response ->
            val body = response.body.string()
            if (response.code == 401) throw IOException("Sign in to your JARVIS Cloud account first.")
            if (!response.isSuccessful) {
                val detail = runCatching {
                    gson.fromJson(body, Map::class.java)?.get("error")?.toString()
                }.getOrNull()
                throw IOException("JARVIS Cloud request failed (HTTP ${response.code}): ${detail ?: response.message}")
            }
            val parsed = runCatching { gson.fromJson(body, GatewayResponse::class.java) }.getOrNull()
            val content = parsed?.choices?.firstOrNull()?.message?.content?.trim().orEmpty()
            if (content.isBlank()) throw IOException("JARVIS Cloud returned an empty response.")
            LLMResponse(
                content = content,
                tokensUsed = parsed.usage?.total_tokens ?: 0,
                model = parsed.model ?: "jarvis-cloud",
                provider = name,
                latencyMs = System.currentTimeMillis() - started
            )
        }
    }

    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        emit(complete(request).content)
    }

    override suspend fun isAvailable(): Boolean =
        BuildConfig.JARVIS_CLOUD_BASE_URL.startsWith("https://") &&
            runCatching { session.verifySession(BuildConfig.JARVIS_CLOUD_BASE_URL) }.getOrDefault(false)

    private data class GatewayResponse(val model: String? = null, val choices: List<Choice>? = null, val usage: Usage? = null)
    private data class Choice(val message: Message? = null)
    private data class Message(val content: String? = null)
    private data class Usage(val total_tokens: Int? = null)
}
