package pl.home.channeldeck

import android.graphics.Color

/** Technical facts read from the playing stream. fps is declared or measured (0 = unknown). */
data class Tech(val w: Int, val h: Int, val fps: Int, val vcodec: String, val acodec: String, val ach: Int, val maxH: Int)

/** Compares what a channel NAME claims with what the stream really delivers. */
object Quality {
    const val GREEN = 0xFF4CD964.toInt(); const val RED = 0xFFFF5A4F.toInt(); const val GREY = 0xFFB0B7C0.toInt()

    /** 4 = 4K/UHD, 3 = FHD, 2 = HD, 1 = SD, 0 = name makes no claim. */
    fun claimed(name: String): Int {
        val n = name.uppercase()
        return when {
            Regex("""\b(4K|UHD|2160P?)\b""").containsMatchIn(n) -> 4
            Regex("""\b(FHD|1080[PI]?)\b""").containsMatchIn(n) -> 3
            Regex("""\b(HD|720P?)\b""").containsMatchIn(n) -> 2
            Regex("""\bSD\b""").containsMatchIn(n) -> 1
            else -> 0
        }
    }
    fun claimedFps(name: String): Int = Regex("""\b(\d{2})\s?FPS\b""", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    fun actual(w: Int, h: Int): Int = when { w >= 3500 || h >= 2000 -> 4; w >= 1800 || h >= 1000 -> 3; w >= 1200 || h >= 680 -> 2; else -> 1 }
    fun label(rank: Int) = when (rank) { 4 -> "4K UHD"; 3 -> "FHD 1080p"; 2 -> "HD 720p"; 1 -> "SD"; else -> "?" }
    fun short(rank: Int) = when (rank) { 4 -> "4K"; 3 -> "FHD"; 2 -> "HD"; else -> "SD" }
    private fun claimLabel(r: Int) = when (r) { 4 -> "4K"; 3 -> "FHD"; 2 -> "HD"; else -> "SD" }

    data class Verdict(val text: String, val color: Int)

    fun verdict(name: String, t: Tech?): Verdict? {
        if (t == null) return null
        val c = claimed(name); val a = actual(t.w, t.h)
        val parts = ArrayList<String>(); var bad = false
        if (c > a) { bad = true; parts.add("⚠ name says ${claimLabel(c)}, stream is ${label(a)}") }
        else if (c > 0) parts.add("✓ matches \"${claimLabel(c)}\"")
        val cf = claimedFps(name)
        if (cf >= 50 && t.fps in 1 until cf - 5) { bad = true; parts.add("⚠ name says ${cf}fps, stream is ${t.fps}fps") }
        if (parts.isEmpty()) return Verdict("", Color.WHITE)
        return Verdict(parts.joinToString("   "), if (bad) RED else GREEN)
    }

    /** Small tag for channel rows from remembered measurements. */
    fun rowTag(name: String, m: IntArray?): Pair<String, Int>? {
        if (m == null) return null
        val a = actual(m[0], m[1]); val c = claimed(name)
        return if (c > a) Pair("⚠${short(a)}", RED) else Pair(short(a), if (a >= 3) GREEN else if (a == 2) Neon.CYAN else Neon.MUTED)
    }

    fun vcodec(mime: String?, codecs: String?): String = when {
        mime == null -> "?"
        mime.endsWith("avc") -> "H.264"; mime.endsWith("hevc") -> "H.265"; mime.endsWith("av01") -> "AV1"
        mime.contains("mpeg2") -> "MPEG-2"; mime.endsWith("mp4v-es") -> "MPEG-4"; else -> mime.substringAfter('/')
    }
    fun acodec(mime: String?): String = when {
        mime == null -> ""
        mime.endsWith("mp4a-latm") -> "AAC"; mime.endsWith("eac3") -> "E-AC-3"; mime.endsWith("ac3") -> "AC-3"
        mime.endsWith("mpeg-L2") -> "MP2"; mime.contains("mpeg") -> "MP3"; else -> mime.substringAfter('/')
    }
}
