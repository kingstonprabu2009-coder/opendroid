package com.opendroid.ai.ui.screens

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.core.voice.EdgeTtsClient
import com.opendroid.ai.ui.theme.AccentCyan
import com.opendroid.ai.ui.theme.CardBackground
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary
import com.opendroid.ai.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

private data class TamilVoiceProfile(val id: String, val label: String, val pitch: String = "+0Hz")

private val tamilVoiceProfiles = listOf(
    TamilVoiceProfile("ta-IN-ValluvarNeural", "Valluvar · India · Male"),
    TamilVoiceProfile("ta-IN-PallaviNeural", "Pallavi · India · Female"),
    TamilVoiceProfile("ta-LK-KumarNeural", "Kumar · Sri Lanka · Male"),
    TamilVoiceProfile("ta-LK-SaranyaNeural", "Saranya · Sri Lanka · Female"),
    TamilVoiceProfile("ta-MY-SuryaNeural", "Surya · Malaysia · Male"),
    TamilVoiceProfile("ta-MY-KaniNeural", "Kani · Malaysia · Female"),
    TamilVoiceProfile("ta-SG-AnbuNeural", "Anbu · Singapore · Male"),
    TamilVoiceProfile("ta-SG-VenbaNeural", "Venba · Singapore · Female"),
    TamilVoiceProfile("ta-IN-ValluvarNeural", "Valluvar · Soft pitch", "-2Hz"),
    TamilVoiceProfile("ta-IN-PallaviNeural", "Pallavi · Bright pitch", "+2Hz")
)

@Composable
fun VoiceScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val config by viewModel.llmConfig.collectAsState()
    val prefs = remember { context.getSharedPreferences("jarvis_voice", Context.MODE_PRIVATE) }
    var provider by remember { mutableStateOf(prefs.getString("provider", "phone") ?: "phone") }
    var selectedVoiceId by remember { mutableStateOf(prefs.getString("edge_voice", "ta-IN-ValluvarNeural") ?: "ta-IN-ValluvarNeural") }
    var selectedPitch by remember { mutableStateOf(prefs.getString("edge_pitch", "+0Hz") ?: "+0Hz") }
    var menuOpen by remember { mutableStateOf(false) }
    var liveOn by remember { mutableStateOf(prefs.getBoolean("live_gemini", false)) }
    var batterySaver by remember { mutableStateOf(prefs.getBoolean("battery_saver", true)) }
    var bargeIn by remember { mutableStateOf(prefs.getBoolean("barge_in", true)) }
    var status by remember { mutableStateOf("Choose a voice, then tap Test Current Voice.") }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val edgeClient = remember { EdgeTtsClient(OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()) }
    val previewClient = remember { OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build() }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var phoneTts by remember { mutableStateOf<TextToSpeech?>(null) }
    var activeSample by remember { mutableStateOf<File?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            edgeClient.cancel()
            runCatching { player?.stop() }
            runCatching { player?.release() }
            player = null
            runCatching { phoneTts?.stop(); phoneTts?.shutdown() }
            phoneTts = null
            activeSample?.delete()
            activeSample = null
        }
    }

    fun playFile(file: File) {
        runCatching {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { mp -> mp.release(); if (player === mp) player = null; file.delete(); activeSample = null; testing = false; status = "Voice test completed." }
                setOnErrorListener { mp, _, _ -> mp.release(); if (player === mp) player = null; file.delete(); activeSample = null; testing = false; status = "Audio playback failed. Try another voice."; true }
                setOnPreparedListener { it.start(); status = "Playing selected voice…" }
                prepareAsync()
            }
            activeSample = file
        }.onFailure {
            file.delete(); testing = false; status = "Could not play the generated voice sample."
        }
    }

    fun testCurrentVoice() {
        if (testing) return
        testing = true
        status = "Preparing selected voice…"
        if (provider == "phone") {
            runCatching { phoneTts?.stop(); phoneTts?.shutdown() }
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context) { result ->
                val tts = engine
                if (result == TextToSpeech.SUCCESS && tts != null) {
                    val lang = tts.setLanguage(Locale.forLanguageTag("ta-IN"))
                    if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                        status = "Tamil voice data is missing. Install Tamil voice data in Android Text-to-speech settings."
                        testing = false
                        tts.shutdown()
                        return@TextToSpeech
                    }
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) { status = "Playing selected phone voice…" }
                        override fun onDone(utteranceId: String?) { status = "Voice test completed."; testing = false; tts.shutdown(); if (phoneTts === tts) phoneTts = null }
                        @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { status = "Phone voice playback failed."; testing = false; tts.shutdown(); if (phoneTts === tts) phoneTts = null }
                    })
                    tts.speak("வணக்கம், நான் ஜார்விஸ். இது நீங்கள் தேர்ந்தெடுத்த குரல் சோதனை.", TextToSpeech.QUEUE_FLUSH, null, "jarvis_voice_preview")
                } else {
                    status = "Phone text-to-speech engine could not start."
                    testing = false
                    tts?.shutdown()
                }
            }
            phoneTts = engine
            return
        }

        scope.launch {
            val file = File.createTempFile("jarvis_voice_preview_", ".mp3", context.cacheDir)
            val success = withContext(Dispatchers.IO) {
                when (provider) {
                    "edge" -> {
                        val profile = tamilVoiceProfiles.firstOrNull { it.id == selectedVoiceId && it.pitch == selectedPitch }
                            ?: tamilVoiceProfiles.firstOrNull { it.id == selectedVoiceId }
                        edgeClient.synthesize(
                            "வணக்கம், நான் ஜார்விஸ். இது நீங்கள் தேர்ந்தெடுத்த குரல் சோதனை.",
                            selectedVoiceId,
                            file,
                            profile?.pitch ?: selectedPitch
                        )
                    }
                    "elevenlabs" -> {
                        val key = config.elevenLabsApiKey
                        val voiceId = config.elevenLabsVoiceId.ifBlank { "pNInz6obpgDQGcFmaJgB" }
                        if (key.isBlank()) false else runCatching {
                            val payload = JSONObject().put("text", "வணக்கம், நான் ஜார்விஸ். இது நீங்கள் தேர்ந்தெடுத்த குரல் சோதனை.")
                                .put("model_id", "eleven_multilingual_v2")
                                .put("voice_settings", JSONObject().put("stability", 0.5).put("similarity_boost", 0.75))
                            val request = Request.Builder().url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
                                .addHeader("xi-api-key", key).addHeader("Content-Type", "application/json")
                                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
                            previewClient.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) false else {
                                    val bytes = response.body?.bytes() ?: byteArrayOf()
                                    if (bytes.isEmpty()) false else { file.writeBytes(bytes); true }
                                }
                            }
                        }.getOrDefault(false)
                    }
                    else -> false
                }
            }
            if (success && file.length() > 0) playFile(file) else {
                file.delete(); testing = false
                status = if (provider == "elevenlabs" && config.elevenLabsApiKey.isBlank()) "Add your ElevenLabs API key below first." else "Voice service unavailable. Check internet/API key and try again."
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("VOICE CONTROL", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Select and test the exact voice JARVIS will use.", color = TextSecondary, fontSize = 12.sp)
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CardBackground), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("VOICE ENGINE", color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    listOf("edge" to "Edge Neural · online", "phone" to "Phone TTS · device", "elevenlabs" to "ElevenLabs · API key").forEach { (id, label) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, color = TextPrimary, modifier = Modifier.weight(1f))
                            Switch(checked = provider == id, onCheckedChange = { checked -> if (checked) { provider = id; prefs.edit().putString("provider", id).apply() } })
                        }
                    }
                    if (provider == "edge") {
                        Text("Tamil neural voice", color = TextSecondary, fontSize = 12.sp)
                        androidx.compose.foundation.layout.Box {
                            OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(tamilVoiceProfiles.firstOrNull { it.id == selectedVoiceId && it.pitch == selectedPitch }?.label
                                    ?: tamilVoiceProfiles.firstOrNull { it.id == selectedVoiceId }?.label ?: "Choose Tamil voice")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                tamilVoiceProfiles.forEach { profile ->
                                    DropdownMenuItem(text = { Text(profile.label) }, onClick = {
                                        selectedVoiceId = profile.id; selectedPitch = profile.pitch
                                        prefs.edit().putString("edge_voice", profile.id).putString("edge_pitch", profile.pitch).putString("provider", "edge").apply()
                                        provider = "edge"; menuOpen = false
                                    })
                                }
                            }
                        }
                        Text("Eight named regional voices + two pitch styles. Edge Neural needs internet and is an unofficial service; availability can change.", color = TextSecondary, fontSize = 10.sp)
                    }
                    if (provider == "elevenlabs") {
                        OutlinedTextField(value = config.elevenLabsApiKey, onValueChange = viewModel::updateElevenLabsApiKey,
                            label = { Text("ElevenLabs API key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = config.elevenLabsVoiceId, onValueChange = viewModel::updateElevenLabsVoiceId,
                            label = { Text("ElevenLabs Voice ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    Button(onClick = ::testCurrentVoice, enabled = !testing, modifier = Modifier.fillMaxWidth()) {
                        Text(if (testing) "TESTING CURRENT VOICE…" else "TEST CURRENT VOICE")
                    }
                    Text(status, color = TextSecondary, fontSize = 11.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CardBackground), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("VOICE BEHAVIOUR", color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { Text("Gemini Live streaming", color = TextPrimary); Text("Requires Gemini key", color = TextSecondary, fontSize = 10.sp) }
                        Switch(checked = liveOn, onCheckedChange = { liveOn = it; prefs.edit().putBoolean("live_gemini", it).apply() })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { Text("Live-call battery saver", color = TextPrimary); Text("End after 40 seconds of silence", color = TextSecondary, fontSize = 10.sp) }
                        Switch(checked = batterySaver, onCheckedChange = { batterySaver = it; prefs.edit().putBoolean("battery_saver", it).apply() })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Interrupt JARVIS by talking", color = TextPrimary, modifier = Modifier.weight(1f))
                        Switch(checked = bargeIn, onCheckedChange = { bargeIn = it; prefs.edit().putBoolean("barge_in", it).apply() })
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}
