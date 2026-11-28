package com.opendroid.ai.ui.viewmodel

import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.opendroid.ai.core.llm.ModelDownloadTestApplication
import com.opendroid.ai.core.llm.OnDeviceEngineReleaser
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.core.service.McpConfigStore
import com.opendroid.ai.core.settings.AppSettingsStore
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.models.selectedModelFor
import com.opendroid.ai.data.repository.ModelRepository
import com.opendroid.ai.data.repository.SettingsRepository
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Drives the real ViewModel against a real DataStore. The ViewModel's coroutines run on the
 * paused Robolectric main looper, so the test advances that clock past the 1 s model debounce
 * and waits for the DataStore writes, which run on the IO dispatcher.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ModelDownloadTestApplication::class)
class SettingsViewModelModelSelectionTest {

    private lateinit var context: Context
    private lateinit var database: OpenDroidDatabase
    private lateinit var settings: SettingsRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(
                context,
                Configuration.Builder().setTaskExecutor(Executors.newSingleThreadExecutor()).build()
            )
        }
        database = Room.inMemoryDatabaseBuilder(context, OpenDroidDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = SettingsRepository(
            dataStore = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = {
                    Files.createTempDirectory("opendroid-settings-vm-test")
                        .resolve("settings.preferences_pb")
                        .toFile()
                }
            ),
            providerCredentialStore = EmptyProviderCredentialStore(),
            runStartupMigration = false
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `only JARVIS Cloud can be selected as the active provider`() {
        runBlocking { settings.updateConfig { LLMConfig(activeProvider = "JARVIS Cloud", activeModel = "glm-flash") } }
        val viewModel = viewModel()
        pumpUntil("initial config loaded") { viewModel.llmConfig.value.activeProvider == "JARVIS Cloud" }

        viewModel.updateActiveProvider("OpenAI")
        assertEquals("JARVIS Cloud", viewModel.llmConfig.value.activeProvider)
        pump(Duration.ofSeconds(2))
        assertEquals("JARVIS Cloud", stored().activeProvider)
    }

    @Test
    fun `only JARVIS Cloud model selections are persisted`() {
        runBlocking { settings.updateConfig { LLMConfig(activeProvider = "JARVIS Cloud", activeModel = "glm-flash") } }
        val viewModel = viewModel()
        pumpUntil("initial config loaded") { viewModel.llmConfig.value.activeProvider == "JARVIS Cloud" }

        viewModel.updateActiveModel("qwen3.5-32b")
        viewModel.updateActiveProvider("JARVIS Cloud")
        pumpUntil("cloud model pick persisted") {
            stored().selectedModelFor("JARVIS Cloud") == "qwen3.5-32b"
        }
        assertEquals("JARVIS Cloud", stored().activeProvider)
        assertEquals("qwen3.5-32b", stored().activeModel)
    }

    @Test
    fun `legacy external model selection is ignored`() {
        runBlocking { settings.updateConfig { LLMConfig(activeProvider = "JARVIS Cloud", activeModel = "glm-flash") } }
        val viewModel = viewModel()
        pumpUntil("initial config loaded") { viewModel.llmConfig.value.activeProvider == "JARVIS Cloud" }

        viewModel.updateActiveModel("gpt-4o", provider = "OpenAI")
        pump(Duration.ofSeconds(2))

        assertEquals("JARVIS Cloud", stored().activeProvider)
        assertEquals("glm-flash", stored().activeModel)
    }

    private fun viewModel() = SettingsViewModel(
        context = context,
        settingsRepository = settings,
        notificationDao = database.notificationDao(),
        llmProviderFactory = { error("no provider calls in this test") },
        modelFetcher = { error("no model fetches in this test") },
        modelRepository = ModelRepository(context, database.modelDao(), settings, OnDeviceEngineReleaser { }),
        okHttpClient = OkHttpClient(),
        providerCredentialStore = EmptyProviderCredentialStore(),
        appSettingsStore = NoOpAppSettingsStore(),
        mcpConfigStore = McpConfigStore(context),
        jarvisCloudSession = com.opendroid.ai.core.cloud.JarvisCloudSession(EmptyProviderCredentialStore(), OkHttpClient())
    )

    private fun stored(): LLMConfig = runBlocking { settings.llmConfig.first() }

    private fun pump(duration: Duration) {
        shadowOf(Looper.getMainLooper()).idleFor(duration)
    }

    /**
     * Advances the main-looper clock in [clockStep] steps, letting IO work finish in real time
     * between steps, until [condition] holds. At most 100 steps, so the 1 ms variant covers
     * 100 ms of ViewModel time and cannot reach the debounce.
     */
    private fun pumpUntil(
        what: String,
        clockStep: Duration = Duration.ofMillis(100),
        condition: () -> Boolean
    ) {
        repeat(100) {
            pump(clockStep)
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for: $what; stored = ${stored()}")
    }

    private class NoOpAppSettingsStore : AppSettingsStore {
        override fun isOnboardingCompleted(): Boolean = true
        override fun setOnboardingCompleted(completed: Boolean): Boolean = true
        override fun huggingFaceLastVerified(): String? = null
        override fun setHuggingFaceLastVerified(value: String?): Boolean = true
        override fun migrateLegacySettings(): Boolean = true
    }

    private class EmptyProviderCredentialStore : ProviderCredentialStore {
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
