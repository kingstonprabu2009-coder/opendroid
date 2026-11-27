package com.opendroid.ai.core.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast

class WakeWordDetector(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var intent: Intent? = null
    private var onWakeWordDetectedCallback: (() -> Unit)? = null
    private var isListening = false
    private var lastToastError = -1
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Only these two streams are muted for the beep. Music/YouTube/TTS are NOT touched.
    private val beepStreams = intArrayOf(
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_SYSTEM
    )
    private val mutedByUs = mutableSetOf<Int>()

    private val wakeWords = listOf(
        "jarvis", "jarvish", "jarvas", "charvis", "jarwis", "jar vis",
        "ஜார்விஸ்", "ஜார்விஸ", "ஜார்வீஸ்"
    )

    private fun isWakeWord(text: String): Boolean {
        return wakeWords.any { text.contains(it, ignoreCase = true) }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val restartRunnable = Runnable {
        startSpeechListening()
    }

    init {
        initializeRecognizer()
    }

    private fun initializeRecognizer() {
        intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }

    private fun debugToast(text: String) {
        // Wake-word recognition is owned by a Service; never assume its callback
        // is running on the UI thread when displaying a diagnostic toast.
        handler.post {
            runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "mic permission"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        SpeechRecognizer.ERROR_SERVER -> "server"
        else -> "code $error"
    }

    private fun muteBeep() {
        for (stream in beepStreams) {
            try {
                if (!audioManager.isStreamMute(stream)) {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                    mutedByUs.add(stream)
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun unmuteBeep() {
        for (stream in mutedByUs.toList()) {
            try {
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            } catch (e: Exception) {
            }
        }
        mutedByUs.clear()
    }

    fun startListening(onWakeWordDetected: () -> Unit) {
        if (isListening) return
        this.onWakeWordDetectedCallback = onWakeWordDetected
        isListening = true
        muteBeep()
        startSpeechListening()
    }

    private fun startSpeechListening() {
        if (!isListening) return

        cleanupRecognizer()

        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            } catch (e: Exception) {
                scheduleRestart(3000L)
                return
            }
        } else {
            if (lastToastError != -99) {
                lastToastError = -99
                debugToast("Jarvis wake error: no speech service")
            }
            scheduleRestart(3000L)
            return
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                val normal = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (!normal && error != lastToastError) {
                    lastToastError = error
                    debugToast("Jarvis wake error: ${errorName(error)}")
                }
                scheduleRestart(if (normal) 1000L else 3000L)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null) {
                    for (match in matches) {
                        if (isWakeWord(match)) {
                            triggerWakeWord()
                            return
                        }
                    }
                }
                scheduleRestart(1000L)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null) {
                    for (match in matches) {
                        if (isWakeWord(match)) {
                            triggerWakeWord()
                            return
                        }
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            scheduleRestart(3000L)
        }
    }

    private fun triggerWakeWord() {
        if (!isListening) return
        unmuteBeep()
        onWakeWordDetectedCallback?.invoke()
    }

    private fun scheduleRestart(delayMs: Long = 1000L) {
        handler.removeCallbacks(restartRunnable)
        if (isListening) {
            handler.postDelayed(restartRunnable, delayMs)
        }
    }

    private fun cleanupRecognizer() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
        }
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
        }
        speechRecognizer = null
    }

    fun stopListening() {
        isListening = false
        handler.removeCallbacks(restartRunnable)
        cleanupRecognizer()
        unmuteBeep()
        onWakeWordDetectedCallback = null
    }

    fun destroy() {
        stopListening()
    }
}
// END
