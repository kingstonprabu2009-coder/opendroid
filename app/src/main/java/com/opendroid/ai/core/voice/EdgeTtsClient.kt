package com.opendroid.ai.core.voice

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Free Microsoft Edge "read aloud" neural voices (unofficial, no key, no credits).
 * Used when Edge Neural is explicitly selected in Settings. Voice IDs are selected per utterance.
 * Microsoft can change or block this service at any time, so callers must keep a fallback.
 */
class EdgeTtsClient(private val client: OkHttpClient = OkHttpClient()) {

    @Volatile private var socket: WebSocket? = null

    /** Cancels a synthesis in progress. */
    fun cancel() {
        socket?.cancel()
    }

    /**
     * Writes an MP3 of [text] spoken by [voice] to [target].
     * Blocks only its caller thread (up to 12 seconds). Call from Dispatchers.IO; returns false on failure.
     */
    fun synthesize(text: String, voice: String, target: File, pitch: String = "+0Hz", rate: String = "+0%"): Boolean {
        val done = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val out = FileOutputStream(target)
        try {
            val connectionId = UUID.randomUUID().toString().replace("-", "")
            val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=$TOKEN&ConnectionId=$connectionId" +
                "&Sec-MS-GEC=${secMsGec()}&Sec-MS-GEC-Version=1-$CHROMIUM_VERSION"
            val request = Request.Builder()
                .url(url)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/${CHROMIUM_VERSION.substringBefore('.')}.0.0.0 Safari/537.36 " +
                        "Edg/${CHROMIUM_VERSION.substringBefore('.')}.0.0.0"
                )
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "muid=${UUID.randomUUID().toString().replace("-", "").uppercase()};")
                .build()

            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val stamp = timestamp()
                    webSocket.send(
                        "X-Timestamp:$stamp\r\nContent-Type:application/json; charset=utf-8\r\n" +
                            "Path:speech.config\r\n\r\n" +
                            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                            "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                    )
                    val requestId = UUID.randomUUID().toString().replace("-", "")
                    val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' " +
                        "xml:lang='${voice.substringBeforeLast('-')}'><voice name='$voice'>" +
                        "<prosody pitch='$pitch' rate='$rate' volume='+0%'>${escapeXml(text)}</prosody>" +
                        "</voice></speak>"
                    webSocket.send(
                        "X-RequestId:$requestId\r\nContent-Type:application/ssml+xml\r\n" +
                            "X-Timestamp:${stamp}Z\r\nPath:ssml\r\n\r\n$ssml"
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end")) {
                        finished.set(true)
                        done.countDown()
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val data = bytes.toByteArray()
                    if (data.size < 2) return
                    val headerLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                    val start = 2 + headerLength
                    if (start > data.size) return
                    val header = String(data, 2, headerLength, Charsets.UTF_8)
                    if (header.contains("Path:audio") && data.size > start) {
                        out.write(data, start, data.size - start)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    done.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    done.countDown()
                }
            })
            socket = ws
            val completed = done.await(2500, TimeUnit.MILLISECONDS)
            if (!completed) ws.cancel()
            else try { ws.close(1000, null) } catch (_: Exception) {}
        } finally {
            socket = null
            try { out.close() } catch (_: Exception) {}
        }
        return finished.get() && target.length() > 0L
    }

    private fun timestamp(): String {
        val format = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }

    /** Anti-abuse token Microsoft now requires: SHA-256 of 5-minute-rounded Windows ticks + client token. */
    private fun secMsGec(): String {
        var ticks = System.currentTimeMillis() / 1000L + 11644473600L
        ticks -= ticks % 300L
        ticks *= 10_000_000L
        val digest = MessageDigest.getInstance("SHA-256").digest("$ticks$TOKEN".toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    companion object {
        private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val CHROMIUM_VERSION = "143.0.3650.75"

        /** Male neural voice for the language of [text]. */
        fun voiceFor(text: String): String {
            var tamil = false; var hindi = false; var telugu = false; var kannada = false; var malayalam = false
            for (c in text) {
                when (c) {
                    in '\u0B80'..'\u0BFF' -> tamil = true
                    in '\u0900'..'\u097F' -> hindi = true
                    in '\u0C00'..'\u0C7F' -> telugu = true
                    in '\u0C80'..'\u0CFF' -> kannada = true
                    in '\u0D00'..'\u0D7F' -> malayalam = true
                }
            }
            return when {
                tamil -> "ta-IN-ValluvarNeural"
                hindi -> "hi-IN-MadhurNeural"
                telugu -> "te-IN-MohanNeural"
                kannada -> "kn-IN-GaganNeural"
                malayalam -> "ml-IN-MidhunNeural"
                else -> "en-IN-PrabhatNeural"
            }
        }
    }
}
