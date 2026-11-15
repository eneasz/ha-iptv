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
    /** Shared between all sticks (through Home Assistant): favourites, list order and the last live channel. */
    val sync = SyncStore(ctx)

    init { seedSync() }
    /** A Home Assistant was approved here already (or a token was set by hand before this version). Closed to new pairing requests until opened in Settings. */
    var paired: Boolean
        get() = sp.getBoolean("paired", false)
        set(v) = sp.edit().putBoolean("paired", v).apply()
    init { if (!sp.contains("paired") && sp.contains("token")) paired = true }
    @Volatile var pairOpenUntil = 0L
    fun pairingOpen() = !paired || System.currentTimeMillis() < pairOpenUntil
    /** First run with sync: what this stick already had becomes the first synced state (empty values are never stamped). */
    private fun seedSync() {
        if (sp.getBoolean("seeded", false)) return
        val now = System.currentTimeMillis() - 1000L * 3600      // an hour back, so a deliberate edit elsewhere wins
        fun arr(k: String) = try { JSONArray(sp.getString(k, "[]") ?: "[]").let { a -> List(a.length()) { a.getString(it) } } } catch (e: Exception) { emptyList() }
        arr("favs").forEachIndexed { i, id -> sync.put("fav:$id", "1", now + i, quiet = true) }
        arr("order").takeIf { it.isNotEmpty() }?.let { sync.put("order:groups", JSONArray(it).toString(), now, quiet = true) }
        arr("rorder").takeIf { it.isNotEmpty() }?.let { sync.put("order:regions", JSONArray(it).toString(), now, quiet = true) }
        val c = sp.getString("lastChannel", "") ?: ""; val g = sp.getString("lastGroup", "") ?: ""
        if (c.isNotEmpty()) sync.put("last:live", "$c|$g", now, quiet = true)
        sp.edit().putBoolean("seeded", true).apply()
    }
    private fun list(key: String): List<String> = sync.get(key)?.let { s -> try { JSONArray(s).let { a -> List(a.length()) { a.getString(it) } } } catch (e: Exception) { null } } ?: emptyList()

    var lastGroup: String
        get() = sync.get("last:live")?.substringAfter('|', "") ?: ""
        set(v) { sync.put("last:live", "$lastChannel|$v") }
    var lastChannel: String
        get() = sync.get("last:live")?.substringBefore('|') ?: ""
        set(v) { sync.put("last:live", "$v|$lastGroup") }
    /** "m3u8" or "ts" – whichever last worked. */
    var format: String
        get() = sp.getString("format", "m3u8") ?: "m3u8"
        set(v) = sp.edit().putString("format", v).apply()

    var groupOrder: List<String>
        get() = list("order:groups")
        set(v) { sync.put("order:groups", JSONArray(v).toString()) }

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
        get() = list("order:regions")
        set(v) { sync.put("order:regions", JSONArray(v).toString()) }

    /** Favourite channel ids, in the order they were added. Each channel is its own synced entry, so adds on two sticks both survive. */
    private var favCache: List<String>? = null
    fun favsChanged() { favCache = null }
    var favs: List<String>
        get() = favCache ?: sync.entries("fav:").sortedBy { it.second.t }.map { it.first.removePrefix("fav:") }.also { favCache = it }
        set(v) {
            val old = favs.toSet(); val new = v.toSet(); val now = System.currentTimeMillis()
            (old - new).forEach { sync.put("fav:$it", null, now) }
            v.filter { it !in old }.forEachIndexed { i, id -> sync.put("fav:$id", "1", now + i) }
            favCache = null
        }

    val configured get() = server.isNotEmpty() && user.isNotEmpty() && pass.isNotEmpty()
}
