package com.zikriyo.geminisharh.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Microsoft Azure Cognitive Services Text-to-Speech (REST).
 * Aniq o'zbekcha neural ovozlar: uz-UZ-SardorNeural, uz-UZ-MadinaNeural.
 *
 * Bepul kvota: odatda 500 000 belgi / oy (Azure Speech free tier).
 * Kalit: https://portal.azure.com → Speech resource → Keys and Endpoint
 */
class AzureTtsClient(
    private val subscriptionKey: String,
    private val region: String = "eastus"   // o'zgartirish mumkin
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val endpoint = "https://$region.tts.speech.microsoft.com/cognitiveservices/v1"

    /**
     * Matnni berilgan ovozda sintez qiladi va MP3/WAV faylga yozadi.
     * @param voiceId masalan "uz-UZ-SardorNeural"
     * @return true agar muvaffaqiyatli
     */
    fun synthesizeToFile(
        text: String,
        voiceId: String,
        outFile: File,
        outputFormat: String = "audio-24khz-48kbitrate-mono-mp3"
    ): Boolean {
        if (subscriptionKey.isBlank()) return false
        if (text.isBlank()) return false

        val lang = when {
            voiceId.startsWith("uz-") -> "uz-UZ"
            voiceId.startsWith("ru-") -> "ru-RU"
            voiceId.startsWith("en-") -> "en-US"
            else -> "uz-UZ"
        }

        // Faqat Azure neural ovozlar (Gemini ovozlari bu yerda ishlamaydi)
        val azureVoice = when (voiceId) {
            "Charon", "Fenrir", "Puck" -> "uz-UZ-SardorNeural"   // fallback
            "Aoede", "Kore" -> "uz-UZ-MadinaNeural"
            else -> voiceId
        }

        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        val ssml = """
            <speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'>
              <voice name='$azureVoice'>
                <prosody rate='0%' pitch='0%'>
                  $escaped
                </prosody>
              </voice>
            </speak>
        """.trimIndent()

        val body = ssml.toRequestBody("application/ssml+xml".toMediaType())

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Ocp-Apim-Subscription-Key", subscriptionKey)
            .addHeader("Content-Type", "application/ssml+xml")
            .addHeader("X-Microsoft-OutputFormat", outputFormat)
            .addHeader("User-Agent", "GeminiSharhAndroid")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string() ?: ""
                    throw Exception("Azure TTS ${resp.code}: $err")
                }
                val bytes = resp.body?.bytes() ?: return false
                outFile.parentFile?.mkdirs()
                outFile.writeBytes(bytes)
                bytes.isNotEmpty()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun isConfigured(): Boolean = subscriptionKey.isNotBlank()
}
