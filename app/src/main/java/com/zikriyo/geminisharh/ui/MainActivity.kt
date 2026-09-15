package com.zikriyo.geminisharh.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zikriyo.geminisharh.R
import com.zikriyo.geminisharh.api.AzureTtsClient
import com.zikriyo.geminisharh.api.EdgeTtsClient
import com.zikriyo.geminisharh.api.GeminiApiClient
import com.zikriyo.geminisharh.data.Prefs
import com.zikriyo.geminisharh.databinding.ActivityMainBinding
import com.zikriyo.geminisharh.util.MediaComposer
import com.zikriyo.geminisharh.util.OutputSaver
import com.zikriyo.geminisharh.util.TimestampParser
import com.zikriyo.geminisharh.util.TtsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var selectedUri: Uri? = null
    private var selectedName: String = ""
    private var isProcessing = false
    private var videoMergeFailed = false

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedUri = uri
            selectedName = queryDisplayName(uri) ?: "video.mp4"
            binding.tvSelectedVideo.text = selectedName
            binding.btnStart.isEnabled = true
            appendLog("Tanlandi: $selectedName")
        }
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handled on demand */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        prefs = Prefs(this)

        binding.btnPickVideo.setOnClickListener {
            ensurePermissions()
            pickVideo.launch("video/*")
        }

        binding.btnStart.setOnClickListener {
            if (isProcessing) return@setOnClickListener
            startProcessing()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, getString(R.string.open_settings))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED
            ) needed.add(Manifest.permission.READ_MEDIA_VIDEO)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (needed.isNotEmpty()) requestPermission.launch(needed.toTypedArray())
    }

    private fun startProcessing() {
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            Toast.makeText(this, R.string.no_api_key, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val uri = selectedUri
        if (uri == null) {
            Toast.makeText(this, R.string.select_video_first, Toast.LENGTH_SHORT).show()
            return
        }

        isProcessing = true
        videoMergeFailed = false
        binding.btnStart.isEnabled = false
        binding.btnPickVideo.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.setText(R.string.status_uploading)
        binding.tvLog.text = "" // Yangi jarayon — eski loglarni tozalash
        appendLog("Jarayon boshlandi…")
        appendLog("Ovoz: ${prefs.selectedVoiceId}")
        appendLog("Model: ${prefs.selectedVisionModel}")

        lifecycleScope.launch {
            try {
                val resultDir = withContext(Dispatchers.IO) {
                    processVideo(uri, apiKey)
                }
                if (videoMergeFailed) {
                    binding.tvStatus.text = "Audio tayyor, lekin video yaratilmadi (log’ni ko‘ring)"
                    appendLog("DIQQAT: Video fayl yaratilmadi, faqat audio (narration.m4a) saqlandi.")
                    Toast.makeText(
                        this@MainActivity,
                        "Video yaratilmadi — faqat audio saqlandi. Log’dagi sababni tekshiring.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    binding.tvStatus.setText(R.string.success)
                    Toast.makeText(this@MainActivity, R.string.success, Toast.LENGTH_LONG).show()
                }
                appendLog("Tayyor! Papka: ${resultDir.absolutePath}")
            } catch (e: OutOfMemoryError) {
                binding.tvStatus.text = "Xotira yetmadi — video juda katta yoki rezolyutsiyasi baland"
                appendLog("XATO (OutOfMemoryError): Video/audio qayta ishlashda xotira yetmadi. " +
                    "Qisqaroq yoki pastroq sifatli video bilan urinib ko‘ring.")
            } catch (e: Throwable) {
                binding.tvStatus.text = getString(R.string.error_generic) + ": ${e.message}"
                appendLog("XATO (${e.javaClass.simpleName}): ${e.message}")
                e.printStackTrace()
            } finally {
                isProcessing = false
                binding.btnStart.isEnabled = true
                binding.btnPickVideo.isEnabled = true
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private suspend fun processVideo(uri: Uri, apiKey: String): File {
        val delayMs = (prefs.requestDelaySec * 1000).toLong()
        val client = GeminiApiClient(apiKey, delayMs)
        val voiceId = prefs.selectedVoiceId
        val modelId = prefs.selectedVisionModel

        // Copy content URI to cache file
        updateStatus(R.string.status_uploading)
        val ext = selectedName.substringAfterLast('.', "mp4").lowercase()
        val mime = when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/avi"
            "mkv" -> "video/x-matroska"
            "3gp" -> "video/3gpp"
            "wmv" -> "video/wmv"
            else -> "video/mp4"
        }
        val cacheFile = File(cacheDir, "input_${System.currentTimeMillis()}.$ext")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
        } ?: throw Exception("Videoni o‘qib bo‘lmadi")

        val sizeMb = cacheFile.length() / (1024.0 * 1024.0)
        appendLog("Fayl nusxalandi: %.1f MB, mime=$mime".format(sizeMb))
        if (sizeMb > 100) {
            appendLog("OGOHLANTIRISH: Fayl katta (>100 MB). Qisqaroq yoki siqilgan video sinab ko‘ring.")
        }

        // Upload + tahlil (Files API, FAILED bo'lsa inline fallback)
        updateStatus(R.string.status_analyzing)
        var rawText: String? = null
        try {
            val uploaded = client.uploadVideo(cacheFile, mime)
            appendLog("Yuklandi: ${uploaded.name}")
            appendLog("URI: ${uploaded.uri}")

            val active = client.waitUntilActive(uploaded.name)
            if (!active) throw Exception("Video serverda ACTIVE holatga o'tmadi (timeout)")
            appendLog("Video ACTIVE")

            rawText = client.generateTimedDescription(uploaded.uri, mime, "uz", modelId)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("FAILED") || msg.contains("qayta ishlanmadi")) {
                val maxInline = 18L * 1024 * 1024
                if (cacheFile.length() <= maxInline) {
                    appendLog("Files API FAILED - inline usul bilan qayta urinilmoqda...")
                    rawText = client.generateTimedDescriptionInline(cacheFile, mime, "uz", modelId)
                } else {
                    val mb = cacheFile.length() / 1024.0 / 1024.0
                    val mbStr = String.format("%.1f", mb)
                    throw Exception(
                        "Video serverda qayta ishlanmadi va fayl inline uchun katta " +
                            "($mbStr MB > 18 MB). Qisqaroq yoki H.264 MP4 qilib qayta kodlang. " +
                            "Asosiy xato: $msg"
                    )
                }
            } else {
                throw e
            }
        }
        val finalText = rawText ?: throw Exception("Gemini javobi bo'sh")
        appendLog("Gemini javobi olingan (${finalText.length} belgi)")

        val segments = TimestampParser.parse(finalText)
        if (segments.isEmpty()) {
            throw Exception("Vaqt kodlari topilmadi. Gemini javobi:\n$finalText")
        }
        appendLog("${segments.size} ta segment topildi")

        // Ochiq papka: Yuklamalar/GeminiSharh_...
        var outDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "GeminiSharh_${System.currentTimeMillis()}"
        )
        if (!outDir.exists() && !outDir.mkdirs()) {
            outDir = File(
                getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                "GeminiSharh_${System.currentTimeMillis()}"
            )
            outDir.mkdirs()
            appendLog("Yuklamalar yopiq — ichki papka ishlatiladi")
        }
        appendLog("Natija papkasi: ${outDir.absolutePath}")

        // Save SRT + TXT
        updateStatus(R.string.status_saving)
        File(outDir, "description.srt").writeText(TimestampParser.createSrt(segments))
        File(outDir, "description.txt").writeText(TimestampParser.createTxt(segments))
        File(outDir, "raw_gemini.txt").writeText(finalText)

        // TTS: Edge (har doim) → Azure → System
        // Fenrir/Charon/Puck/Aoede/Kore → Sardor yoki Madina ga map qilinadi
        updateStatus(R.string.status_tts)
        val audioDir = File(outDir, "audio_segments")
        audioDir.mkdirs()

        val edgeVoice = mapToEdgeVoice(voiceId)
        appendLog("TTS ovoz: tanlangan=$voiceId → Edge=$edgeVoice")

        val edge = EdgeTtsClient()
        val azureKey = prefs.azureKey
        val azure = if (azureKey.isNotBlank()) AzureTtsClient(azureKey, prefs.azureRegion) else null
        val systemTts = TtsHelper(this@MainActivity)

        var edgeOkCount = 0
        var azureOkCount = 0
        var systemOkCount = 0

        try {
            segments.forEachIndexed { idx, seg ->
                val audioFile = File(audioDir, "seg_%03d.mp3".format(idx))
                var ok = false

                runOnUiThread {
                    binding.tvStatus.text = getString(
                        R.string.status_tts_progress, idx + 1, segments.size
                    )
                }

                // 1. Edge — har doim (kalitsiz)
                try {
                    ok = edge.synthesizeToFile(seg.text, edgeVoice, audioFile)
                    if (ok) {
                        edgeOkCount++
                        appendLog("TTS ${idx + 1}/${segments.size} [Edge]: OK")
                    } else {
                        appendLog("TTS ${idx + 1}/${segments.size} [Edge]: FAIL")
                    }
                } catch (e: Exception) {
                    appendLog("TTS ${idx + 1}/${segments.size} [Edge]: ${e.message}")
                }

                // 2. Azure
                if (!ok && azure != null) {
                    ok = azure.synthesizeToFile(seg.text, edgeVoice, audioFile)
                    if (ok) {
                        azureOkCount++
                        appendLog("TTS ${idx + 1}/${segments.size} [Azure]: OK")
                    }
                }

                // 3. System TTS
                if (!ok) {
                    val wavFile = File(audioDir, "seg_%03d.wav".format(idx))
                    ok = systemTts.speakToFile(seg.text, wavFile)
                    if (ok) {
                        systemOkCount++
                        appendLog("TTS ${idx + 1}/${segments.size} [System]: OK")
                    } else {
                        appendLog("TTS ${idx + 1}/${segments.size} [System]: FAIL")
                    }
                }

                Thread.sleep(200)
            }
        } finally {
            systemTts.shutdown()
        }

        appendLog("TTS yakun: Edge=$edgeOkCount, Azure=$azureOkCount, System=$systemOkCount")

        // Segment fayllar ro'yxati
        val segFiles = segments.indices.map { idx ->
            val mp3 = File(audioDir, "seg_%03d.mp3".format(idx))
            val wav = File(audioDir, "seg_%03d.wav".format(idx))
            when {
                mp3.exists() && mp3.length() > 0 -> mp3
                wav.exists() && wav.length() > 0 -> wav
                else -> mp3
            }
        }

        // 1) Segmentlarni bitta narration.m4a ga yig'ish
        updateStatus(R.string.status_saving)
        appendLog("Narration yig'ilmoqda...")
        val narrationFile = File(outDir, "narration.m4a")
        var narrResult = MediaComposer.buildTimedNarration(segments, segFiles, narrationFile)
        if (!narrResult.ok) {
            appendLog("Timed narration: ${narrResult.log.take(200)}")
            appendLog("Oddiy concat urinilmoqda...")
            narrResult = MediaComposer.concatAudioSimple(segFiles, narrationFile)
        }
        if (narrResult.ok && narrationFile.exists()) {
            appendLog("Narration OK: ${narrationFile.length() / 1024} KB")
        } else {
            appendLog("Narration FAIL: ${narrResult.log.take(400)}")
        }

        // 2) Original video + narration (original ovoz ~28%)
        if (narrResult.ok && narrationFile.exists() && narrationFile.length() > 0) {
            updateStatus(R.string.status_merging)
            appendLog("Video bilan birlashtirilmoqda (original ovoz past)...")
            // cache o'chirilmasin deb oldin merge — cacheFile hali bor
            val finalVideo = File(outDir, "video_with_sharh.mp4")
            // Videoni outDir ga nusxa (cache keyin o'chadi)
            val videoCopy = File(outDir, "source_video.mp4")
            if (!videoCopy.exists()) {
                cacheFile.copyTo(videoCopy, overwrite = true)
            }
            val mergeResult = MediaComposer.mergeVideoWithNarration(
                videoFile = videoCopy,
                narrationFile = narrationFile,
                outputFile = finalVideo,
                originalVolume = 0.5,
                narrationVolume = 2.2
            )
            if (mergeResult.ok && finalVideo.exists()) {
                appendLog("Tayyor video: ${finalVideo.name} (${finalVideo.length() / 1024} KB)")
            } else {
                appendLog("Birlashtirish FAIL: ${mergeResult.log.take(500)}")
                appendLog("Narration alohida saqlangan: narration.m4a")
                videoMergeFailed = true
            }
        } else {
            appendLog("Merge o'tkazib yuborildi — narration yo'q")
        }

        // Media scanner + Yuklamalar/GeminiSharh_* ga e'lon qilish
        try {
            val toScan = outDir.listFiles()?.map { it.absolutePath }?.toTypedArray() ?: emptyArray()
            if (toScan.isNotEmpty()) {
                MediaScannerConnection.scanFile(this@MainActivity, toScan, null, null)
            }
            audioDir.listFiles()?.map { it.absolutePath }?.toTypedArray()?.let { arr ->
                if (arr.isNotEmpty()) MediaScannerConnection.scanFile(this@MainActivity, arr, null, null)
            }
            val pub = OutputSaver.publishToDownloads(this@MainActivity, outDir, outDir.name)
            appendLog("Yuklamalarda: $pub")
        } catch (e: Exception) {
            appendLog("Publish: ${e.message}")
        }

        // Clean cache
        cacheFile.delete()

        return outDir
    }

    private fun updateStatus(resId: Int) {
        runOnUiThread { binding.tvStatus.setText(resId) }
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            binding.tvLog.append("$msg\n")
            binding.scrollLog.post {
                binding.scrollLog.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return null
    }

    /**
     * Edge TTS faqat "xx-XX-NomNeural" formatidagi ovozlarni qo'llab-quvvatlaydi.
     * Gemini original ovozlari (Charon/Fenrir/Puck — erkak, Aoede/Kore — ayol)
     * mos O'zbekcha Edge ovozlariga (Sardor/Madina) moslashtiriladi.
     */
    private fun mapToEdgeVoice(voiceId: String): String {
        if (voiceId.contains("Neural")) return voiceId
        val maleGeminiVoices = setOf("Charon", "Fenrir", "Puck")
        val femaleGeminiVoices = setOf("Aoede", "Kore")
        return when (voiceId) {
            in maleGeminiVoices -> "uz-UZ-SardorNeural"
            in femaleGeminiVoices -> "uz-UZ-MadinaNeural"
            else -> "uz-UZ-SardorNeural"
        }
    }
}
