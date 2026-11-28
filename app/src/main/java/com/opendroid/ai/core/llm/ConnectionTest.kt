package com.opendroid.ai.core.llm

import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMErrorMapper
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.models.selectedModelFor

/**
 * Typed connection-test outcomes shared by Settings and Benchmark.
 * Latency is recorded only for successful responses — never as a failure sentinel.
 */
sealed class ConnectionTestState {
    data object Idle : ConnectionTestState()
    data class Testing(val provider: String, val index: Int = 1, val total: Int = 1) : ConnectionTestState()
    data class Connected(
        val provider: String,
        val model: String,
        val latencyMs: Long,
        val testedAtMillis: Long
    ) : ConnectionTestState()
    data class Failed(
        val provider: String,
        val model: String,
        val error: LLMError,
        val status: Int? = null,
        val retryAfterMillis: Long? = null,
        val testedAtMillis: Long
    ) : ConnectionTestState()
    data class ConfigMissing(
        val provider: String,
        val reason: LLMError,
        val testedAtMillis: Long
    ) : ConnectionTestState()
}

object ConnectionTestPlanner {
    fun cloudProviders(): List<String> = listOf("JARVIS Cloud")

    fun configuredProviders(config: LLMConfig): List<String> =
        cloudProviders().filter { provider -> configurationGap(config, provider) == null }

    /**
     * Only the first-party JARVIS Cloud brain can be probed. Legacy provider settings
     * are intentionally not activated or sent credentials after the cloud-only switch.
     */
    fun configurationGap(config: LLMConfig, providerName: String): ConnectionTestState.ConfigMissing? {
        val provider = ProviderCatalog.canonicalName(providerName)
        if (!isFirstPartyBrainProvider(provider)) {
            return ConnectionTestState.ConfigMissing(provider, LLMError.ProviderUnrecognized, 0L)
        }
        return null
    }

    /** Legacy provider key/endpoint edits are ignored; JARVIS Cloud has no user API key. */
    fun withCandidateConnection(saved: LLMConfig, candidate: LLMConfig, providerName: String): LLMConfig =
        if (isFirstPartyBrainProvider(providerName)) saved else saved


    fun fromException(
        provider: String,
        model: String,
        throwable: Throwable,
        testedAtMillis: Long
    ): ConnectionTestState {
        val failure = throwable as? LLMException
            ?: LLMErrorMapper.fromThrowable(provider, model, throwable)
        return ConnectionTestState.Failed(
            provider = failure.provider,
            model = failure.model,
            error = failure.error,
            status = failure.status,
            retryAfterMillis = failure.retryAfterMillis,
            testedAtMillis = testedAtMillis
        )
    }

    fun success(
        provider: String,
        model: String,
        latencyMs: Long,
        testedAtMillis: Long
    ): ConnectionTestState.Connected {
        require(isFirstPartyBrainProvider(provider)) { "Only the JARVIS Cloud brain can be benchmarked." }
        return ConnectionTestState.Connected(
        provider = ProviderCatalog.canonicalName(provider),
        model = model,
        latencyMs = latencyMs,
        testedAtMillis = testedAtMillis
        )
    }

    fun stamp(
        state: ConnectionTestState.ConfigMissing,
        testedAtMillis: Long
    ): ConnectionTestState.ConfigMissing = state.copy(testedAtMillis = testedAtMillis)
}
