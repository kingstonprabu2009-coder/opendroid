package com.opendroid.ai.core.llm.providers

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.opendroid.ai.core.llm.LLMProvider
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.LLMResponse
import com.opendroid.ai.core.llm.StreamChunk
import com.opendroid.ai.core.llm.ToolDefinition
import com.opendroid.ai.data.models.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local Qwen2.5 7B GGUF provider. It has no API key, cloud endpoint or usage-credit path.
 * Supports local text chat and a constrained JSON tool-call bridge; validate tools on-device before production use.
 */
@Singleton
class LlamaCppProvider @Inject constructor(private val context: Context) : LLMProvider {
    companion object {
    private const val TAG = "LlamaCppProvider"
    const val MODEL_ID = "qwen2.5-7b-instruct-q4km-gguf"
    const val MODEL_FILE = "Qwen2.5-7B-Instruct-Q4_K_M.gguf"
    const val MODEL_URL = "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf"
    // 7B Q4 is a high-memory option. Keep context deliberately small on 6 GB phones.
    private const val CONTEXT_SIZE = 1024
    private const val MIN_AVAILABLE_RAM = 4_000L * 1024L * 1024L
    private const val MIN_FREE_STORAGE = 500L * 1024L * 1024L
    private val nativeAvailable: Boolean by lazy {
        try { System.loadLibrary("opendroid_llama"); true }
        catch (e: UnsatisfiedLinkError) { Log.e(TAG, "llama.cpp native library is not packaged", e); false }
    }
    }

    override val name: String = "Llama.cpp (Local 7B)"
    override val availableModels: List<String> = listOf(MODEL_ID)
    private val native = LlamaCppNative()
    private val modelFile: File
        get() = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, MODEL_FILE)
    @Volatile private var loaded = false

    /** Starts the official GGUF download through Android's system Download Manager. */
    fun enqueueModelDownload(): Long {
        val dir = modelFile.parentFile ?: error("Model storage is unavailable")
        if (!dir.exists() && !dir.mkdirs()) error("Cannot create model storage")
        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("JARVIS Qwen 2.5 7B model")
            .setDescription("Downloading approximately 4.68 GB. Wi-Fi recommended.")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, MODEL_FILE)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return manager.enqueue(request)
    }

    fun isModelDownloaded(): Boolean {
        if (!modelFile.isFile || modelFile.length() < 4_500_000_000L) return false
        return runCatching { modelFile.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF"
        } }.getOrDefault(false)
    }

    override suspend fun isAvailable(): Boolean = nativeAvailable && isModelDownloaded()

    private fun checkDeviceSafety() {
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        if (memory.lowMemory || memory.availMem < MIN_AVAILABLE_RAM) {
            throw IllegalStateException("7B needs at least 4 GB currently available RAM for this safety profile. Close other apps or use a smaller model; JARVIS stopped safely to protect your phone.")
        }
        val stat = StatFs(modelFile.parentFile?.absolutePath ?: context.filesDir.absolutePath)
        if (stat.availableBytes < MIN_FREE_STORAGE) {
            throw IllegalStateException("At least 500 MB additional free storage is required to load the 7B model safely.")
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        checkDeviceSafety()
        if (!nativeAvailable) throw IllegalStateException("llama.cpp native engine is not included in this build yet. Build the Android arm64 native target first.")
        if (!isModelDownloaded()) throw IllegalStateException("Qwen 7B model is not downloaded. Download it from JARVIS model setup first.")
        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)
        if (!native.nativeLoad(modelFile.absolutePath, CONTEXT_SIZE, threads)) {
            throw IllegalStateException("Qwen 7B failed to load. Device memory may be insufficient.")
        }
        loaded = true
    }

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val started = System.currentTimeMillis()
        val output = streamComplete(request).let { flow ->
            val chunks = mutableListOf<String>()
            flow.collect { chunks += it }
            chunks.joinToString("")
        }
        return LLMResponse(output, 0, MODEL_ID, name, System.currentTimeMillis() - started)
    }

    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        try {
            emit(runInference(buildPrompt(request), request.maxTokens, request.temperature))
        } catch (cancelled: CancellationException) {
            withContext(Dispatchers.IO) { runCatching { native.nativeCancel() } }
            throw cancelled
        }
    }

    override suspend fun generate(messages: List<ChatMessage>, tools: List<ToolDefinition>): Flow<StreamChunk> = flow {
        val toolPrompt = if (tools.isEmpty()) "" else buildString {
            append("\nAvailable tools (use only when necessary):\n")
            tools.forEach { tool ->
                append("TOOL ").append(tool.name).append(": ").append(tool.description)
                    .append("\nParameters JSON Schema: ").append(tool.parameters).append("\n")
            }
            append("If a tool is required, output ONLY one JSON object in this exact form: ")
            append("{\"tool\":\"tool_name\",\"arguments\":{...}}. ")
            append("Do not invent tool names. Otherwise answer normally in plain text.")
        }
        val prompt = buildString {
            append("<|im_start|>system\nYou are JARVIS, a helpful Android assistant. Reply in the user's language; use natural Tamil when the user speaks Tamil. ")
            append(toolPrompt).append("\n<|im_end|>\n")
            messages.takeLast(8).forEach { message ->
                append(if (message.sender == ChatMessage.Sender.USER) "<|im_start|>user\n" else "<|im_start|>assistant\n")
                append(message.text).append("\n<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }
        val result = runInference(prompt, 384, 0.25f).trim()
        val candidate = result.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val parsed = runCatching { JSONObject(candidate) }.getOrNull()
        val toolName = parsed?.optString("tool").orEmpty()
        val allowed = tools.any { it.name == toolName }
        if (allowed) {
            emit(StreamChunk.ToolCall(toolName, parsed?.optJSONObject("arguments")?.toString() ?: "{}"))
        } else {
            emit(StreamChunk.Content(result))
        }
    }

    private suspend fun runInference(prompt: String, maxTokens: Int, temperature: Float): String =
        withContext(Dispatchers.IO) {
            ensureLoaded()
            native.nativeGenerate(prompt, maxTokens.coerceIn(32, 384), temperature)
        }.also { answer ->
            if (answer.startsWith("[JARVIS ERROR]")) throw IllegalStateException(answer)
        }

    suspend fun releaseEngine() = withContext(Dispatchers.IO) {
        if (loaded) runCatching { native.nativeRelease() }
        loaded = false
    }

    private fun buildPrompt(request: LLMRequest): String = buildString {
        append("<|im_start|>system\n")
        append(request.systemPrompt.ifBlank { "You are JARVIS, a helpful Android assistant. Reply naturally and clearly in the user's language. If the user speaks Tamil, reply in natural Tamil." })
        append("\n<|im_end|>\n")
        request.messages.takeLast(8).forEach { message ->
            val role = if (message.sender == ChatMessage.Sender.USER) "user" else "assistant"
            append("<|im_start|>").append(role).append('\n')
            append(message.text)
            append("\n<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }
}
