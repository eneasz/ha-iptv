package pl.home.channeldeck

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Xtream Codes live-TV client with an on-disk cache so start-up is instant. */
class Repo(private val prefs: Prefs, private val cacheDir: File) {
    private var baseGroups: List<Group> = emptyList()
    /** Favourites (pseudo-group "__fav") is always first when it has channels. */
    val groups: List<Group> get() {
        val f = favChannels()
        val head = ArrayList<Group>()
        if (f.isNotEmpty()) head += Group(FAV, "★ Favourites", f.size, FAV)
        liveChannels().size.let { if (it > 0) head += Group(LIVE, "Live now", it, LIVE) }
        if (searchRes.isNotEmpty()) head += Group(SEARCH, "“$searchQ”", searchRes.size, SEARCH)
        return head + baseGroups
    }
    companion object { const val FAV = "__fav"; const val SEARCH = "__search"; const val LIVE = "__live"; fun pseudo(id: String) = id == FAV || id == SEARCH || id == LIVE }
    var searchQ = ""; var searchRes: List<Channel> = emptyList()
    fun runSearch(q: String) { searchQ = q.trim(); searchRes = if (searchQ.isEmpty()) emptyList() else search(searchQ, 400) }
    private fun liveChannels(): List<Channel> = prefs.liveIds().mapNotNull { byId[it] }
    private fun favChannels(): List<Channel> = prefs.favs.mapNotNull { byId[it] }
    fun isFav(id: String) = prefs.favs.contains(id)
    fun setFav(id: String, on: Boolean?) {          // null = toggle
        val l = prefs.favs.toMutableList(); val has = l.contains(id)
        val want = on ?: !has
        if (want && !has) l.add(id) else if (!want && has) l.remove(id)
        prefs.favs = l
    }
    fun inGroup(gid: String, id: String) = channels(gid).any { it.id == id }
    private var channelsByGroup: Map<String, List<Channel>> = emptyMap()
    private var byId: Map<String, Channel> = emptyMap()

    fun channels(groupId: String): List<Channel> = if (groupId == FAV) favChannels() else if (groupId == SEARCH) searchRes else if (groupId == LIVE) liveChannels() else channelsByGroup[groupId] ?: emptyList()
    fun channel(id: String): Channel? = byId[id]

    fun streamUrl(c: Channel, fmt: String = prefs.format) =
        "${prefs.server}/live/${enc(prefs.user)}/${enc(prefs.pass)}/${c.id}.$fmt"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun api(action: String) =
        "${prefs.server}/player_api.php?username=${enc(prefs.user)}&password=${enc(prefs.pass)}&action=$action"

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "ChannelDeck/0.1")
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    private val catFile get() = File(cacheDir, "cats.json")
    private val strFile get() = File(cacheDir, "streams.json")

    /** Load from cache (fast). Returns false if there is no cache yet. */
    suspend fun loadCache(): Boolean = withContext(Dispatchers.IO) {
        if (!catFile.exists() || !strFile.exists()) return@withContext false
        try { build(catFile.readText(), strFile.readText()); true } catch (e: Exception) { false }
    }

    /** Fetch from the provider, refresh the cache, rebuild. Throws on failure. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val cats = get(api("get_live_categories"))
        val streams = get(api("get_live_streams"))
        if (!cats.trimStart().startsWith("[") || !streams.trimStart().startsWith("["))
            throw RuntimeException("Provider did not return a channel list (wrong URL or login?)")
        catFile.writeText(cats); strFile.writeText(streams)
        build(cats, streams)
    }

    private fun build(catsJson: String, streamsJson: String) {
        val cats = JSONArray(catsJson)
        val streams = JSONArray(streamsJson)
        val map = LinkedHashMap<String, MutableList<Channel>>()
        val names = LinkedHashMap<String, String>()
        for (i in 0 until cats.length()) {
            val o = cats.getJSONObject(i)
            names[o.getString("category_id")] = o.optString("category_name", "?")
            map[o.getString("category_id")] = ArrayList()
        }
        val ids = HashMap<String, Channel>()
        for (i in 0 until streams.length()) {
            val o = streams.getJSONObject(i)
            val gid = o.optString("category_id", "")
            val list = map.getOrPut(gid) { names.putIfAbsent(gid, "Other"); ArrayList() }
            fun str(k: String) = if (o.isNull(k)) "" else o.optString(k, "")
            val ch = Channel(o.get("stream_id").toString(), str("name").ifEmpty { "?" }, gid, o.optInt("num", i), str("stream_icon"), str("epg_channel_id"))
            list.add(ch); ids[ch.id] = ch
        }
        // user order first, anything new from the provider appended in provider order
        val saved = prefs.groupOrder
        val ordered = (saved.filter { map.containsKey(it) } + map.keys.filter { it !in saved })
        baseGroups = ordered.filter { (map[it]?.size ?: 0) > 0 }
            .map { Group(it, names[it] ?: "Other", map[it]!!.size, regionOf(names[it] ?: "Other")) }
        channelsByGroup = map
        byId = ids; normCache = null
    }

    // ------------------------------------------------------------ EPG
    private var serverOffset: Long? = null           // server wall-clock minus UTC, seconds
    private val epgCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Prog>>>()

    /** The provider encodes EPG times on its local clock (as if it were UTC). Learn the offset once. */
    private fun ensureOffset() {
        if (serverOffset != null) return
        try {
            val si = JSONObject(get("${prefs.server}/player_api.php?username=${enc(prefs.user)}&password=${enc(prefs.pass)}")).getJSONObject("server_info")
            val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            val local = f.parse(si.getString("time_now"))!!.time / 1000
            val utc = si.getLong("timestamp_now")
            serverOffset = Math.round((local - utc) / 900.0) * 900     // snap to quarter hours
        } catch (e: Exception) { serverOffset = 0 }
    }

    /** null = this channel has no guide id at all; empty list = guide id but no data right now. */
    suspend fun epg(c: Channel, limit: Int = 5): List<Prog>? {
        if (c.epgId.isEmpty()) return null
        val now = System.currentTimeMillis() / 1000
        epgCache["${c.id}:$limit"]?.let { (at, l) -> if (now - at < 300 && (l.isEmpty() || l.first().stop > now)) return l }
        return withContext(Dispatchers.IO) {
            ensureOffset(); val off = serverOffset ?: 0
            val arr = try { JSONObject(get(api("get_short_epg") + "&stream_id=${c.id}&limit=$limit")).optJSONArray("epg_listings") } catch (e: Exception) { null }
            val out = ArrayList<Prog>()
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                fun b64(k: String): String { val v = o.optString(k, ""); return try { String(Base64.decode(v, Base64.DEFAULT), Charsets.UTF_8) } catch (e: Exception) { v } }
                val st = o.optString("start_timestamp", "0").toLongOrNull() ?: continue
                val sp = o.optString("stop_timestamp", "0").toLongOrNull() ?: continue
                out.add(Prog(b64("title"), b64("description"), st - off, sp - off))
            }
            out.sortBy { it.start }
            epgCache["${c.id}:$limit"] = now to out
            out
        }
    }

    // ------------------------------------------------------------ regions (prefix before "|")
    fun regionOf(name: String) = if (name.contains('|')) name.substringBefore('|').trim().uppercase().ifEmpty { "OTHER" } else "OTHER"
    fun shortName(g: Group) = if (g.name.contains('|')) g.name.substringAfter('|').trim().ifEmpty { g.name } else g.name

    val regions: List<Region> get() {
        val m = LinkedHashMap<String, IntArray>()
        baseGroups.forEach { val a = m.getOrPut(it.region) { IntArray(2) }; a[0]++; a[1] += it.count }
        val saved = prefs.regionOrder
        val list = (saved.filter { m.containsKey(it) } + m.keys.filter { it !in saved }).map { Region(it, it, m[it]!![0], m[it]!![1]) }
        val f = favChannels()
        val head = ArrayList<Region>()
        if (f.isNotEmpty()) head += Region(FAV, "★ Favourites", 1, f.size)
        liveChannels().size.let { if (it > 0) head += Region(LIVE, "● Live", 1, it) }
        head += Region(SEARCH, "🔍 Find", if (searchRes.isEmpty()) 0 else 1, searchRes.size)
        return head + list
    }
    fun groupsIn(region: String): List<Group> = if (pseudo(region)) groups.filter { it.id == region } else baseGroups.filter { it.region == region }
    fun regionOfGroup(gid: String) = groups.firstOrNull { it.id == gid }?.region ?: ""
    fun resolveRegion(name: String): Region? {
        val n = name.trim().uppercase(); val all = regions
        return all.firstOrNull { it.id == n || it.name.uppercase() == n } ?: all.firstOrNull { it.id.startsWith(n) }
    }
    /** Reordering groups inside a region keeps the rest of the global order intact. */
    fun saveRegionGroups(region: String, newOrder: List<Group>) {
        val g = baseGroups.toMutableList(); val slots = g.indices.filter { g[it].region == region }
        if (slots.size != newOrder.size) return
        newOrder.forEachIndexed { i, x -> g[slots[i]] = x }
        prefs.groupOrder = g.map { it.id }; baseGroups = g
    }
    fun saveRegionOrder(order: List<Region>) { prefs.regionOrder = order.filter { !pseudo(it.id) }.map { it.id } }

    // ------------------------------------------------------------ search
    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace('ł', 'l').replace(Regex("[^a-z0-9]+"), " ").trim()
    @Volatile private var normCache: Map<String, String>? = null
    private val normNames: Map<String, String> get() = normCache ?: synchronized(this) { normCache ?: byId.mapValues { norm(it.value.name) }.also { normCache = it } }
    /** Build the search index off the UI thread (36k names are slow to normalise on a Fire stick). */
    fun warm() { normNames }

    /** Channels whose name contains every word of the query; exact > prefix > contains; optional group-name hint. */
    fun search(q: String, limit: Int = 20, groupHint: String? = null): List<Channel> {
        val nq = norm(q); if (nq.isEmpty()) return emptyList()
        val toks = nq.split(' ')
        val gh = groupHint?.let { norm(it) }?.takeIf { it.isNotEmpty() }
        val gnames = baseGroups.associate { it.id to norm(it.name) }
        return byId.values.asSequence().mapNotNull { c ->
            val n = normNames[c.id] ?: return@mapNotNull null
            if (!toks.all { n.contains(it) }) return@mapNotNull null
            if (gh != null && gnames[c.groupId]?.contains(gh) != true) return@mapNotNull null
            val score = (if (n == nq) 0 else if (n.startsWith(nq)) 1 else 2) * 100000 + c.num
            c to score
        }.sortedBy { it.second }.take(limit).map { it.first }.toList()
    }
    fun resolveGroup(idOrName: String): Group? {
        groups.firstOrNull { it.id == idOrName }?.let { return it }
        val n = norm(idOrName)
        return groups.firstOrNull { norm(it.name) == n } ?: groups.firstOrNull { norm(it.name).contains(n) }
    }
}
