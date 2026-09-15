package com.zikriyo.geminisharh.util

data class TimedSegment(
    val seconds: Double,
    val timestampRaw: String,
    val text: String
)

object TimestampParser {

    private val lineRegex = Regex(
        """^\s*[\[\(]?(\d{1,2}:\d{2}(?::\d{2})?(?:\.\d+)?)[\]\)]?\s*[-–—:]?\s*(.+)$""",
        RegexOption.MULTILINE
    )

    fun parse(text: String): List<TimedSegment> {
        val result = mutableListOf<TimedSegment>()
        for (line in text.lines()) {
            val m = lineRegex.find(line.trim()) ?: continue
            val ts = m.groupValues[1]
            val desc = m.groupValues[2].trim()
            if (desc.isEmpty()) continue
            result.add(TimedSegment(parseSeconds(ts), ts, desc))
        }
        return result.sortedBy { it.seconds }
    }

    fun parseSeconds(ts: String): Double {
        val clean = ts.trim().trim('[', ']', '(', ')')
        val parts = clean.split(":")
        return try {
            when (parts.size) {
                3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toDouble()
                2 -> parts[0].toInt() * 60 + parts[1].toDouble()
                1 -> parts[0].toDouble()
                else -> 0.0
            }
        } catch (_: Exception) {
            0.0
        }
    }

    fun formatSrtTime(seconds: Double): String {
        val totalMs = (seconds * 1000).toLong().coerceAtLeast(0)
        val h = totalMs / 3_600_000
        val m = (totalMs % 3_600_000) / 60_000
        val s = (totalMs % 60_000) / 1000
        val ms = totalMs % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms)
    }

    fun createSrt(segments: List<TimedSegment>): String {
        val sb = StringBuilder()
        segments.forEachIndexed { i, seg ->
            val start = formatSrtTime(seg.seconds)
            val endSec = if (i + 1 < segments.size) segments[i + 1].seconds else seg.seconds + 4.0
            val end = formatSrtTime(endSec)
            sb.append("${i + 1}\n")
            sb.append("$start --> $end\n")
            sb.append("${seg.text}\n\n")
        }
        return sb.toString()
    }

    fun createTxt(segments: List<TimedSegment>): String {
        val sb = StringBuilder()
        sb.append("GEMINI SHARH - VIDEO TIFLOSHARHI\n")
        sb.append("=".repeat(50)).append("\n\n")
        segments.forEach { seg ->
            sb.append("[${seg.timestampRaw}] ${seg.text}\n")
        }
        return sb.toString()
    }
}
