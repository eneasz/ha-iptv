package pl.home.channeldeck

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Last-write-wins key/value store that every stick shares through Home Assistant.
 *
 * Every entry is `key -> (value, t)`; `t` is the wall-clock time of the edit in ms. A write from elsewhere only wins when it is
 * newer (ties broken by value so all devices converge). An empty value is a tombstone (the thing was removed).
 * Each local change also gets a per-device sequence number: Home Assistant pulls "everything after seq N", so clock differences
 * between sticks can never hide an edit from it.
 *
 * Keys:  fav:<channel>  order:groups  order:regions  last:live  vod:<id>  ser:<episode>  serlast:<series>  serwatched:<episode>  np:<device>
 * Provider credentials are never stored here.
 */
class SyncStore(ctx: Context) {
    data class Entry(val value: String, val t: Long, val seq: Long)

    private val sp = ctx.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val map = LinkedHashMap<String, Entry>()
    private val main = Handler(Looper.getMainLooper())
    private var seq = 0L

    val deviceId: String = sp.getString("device", null) ?: rnd(4).also { sp.edit().putString("device", it).apply() }
    /** Changes if the app's data is wiped, so Home Assistant knows to start over with this device. */
    val epoch: String = sp.getString("epoch", null) ?: rnd(4).also { sp.edit().putString("epoch", it).apply() }
    var deviceName: String
        get() = sp.getString("name", null) ?: "Fire TV ${deviceId.take(4)}"
        set(v) { sp.edit().putString("name", v.trim().take(40)).apply() }
    /** Stop playing here when another stick starts (the provider account normally allows one stream). */
    var yieldToOthers: Boolean
        get() = sp.getBoolean("yield", true)
        set(v) { sp.edit().putBoolean("yield", v).apply() }

    /** Local edit that should wake Home Assistant now. */
    var onChange: (() -> Unit)? = null
    /** Keys changed by a merge from another device (called on the main thread). */
    var onRemote: ((List<String>) -> Unit)? = null

    init {
        try {
            val o = JSONObject(sp.getString("data", "{}") ?: "{}")
            val cut = System.currentTimeMillis() - 90L * 86400_000
            o.keys().forEach { k ->
                val a = o.getJSONArray(k); val e = Entry(a.getString(0), a.getLong(1), a.getLong(2))
                if (e.value.isEmpty() && e.t < cut) return@forEach          // old tombstones are forgotten
                map[k] = e; if (e.seq > seq) seq = e.seq
            }
        } catch (e: Exception) { }
    }

    @Synchronized fun get(key: String): String? = map[key]?.value?.takeIf { it.isNotEmpty() }
    @Synchronized fun time(key: String): Long = map[key]?.t ?: 0L
    @Synchronized fun keys(prefix: String): List<String> = map.entries.filter { it.key.startsWith(prefix) && it.value.value.isNotEmpty() }.map { it.key }
    @Synchronized fun entries(prefix: String): List<Pair<String, Entry>> = map.entries.filter { it.key.startsWith(prefix) && it.value.value.isNotEmpty() }.map { it.key to it.value }
    @Synchronized fun currentSeq() = seq

    /**
     * Record a local edit. `quiet` edits (playback position) are stored at once but only nudge Home Assistant every few seconds.
     * Returns false when nothing changed.
     */
    fun put(key: String, value: String?, t: Long = System.currentTimeMillis(), quiet: Boolean = false): Boolean {
        val v = value ?: ""
        synchronized(this) {
            val old = map[key]
            if (old != null && old.value == v) return false
            if (old == null && v.isEmpty()) return false
            map[key] = Entry(v, maxOf(t, (old?.t ?: 0L) + if (old != null && t <= old.t) 1 else 0), ++seq)
            save()
        }
        if (quiet) nudgeLater() else { nudgeNow() }
        return true
    }

    /** Apply entries from another device. Returns the keys that actually changed. */
    fun merge(incoming: JSONObject, skipKey: String? = null): List<String> {
        val changed = ArrayList<String>()
        val horizon = System.currentTimeMillis() + 120_000        // a clock running ahead must not win forever
        synchronized(this) {
            incoming.keys().forEach { k ->
                if (k == skipKey) return@forEach
                val a = incoming.optJSONArray(k) ?: return@forEach
                val v = a.optString(0, ""); val t = minOf(a.optLong(1, 0L), horizon)
                val old = map[k]
                val newer = old == null || t > old.t || (t == old.t && v > old.value)
                if (newer && !(old == null && v.isEmpty())) { map[k] = Entry(v, t, ++seq); changed += k }
            }
            if (changed.isNotEmpty()) save()
        }
        if (changed.isNotEmpty()) main.post { onRemote?.invoke(changed) }
        return changed
    }

    /** Everything that changed after `since` (a sequence number from an earlier call; same epoch). */
    @Synchronized fun delta(since: Long): JSONObject {
        val o = JSONObject()
        map.forEach { (k, e) -> if (e.seq > since) o.put(k, JSONArray().put(e.value).put(e.t)) }
        return o
    }

    private var pending = false
    private fun nudgeLater() { synchronized(this) { if (pending) return; pending = true }; main.postDelayed({ synchronized(this) { pending = false }; onChange?.invoke() }, 10_000) }
    private fun nudgeNow() { main.post { onChange?.invoke() } }

    private fun save() {
        if (map.size > 4000) map.entries.filter { it.value.value.isEmpty() }.sortedBy { it.value.t }.take(map.size - 3500).forEach { map.remove(it.key) }
        val o = JSONObject(); map.forEach { (k, e) -> o.put(k, JSONArray().put(e.value).put(e.t).put(e.seq)) }
        sp.edit().putString("data", o.toString()).apply()
    }

    private fun rnd(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}
