package com.opendroid.ai.core.media
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

data class VideoCutSegment(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

data class VideoCaptionCue(val startMs: Long, val endMs: Long, val text: String)

object VideoCutPlan {
    /** Parses comma-separated start-end ranges in seconds; rejects NaN and infinities. */
    fun parseSeconds(input: String): List<VideoCutSegment>? {
        val ranges = input.split(",")
        if (input.isBlank() || ranges.isEmpty()) return null
        val parsed = ranges.map { raw ->
            val pair = raw.trim().split("-", limit = 2)
            if (pair.size != 2) return null
            val start = pair[0].trim().toDoubleOrNull() ?: return null
            val end = pair[1].trim().toDoubleOrNull() ?: return null
            if (!start.isFinite() || !end.isFinite() || start < 0.0 || end <= start) return null
            VideoCutSegment((start * 1000.0).toLong(), (end * 1000.0).toLong())
        }
        return parsed.takeIf { validate(it, Long.MAX_VALUE) == null }
    }

    /** Parses semicolon-separated output-timeline captions: `0-2|Hello;2-4|World`. */
    fun parseCaptions(input: String): List<VideoCaptionCue>? {
        if (input.isBlank()) return emptyList()
        if (input.length > 16_000) return null
        val entries = input.split(';')
        if (entries.size > 100) return null
        val parsed = mutableListOf<VideoCaptionCue>()
        var previousEnd = -1L
        for (entry in entries) {
            val parts = entry.trim().split('|', limit = 2)
            if (parts.size != 2 || parts[1].isBlank() || parts[1].length > 120) return null
            val range = parts[0].trim().split('-', limit = 2)
            if (range.size != 2) return null
            val startSeconds = range[0].trim().toDoubleOrNull() ?: return null
            val endSeconds = range[1].trim().toDoubleOrNull() ?: return null
            if (!startSeconds.isFinite() || !endSeconds.isFinite() ||
                startSeconds < 0.0 || endSeconds <= startSeconds || endSeconds > 86_400.0
            ) return null
            val startMs = (startSeconds * 1000.0).toLong()
            val endMs = (endSeconds * 1000.0).toLong()
            if (startMs < previousEnd || endMs <= startMs) return null
            parsed += VideoCaptionCue(startMs, endMs, parts[1].trim())
            previousEnd = endMs
        }
        return parsed
    }

    fun validate(segments: List<VideoCutSegment>, sourceDurationMs: Long): String? {
        if (segments.isEmpty()) return "Add at least one cut range."
        if (sourceDurationMs <= 0L) return "The source video duration is unavailable."
        if (segments.size > 40) return "A maximum of 40 cut ranges is supported per export."
        var previousEnd = -1L
        for ((index, segment) in segments.withIndex()) {
            if (segment.startMs < 0L || segment.endMs <= segment.startMs) return "Cut ${index + 1} has an invalid start/end time."
            if (segment.endMs > sourceDurationMs) return "Cut ${index + 1} exceeds the source video duration."
            if (previousEnd > segment.startMs) return "Cut ranges must be sorted and must not overlap."
            previousEnd = segment.endMs
        }
        return null
    }
}

/** Lossless stream-copy cut/concatenate engine. Cut starts snap back to a prior video keyframe. */
@Singleton
class NativeVideoCutEngine @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun export(source: Uri, destination: Uri, segments: List<VideoCutSegment>, onProgress: (Int) -> Unit = {}): Result<String> =
        withContext(Dispatchers.IO) {
            var muxer: MediaMuxer? = null
            var output: ParcelFileDescriptor? = null
            try {
                require(source != destination) { "Source and destination must be different files." }
                val durationMs = readDuration(source)
                VideoCutPlan.validate(segments, durationMs)?.let { return@withContext Result.failure(IllegalArgumentException(it)) }
                val probe = MediaExtractor()
                val formats = mutableListOf<MediaFormat>()
                val sourceTrackIndices = mutableListOf<Int>()
                val videoTrack = try {
                    probe.setDataSource(context, source, null)
                    var found = -1
                    for (i in 0 until probe.trackCount) {
                        val f = probe.getTrackFormat(i)
                        val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                            formats += f
                            sourceTrackIndices += i
                            if (mime.startsWith("video/")) found = formats.lastIndex
                        }
                    }
                    found
                } finally { probe.release() }
                if (videoTrack < 0) throw IllegalArgumentException("No video track was found.")
                output = context.contentResolver.openFileDescriptor(destination, "rwt") ?: throw IllegalStateException("Cannot create output file.")
                val activeMuxer = MediaMuxer(output.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer = activeMuxer
                val muxTracks = formats.map { activeMuxer.addTrack(it) }
                val rotation = formats.getOrNull(videoTrack)
                    ?.takeIf { it.containsKey(MediaFormat.KEY_ROTATION) }
                    ?.getInteger(MediaFormat.KEY_ROTATION)
                    ?: 0
                if (rotation in setOf(90, 180, 270)) activeMuxer.setOrientationHint(rotation)
                activeMuxer.start()
                val total = segments.sumOf { it.durationMs }.coerceAtLeast(1L)
                var completed = 0L
                var outputOffset = 0L
                var rounded = false
                var previousSourceEndUs = -1L
                // Older Android releases do not expose sampleSize; keep a generous
                // bounded buffer there. API 28+ grows exactly for larger encoded frames.
                var buffer = ByteBuffer.allocate(16 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()
                for (segment in segments) {
                    val startUs = segment.startMs * 1000L
                    val endUs = segment.endMs * 1000L
                    val anchorUs = findPreviousSync(source, sourceTrackIndices[videoTrack], startUs)
                    if (anchorUs < 0L || anchorUs >= endUs) throw IllegalArgumentException("No usable keyframe near cut ${segment.startMs} ms.")
                    if (previousSourceEndUs >= 0L && anchorUs < previousSourceEndUs) throw IllegalArgumentException("Keyframe snapping would overlap adjacent cuts. Move the next cut later.")
                    if (anchorUs < startUs) rounded = true
                    for (track in formats.indices) {
                        val mime = formats[track].getString(MediaFormat.KEY_MIME).orEmpty()
                        if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                        val extractor = MediaExtractor()
                        try {
                            extractor.setDataSource(context, source, null)
                            extractor.selectTrack(sourceTrackIndices[track])
                            extractor.seekTo(anchorUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                            while (true) {
                                val pts = extractor.sampleTime
                                if (pts < 0L || pts >= endUs) break
                                if (pts >= anchorUs) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                        val required = extractor.sampleSize
                                        if (required > 64L * 1024L * 1024L) {
                                            throw IllegalArgumentException("Encoded sample is too large to export safely.")
                                        }
                                        if (required > buffer.capacity()) {
                                            buffer = ByteBuffer.allocate(required.toInt())
                                        }
                                    }
                                    buffer.clear()
                                    val size = extractor.readSampleData(buffer, 0)
                                    if (size < 0) break
                                    info.set(0, size, outputOffset + pts - anchorUs, extractor.sampleFlags)
                                    activeMuxer.writeSampleData(muxTracks[track], buffer, info)
                                }
                                if (!extractor.advance()) break
                            }
                        } finally { extractor.release() }
                    }
                    outputOffset += endUs - anchorUs
                    previousSourceEndUs = endUs
                    completed += segment.durationMs
                    onProgress((completed * 100L / total).toInt().coerceIn(0, 100))
                }
                activeMuxer.stop()
                activeMuxer.release()
                muxer = null
                output.close()
                output = null
                Result.success(if (rounded) "Export completed. Cut starts were rounded to previous keyframes; exact frame cuts need re-encoding. No transitions/effects were applied." else "Export completed using stream copy. No transitions/effects were applied.")
            } catch (e: Exception) {
                Log.e("NativeVideoCutEngine", "Export failed: ${e.javaClass.simpleName}", e)
                runCatching { muxer?.stop() }; runCatching { muxer?.release() }; runCatching { output?.close() }
                // Do not delete a user-selected document on failure. A provider may
                // have resolved the URI to an existing file; preserve it rather than
                // risk destructive cleanup of content we do not own.
                Result.failure(e)
            }
        }

    private fun readDuration(source: Uri): Long {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, source, null)
            var duration = 0L
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.containsKey(MediaFormat.KEY_DURATION)) duration = maxOf(duration, f.getLong(MediaFormat.KEY_DURATION))
            }
            duration / 1000L
        } finally { ex.release() }
    }

    private fun findPreviousSync(source: Uri, track: Int, timeUs: Long): Long {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, source, null)
            ex.selectTrack(track)
            ex.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            ex.sampleTime
        } finally { ex.release() }
    }
}
