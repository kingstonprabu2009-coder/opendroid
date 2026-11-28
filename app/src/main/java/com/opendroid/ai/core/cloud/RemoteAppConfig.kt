package com.opendroid.ai.core.cloud

import android.util.Log
import com.google.gson.Gson
import com.opendroid.ai.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches safe server-driven settings. This deliberately does not download,
 * load, or execute code; native behavior changes still ship through signed APKs.
 */
data class RemoteAppConfig(
    val schemaVersion: Int = 1,
    val configVersion: Int = 0,
    val minimumBuild: Int = 12,
    val features: Features = Features(),
    val ui: Ui = Ui()
) {
    data class Features(
        val remoteUiConfig: Boolean = false,
        val liveConfigRefresh: Boolean = false
    )
    data class Ui(val orb: Orb = Orb())
    data class Orb(
        val speakingScale: Float = 1.075f,
        val activeScale: Float = 1.06f,
        val idleScale: Float = 1.025f,
        val speakingDurationMs: Int = 420,
        val activeDurationMs: Int = 760,
        val idleDurationMs: Int = 2600
    )

    companion object {
        private const val TAG = "JarvisRemoteConfig"
        private val gson = Gson()
        private val client = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()

        suspend fun fetch(): RemoteAppConfig? = withContext(Dispatchers.IO) {
            val base = BuildConfig.JARVIS_CLOUD_BASE_URL.trimEnd('/')
            if (!base.startsWith("https://")) {
                Log.w(TAG, "Fetch skipped: cloud base URL is not configured for HTTPS")
                return@withContext null
            }
            val request = Request.Builder()
                .url("$base/app-config")
                .header("Accept", "application/json")
                .get()
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Fetch failed: HTTP ${response.code}")
                        return@use null
                    }
                    val body = response.body.string()
                    val config = runCatching {
                        gson.fromJson(body, RemoteAppConfig::class.java)
                    }.getOrElse { error ->
                        Log.e(TAG, "Fetch failed: invalid JSON (${error.javaClass.simpleName})", error)
                        return@use null
                    }
                    if (config == null || config.schemaVersion != 1) {
                        Log.e(TAG, "Fetch rejected: missing config or unsupported schemaVersion=${config?.schemaVersion}")
                        return@use null
                    }
                    Log.i(TAG, "Fetch success: HTTP ${response.code}, configVersion=${config.configVersion}")
                    config
                }
            } catch (error: Exception) {
                Log.e(TAG, "Fetch failed: ${error.javaClass.simpleName}: ${error.message}", error)
                null
            }
        }
    }
}
