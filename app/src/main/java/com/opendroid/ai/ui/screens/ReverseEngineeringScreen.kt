package com.opendroid.ai.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.net.Uri
import android.content.Intent
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendroid.ai.ui.theme.AccentCyan
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary
import com.opendroid.ai.ui.viewmodel.TeachingViewModel

/**
 * Dedicated reference-analysis workspace. This deliberately stays separate from
 * the JARVIS chat/brain screen; it analyses a user-selected reference video and
 * stores the resulting style recipe through TeachingEngine.
 */
@Composable
fun ReverseEngineeringScreen(
    modifier: Modifier = Modifier,
    viewModel: TeachingViewModel = hiltViewModel()
) {
    val status by viewModel.status.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val editBusy by viewModel.editBusy.collectAsState()
    val editProgress by viewModel.editProgress.collectAsState()
    val editStatus by viewModel.editStatus.collectAsState()
    val context = LocalContext.current
    var selectedName by remember { mutableStateOf("") }
    var editSource by remember { mutableStateOf<Uri?>(null) }
    var exportedUri by remember { mutableStateOf<Uri?>(null) }
    var backgroundAudio by remember { mutableStateOf<Uri?>(null) }
    var ranges by remember { mutableStateOf("0-5,7-12") }
    var rotation by remember { mutableStateOf("0") }
    var brightness by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(0f) }
    var hueDegrees by remember { mutableStateOf(0f) }
    var saturation by remember { mutableStateOf(0f) }
    var lightness by remember { mutableStateOf(0f) }
    var speed by remember { mutableStateOf(1f) }
    var removeOriginalAudio by remember { mutableStateOf(false) }
    var fadeDurationMs by remember { mutableStateOf(0f) }
    var titleText by remember { mutableStateOf("") }
    var aspectRatioLabel by remember { mutableStateOf("Original") }
    var cropToFill by remember { mutableStateOf(false) }
    var captionsText by remember { mutableStateOf("") }

    fun persistReadAccess(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            persistReadAccess(uri)
            selectedName = uri.lastPathSegment?.substringAfterLast('/')?.take(64) ?: "Selected reference video"
            viewModel.teach(uri)
        }
    }
    val editVideoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) persistReadAccess(uri)
        editSource = uri
    }
    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) persistReadAccess(uri)
        backgroundAudio = uri
    }
    val outputPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("video/mp4")
    ) { output ->
        val source = editSource
        if (source != null && output != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    output,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            exportedUri = output
            viewModel.exportCuts(
                source, output, ranges, backgroundAudio,
                rotation, brightness, contrast, titleText,
                hueDegrees, saturation, lightness, speed, removeOriginalAudio,
                fadeDurationMs.toLong(), aspectRatioLabel, cropToFill, captionsText
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "REVERSE ENGINEERING",
            color = AccentCyan,
            fontSize = 20.sp,
            letterSpacing = 1.8.sp,
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = "Reference video intelligence — analyse visual style separately from the JARVIS Brain.",
            color = TextSecondary,
            fontSize = 13.sp
        )
        Surface(
            color = AppTheme.colors.surface,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("REFERENCE VIDEO", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (selectedName.isBlank()) "Choose a video from your device." else selectedName,
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Button(
                    onClick = { videoPicker.launch(arrayOf("video/*")) },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF103B55)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "ANALYSING..." else "SELECT & ANALYSE VIDEO")
                }
                if (busy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(color = AccentCyan, strokeWidth = 2.dp)
                        Text("Extracting reference frames and analysing the edit style...", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
        if (status.isNotBlank()) {
            Surface(
                color = AppTheme.colors.surface,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("ANALYSIS RESULT", color = AccentCyan, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(status, color = TextPrimary, fontSize = 13.sp)
                }
            }
        }
        Surface(
            color = AppTheme.colors.surface,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("JARVIS TIMELINE EDITOR", color = AccentCyan, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Select source video, then enter keep-ranges in seconds. Example: 0-5,7-12,15-20. JARVIS trims each clip and joins them into one MP4 timeline.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Button(
                    onClick = { editVideoPicker.launch(arrayOf("video/*")) },
                    enabled = !editBusy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (editSource == null) "SELECT SOURCE VIDEO" else "CHANGE SOURCE VIDEO") }
                Text(
                    editSource?.lastPathSegment ?: "No source video selected",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                OutlinedTextField(
                    value = ranges,
                    onValueChange = { ranges = it },
                    enabled = !editBusy,
                    label = { Text("Keep ranges (seconds)") },
                    supportingText = { Text("Sorted, non-overlapping ranges; up to 40 cuts.") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { audioPicker.launch(arrayOf("audio/*")) },
                    enabled = !editBusy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (backgroundAudio == null) "ADD BACKGROUND MUSIC (OPTIONAL)" else "CHANGE BACKGROUND MUSIC") }
                Text(
                    backgroundAudio?.lastPathSegment ?: "No background music selected",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                OutlinedTextField(
                    value = rotation,
                    onValueChange = { rotation = it.filter { ch -> ch.isDigit() || ch == '-' || ch == '.' }.take(8) },
                    enabled = !editBusy,
                    label = { Text("Rotation degrees (-180 to 180)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = aspectRatioLabel,
                    onValueChange = { aspectRatioLabel = it.take(12) },
                    enabled = !editBusy,
                    label = { Text("Output ratio: Original / 9:16 / 16:9 / 1:1") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = cropToFill, onCheckedChange = { cropToFill = it }, enabled = !editBusy)
                    Text("Crop to fill ratio (otherwise preserve full frame)", color = TextSecondary, fontSize = 12.sp)
                }
                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it.take(80) },
                    enabled = !editBusy,
                    label = { Text("Static title overlay (optional)") },
                    supportingText = { Text("Up to 80 characters; default overlay placement.") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = captionsText,
                    onValueChange = { captionsText = it.take(16_000) },
                    enabled = !editBusy,
                    label = { Text("Timed captions (optional)") },
                    placeholder = { Text("0-2|First line;2-4|Second line") },
                    supportingText = { Text("Output timeline seconds; max 100 captions, 120 characters each. Use 1.00x speed when captions are enabled.") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Text("Brightness: ${"%.2f".format(brightness)}", color = TextSecondary, fontSize = 12.sp)
                Slider(
                    value = brightness,
                    onValueChange = { brightness = it },
                    valueRange = -1f..1f,
                    enabled = !editBusy
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(
                        checked = removeOriginalAudio,
                        onCheckedChange = { removeOriginalAudio = it },
                        enabled = !editBusy
                    )
                    Text("Mute original clip audio", color = TextSecondary, fontSize = 12.sp)
                }
                Text("Fade at clip edges: ${fadeDurationMs.toInt()} ms", color = TextSecondary, fontSize = 12.sp)
                Slider(
                    value = fadeDurationMs,
                    onValueChange = { fadeDurationMs = it },
                    valueRange = 0f..1500f,
                    enabled = !editBusy
                )
                Text("Playback speed: ${"%.2f".format(speed)}x", color = TextSecondary, fontSize = 12.sp)
                Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.5f..2f, enabled = !editBusy)
                Text("Hue: ${"%.0f".format(hueDegrees)}°", color = TextSecondary, fontSize = 12.sp)
                Slider(value = hueDegrees, onValueChange = { hueDegrees = it }, valueRange = -180f..180f, enabled = !editBusy)
                Text("Saturation: ${"%.0f".format(saturation)}", color = TextSecondary, fontSize = 12.sp)
                Slider(value = saturation, onValueChange = { saturation = it }, valueRange = -100f..100f, enabled = !editBusy)
                Text("Lightness: ${"%.0f".format(lightness)}", color = TextSecondary, fontSize = 12.sp)
                Slider(value = lightness, onValueChange = { lightness = it }, valueRange = -100f..100f, enabled = !editBusy)
                Text("Contrast: ${"%.2f".format(contrast)}", color = TextSecondary, fontSize = 12.sp)
                Slider(
                    value = contrast,
                    onValueChange = { contrast = it },
                    valueRange = -1f..1f,
                    enabled = !editBusy
                )
                Button(
                    onClick = { outputPicker.launch("JARVIS_Cut_Edit.mp4") },
                    enabled = editSource != null && !editBusy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (editBusy) "EXPORTING..." else "EXPORT EDITED VIDEO") }
                if (editBusy) {
                    CircularProgressIndicator(color = AccentCyan)
                    Text("$editProgress% completed", color = TextSecondary, fontSize = 12.sp)
                }
                if (editStatus.isNotBlank()) {
                    Text(editStatus, color = TextPrimary, fontSize = 12.sp)
                }
                if (!editBusy && exportedUri != null && editStatus.startsWith("Timeline export completed")) {
                    Button(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "video/mp4"
                                putExtra(Intent.EXTRA_STREAM, exportedUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Continue editing with"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("CONTINUE EDITING IN CAPCUT / ANOTHER APP") }
                }
                Text(
                    "Current engine: Media3 multi-clip timeline export, optional looping background audio, original-audio mute, output aspect ratio, rotation, brightness, contrast, HSL colour adjustments, static title, timed captions, playback-speed control and fade-to-black transitions. Crossfades, animated caption styles, LUT presets and keyframes are not included yet.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
        Text(
            text = "Reference analysis and timeline editing are separate tools. The original source is never overwritten.",
            color = TextSecondary,
            fontSize = 11.sp
        )
    }
}
