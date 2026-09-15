package com.zikriyo.geminisharh.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Microsoft Edge online TTS (norasmiy / kalitsiz).
 * Python edge-tts bilan bir xil endpoint va Sec-MS-GEC algoritmi.
 * uz-UZ-SardorNeural, uz-UZ-MadinaNeural va boshqa Neural ovozlar ishlaydi.
 *
 * Diqqat: Microsoft protocolni o'zgartirishi mumkin — ishlamasa Azure fallback ishlatiladi.
 */
class EdgeTtsClient {

    companion object {
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val CHROMIUM_FULL = "143.0.3650.75"
        private const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL"
        private const val WIN_EPOCH = 11_644_473_600L  // seconds between 1601 and 1970

        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        fun generateSecMsGec(): String {
            // Unix seconds + clock (no skew correction for simplicity)
            var ticks = System.currentTimeMillis() / 1000.0
            ticks += WIN_EPOCH
            // Round down to nearest 5 minutes
            ticks -= ticks % 300
            // Windows file time: 100-nanosecond intervals
            val winTicks = (ticks * 10_000_000).toLong()
            val strToHash = "${winTicks}$TRUSTED_CLIENT_TOKEN"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(strToHash.toByteArray(Charsets.US_ASCII))
            return digest.joinToString("") { "%02X".format(it) }
        }
    }

    /**
     * Matnni berilgan ovozda sintez qiladi, MP3 faylga yozadi.
     * @return true agar muvaffaqiyatli
     */
    fun synthesizeToFile(text: String, voiceId: String, outFile: File): Boolean {
        if (text.isBlank()) return false

        val requestId = UUID.randomUUID().toString().replace("-", "")
        val secGec = generateSecMsGec()
        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
            "&Sec-MS-GEC=$secGec" +
            "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        val ssml = """
            <speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>
              <voice name='$voiceId'>
                <prosody pitch='+0Hz' rate='+0%' volume='+0%'>
                  $escaped
                </prosody>
              </voice>
            </speak>
        """.trimIndent()

        val audioBuffer = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<String?>(null)
        val done = AtomicReference(false)

        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
            )
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 1) Speech config
                val configMsg =
                    "X-Timestamp:${jsDate()}\r\n" +
                    "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
                webSocket.send(configMsg)

                // 2) SSML
                val ssmlMsg =
                    "X-RequestId:$requestId\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:${jsDate()}Z\r\n" +
                    "Path:ssml\r\n\r\n" +
                    ssml
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Text frames: headers + optional JSON. Look for Path:turn.end
                if (text.contains("Path:turn.end") || text.contains("Path:turn.end\r\n")) {
                    done.set(true)
                    webSocket.close(1000, "done")
                    latch.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Binary: header length (2 bytes big-endian) then header then audio
                val arr = bytes.toByteArray()
                if (arr.size < 2) return
                val headerLen = ((arr[0].toInt() and 0xFF) shl 8) or (arr[1].toInt() and 0xFF)
                if (arr.size > headerLen + 2) {
                    audioBuffer.write(arr, headerLen + 2, arr.size - headerLen - 2)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                errorRef.set(t.message ?: "WebSocket failure")
                latch.countDown()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (!done.get()) latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!done.get()) latch.countDown()
            }
        }

        val ws = client.newWebSocket(request, listener)
        val finished = latch.await(55, TimeUnit.SECONDS)
        ws.cancel()

        if (!finished || errorRef.get() != null) {
            return false
        }

        val data = audioBuffer.toByteArray()
        if (data.isEmpty()) return false

        outFile.parentFile?.mkdirs()
        outFile.writeBytes(data)
        return true
    }

    private fun jsDate(): String {
        // Approximate JS Date string used by edge-tts
        val sdf = java.text.SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date())
    }
}
