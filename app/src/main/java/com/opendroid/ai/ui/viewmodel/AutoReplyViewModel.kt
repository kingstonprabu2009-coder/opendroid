package com.opendroid.ai.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.opendroid.ai.data.models.AutoReplyConfig
import com.opendroid.ai.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TEXT_EDIT_DEBOUNCE_MILLIS = 1000L

@HiltViewModel
class AutoReplyViewModel internal constructor(
    private val settingsRepository: SettingsRepository,
    // Deliberately not viewModelScope: the write started by onCleared() has to outlive the
    // ViewModel, or the last edit is lost exactly when the user leaves the screen.
    private val writeScope: CoroutineScope,
    private val debounceMillis: Long
) : ViewModel() {

    @Inject
    constructor(settingsRepository: SettingsRepository) : this(
        settingsRepository,
        CoroutineScope(Dispatchers.IO + SupervisorJob()),
        TEXT_EDIT_DEBOUNCE_MILLIS
    )

    // Snapshot state rather than a StateFlow: the text fields read this back on every keystroke,
    // and an asynchronous round trip drops characters and moves the cursor.
    var config by mutableStateOf(AutoReplyConfig())
        private set
    var isLoading by mutableStateOf(true)
        private set

    private val pendingConfig = AtomicReference<AutoReplyConfig?>(null)
    private val writeMutex = Mutex()
    private var debounceJob: Job? = null

    init {
        writeScope.launch {
            config = settingsRepository.autoReplyConfig.first()
            isLoading = false
        }
    }

    fun saveConfig(newConfig: AutoReplyConfig, debounce: Boolean = false) {
        config = newConfig
        pendingConfig.set(newConfig)
        debounceJob?.cancel()
        if (debounce) {
            debounceJob = writeScope.launch {
                delay(debounceMillis)
                // The debounce job only schedules persistence. The actual DataStore write runs
                // in an independent scope job, so cancelling/replacing a later debounce can
                // never cancel a write that has already started.
                writeScope.launch {
                    persistPendingConfig()
                }
            }
        } else {
            flushPendingConfig()
        }
    }

    /** Writes a debounced edit now instead of waiting out the delay. */
    fun flushPendingConfig() {
        debounceJob?.cancel()
        debounceJob = null
        // A lifecycle flush must finish before the screen can be disposed or the app
        // backgrounded. Run the DataStore transaction on IO and wait for its completion;
        // otherwise the process may suspend the independent write scope after the callback.
        runBlocking(Dispatchers.IO) {
            withContext(NonCancellable) {
                persistPendingConfig()
            }
        }
    }

    override fun onCleared() {
        // ViewModelStore.clear() is synchronous. Complete the final pending DataStore
        // transaction before returning so a popped screen cannot lose its last edit.
        debounceJob?.cancel()
        debounceJob = null
        runBlocking(Dispatchers.IO) {
            withContext(NonCancellable) {
                persistPendingConfig()
            }
        }
    }

    // A separate job, so cancelling the debounce can never interrupt a write already under way.
    private suspend fun persistPendingConfig() {
        // Each write takes the newest config, and the lock keeps an older write from landing
        // after it. Flush starts this suspend function immediately from lifecycle callbacks.
        writeMutex.withLock {
            val toWrite = pendingConfig.get() ?: return@withLock
            try {
                settingsRepository.updateAutoReplyConfig(toWrite)
                // Clear only the exact snapshot successfully persisted. If the user typed
                // again while this transaction was running, keep the newer value pending.
                pendingConfig.compareAndSet(toWrite, null)
            } catch (e: Exception) {
                // Keep the pending value so a later lifecycle flush can retry it.
                android.util.Log.e("AutoReplySettings", "Failed to save config: ${e.message}", e)
            }
        }
    }
}
