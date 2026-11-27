package com.opendroid.ai.core.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class SpeechRecognitionEngine(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var usingOnDeviceRecognition = false

    // Identifies the currently active recognition session. Every startListening() call mints a
    // new token and each RecognitionListener callback closure captures the token it was created
    // with, comparing it against [activeSessionId] before delivering anything. Stale callbacks
    // from a cancelled or superseded session are dropped.
    private var activeSessionId = 0

    // Tamil (India) is the main language for Jarvis commands.
    private val languageTag = "ta-IN"

    init {
        initializeRecognizer()
    }

    private val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    private val beepStreams = listOf(
        android.media.AudioManager.STREAM_NOTIFICATION,
        android.media.AudioManager.STREAM_SYSTEM
    )
    private val mutedByUs = mutableSetOf<Int>()

    // Silences the mic start/stop beep so the mic feels like a live call.
    private fun muteBeep() {
        for (st in beepStreams) {
            try {
                if (!audioManager.isStreamMute(st)) {
                    audioManager.adjustStreamVolume(st, android.media.AudioManager.ADJUST_MUTE, 0)
                    mutedByUs.add(st)
                }
            } catch (e: Exception) {}
        }
    }

    private fun unmuteBeep() {
        for (st in mutedByUs.toList()) {
            try { audioManager.adjustStreamVolume(st, android.media.AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) {}
        }
        mutedByUs.clear()
    }

    private fun initializeRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            // Prefer offline recognition when Android has an installed on-device engine.
            // This avoids depending on a second network service just to capture a command.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                usingOnDeviceRecognition = true
            } else {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, usingOnDeviceRecognition)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Prolong listening limits to avoid early cut-offs
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            }
        }
    }

    fun startListening(
        onResult: (String) -> Unit,
        onPartialResult: (String) -> Unit = {},
        onError: (String) -> Unit,
        // Optional: all alternative transcriptions (best first). Used later for auto-correction.
        onAlternatives: (List<String>) -> Unit = {}
    ) {
        if (speechRecognizer == null) {
            onError("Speech recognition not available on this device")
            return
        }

        activeSessionId += 1
        val sessionId = activeSessionId
        muteBeep()

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { unmuteBeep() }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                if (sessionId != activeSessionId) return
                // Some devices expose an offline recognizer but do not have Tamil data.
                // Retry this session once with Android's standard recognizer instead of
                // leaving the user stuck on an unavailable offline language pack.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && usingOnDeviceRecognition &&
                    (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
                ) {
                    runCatching {
                        speechRecognizer?.destroy()
                        usingOnDeviceRecognition = false
                        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                        recognizerIntent?.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                        speechRecognizer?.setRecognitionListener(this)
                        speechRecognizer?.startListening(recognizerIntent)
                    }.onFailure { fallbackError ->
                        unmuteBeep()
                        onError(fallbackError.message?.take(120)
                            ?: "Tamil offline speech is unavailable. Check internet and retry.")
                    }
                    return
                }
                unmuteBeep()
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> if (usingOnDeviceRecognition) {
                        "On-device speech service failed. Check that Tamil offline speech data is installed."
                    } else {
                        "Android speech recognition network unavailable. Check internet or install offline Tamil speech data."
                    }
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition timed out. Check internet or try offline speech data."
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech match found"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition engine busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech input timeout"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Tamil is not supported by this speech engine. Install Tamil speech data."
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Tamil speech data is unavailable. Install the Tamil language pack."
                    else -> "Unknown error ($error)"
                }
                onError(message)
            }

            override fun onResults(results: Bundle?) {
                unmuteBeep()
                if (sessionId != activeSessionId) return
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    onAlternatives(matches.toList())
                    onResult(matches[0])
                } else {
                    onError("No transcription results found")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (sessionId != activeSessionId) return
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    onPartialResult(matches[0])
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            speechRecognizer?.startListening(recognizerIntent)
        } catch (error: Exception) {
            unmuteBeep()
            if (sessionId == activeSessionId) {
                onError(error.message?.take(120)?.let { "Could not start speech recognition: $it" }
                    ?: "Could not start speech recognition. Try again.")
            }
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {
            unmuteBeep()
        }
    }

    /**
     * Cancels the in-progress recognition session outright. Any callback still in flight is
     * dropped because the session token it captured no longer matches [activeSessionId].
     */
    fun cancel() {
        activeSessionId += 1
        unmuteBeep()
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
            // Session is invalidated regardless; stale callbacks are ignored.
        }
    }

    fun destroy() {
        // Invalidate the active session so a callback already in flight cannot fire after
        // this engine (and the owner that created it) has been disposed.
        activeSessionId += 1
        unmuteBeep()
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
            // Destruction is best-effort during UI disposal.
        }
        speechRecognizer = null
    }
}
// END
