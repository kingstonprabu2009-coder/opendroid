package com.opendroid.ai.core.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.opendroid.ai.core.llm.LLMProviderFactory
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.Memory
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.data.repository.MemoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Teaching Mode: the user shows a reference video, Jarvis looks at a handful of frames
 * with a vision model, writes a short "style recipe" and stores it in memory. The recipe
 * then reaches every later prompt through the normal memory context, so "edit like the
 * reference" works without any new agent code.
 */
@Singleton
class TeachingEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val llmProviderFactory: LLMProviderFactory,
    private val memoryRepository: MemoryRepository
) {
    companion object {
        private const val TAG = "TeachingEngine"
        const val STYLE_KEY = "reference_video_style"
        private const val FRAME_COUNT = 6
        private const val COLUMNS = 3
        private const val CELL_WIDTH = 360
        private const val CELL_HEIGHT = 640
    }

    /** Returns the saved recipe, or a human readable problem starting with "ERROR:". */
    suspend fun learnFromVideo(uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val provider = llmProviderFactory.getActiveProvider()
            val providerName = provider.name.lowercase()
            if (!providerName.contains("gemini") && !providerName.contains("claude")) {
                return@withContext "ERROR: Teaching Mode ku Gemini provider venum. Settings la Gemini select pannunga."
            }

            val sheet = buildContactSheet(uri)
                ?: return@withContext "ERROR: Video ah padikka mudiyala. Vera video try pannunga."

            val message = ChatMessage(
                id = UUID.randomUUID().toString(),
                text = """
                    This image is a contact sheet of $FRAME_COUNT frames taken in order from one reference video
                    (left to right, top to bottom). Write a short editing recipe so someone could recreate
                    this style in CapCut. Cover only what you can actually see: text style (font look, colour,
                    size, position, animation), colour grade or filter, cut pace and transitions, effects,
                    aspect ratio and overall mood. Plain text, under 600 characters, no markdown.
                    If something cannot be told from still frames, say "unknown" for it.
                """.trimIndent(),
                sender = ChatMessage.Sender.USER,
                imageBase64 = sheet
            )
            val response = provider.complete(
                LLMRequest(
                    systemPrompt = "You are a video editing assistant that describes editing style from frames. Be concise and honest.",
                    messages = listOf(message),
                    temperature = 0.3f,
                    maxTokens = 400,
                    responseFormat = ResponseFormat.TEXT
                )
            )
            val recipe = response.content.trim().take(700)
            if (recipe.isBlank()) return@withContext "ERROR: Model empty reply kuduthuchu."

            memoryRepository.saveMemory(
                Memory(
                    key = STYLE_KEY,
                    value = "Reference video edit style (use when user says edit like reference): $recipe",
                    type = MemoryType.SEMANTIC
                )
            )
            recipe
        } catch (e: Exception) {
            Log.e(TAG, "Teaching failed: ${e.message}")
            "ERROR: ${e.message ?: "Teaching fail aachu"}"
        }
    }

    private fun buildContactSheet(uri: Uri): String? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return null
            if (durationMs <= 0L) return null

            val rows = (FRAME_COUNT + COLUMNS - 1) / COLUMNS
            val sheet = Bitmap.createBitmap(CELL_WIDTH * COLUMNS, CELL_HEIGHT * rows, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sheet)
            var drawn = 0
            for (i in 0 until FRAME_COUNT) {
                val timeUs = durationMs * 1000L * (2 * i + 1) / (2 * FRAME_COUNT)
                val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue
                val col = i % COLUMNS
                val row = i / COLUMNS
                val dst = Rect(col * CELL_WIDTH, row * CELL_HEIGHT, (col + 1) * CELL_WIDTH, (row + 1) * CELL_HEIGHT)
                canvas.drawBitmap(frame, null, dst, null)
                frame.recycle()
                drawn++
            }
            if (drawn == 0) return null

            val out = ByteArrayOutputStream()
            sheet.compress(Bitmap.CompressFormat.JPEG, 70, out)
            sheet.recycle()
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Frame extraction failed: ${e.message}")
            return null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
