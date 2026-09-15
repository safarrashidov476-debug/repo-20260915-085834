package com.zikriyo.geminisharh.api

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Pure OkHttp client for Google Gemini API (v1beta).
 * Supports file upload (resumable), poll, generateContent, and inline fallback.
 */
class GeminiApiClient(
    private val apiKey: String,
    private val requestDelayMs: Long = 3000L
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    data class UploadedFile(
        val name: String,
        val uri: String,
        val mimeType: String
    )

    private suspend fun rateLimit() {
        if (requestDelayMs > 0) delay(requestDelayMs)
    }

    /**
     * Uploads a video via resumable protocol. Returns name + uri from server.
     */
    suspend fun uploadVideo(file: File, mimeType: String = "video/mp4"): UploadedFile {
        val startBody = """
            {
              "file": {
                "display_name": "${file.name.replace("\"", "")}"
              }
            }
        """.trimIndent().toRequestBody(jsonMedia)

        val startReq = Request.Builder()
            .url("https://generativelanguage.googleapis.com/upload/v1beta/files?key=$apiKey")
            .addHeader("X-Goog-Upload-Protocol", "resumable")
            .addHeader("X-Goog-Upload-Command", "start")
            .addHeader("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .addHeader("X-Goog-Upload-Header-Content-Type", mimeType)
            .addHeader("Content-Type", "application/json")
            .post(startBody)
            .build()

        val startResp = client.newCall(startReq).execute()
        if (!startResp.isSuccessful) {
            throw Exception("Upload start failed: ${startResp.code} ${startResp.body?.string()}")
        }
        val uploadUrl = startResp.header("X-Goog-Upload-URL")
            ?: throw Exception("No upload URL returned")
        startResp.close()
        rateLimit()

        val uploadReq = Request.Builder()
            .url(uploadUrl)
            .addHeader("Content-Length", file.length().toString())
            .addHeader("X-Goog-Upload-Offset", "0")
            .addHeader("X-Goog-Upload-Command", "upload, finalize")
            .post(file.asRequestBody(mimeType.toMediaType()))
            .build()

        val uploadResp = client.newCall(uploadReq).execute()
        val body = uploadResp.body?.string() ?: ""
        if (!uploadResp.isSuccessful) {
            throw Exception("Upload finalize failed: ${uploadResp.code} $body")
        }
        rateLimit()

        val fileObj = JsonParser.parseString(body).asJsonObject.getAsJsonObject("file")
            ?: throw Exception("No file object in response: $body")
        val name = fileObj.get("name")?.asString
            ?: throw Exception("No file name in response: $body")
        val uri = fileObj.get("uri")?.asString
            ?: "https://generativelanguage.googleapis.com/v1beta/$name"

        return UploadedFile(name, uri, mimeType)
    }

    /**
     * Polls until ACTIVE. Returns true if active, false if timeout.
     * Throws with detail if FAILED.
     */
    suspend fun waitUntilActive(fileName: String, maxAttempts: Int = 90): Boolean {
        repeat(maxAttempts) { attempt ->
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/$fileName?key=$apiKey")
                .get()
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()
            if (!resp.isSuccessful) {
                throw Exception("Poll failed: ${resp.code} $body")
            }
            val root = JsonParser.parseString(body).asJsonObject
            val state = root.get("state")?.asString
            when (state) {
                "ACTIVE" -> return true
                "FAILED" -> {
                    val errObj = root.getAsJsonObject("error")
                    val errMsg = errObj?.get("message")?.asString
                        ?: errObj?.toString()
                        ?: body.take(500)
                    throw Exception("Video serverda qayta ishlanmadi (FAILED). Sabab: $errMsg")
                }
            }
            // PROCESSING — kutish
            delay(if (attempt < 10) 2000L else 3000L)
        }
        return false
    }

    private fun buildPrompt(language: String): String = """
        Siz professional tiflosharh (audio description) yozuvchisisiz.
        Ushbu videoni diqqat bilan tomosha qiling va ko'zi ojiz tomoshabinlar uchun
        sinxron audio tavsif (tiflosharh) matnini yarating.

        Qoidalar:
        1. Har bir muhim vizual voqea, harakat, imo-ishora, kadr o'zgarishi va muhim detal uchun alohida qator yozing.
        2. Har bir qatorni aniq vaqt kodi bilan boshlang: [MM:SS] yoki [HH:MM:SS]
        3. Matn qisqa, aniq va tabiiy bo'lsin (1-2 jumla).
        4. Faqat ko'rinadigan narsalarni tavsiflang, dialog yoki ovozli effektlarni takrorlamang.
        5. Til: $language (O'zbekcha / Русский / English).
        6. Natijani faqat quyidagi formatda qaytaring, boshqa izoh qo'shmang:

        [00:05] Kamera xonani ko'rsatadi...
        [00:18] Erkak o'rnidan turadi...
        [01:02] ...
    """.trimIndent()

    /**
     * Generate timed description using uploaded file URI.
     */
    suspend fun generateTimedDescription(
        fileUri: String,
        mimeType: String = "video/mp4",
        language: String = "uz",
        modelId: String = "models/gemini-3.6-flash"
    ): String {
        val prompt = buildPrompt(language)
        val bodyJson = """
            {
              "contents": [{
                "parts": [
                  {"file_data": {"mime_type": "$mimeType", "file_uri": "$fileUri"}},
                  {"text": ${gson.toJson(prompt)}}
                ]
              }],
              "generationConfig": {
                "temperature": 0.4,
                "maxOutputTokens": 8192
              }
            }
        """.trimIndent()

        return callGenerateContent(modelId, bodyJson)
    }

    /**
     * Fallback: send video as inline base64 (for smaller files when Files API fails).
     * Practical limit ~15–20 MB on many devices/networks.
     */
    suspend fun generateTimedDescriptionInline(
        file: File,
        mimeType: String = "video/mp4",
        language: String = "uz",
        modelId: String = "models/gemini-3.6-flash"
    ): String {
        val bytes = file.readBytes()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val prompt = buildPrompt(language)
        val bodyJson = """
            {
              "contents": [{
                "parts": [
                  {"inline_data": {"mime_type": "$mimeType", "data": "$b64"}},
                  {"text": ${gson.toJson(prompt)}}
                ]
              }],
              "generationConfig": {
                "temperature": 0.4,
                "maxOutputTokens": 8192
              }
            }
        """.trimIndent()

        return callGenerateContent(modelId, bodyJson)
    }

    private suspend fun callGenerateContent(modelId: String, bodyJson: String): String {
        val model = modelId.ifBlank { "models/gemini-3.6-flash" }
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/$model:generateContent?key=$apiKey")
            .post(bodyJson.toRequestBody(jsonMedia))
            .build()

        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: ""
        if (!resp.isSuccessful) {
            throw Exception("generateContent failed: ${resp.code} $body")
        }
        rateLimit()

        val root = JsonParser.parseString(body).asJsonObject
        val text = root
            .getAsJsonArray("candidates")
            ?.get(0)?.asJsonObject
            ?.getAsJsonObject("content")
            ?.getAsJsonArray("parts")
            ?.get(0)?.asJsonObject
            ?.get("text")?.asString
            ?: throw Exception("Empty response from Gemini: $body")

        return text
    }

    suspend fun testApiKey(): Pair<Boolean, String> {
        return try {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
                .get()
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()
            if (resp.isSuccessful) {
                val count = JsonParser.parseString(body).asJsonObject
                    .getAsJsonArray("models")?.size() ?: 0
                true to "OK, $count model topildi"
            } else {
                false to "HTTP ${resp.code}: $body"
            }
        } catch (e: Exception) {
            false to (e.message ?: "Unknown error")
        }
    }
}
