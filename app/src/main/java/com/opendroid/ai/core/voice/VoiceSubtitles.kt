package com.opendroid.ai.core.voice

import kotlinx.coroutines.flow.MutableStateFlow

/** Live Voice subtitles: what the user said and what Jarvis answered. */
object VoiceSubtitles {
    val heard = MutableStateFlow("")
    val reply = MutableStateFlow("")

    fun clear() {
        heard.value = ""
        reply.value = ""
    }
}
