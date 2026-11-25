package com.opendroid.ai.core.voice

import kotlinx.coroutines.flow.MutableStateFlow

/** Live Voice subtitles: what the user said and what Jarvis answered. */
object VoiceSubtitles {
    val heard = MutableStateFlow("")
    val reply = MutableStateFlow("")
    /** True only while Jarvis's voice is really coming out of the speaker in a live call. */
    val liveSpeaking = MutableStateFlow(false)

    fun clear() {
        heard.value = ""
        reply.value = ""
    }
}
