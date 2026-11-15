package pl.home.channeldeck

import android.content.Context
import org.json.JSONArray

/** Settings + remembered state. Credentials never leave the device except to the provider. */
class Prefs(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("deck", Context.MODE_PRIVATE)

    var server: String
        get() = sp.getString("server", "") ?: ""
        set(v) = sp.edit().putString("server", v.trimEnd('/')).apply()
    var user: String
        get() = sp.getString("user", "") ?: ""
        set(v) = sp.edit().putString("user", v).apply()
    var pass: String
        get() = sp.getString("pass", "") ?: ""
        set(v) = sp.edit().putString("pass", v).apply()
    var lastGroup: String
        get() = sp.getString("lastGroup", "") ?: ""
        set(v) = sp.edit().putString("lastGroup", v).apply()
    var lastChannel: String
        get() = sp.getString("lastChannel", "") ?: ""
        set(v) = sp.edit().putString("lastChannel", v).apply()
    /** "m3u8" or "ts" – whichever last worked. */
    var format: String
        get() = sp.getString("format", "m3u8") ?: "m3u8"
        set(v) = sp.edit().putString("format", v).apply()

    var groupOrder: List<String>
        get() = (sp.getString("order", "[]") ?: "[]").let { s ->
            val a = JSONArray(s); List(a.length()) { a.getString(it) }
        }
        set(v) = sp.edit().putString("order", JSONArray(v).toString()).apply()

    /** What we actually measured per channel: id -> "w,h,fps". Builds up from what you watch. */
    private val q: MutableMap<String, String> by lazy {
        val m = HashMap<String, String>()
        try { val o = org.json.JSONObject(sp.getString("quality", "{}") ?: "{}"); o.keys().forEach { m[it] = o.getString(it) } } catch (e: Exception) {}
        m
    }
    fun measured(id: String): IntArray? = q[id]?.split(",")?.let { if (it.size == 3) intArrayOf(it[0].toInt(), it[1].toInt(), it[2].toInt()) else null }
    fun setMeasured(id: String, w: Int, h: Int, fps: Int) {
        val v = "$w,$h,$fps"; if (q[id] == v) return
        q[id] = v; sp.edit().putString("quality", org.json.JSONObject(q as Map<*, *>).toString()).apply()
    }

    /** Last known picture state per channel: id -> "ok|black|error" + "|" + epoch seconds. Filled by watching and by surveys. */
    private val pic: MutableMap<String, String> by lazy {
        val m = HashMap<String, String>()
        try { val o = org.json.JSONObject(sp.getString("pic", "{}") ?: "{}"); o.keys().forEach { m[it] = o.getString(it) } } catch (e: Exception) {}
        m
    }
    fun picture(id: String): Pair<String, Long>? = pic[id]?.split("|")?.let { if (it.size == 2) Pair(it[0], it[1].toLongOrNull() ?: 0L) else null }
    fun setPicture(id: String, state: String) {
        val now = System.currentTimeMillis() / 1000
        picture(id)?.let { if (it.first == state && now - it.second < 600) return }
        pic[id] = "$state|$now"
        if (pic.size > 4000) { val cut = now - 7 * 86400; pic.entries.removeAll { (it.value.substringAfter('|').toLongOrNull() ?: 0L) < cut } }
        sp.edit().putString("pic", org.json.JSONObject(pic as Map<*, *>).toString()).apply()
    }
    /** Channels verified to show a picture within maxAge seconds, newest check first. */
    fun liveIds(maxAge: Long = 3 * 3600): List<String> {
        val now = System.currentTimeMillis() / 1000
        return pic.entries.mapNotNull { e -> val p = e.value.split("|"); if (p.size == 2 && p[0] == "ok" && now - (p[1].toLongOrNull() ?: 0L) < maxAge) Pair(e.key, p[1].toLong()) else null }
            .sortedByDescending { it.second }.map { it.first }
    }

    /** Bearer token the HA integration must present to the local API. */
    var token: String
        get() = sp.getString("token", null) ?: java.security.SecureRandom().let { r -> ByteArray(12).also { r.nextBytes(it) }.joinToString("") { "%02x".format(it) } }.also { sp.edit().putString("token", it).apply() }
        set(v) = sp.edit().putString("token", v).apply()

    var regionOrder: List<String>
        get() = (sp.getString("rorder", "[]") ?: "[]").let { s -> val a = JSONArray(s); List(a.length()) { a.getString(it) } }
        set(v) = sp.edit().putString("rorder", JSONArray(v).toString()).apply()

    /** Favourite channel ids, in the order they were added. */
    var favs: List<String>
        get() = (sp.getString("favs", "[]") ?: "[]").let { s -> val a = JSONArray(s); List(a.length()) { a.getString(it) } }
        set(v) = sp.edit().putString("favs", JSONArray(v).toString()).apply()

    val configured get() = server.isNotEmpty() && user.isNotEmpty() && pass.isNotEmpty()
}
