package com.opendroid.ai.core.cloud

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
        private val gson = Gson()
        private val client = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()

        suspend fun fetch(): RemoteAppConfig? = withContext(Dispatchers.IO) {
            val base = BuildConfig.JARVIS_CLOUD_BASE_URL.trimEnd('/')
            if (!base.startsWith("https://")) return@withContext null
            val request = Request.Builder()
                .url("$base/app-config")
                .header("Accept", "application/json")
                .get()
                .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body.string()
                    gson.fromJson(body, RemoteAppConfig::class.java)
                }
            }.getOrNull()
        }
    }
}
