package com.opendroid.ai.core.llm

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.Data
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.CredentialStoreResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

private const val QWEN_WORK_NAME = "jarvis_qwen_7b_private_download"
private const val QWEN_MODEL_URL = "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf"
private const val QWEN_MODEL_FILE = "Qwen2.5-7B-Instruct-Q4_K_M.gguf"
private const val MIN_QWEN_BYTES = 4_500_000_000L

/** Qwen GGUF downloader. Uses app-private filesDir/models and resumes via HTTP Range. */
class QwenGgufDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun okHttpClient(): OkHttpClient
        fun providerCredentialStore(): ProviderCredentialStore
    }

    private val entry = EntryPointAccessors.fromApplication(
        applicationContext, WorkerEntryPoint::class.java
    )
    private val client get() = entry.okHttpClient()
    private val credentials get() = entry.providerCredentialStore()
    private val targetDir get() = File(applicationContext.filesDir, "models")
    private val targetFile get() = File(targetDir, QWEN_MODEL_FILE)
    private val partialFile get() = File(targetDir, "$QWEN_MODEL_FILE.part")

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ModelDownloadForegroundInfoFactory.create(applicationContext, id)

    override suspend fun doWork(): Result {
        return try {
            setForeground(getForegroundInfo())
            credentials.migrateLegacyCredentials()
            if (!targetDir.exists() && !targetDir.mkdirs()) return Result.failure()
            if (isValidModel(targetFile)) {
                return Result.success(QwenGgufDownloadResult.completedData(targetFile.length()))
            }
            if (targetFile.exists()) targetFile.delete()

            val start = if (partialFile.exists()) partialFile.length() else 0L
            val request = Request.Builder().url(QWEN_MODEL_URL).apply {
                if (start > 0L) header("Range", "bytes=$start-")
                val token = when (val value = credentials.read(ProviderCredentialId.HuggingFaceToken)) {
                    is CredentialStoreResult.Success -> value.value
                    else -> null
                }
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            }.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    return if (response.code == 408 || response.code == 429 || response.code >= 500) {
                        Result.retry()
                    } else Result.failure()
                }
                val append = start > 0L && response.code == 206
                if (append && response.header("Content-Range")?.startsWith("bytes $start-") != true) {
                    return Result.failure()
                }
                val body = response.body ?: return Result.retry()
                val total = if (append) {
                    response.header("Content-Range")?.substringAfterLast("/")?.toLongOrNull()
                } else body.contentLength().takeIf { it > 0L }
                if (!append && start > 0L) partialFile.delete()

                body.byteStream().use { input ->
                    FileOutputStream(partialFile, append).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var downloaded = if (append) start else 0L
                        var lastProgress = 0L
                        while (true) {
                            if (isStopped) throw CancellationException("Worker stopped")
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (downloaded - lastProgress >= 2L * 1024L * 1024L) {
                                lastProgress = downloaded
                                val percent = if (total != null && total > 0) {
                                    (downloaded * 100L / total).toInt().coerceIn(0, 99)
                                } else -1
                                setProgress(Data.Builder()
                                    .putLong("downloaded", downloaded)
                                    .putLong("total", total ?: -1L)
                                    .putInt("percent", percent).build())
                                setForeground(getForegroundInfo())
                            }
                        }
                        output.fd.sync()
                    }
                }
            }

            if (!isValidModel(partialFile)) {
                Log.e("QwenGgufDownload", "Downloaded file failed GGUF/size validation")
                return Result.failure()
            }
            if (targetFile.exists()) targetFile.delete()
            if (!partialFile.renameTo(targetFile)) return Result.failure()
            setProgress(Data.Builder().putLong("downloaded", targetFile.length())
                .putLong("total", targetFile.length()).putInt("percent", 100).build())
            Result.success(QwenGgufDownloadResult.completedData(targetFile.length()))
        } catch (cancelled: CancellationException) {
            throw cancelled // Keep .part for a future retry/resume.
        } catch (error: Exception) {
            Log.e("QwenGgufDownload", "Qwen download interrupted; partial file retained", error)
            Result.retry()
        }
    }

    private fun isValidModel(file: File): Boolean {
        if (!file.isFile || file.length() < MIN_QWEN_BYTES) return false
        return runCatching {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF"
            }
        }.getOrDefault(false)
    }
}

/** Starts one app-managed download; CONNECTED permits both metered mobile data and Wi-Fi. */
object QwenGgufDownload {
    fun enqueue(context: Context): java.util.UUID {
        val request = OneTimeWorkRequestBuilder<QwenGgufDownloadWorker>()
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            QWEN_WORK_NAME, ExistingWorkPolicy.KEEP, request
        )
        return request.id
    }

    const val WORK_NAME = QWEN_WORK_NAME
}


/** Persists final progress in WorkInfo.outputData, which survives SUCCEEDED state. */
internal object QwenGgufDownloadResult {
    fun completedData(fileLength: Long): Data = Data.Builder()
        .putLong("downloaded", fileLength.coerceAtLeast(0L))
        .putLong("total", fileLength.coerceAtLeast(0L))
        .putInt("percent", 100)
        .putBoolean("verified", true)
        .build()
}
