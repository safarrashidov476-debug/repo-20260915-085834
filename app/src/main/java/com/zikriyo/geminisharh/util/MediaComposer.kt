package com.zikriyo.geminisharh.util

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * Original NVDA addon bilan bir xil yondashuv:
 * 1) Segmentlarni birlashtirish (narration)
 * 2) ffmpeg: original volume=0.5, narration volume=2.2, amix
 * 3) -c:v copy (video sifatini saqlash)
 *
 * executeWithArguments — filter_complex dagi [0:a] qavslari buzilmasin.
 */
object MediaComposer {

    data class Result(val ok: Boolean, val log: String)

    fun concatAudioSimple(files: List<File>, output: File): Result {
        val existing = files.filter { it.exists() && it.length() > 0 }
        if (existing.isEmpty()) return Result(false, "Segment audio yo'q")
        if (existing.size == 1) {
            return runArgs(
                arrayOf(
                    "-y", "-i", existing[0].absolutePath,
                    "-c:a", "aac", "-b:a", "192k",
                    output.absolutePath
                )
            )
        }

        val listFile = File(output.parentFile, "concat_list.txt")
        listFile.writeText(
            existing.joinToString("\n") { f ->
                "file '${f.absolutePath.replace("'", "'\\''")}'"
            }
        )
        val result = runArgs(
            arrayOf(
                "-y", "-f", "concat", "-safe", "0",
                "-i", listFile.absolutePath,
                "-c:a", "aac", "-b:a", "192k",
                output.absolutePath
            )
        )
        listFile.delete()
        return result
    }

    /**
     * Timestamp + adelay orqali sinxron narration (addon create_synchronized_wav o'rniga).
     */
    fun buildTimedNarration(
        segments: List<TimedSegment>,
        segmentFiles: List<File>,
        output: File
    ): Result {
        val pairs = segmentFiles.mapIndexedNotNull { i, file ->
            if (file.exists() && file.length() > 0) {
                val delayMs = ((segments.getOrNull(i)?.seconds ?: 0.0) * 1000.0)
                    .toLong().coerceAtLeast(0)
                file to delayMs
            } else null
        }
        if (pairs.isEmpty()) return Result(false, "Segment audio yo'q")
        if (pairs.size == 1 && pairs[0].second <= 50L) {
            return concatAudioSimple(listOf(pairs[0].first), output)
        }

        val args = mutableListOf("-y")
        pairs.forEach { (f, _) ->
            args.add("-i")
            args.add(f.absolutePath)
        }

        val filters = mutableListOf<String>()
        pairs.forEachIndexed { idx, (_, delayMs) ->
            filters.add("[$idx:a]adelay=${delayMs}|${delayMs}[a$idx]")
        }
        val mixIn = pairs.indices.joinToString("") { "[a$it]" }
        filters.add("${mixIn}amix=inputs=${pairs.size}:duration=longest:normalize=0[aout]")

        args.add("-filter_complex")
        args.add(filters.joinToString(";"))
        args.add("-map")
        args.add("[aout]")
        args.add("-c:a")
        args.add("aac")
        args.add("-b:a")
        args.add("192k")
        args.add(output.absolutePath)

        return runArgs(args.toTypedArray())
    }

    /**
     * Addon: mix_audio_description_into_video
     * filter: [0:a]volume=0.5[a0];[1:a]volume=2.2[a1];[a0][a1]amix=...
     */
    fun mergeVideoWithNarration(
        videoFile: File,
        narrationFile: File,
        outputFile: File,
        originalVolume: Double = 0.5,
        narrationVolume: Double = 2.2
    ): Result {
        if (!videoFile.exists()) return Result(false, "Video topilmadi: ${videoFile.absolutePath}")
        if (!narrationFile.exists() || narrationFile.length() == 0L) {
            return Result(false, "Narration yo'q")
        }

        // Asosiy: addon bilan bir xil filter (original video ovozi + sharh mikslanadi)
        val mixFilter =
            "[0:a]volume=${originalVolume}[a0];" +
            "[1:a]volume=${narrationVolume}[a1];" +
            "[a0][a1]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[aout]"

        // 1) Tezkor: video kodeksni o'zgartirmasdan (copy), ovozlarni mikslab
        val main = runArgs(
            arrayOf(
                "-y",
                "-i", videoFile.absolutePath,
                "-i", narrationFile.absolutePath,
                "-filter_complex", mixFilter,
                "-map", "0:v",
                "-map", "[aout]",
                "-c:v", "copy",
                "-c:a", "aac",
                "-b:a", "192k",
                outputFile.absolutePath
            )
        )
        if (main.ok && outputFile.exists() && outputFile.length() > 0L) return main

        // 2) Videoda audio yo'q bo'lishi mumkin — faqat sharh ovozini qo'yib, videoni copy qilib ko'ramiz
        val fbNoMix = runArgs(
            arrayOf(
                "-y",
                "-i", videoFile.absolutePath,
                "-i", narrationFile.absolutePath,
                "-map", "0:v",
                "-map", "1:a",
                "-c:v", "copy",
                "-c:a", "aac",
                "-b:a", "192k",
                outputFile.absolutePath
            )
        )
        if (fbNoMix.ok && outputFile.exists() && outputFile.length() > 0L) return fbNoMix

        // 3) "-c:v copy" ishlamadi — video kodeksi mp4 konteyneriga mos emas yoki
        // o'lchamlari (width/height) juft son emas (H.264 talabi). Qayta kodlab,
        // o'lchamlarni juftlab, pixel formatni standartlashtirib, ovozlarni mikslaymiz.
        // Diqqat: juda baland rezolyutsiya (4K va h.k.) telefonda dasturiy qayta kodlashda
        // OutOfMemoryError'ga olib kelishi mumkin — shuning uchun kenglik 1280px bilan cheklanadi.
        val scaleAndFormat =
            "[0:v]scale='min(1280,iw)':-2,scale=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p[vout]"
        val reencMix = runArgs(
            arrayOf(
                "-y",
                "-i", videoFile.absolutePath,
                "-i", narrationFile.absolutePath,
                "-filter_complex", "$scaleAndFormat;$mixFilter",
                "-map", "[vout]",
                "-map", "[aout]",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "23",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-b:a", "192k",
                outputFile.absolutePath
            )
        )
        if (reencMix.ok && outputFile.exists() && outputFile.length() > 0L) return reencMix

        // 4) Oxirgi urinish: qayta kodlash + faqat sharh ovozi (original video audiosiz
        // yoki umuman mos kelmasa ham ishlaydi — bu deyarli har doim muvaffaqiyatli bo'ladi)
        val reencNoMix = runArgs(
            arrayOf(
                "-y",
                "-i", videoFile.absolutePath,
                "-i", narrationFile.absolutePath,
                "-filter_complex", scaleAndFormat,
                "-map", "[vout]",
                "-map", "1:a",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "23",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-b:a", "192k",
                outputFile.absolutePath
            )
        )
        return if (reencNoMix.ok && outputFile.exists() && outputFile.length() > 0L) reencNoMix else Result(
            false,
            "1)copy+mix=${main.log}\n2)copy+narration=${fbNoMix.log}\n" +
                "3)reencode+mix=${reencMix.log}\n4)reencode+narration=${reencNoMix.log}"
        )
    }

    private fun runArgs(args: Array<String>): Result {
        return try {
            val session = FFmpegKit.executeWithArguments(args)
            val code = session.returnCode
            val logs = (session.allLogsAsString ?: "") + "\n" + (session.failStackTrace ?: "")
            val ok = ReturnCode.isSuccess(code)
            Result(
                ok,
                if (ok) "OK"
                else "rc=$code cmd=${args.joinToString(" ").take(200)}\n${logs.takeLast(1200)}"
            )
        } catch (e: OutOfMemoryError) {
            // Video juda katta/rezolyutsiyasi yuqori — qayta kodlash xotirani yetarlicha
            // ololmadi. Bu Exception emas Error, shuning uchun alohida ushlanadi —
            // aks holda butun ilova yiqiladi.
            System.gc()
            Result(false, "OutOfMemoryError: video juda katta/yuqori rezolyutsiyali (${e.message})")
        } catch (e: Throwable) {
            // FFmpegKit native tomondan kutilmagan xato (Exception yoki Error) — ilovani
            // yiqitmasdan, xatoni qaytarib, keyingi fallback bosqichiga o'tkazamiz.
            Result(false, "Xato: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
