package com.opendroid.ai.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.agent.TeachingEngine
import com.opendroid.ai.core.media.Media3TimelineEngine
import com.opendroid.ai.core.media.VideoCutPlan
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TeachingViewModel @Inject constructor(
    private val teachingEngine: TeachingEngine,
    private val videoCutEngine: Media3TimelineEngine
) : ViewModel() {

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _editBusy = MutableStateFlow(false)
    val editBusy: StateFlow<Boolean> = _editBusy
    private val _editProgress = MutableStateFlow(0)
    val editProgress: StateFlow<Int> = _editProgress
    private val _editStatus = MutableStateFlow("")
    val editStatus: StateFlow<String> = _editStatus

    fun exportCuts(
        source: Uri,
        destination: Uri,
        rangesSeconds: String,
        backgroundAudio: Uri? = null,
        rotationDegreesText: String = "0",
        brightness: Float = 0f,
        contrast: Float = 0f,
        titleText: String = "",
        hueDegrees: Float = 0f,
        saturation: Float = 0f,
        lightness: Float = 0f,
        speed: Float = 1f,
        removeOriginalAudio: Boolean = false,
        fadeDurationMs: Long = 0L,
        aspectRatioLabel: String = "Original",
        cropToFill: Boolean = false,
        captionsText: String = ""
    ) {
        if (_editBusy.value) return
        val segments = VideoCutPlan.parseSeconds(rangesSeconds)
        val captions = VideoCutPlan.parseCaptions(captionsText)
        if (captions == null) {
            _editStatus.value = "Invalid captions. Use: 0-2|First line;2-4|Second line (max 100 cues, 120 chars each)."
            return
        }
        if (captions.isNotEmpty() && speed != 1f) {
            _editStatus.value = "For accurate caption timing, set playback speed to 1.00x or remove timed captions."
            return
        }
        if (fadeDurationMs > 0L && speed != 1f) {
            _editStatus.value = "For accurate fade timing, set playback speed to 1.00x or turn off fade transitions."
            return
        }
        val rotationDegrees = rotationDegreesText.trim().ifBlank { "0" }.toFloatOrNull()
        if (rotationDegrees == null || !rotationDegrees.isFinite() || rotationDegrees !in -180f..180f) {
            _editStatus.value = "Enter a valid rotation between -180 and 180 degrees."
            return
        }
        val aspectRatio = when (aspectRatioLabel.trim().lowercase()) {
            "", "original" -> null
            "9:16" -> 9f / 16f
            "16:9" -> 16f / 9f
            "1:1" -> 1f
            else -> {
                _editStatus.value = "Choose Original, 9:16, 16:9 or 1:1 for the output ratio."
                return
            }
        }
        if (segments == null || VideoCutPlan.validate(segments, Long.MAX_VALUE) != null) {
            _editStatus.value = "Invalid cut ranges. Example: 0-5,7-12 (seconds)."
            return
        }
        _editBusy.value = true
        _editProgress.value = 0
        _editStatus.value = "Preparing multi-clip timeline export..."
        viewModelScope.launch {
            try {
                val result = videoCutEngine.export(
                    source = source,
                    destination = destination,
                    segments = segments,
                    backgroundAudio = backgroundAudio,
                    rotationDegrees = rotationDegrees,
                    brightness = brightness,
                    contrast = contrast,
                    titleText = titleText,
                    hueDegrees = hueDegrees,
                    saturation = saturation,
                    lightness = lightness,
                    speed = speed,
                    removeOriginalAudio = removeOriginalAudio,
                    fadeDurationMs = fadeDurationMs,
                    aspectRatio = aspectRatio,
                    cropToFill = cropToFill,
                    captions = captions
                ) { progress ->
                    _editProgress.value = progress
                }
                _editStatus.value = result.getOrElse { "Export failed: ${it.message ?: "unknown error"}" }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _editStatus.value = "Export cancelled."
                throw cancelled
            } catch (error: Exception) {
                _editStatus.value = "Export failed: ${error.message ?: "unknown error"}"
            } finally {
                _editBusy.value = false
            }
        }
    }

    fun teach(uri: Uri) {
        if (_busy.value) return
        _busy.value = true
        _status.value = "Video paarkkuren..."
        viewModelScope.launch {
            try {
                val result = teachingEngine.learnFromVideo(uri)
                _status.value = if (result.startsWith("ERROR:")) {
                    result.removePrefix("ERROR:").trim()
                } else {
                    "Reference style analysis saved. Use the recipe when requesting a matching edit.\n\n$result"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _status.value = "Reference analysis cancelled."
                throw cancelled
            } catch (error: Exception) {
                _status.value = "Analysis failed: ${error.message ?: "unknown error"}"
            } finally {
                _busy.value = false
            }
        }
    }
}
