package com.zikriyo.geminisharh.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

class TtsHelper(context: Context) {

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val result = tts?.setLanguage(Locale("uz"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    if (tts?.setLanguage(Locale("ru")) == TextToSpeech.LANG_MISSING_DATA ||
                        tts?.setLanguage(Locale("ru")) == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        tts?.setLanguage(Locale.US)
                    }
                }
            }
        }
    }

    private suspend fun waitReady(timeoutMs: Long = 5000): Boolean {
        val start = System.currentTimeMillis()
        while (!ready && System.currentTimeMillis() - start < timeoutMs) {
            delay(100)
        }
        return ready && tts != null
    }

    suspend fun speakToFile(text: String, outFile: File): Boolean {
        if (!waitReady()) return false
        outFile.parentFile?.mkdirs()

        return suspendCancellableCoroutine { cont ->
            val utteranceId = UUID.randomUUID().toString()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (cont.isActive) cont.resume(outFile.exists() && outFile.length() > 0)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (cont.isActive) cont.resume(false)
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (cont.isActive) cont.resume(false)
                }
            })

            val params = android.os.Bundle()
            val result = tts?.synthesizeToFile(text, params, outFile, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        ready = false
    }
}
