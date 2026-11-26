package com.opendroid.ai.core.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Listens while Jarvis is speaking. Returns the last ~0.8 s of audio when the user starts
 * talking over Jarvis, so the reply can be cut and the user's words are not lost.
 * Uses the voice-communication mic source with echo cancellation where the phone has it.
 * Works best with earphones; on the loud speaker Jarvis's own voice can look like speech.
 */
class BargeInDetector {

    /** Suspends until the user talks over Jarvis. Null on error. Cancel the coroutine to stop. */
    @SuppressLint("MissingPermission")
    suspend fun waitForSpeech(): ByteArray? = withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 8)
            )
        } catch (e: Exception) { return@withContext null }
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) return@withContext null
            try { if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true } } catch (_: Exception) {}
            try { if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true } } catch (_: Exception) {}
            rec.startRecording()
            val frame = ShortArray(FRAME)
            val ring = ArrayDeque<ShortArray>()
            var baseline = 0.0
            var loud = 0
            while (true) {
                coroutineContext.ensureActive()
                val read = rec.read(frame, 0, FRAME)
                if (read <= 0) throw IOException("Mic read error")
                var sum = 0.0
                for (i in 0 until read) sum += frame[i].toDouble() * frame[i]
                val level = sqrt(sum / read)
                ring.addLast(frame.copyOf(read))
                if (ring.size > RING) ring.removeFirst()
                val threshold = baseline * 2.2 + 600.0
                if (level > threshold) loud++ else {
                    loud = 0
                    // Follow Jarvis's own echo level slowly so it is not mistaken for the user.
                    baseline += (level - baseline) * (if (level > baseline) 0.3 else 0.05)
                }
                if (loud >= NEED_LOUD) {
                    val out = ByteArrayOutputStream()
                    for (f in ring) {
                        val b = ByteBuffer.allocate(f.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (v in f) b.putShort(v)
                        out.write(b.array())
                    }
                    return@withContext out.toByteArray()
                }
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            aec?.release(); ns?.release()
            rec.release()
        }
    }

    private companion object {
        const val RATE = 16000
        const val FRAME = 1600   // 100 ms
        const val RING = 8       // keep 0.8 s
        const val NEED_LOUD = 4  // 0.4 s of speech
    }
}
