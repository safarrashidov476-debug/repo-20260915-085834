package com.zikriyo.geminisharh.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

/**
 * Muhim natijalarni foydalanuvchi ko'radigan Yuklamalar papkasiga joylash.
 */
object OutputSaver {

    /**
     * Papkadagi asosiy fayllarni MediaStore Downloads orqali "Yuklamalar/GeminiSharh" ga nusxalaydi.
     * @return ochiq ko'rinadigan papka nomi yoki yo'l
     */
    fun publishToDownloads(context: Context, sourceDir: File, folderName: String): String {
        val published = mutableListOf<String>()
        val files = sourceDir.listFiles()?.filter { it.isFile } ?: emptyList()

        for (file in files) {
            val mime = when (file.extension.lowercase()) {
                "mp4" -> "video/mp4"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "srt" -> "application/x-subrip"
                "txt" -> "text/plain"
                else -> "application/octet-stream"
            }
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                insertMediaStore(context, file, mime, folderName)
            } else {
                copyLegacy(file, folderName)
            }
            if (ok) published.add(file.name)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Yuklamalar/$folderName (${published.size} ta fayl)"
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                folderName
            )
            dir.absolutePath
        }
    }

    private fun insertMediaStore(context: Context, file: File, mime: String, relativeFolder: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/$relativeFolder")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { input -> input.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun copyLegacy(file: File, folderName: String): Boolean {
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                folderName
            )
            dir.mkdirs()
            file.copyTo(File(dir, file.name), overwrite = true)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
