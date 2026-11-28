package com.opendroid.ai.core.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.min
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reference analysis for the separate Reverse Engineering workspace. The user-selected
 * video is sampled into a timestamped, aspect-ratio-preserving contact sheet; the configured
 * JARVIS vision path describes visible style evidence and stores a reusable style recipe.
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
        private const val FRAME_COUNT = 9
        private const val COLUMNS = 3
        private const val CELL_WIDTH = 320
        private const val CELL_HEIGHT = 568
    }

    /** Returns the saved recipe, or a human readable problem starting with "ERROR:". */
    suspend fun learnFromVideo(uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            // Teaching uses the configured JARVIS Cloud brain; no vendor-specific
            // vision provider or separate API key is required.
            val provider = llmProviderFactory.getActiveProvider()

            val sheet = buildContactSheet(uri)
                ?: return@withContext "ERROR: Video ah padikka mudiyala. Vera video try pannunga."

            val message = ChatMessage(
                id = UUID.randomUUID().toString(),
                text = """
                    This image is a contact sheet of $FRAME_COUNT representative frames sampled evenly from one reference video.
                    Each frame has its approximate timestamp printed at the bottom. Read left to right, top to bottom.
                    Produce a structured editing-style analysis for a separate JARVIS Reverse Engineering workspace.
                    Identify visible text treatment, colour grade, framing/aspect ratio, overlays, visual effects,
                    likely cut rhythm, and transitions only when evidence supports them. Separate OBSERVED facts
                    from ESTIMATES. Still frames cannot prove exact transition duration, audio sync, or motion curves;
                    mark those UNKNOWN rather than guessing. Return concise bullet points with timestamp references.
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
        } catch (cancelled: CancellationException) {
            throw cancelled
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
            canvas.drawColor(Color.BLACK)
            val timestampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 22f
                setShadowLayer(3f, 1f, 1f, Color.BLACK)
            }
            var drawn = 0
            for (i in 0 until FRAME_COUNT) {
                val sampleMs = durationMs * (2 * i + 1) / (2 * FRAME_COUNT)
                val timeUs = sampleMs * 1000L
                val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue
                val col = i % COLUMNS
                val row = i / COLUMNS
                val cellLeft = col * CELL_WIDTH.toFloat()
                val cellTop = row * CELL_HEIGHT.toFloat()
                // Preserve the source frame's aspect ratio; never stretch a reference frame.
                val scale = min(CELL_WIDTH / frame.width.toFloat(), CELL_HEIGHT / frame.height.toFloat())
                val scaledWidth = frame.width * scale
                val scaledHeight = frame.height * scale
                val left = cellLeft + (CELL_WIDTH - scaledWidth) / 2f
                val top = cellTop + (CELL_HEIGHT - scaledHeight) / 2f
                canvas.drawBitmap(frame, null, RectF(left, top, left + scaledWidth, top + scaledHeight), null)
                val timestamp = String.format(java.util.Locale.ROOT, "%.1fs", sampleMs / 1000.0)
                canvas.drawText(timestamp, cellLeft + 8f, cellTop + CELL_HEIGHT - 12f, timestampPaint)
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
