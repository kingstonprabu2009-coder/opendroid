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
import com.opendroid.ai.data.repository.SettingsRepository
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import okhttp3.OkHttpClient

/** The saved provider setting must not bypass JARVIS's single online cloud gateway. */
class LLMProviderFactoryUnknownProviderTest {

    @Test
    fun `an old or unknown saved provider is routed through JARVIS Cloud`() {
        val factory = newFactory(savedActiveProvider = "Requesty")
        val active = runBlocking { factory.getActiveProvider() }

        assertEquals("JARVIS Cloud", active.name)
    }

    @Test
    fun `planning uses only the JARVIS Cloud gateway`() {
        val factory = newFactory(savedActiveProvider = "Llama.cpp (Local 7B)")
        val providers = runBlocking { factory.getPlanningProviders(emptyList()) }

        assertEquals(1, providers.size)
        assertEquals("JARVIS Cloud", providers.single().name)
    }

    @Test
    fun `a saved local provider cannot become the active brain`() {
        val factory = newFactory(savedActiveProvider = "On-Device AI")
        val active = runBlocking { factory.getActiveProvider() }

        assertEquals("JARVIS Cloud", active.name)
        assertEquals(listOf("jarvis-cloud"), active.availableModels)
    }

    private fun newFactory(savedActiveProvider: String): LLMProviderFactory {
        val settings = newSettingsRepository()
        runBlocking {
            settings.updateConfig { it.copy(activeProvider = savedActiveProvider) }
        }
        return LLMProviderFactory(
            jarvisCloudProvider = JarvisCloudProvider(
                JarvisCloudSession(FixedKeyCredentialStore(), OkHttpClient())
            ),
            settingsRepository = settings,
            onDeviceLatencyTracker = OnDeviceLatencyTracker(settings),
            actionDispatcher = dagger.Lazy<ActionDispatcher> { throw AssertionError("unused") },
            intentClassifier = dagger.Lazy<IntentClassifier> { throw AssertionError("unused") },
            deviceStateProvider = DeviceStateProvider(ContextWrapper(null))
        )
    }

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
