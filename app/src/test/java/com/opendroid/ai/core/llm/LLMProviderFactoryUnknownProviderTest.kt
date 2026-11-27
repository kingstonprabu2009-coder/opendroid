package com.opendroid.ai.core.llm

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.core.agent.DeviceStateProvider
import com.opendroid.ai.core.agent.IntentClassifier
import com.opendroid.ai.core.cloud.JarvisCloudSession
import com.opendroid.ai.core.llm.providers.JarvisCloudProvider
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.repository.SettingsRepository
import java.nio.file.Files
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.OkHttpClient

/** The saved provider setting must not bypass JARVIS's single online cloud gateway. */
class LLMProviderFactoryUnknownProviderTest {

    private var geminiRequests = 0

    @Test
    fun `an old or unknown saved provider is routed through JARVIS Cloud`() {
        val factory = newFactory(savedActiveProvider = "Requesty")
        val active = runBlocking { factory.getActiveProvider() }

        assertEquals("JARVIS Cloud", active.name)
        assertEquals(0, geminiRequests)
    }

    @Test
    fun `planning uses only the JARVIS Cloud gateway`() {
        val factory = newFactory(savedActiveProvider = "Llama.cpp (Local 7B)")
        val providers = runBlocking { factory.getPlanningProviders(emptyList()) }

        assertEquals(1, providers.size)
        assertEquals("JARVIS Cloud", providers.single().name)
        assertEquals(0, geminiRequests)
    }

    @Test
    fun `a saved local provider cannot become the active brain`() {
        val factory = newFactory(savedActiveProvider = "On-Device AI")
        val active = runBlocking { factory.getActiveProvider() }

        assertEquals("JARVIS Cloud", active.name)
        assertEquals(listOf("jarvis-cloud"), active.availableModels)
        assertEquals(0, geminiRequests)
    }

    private fun newFactory(savedActiveProvider: String): LLMProviderFactory {
        val settings = newSettingsRepository()
        runBlocking {
            settings.updateConfig { it.copy(activeProvider = savedActiveProvider) }
        }
        return LLMProviderFactory(
            claudeProvider = unused("Claude"),
            openAIProvider = unused("OpenAI"),
            geminiProvider = Provider {
                geminiRequests++
                throw AssertionError("Direct Gemini must not be used by the cloud-only brain")
            },
            jarvisCloudProvider = Provider { JarvisCloudProvider(JarvisCloudSession(FixedKeyCredentialStore(), OkHttpClient())) },
            mistralProvider = unused("Mistral"),
            groqProvider = unused("Groq"),
            ollamaProvider = unused("Ollama"),
            openRouterProvider = unused("OpenRouter"),
            togetherAIProvider = unused("Together"),
            cohereProvider = unused("Cohere"),
            deepSeekProvider = unused("DeepSeek"),
            copilotProvider = unused("Copilot"),
            opencodeGoProvider = unused("Opencode-Go"),
            customOpenAIProvider = unused("Custom"),
            gemmaProvider = unused("Gemma"),
            liteRTLMProvider = unused("LiteRT-LM"),
            hybridOnDeviceProvider = unused("Hybrid"),
            llamaCppProvider = unused("Llama.cpp"),
            settingsRepository = settings,
            onDeviceLatencyTracker = OnDeviceLatencyTracker(settings),
            actionDispatcher = dagger.Lazy<ActionDispatcher> { throw AssertionError("unused") },
            intentClassifier = dagger.Lazy<IntentClassifier> { throw AssertionError("unused") },
            deviceStateProvider = DeviceStateProvider(ContextWrapper(null))
        )
    }

    private fun <T> unused(name: String): Provider<T> =
        Provider { throw AssertionError("$name must not be used") }

    private fun newSettingsRepository() = SettingsRepository(
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = {
                Files.createTempDirectory("opendroid-cloud-only-test")
                    .resolve("settings.preferences_pb")
                    .toFile()
            }
        ),
        providerCredentialStore = FixedKeyCredentialStore(),
        runStartupMigration = false
    )

    private class FixedKeyCredentialStore : ProviderCredentialStore {
        override val recoveryState: StateFlow<ProviderCredentialRecoveryState> =
            MutableStateFlow(ProviderCredentialRecoveryState.Ready)

        override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> =
            CredentialStoreResult.Success(null)
        override fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>> =
            CredentialStoreResult.Success(emptyMap())
        override fun write(credential: ProviderCredentialId, value: String): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)
        override fun remove(credential: ProviderCredentialId): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)
        override fun migrateLegacyCredentials(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)
        override fun resetForReentry(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)
    }
}
