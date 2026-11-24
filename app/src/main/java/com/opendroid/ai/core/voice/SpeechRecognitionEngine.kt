package com.opendroid.ai.core.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class SpeechRecognitionEngine(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null

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

    private fun initializeRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Prolong listening limits to avoid early cut-offs
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
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

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                if (sessionId != activeSessionId) return
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech match found"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition engine busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech input timeout"
                    else -> "Unknown error ($error)"
                }
                onError(message)
            }

            override fun onResults(results: Bundle?) {
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

        speechRecognizer?.startListening(recognizerIntent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    /**
     * Cancels the in-progress recognition session outright. Any callback still in flight is
     * dropped because the session token it captured no longer matches [activeSessionId].
     */
    fun cancel() {
        activeSessionId += 1
        speechRecognizer?.cancel()
    }

    fun destroy() {
        // Invalidate the active session so a callback already in flight cannot fire after
        // this engine (and the owner that created it) has been disposed.
        activeSessionId += 1
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
// END
