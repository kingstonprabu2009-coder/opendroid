package com.opendroid.ai.core.voice

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
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
 * Free male Tamil voice (ta-IN-ValluvarNeural) through Microsoft Edge's read-aloud service.
 * Unofficial: it can stop working any time. The caller must fall back to the phone voice
 * when synthesize() returns null.
 */
class EdgeTtsClient(private val client: OkHttpClient) {

    @Volatile private var socket: WebSocket? = null

    fun cancel() {
        socket?.cancel()
    }

    /** Blocking. Returns mp3 bytes, or null on any failure. Call from a background thread. */
    fun synthesize(text: String, voice: String = VOICE): ByteArray? {
        val audio = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(configMessage())
                webSocket.send(ssmlMessage(text, voice))
            }

            override fun onMessage(webSocket: WebSocket, message: String) {
                if (message.contains("Path:turn.end")) {
                    ok.set(true)
                    done.countDown()
                    webSocket.close(1000, null)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size < 2) return
                val headerLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                val start = 2 + headerLen
                if (start > bytes.size) return
                val header = bytes.substring(2, start).utf8()
                if (header.contains("Path:audio")) {
                    synchronized(audio) { audio.write(bytes.substring(start).toByteArray()) }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.countDown()
            }
        }

        val request = Request.Builder()
            .url(buildUrl())
            .addHeader("Pragma", "no-cache")
            .addHeader("Cache-Control", "no-cache")
            .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .build()

        val ws = client.newWebSocket(request, listener)
        socket = ws
        try {
            done.await(20, TimeUnit.SECONDS)
        } finally {
            if (socket === ws) socket = null
            ws.cancel()
        }
        if (!ok.get()) return null
        val bytes = synchronized(audio) { audio.toByteArray() }
        return if (bytes.isEmpty()) null else bytes
    }

    private fun buildUrl(): String =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$TOKEN" +
            "&Sec-MS-GEC=${secMsGec()}" +
            "&Sec-MS-GEC-Version=$GEC_VERSION" +
            "&ConnectionId=${UUID.randomUUID().toString().replace("-", "")}"

    /** Time based token the service checks: SHA-256 of Windows file time (5 min steps) + token. */
    private fun secMsGec(): String {
        var seconds = System.currentTimeMillis() / 1000 + 11644473600L
        seconds -= seconds % 300
        val ticks = seconds * 10_000_000L
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("$ticks$TOKEN".toByteArray(Charsets.US_ASCII))
        return hash.joinToString("") { "%02X".format(it) }
    }

    private fun timestamp(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun configMessage(): String =
        "X-Timestamp:${timestamp()}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
            "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"

    private fun ssmlMessage(text: String, voice: String): String {
        val safe = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
        val lang = voice.split("-").take(2).joinToString("-")
        return "X-RequestId:${UUID.randomUUID().toString().replace("-", "")}\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${timestamp()}Z\r\n" +
            "Path:ssml\r\n\r\n" +
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'>" +
            "<voice name='$voice'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>$safe</prosody></voice></speak>"
    }

    private companion object {
        const val VOICE = "ta-IN-ValluvarNeural"
        const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        const val GEC_VERSION = "1-130.0.2849.68"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
    }
}
// END
