package com.opendroid.ai.core.media

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.Brightness
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-clip timeline exporter backed by AndroidX Media3 Transformer.
 * Unlike the legacy stream-copy cutter, clip boundaries are processed by the
 * media transformer pipeline and clips are assembled into one MP4 timeline.
 * Device codec support and real output playback still require device validation.
 */
@OptIn(UnstableApi::class)
@Singleton
class Media3TimelineEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun export(
        source: Uri,
        destination: Uri,
        segments: List<VideoCutSegment>,
        backgroundAudio: Uri? = null,
        rotationDegrees: Float = 0f,
        brightness: Float = 0f,
        contrast: Float = 0f,
        titleText: String = "",
        hueDegrees: Float = 0f,
        saturation: Float = 0f,
        lightness: Float = 0f,
        speed: Float = 1f,
        removeOriginalAudio: Boolean = false,
        fadeDurationMs: Long = 0L,
        aspectRatio: Float? = null,
        cropToFill: Boolean = false,
        captions: List<VideoCaptionCue> = emptyList(),
        onProgress: (Int) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        if (source == destination) {
            return@withContext Result.failure(IllegalArgumentException("Source and destination must be different files."))
        }
        cleanupStaleTimelineFiles()
        val tempFile = File(context.cacheDir, "jarvis-timeline-${UUID.randomUUID()}.mp4")
        val keepTempAfterCancellation = AtomicBoolean(false)
        try {
            VideoCutPlan.validate(segments, readDurationMs(source))?.let {
                throw IllegalArgumentException(it)
            }
            require(rotationDegrees.isFinite() && rotationDegrees in -180f..180f) {
                "Rotation must be a finite value between -180 and 180 degrees."
            }
            require(brightness.isFinite() && brightness in -1f..1f) {
                "Brightness must be between -1 and 1."
            }
            require(contrast.isFinite() && contrast in -1f..1f) {
                "Contrast must be between -1 and 1."
            }
            require(hueDegrees.isFinite() && hueDegrees in -180f..180f) {
                "Hue adjustment must be between -180 and 180 degrees."
            }
            require(saturation.isFinite() && saturation in -100f..100f) {
                "Saturation adjustment must be between -100 and 100."
            }
            require(lightness.isFinite() && lightness in -100f..100f) {
                "Lightness adjustment must be between -100 and 100."
            }
            require(speed.isFinite() && speed in 0.5f..2f) {
                "Playback speed must be between 0.5x and 2x."
            }
            require(fadeDurationMs in 0L..1_500L) {
                "Fade duration must be between 0 and 1500 milliseconds."
            }
            require(aspectRatio == null || (aspectRatio.isFinite() && aspectRatio in 0.1f..10f)) {
                "Output aspect ratio is invalid."
            }
            require(captions.isEmpty() || speed == 1f) {
                "Timed captions currently require normal 1x playback speed."
            }
            require(fadeDurationMs == 0L || speed == 1f) {
                "Fade timing currently requires normal 1x playback speed."
            }
            require(captions.size <= 100 && captions.all {
                it.startMs >= 0L && it.endMs > it.startMs && it.text.isNotBlank() && it.text.length <= 120
            }) { "Caption cues are invalid or exceed the 100-cue limit." }
            var previousCaptionEndMs = 0L
            captions.forEach { cue ->
                require(cue.startMs >= previousCaptionEndMs) { "Caption cues must be sorted and non-overlapping." }
                previousCaptionEndMs = cue.endMs
            }
            val totalOutputDurationMs = segments.sumOf { (it.durationMs / speed).toLong().coerceAtLeast(1L) }
            require(captions.all { it.endMs <= totalOutputDurationMs }) {
                "A caption ends after the edited timeline duration."
            }
            val safeTitle = titleText.trim().take(80)
            var outputOffsetMs = 0L
            val editedItems = segments.map { segment ->
                val clipOutputDurationMs = (segment.durationMs / speed).toLong().coerceAtLeast(1L)
                val clipOutputEndMs = outputOffsetMs + clipOutputDurationMs
                val clipCaptions = captions.mapNotNull { cue ->
                    val overlapStart = maxOf(cue.startMs, outputOffsetMs)
                    val overlapEnd = minOf(cue.endMs, clipOutputEndMs)
                    if (overlapEnd <= overlapStart) null else VideoCaptionCue(
                        startMs = overlapStart - outputOffsetMs,
                        endMs = overlapEnd - outputOffsetMs,
                        text = cue.text
                    )
                }
                val videoEffects = buildList<androidx.media3.common.Effect> {
                    if (rotationDegrees != 0f) add(ScaleAndRotateTransformation.Builder().setRotationDegrees(rotationDegrees).build())
                    if (brightness != 0f) add(Brightness(brightness))
                    if (contrast != 0f) add(Contrast(contrast))
                    if (hueDegrees != 0f || saturation != 0f || lightness != 0f) {
                        add(HslAdjustment.Builder()
                            .adjustHue(hueDegrees)
                            .adjustSaturation(saturation)
                            .adjustLightness(lightness)
                            .build())
                    }
                    if (aspectRatio != null) {
                        add(Presentation.createForAspectRatio(
                            aspectRatio,
                            if (cropToFill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
                        ))
                    }
                    if (safeTitle.isNotBlank()) {
                        add(OverlayEffect(listOf(TextOverlay.createStaticTextOverlay(SpannableString(safeTitle)))))
                    }
                    if (clipCaptions.isNotEmpty()) {
                        add(OverlayEffect(listOf(TimedCaptionOverlay(clipCaptions))))
                    }
                    if (fadeDurationMs > 0L) {
                        val sourceDurationUs = segment.durationMs * 1_000L
                        val fadeUs = min(fadeDurationMs * 1_000L, sourceDurationUs / 2L)
                        if (fadeUs > 0L) add(OverlayEffect(listOf(FadeToBlackOverlay(sourceDurationUs, fadeUs))))
                    }
                }
                val clipping = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(segment.startMs)
                    .setEndPositionMs(segment.endMs)
                    .build()
                val mediaItem = MediaItem.Builder()
                    .setUri(source)
                    .setClippingConfiguration(clipping)
                    .build()
                outputOffsetMs = clipOutputEndMs
                val editedItemBuilder = EditedMediaItem.Builder(mediaItem)
                    .setRemoveAudio(removeOriginalAudio)
                    .setSpeed(ConstantSpeedProvider(speed))
                    .setEffects(Effects(emptyList(), videoEffects))
                if (speed > 1f) {
                    // Prevent fast clips from producing an excessive effective frame rate.
                    editedItemBuilder.setFrameRate(60)
                }
                editedItemBuilder.build()
            }
            val videoSequence = EditedMediaItemSequence.withAudioAndVideoFrom(editedItems)
            val composition = if (backgroundAudio != null) {
                val musicItem = EditedMediaItem.Builder(MediaItem.fromUri(backgroundAudio)).build()
                val musicSequence = EditedMediaItemSequence.withAudioFrom(listOf(musicItem))
                    .buildUpon()
                    .setIsLooping(true)
                    .build()
                Composition.Builder(videoSequence, musicSequence).build()
            } else {
                Composition.Builder(videoSequence).build()
            }

            val exportResult = withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine<Result<Unit>> { continuation ->
                    val handler = Handler(Looper.getMainLooper())
                    val progressHolder = ProgressHolder()
                    lateinit var transformer: Transformer
                    val progressPoller = object : Runnable {
                        override fun run() {
                            if (!continuation.isActive) return
                            try {
                                if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                                    onProgress(progressHolder.progress.coerceIn(0, 99))
                                }
                                handler.postDelayed(this, 350L)
                            } catch (_: IllegalStateException) {
                                // Export may have completed between callbacks.
                            }
                        }
                    }
                    val listener = object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            handler.removeCallbacks(progressPoller)
                            transformer.removeListener(this)
                            if (continuation.isActive) continuation.resume(Result.success(Unit))
                        }

                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            exception: ExportException
                        ) {
                            handler.removeCallbacks(progressPoller)
                            transformer.removeListener(this)
                            if (continuation.isActive) continuation.resume(Result.failure(exception))
                        }
                    }

                    try {
                        transformer = Transformer.Builder(context)
                            .addListener(listener)
                            .build()
                        continuation.invokeOnCancellation {
                            keepTempAfterCancellation.set(true)
                            handler.removeCallbacks(progressPoller)
                            handler.post {
                                runCatching { transformer.cancel() }
                                runCatching { transformer.removeListener(listener) }
                                // Transformer cancellation is asynchronous. Keep its output
                                // file until a later export's stale-cache sweep rather than
                                // deleting a file that the muxer may still be closing.
                            }
                        }
                        transformer.start(composition, tempFile.absolutePath)
                        handler.post(progressPoller)
                    } catch (error: Exception) {
                        handler.removeCallbacks(progressPoller)
                        if (continuation.isActive) continuation.resume(Result.failure(error))
                    }
                }
            }

            exportResult.getOrElse { throw it }
            require(tempFile.isFile && tempFile.length() > 0L) { "The media engine completed without producing an MP4 file." }
            val tempHasMp4Header = tempFile.inputStream().use { input ->
                val header = ByteArray(12)
                var offset = 0
                while (offset < header.size) {
                    val count = input.read(header, offset, header.size - offset)
                    if (count <= 0) break
                    offset += count
                }
                offset == header.size && String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
            }
            require(tempHasMp4Header) { "The media engine output is not a valid MP4 container." }
            val destinationStream = context.contentResolver.openOutputStream(destination, "wt")
                ?: throw IllegalStateException("Cannot open the selected output destination.")
            val copiedBytes = destinationStream.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
            }
            require(copiedBytes == tempFile.length() && copiedBytes > 0L) {
                "The edited video could not be fully copied to the selected destination."
            }
            onProgress(100)
            val audioNote = if (backgroundAudio == null) "" else " Background music was mixed into the timeline."
            val rotationNote = if (rotationDegrees == 0f) "" else " Rotation: ${rotationDegrees} degrees."
            val colourNote = if (brightness == 0f && contrast == 0f && hueDegrees == 0f && saturation == 0f && lightness == 0f) "" else " Brightness, contrast, and HSL colour adjustments applied."
            val titleNote = if (safeTitle.isBlank()) "" else " A static title overlay was added."
            val captionNote = if (captions.isEmpty()) "" else " ${captions.size} timed captions were applied."
            val ratioNote = if (aspectRatio == null) "" else " Output ratio: ${aspectRatio}."
            val speedNote = if (speed == 1f) "" else " Playback speed: ${speed}x."
            val sourceAudioNote = if (removeOriginalAudio) " Original clip audio was muted." else ""
            val fadeNote = if (fadeDurationMs == 0L) "" else " Fade-to-black transitions applied at clip edges."
            Result.success("Timeline export completed: ${segments.size} clips joined into one MP4 using Media3 Transformer.$audioNote$rotationNote$colourNote$titleNote$captionNote$ratioNote$speedNote$sourceAudioNote$fadeNote")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Never delete a user-selected destination on failure: a document provider
            // may have mapped it to an existing document. The temporary render is safe
            // to clean up in finally; a partial destination is reported to the caller.
            Result.failure(error)
        } finally {
            if (!keepTempAfterCancellation.get()) tempFile.delete()
        }
    }

    /**
     * Cancellation does not provide a completion callback. Remove only abandoned
     * timeline files older than a day, never a possibly active export's output.
     */
    private fun cleanupStaleTimelineFiles() {
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1_000L
        context.cacheDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("jarvis-timeline-") && it.name.endsWith(".mp4") && it.lastModified() < cutoff }
            ?.forEach { runCatching { it.delete() } }
    }

    private class FadeToBlackOverlay(
        private val durationUs: Long,
        private val fadeDurationUs: Long
    ) : CanvasOverlay(true) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
            if (fadeDurationUs <= 0L || durationUs <= 0L) return
            val timeUs = presentationTimeUs.coerceIn(0L, durationUs)
            val fadeInAlpha = ((fadeDurationUs - timeUs).toFloat() / fadeDurationUs).coerceIn(0f, 1f)
            val fadeOutStartUs = (durationUs - fadeDurationUs).coerceAtLeast(0L)
            val fadeOutAlpha = ((timeUs - fadeOutStartUs).toFloat() / fadeDurationUs).coerceIn(0f, 1f)
            val alpha = max(fadeInAlpha, fadeOutAlpha)
            if (alpha <= 0f) return
            paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), paint)
        }
    }

    private class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
        override fun getSpeed(presentationTimeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    private class TimedCaptionOverlay(private val cues: List<VideoCaptionCue>) : TextOverlay() {
        override fun getText(presentationTimeUs: Long): SpannableString {
            val timeMs = presentationTimeUs / 1_000L
            val active = cues.firstOrNull { timeMs >= it.startMs && timeMs < it.endMs }
                ?: return SpannableString("")
            return SpannableString(active.text).also { text ->
                val end = text.length
                text.setSpan(ForegroundColorSpan(Color.WHITE), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(BackgroundColorSpan(Color.argb(180, 0, 0, 0)), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(StyleSpan(Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(RelativeSizeSpan(0.42f), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
            StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(0f, -0.78f)
                .setOverlayFrameAnchor(0f, -1f)
                .setScale(0.9f, 0.9f)
                .build()
    }

    private fun readDurationMs(uri: Uri): Long {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }
}
