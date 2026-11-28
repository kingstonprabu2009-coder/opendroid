package com.opendroid.ai.core.llm

import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.core.agent.DeviceStateProvider
import com.opendroid.ai.core.agent.IntentClassifier
import com.opendroid.ai.core.llm.error.LLMErrorMapper
import com.opendroid.ai.core.llm.error.SecretRegistry
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.models.selectedModelFor
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlin.math.min
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LLMProviderFactory @Inject constructor(
    private val jarvisCloudProvider: JarvisCloudProvider,
    private val settingsRepository: SettingsRepository,
    private val onDeviceLatencyTracker: OnDeviceLatencyTracker,
    private val actionDispatcher: dagger.Lazy<ActionDispatcher>,
    private val intentClassifier: dagger.Lazy<IntentClassifier>,
    private val deviceStateProvider: DeviceStateProvider
) {
    private val planningRequestRewriter = PlanningRequestRewriter(
        classifyComplexity = { intentClassifier.get().classifyComplexity(it) },
        registeredActions = { actionDispatcher.get().getAllRegisteredActions() },
        deviceState = { deviceStateProvider.getFullStateString() }
    )

    fun getProviderByName(name: String): LLMProvider {
        if (!isFirstPartyBrainProvider(name)) {
            // Legacy provider names may remain in old settings, but they must never
            // activate another model/API. Refuse them before constructing a provider.
            return UnknownProvider(name)
        }
        return WrappedLLMProvider(
            delegate = jarvisCloudProvider,
            configProvider = { settingsRepository.llmConfig.first() },
            requestRewriter = planningRequestRewriter::rewrite
        )
    }

    /** JARVIS is cloud-only: every chat request uses the single authenticated cloud brain. */
    suspend fun getActiveProvider(): LLMProvider = getProviderByName("JARVIS Cloud")

    /**
     * The cloud gateway is the only planner provider. All specialist selection
     * and recovery happen inside the single JARVIS Cloud Brain Router.
     */
    suspend fun getPlanningProviders(actionNames: Collection<String>): List<LLMProvider> =
        listOf(getProviderByName("JARVIS Cloud"))

    suspend fun recordPlanningLatency(response: LLMResponse): LatencyBudgetResult? =
        runCatching { onDeviceLatencyTracker.recordPlanning(response) }.getOrNull()

}

internal fun isFirstPartyBrainProvider(name: String): Boolean =
    ProviderCatalog.canonicalName(name) == "JARVIS Cloud"

/**
 * Stands in for any legacy or unsupported provider name. Every call fails with
 * LLMError.ProviderUnrecognized before network access, so no prompt reaches a
 * non-JARVIS provider. Not wrapped: [WrappedLLMProvider] resolves a provider model.
 */
private class UnknownProvider(override val name: String) : LLMProvider {
    override val availableModels: List<String> = emptyList()

    override suspend fun complete(request: LLMRequest): LLMResponse =
        throw LLMErrorMapper.providerUnrecognized(name, request.model.orEmpty())

    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        throw LLMErrorMapper.providerUnrecognized(name, request.model.orEmpty())
    }

    override suspend fun isAvailable(): Boolean = false
}

data class RetryRuntime(
    val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    val delayMillis: suspend (Long) -> Unit = { delay(it) },
    val jitterMillis: (Long) -> Long = { upperBound ->
        if (upperBound <= 0L) 0L else Random.nextLong(upperBound + 1L)
    }
)

class WrappedLLMProvider(
    private val delegate: LLMProvider,
    private val configProvider: suspend () -> LLMConfig,
    private val requestRewriter: (LLMRequest) -> LLMRequest = { it },
    private val retryRuntime: RetryRuntime = RetryRuntime()
) : LLMProvider {
    override val name: String get() = delegate.name
    override val availableModels: List<String> get() = delegate.availableModels

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val resolved = resolveRequest(request)
        val registrations = registerSecrets(resolved)
        return try {
            executeWithRetry(resolved) { delegate.complete(it) }
        } finally {
            registrations.asReversed().forEach(AutoCloseable::close)
        }
    }

    override suspend fun generate(
        messages: List<com.opendroid.ai.data.models.ChatMessage>,
        tools: List<ToolDefinition>
    ): Flow<StreamChunk> = flow {
        val request = LLMRequest(
            systemPrompt = "You are an autonomous AI agent for Android.",
            messages = messages,
            tools = tools.map { Tool(it.name, it.description, it.parameters) }
        )
        streamComplete(request).collect { emit(StreamChunk.Content(it)) }
    }

    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        val resolved = resolveRequest(request)
        val registrations = registerSecrets(resolved)
        var attempt = 1
        var emitted = false
        // A failure thrown by our own collector (e.g. a Room write per chunk) is not the provider's.
        var collectorFailure: Throwable? = null
        val startedAt = retryRuntime.nowMillis()
        try {
            while (true) {
                try {
                    delegate.streamComplete(resolved).collect { chunk ->
                        if (chunk.isNotEmpty()) {
                            emitted = true
                            try {
                                emit(chunk)
                            } catch (throwable: Throwable) {
                                collectorFailure = throwable
                                throw throwable
                            }
                        }
                    }
                    if (!emitted) {
                        throw LLMErrorMapper.malformed(name, resolved.model.orEmpty(), transient = true)
                    }
                    break
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    if (throwable === collectorFailure) throw throwable
                    val failure = LLMErrorMapper.fromThrowable(name, resolved.model.orEmpty(), throwable)
                    val delayMillis = proposedDelayMillis(failure, attempt)
                    if (emitted || !shouldRetry(resolved, failure, attempt, startedAt, delayMillis)) throw failure
                    retryRuntime.delayMillis(delayMillis)
                    attempt++
                }
            }
        } finally {
            registrations.asReversed().forEach(AutoCloseable::close)
        }
    }

    override suspend fun isAvailable(): Boolean = delegate.isAvailable()

    private suspend fun resolveRequest(request: LLMRequest): LLMRequest {
        val config = configProvider()
        val provider = ProviderCatalog.canonicalName(name)
        val model = config.selectedModelFor(provider)
        val endpoint = when (provider) {
            "Custom OpenAI Compatible" -> config.customEndpoints[provider].orEmpty().trim()
            "Copilot API" -> config.copilotUrl.trim()
            "Ollama" -> config.ollamaUrl.trim()
            else -> config.customEndpoints[provider].orEmpty().trim()
        }
        val apiKey = config.apiKeys[provider].orEmpty()

        if (ProviderCatalog.requiresApiKey(provider) && apiKey.isBlank()) {
            throw LLMErrorMapper.authMissing(provider, model)
        }
        if (provider in setOf("Custom OpenAI Compatible", "Copilot API", "Ollama") && endpoint.isBlank()) {
            throw LLMErrorMapper.requestInvalid(provider, model)
        }

        return requestRewriter(request).copy(
            model = model,
            providerConfig = ProviderRequestConfig(apiKey = apiKey, endpoint = endpoint)
        )
    }

    private suspend fun <T> executeWithRetry(
        request: LLMRequest,
        operation: suspend (LLMRequest) -> T
    ): T {
        var attempt = 1
        val startedAt = retryRuntime.nowMillis()
        while (true) {
            try {
                return operation(request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                val failure = LLMErrorMapper.fromThrowable(name, request.model.orEmpty(), throwable)
                val delayMillis = proposedDelayMillis(failure, attempt)
                if (!shouldRetry(request, failure, attempt, startedAt, delayMillis)) throw failure
                retryRuntime.delayMillis(delayMillis)
                attempt++
            }
        }
    }

    private fun shouldRetry(
        request: LLMRequest,
        failure: com.opendroid.ai.core.llm.error.LLMException,
        attempt: Int,
        startedAt: Long,
        proposedDelay: Long
    ): Boolean {
        if (request.retryPolicy == RetryPolicy.NONE || !failure.retryable || attempt >= MAX_ATTEMPTS) {
            return false
        }
        val elapsed = (retryRuntime.nowMillis() - startedAt).coerceAtLeast(0L)
        if (elapsed >= RETRY_WINDOW_MILLIS) return false
        return proposedDelay <= RETRY_WINDOW_MILLIS - elapsed
    }

    private fun proposedDelayMillis(
        failure: com.opendroid.ai.core.llm.error.LLMException,
        attempt: Int
    ): Long {
        failure.retryAfterMillis?.let { retryAfter ->
            val jitter = retryRuntime.jitterMillis(250L).coerceIn(0L, 250L)
            // Saturate: a wrapped negative delay would pass the retry window and retry at once.
            return if (retryAfter > Long.MAX_VALUE - jitter) Long.MAX_VALUE else retryAfter + jitter
        }
        val exponentialCap = min(500L shl (attempt - 1), 4_000L)
        return 250L + retryRuntime.jitterMillis(exponentialCap).coerceIn(0L, exponentialCap)
    }

    private fun registerSecrets(request: LLMRequest): List<AutoCloseable> = buildList {
        request.providerConfig?.apiKey?.takeIf(String::isNotBlank)?.let {
            add(SecretRegistry.register(it))
        }
        request.providerConfig?.endpoint?.takeIf(String::isNotBlank)?.let {
            add(SecretRegistry.register(it))
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val RETRY_WINDOW_MILLIS = 30_000L
    }
}
