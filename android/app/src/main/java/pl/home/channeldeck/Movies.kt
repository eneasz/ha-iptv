package pl.home.channeldeck

import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.JsonReader
import android.util.JsonToken
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ====================================================================== data

data class Movie(val id: String, val title: String, val year: String, val cat: String, val poster: String,
                 val rating: Float, val ext: String, val added: Long, val is4k: Boolean, val ep: Boolean = false)   // ep: an episode; its `cat` then holds the series id
data class VCat(val id: String, val label: String, val count: Int)
data class VodInfo(val plot: String, val genre: String, val duration: String, val cast: String, val director: String)

/** Lower-case, accent-free, letters and digits only – for searching titles. */
fun normText(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").replace('ł', 'l').replace(Regex("[^a-z0-9]+"), " ").trim()

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0); val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
}

/** Xtream Codes VOD (movies) client: streamed JSON parse (the list is ~12 MB), on-disk cache, per-movie resume positions. */
open class VodRepo(ctx: Context, private val prefs: Prefs, protected val series: Boolean = false) {
    private val dir = ctx.filesDir
    private val pfx = if (series) "series" else "vod"
    val label get() = if (series) "Series" else "Movies"
    val unit get() = if (series) "series" else "films"
    protected val sp = ctx.getSharedPreferences(pfx, Context.MODE_PRIVATE)
    var cats: List<VCat> = emptyList(); private set
    private var byCat: Map<String, List<Movie>> = emptyMap()
    protected var byId: Map<String, Movie> = emptyMap()
    var newest: List<Movie> = emptyList(); private set
    @Volatile var ready = false; private set
    val total get() = byId.size
    var searchQ = ""; var searchRes: List<Movie> = emptyList()

    private val catFile get() = File(dir, "${pfx}_cats.json")
    private val strFile get() = File(dir, "${pfx}_streams.json")
    fun cacheAgeMs() = (if (idxFile.exists()) idxFile else strFile).let { if (it.exists()) System.currentTimeMillis() - it.lastModified() else Long.MAX_VALUE }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    protected fun api(action: String) = "${prefs.server}/player_api.php?username=${enc(prefs.user)}&password=${enc(prefs.pass)}&action=$action"
    fun movieUrl(m: Movie) = "${prefs.server}/${if (m.ep) "series" else "movie"}/${enc(prefs.user)}/${enc(prefs.pass)}/${m.id}.${m.ext}"

    protected fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "ChannelDeck/0.1")
        if (c.responseCode != 200) { c.disconnect(); throw RuntimeException("HTTP ${c.responseCode}") }
        return c
    }
    private fun download(url: String, to: File) {
        val tmp = File(to.path + ".part"); val c = open(url)
        try { c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 64 * 1024) } } } finally { c.disconnect() }
        if (!tmp.renameTo(to)) throw RuntimeException("could not save ${to.name}")
    }

    private val idxFile get() = File(dir, "${pfx}_index.tsv")

    /** Fast path: the compact index written after the last download. Falls back to (and migrates from) the raw JSON of older versions. */
    suspend fun loadCache(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (idxFile.exists() && catFile.exists()) { finish(readIdx(), readLabels()); return@withContext true }
            if (strFile.exists() && catFile.exists()) {
                val labels = readLabels()
                val all = JsonReader(InputStreamReader(FileInputStream(strFile).buffered(64 * 1024), "UTF-8")).use { parse(it, labels, null) }
                writeIdx(all); strFile.delete(); finish(all, labels); return@withContext true
            }
            false
        } catch (e: Exception) { false }
    }

    /** Download categories + the movie list; the list is parsed while it arrives (progress = films seen so far). */
    suspend fun refresh(progress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        download(api(if (series) "get_series_categories" else "get_vod_categories"), catFile)
        val labels = readLabels()
        val c = open(api(if (series) "get_series" else "get_vod_streams"))
        val all = try { JsonReader(InputStreamReader(c.inputStream.buffered(64 * 1024), "UTF-8")).use { parse(it, labels, progress) } } finally { c.disconnect() }
        if (all.isEmpty()) throw RuntimeException("the provider returned no ${unit}")
        writeIdx(all); strFile.delete()
        finish(all, labels)
    }

    private fun readLabels(): Map<String, String> {
        val catArr = JSONArray(catFile.readText())
        val labels = LinkedHashMap<String, String>()
        for (i in 0 until catArr.length()) { val o = catArr.getJSONObject(i); labels[o.getString("category_id")] = catLabel(o.optString("category_name", "?")) }
        return labels
    }

    private fun str(r: JsonReader): String = when (r.peek()) {
        JsonToken.NULL -> { r.nextNull(); "" }
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean().toString()
        else -> { r.skipValue(); "" }
    }

    private fun parse(r: JsonReader, labels: Map<String, String>, progress: ((Int) -> Unit)?): ArrayList<Movie> {
        val all = ArrayList<Movie>(32000)
        r.beginArray()
        while (r.hasNext()) {
            var id = ""; var name = ""; var icon = ""; var rating = 0f; var cat = ""; var ext = "mp4"; var added = 0L; var rel = ""
            r.beginObject()
            while (r.hasNext()) when (r.nextName()) {
                "stream_id", "series_id" -> id = str(r)
                "name" -> name = str(r)
                "stream_icon", "cover" -> icon = str(r)
                "rating" -> rating = str(r).toFloatOrNull() ?: 0f
                "category_id" -> cat = str(r)
                "container_extension" -> ext = str(r).ifEmpty { "mp4" }
                "added", "last_modified" -> added = str(r).toLongOrNull() ?: 0L
                "releaseDate", "release_date" -> rel = str(r)
                else -> r.skipValue()
            }
            r.endObject()
            if (id.isEmpty()) continue
            val (title, y0) = cleanTitle(name)
            val year = y0.ifEmpty { if (series) rel.take(4).takeIf { it.length == 4 && it.all { c -> c.isDigit() } } ?: "" else "" }
            all.add(Movie(id, title, year, cat, icon, rating, ext, added, name.startsWith("4K", true) || (labels[cat] ?: "").contains("4K")))
            if (progress != null && all.size % 1500 == 0) progress(all.size)
        }
        r.endArray()
        return all
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    private fun writeIdx(all: List<Movie>) {
        val tmp = File(idxFile.path + ".part")
        tmp.bufferedWriter(Charsets.UTF_8, 64 * 1024).use { w ->
            for (m in all) { w.write(listOf(m.id, clean(m.title), m.year, m.cat, clean(m.poster), m.rating.toString(), m.ext, m.added.toString(), if (m.is4k) "1" else "0").joinToString("\t")); w.write("\n") }
        }
        tmp.renameTo(idxFile)
    }
    private fun readIdx(): ArrayList<Movie> {
        val all = ArrayList<Movie>(32000)
        idxFile.bufferedReader(Charsets.UTF_8, 64 * 1024).useLines { lines ->
            for (line in lines) {
                val p = line.split('\t'); if (p.size < 9) continue
                all.add(Movie(p[0], p[1], p[2], p[3], p[4], p[5].toFloatOrNull() ?: 0f, p[6], p[7].toLongOrNull() ?: 0L, p[8] == "1"))
            }
        }
        if (all.isEmpty()) throw RuntimeException("empty index")
        return all
    }

    private fun finish(all: List<Movie>, labels: Map<String, String>) {
        val map = HashMap<String, MutableList<Movie>>()
        val ids = HashMap<String, Movie>(40000)
        for (m in all) { map.getOrPut(m.cat) { ArrayList() }.add(m); ids[m.id] = m }
        // the same film often exists in several categories (4K / HD / Netflix…): list each once, preferring the 4K copy
        val best = HashMap<String, Movie>()
        for (m in all) { val k = m.title + "|" + m.year; val b = best[k]; if (b == null || (m.is4k && !b.is4k)) best[k] = m }
        newest = all.sortedByDescending { it.added }.distinctBy { it.title + "|" + it.year }.map { best[it.title + "|" + it.year] ?: it }.take(300)
        byCat = map; byId = ids; normCache = null
        cats = labels.entries.mapNotNull { (id, l) -> (map[id]?.size ?: 0).takeIf { it > 0 }?.let { VCat(id, l, it) } }
        ready = true
    }

    /** "EN - Black Adam  (2022)" -> ("Black Adam", "2022"); also strips "4K-NF - ", "TOP - ", "D+ - " style provider prefixes. */
    private fun cleanTitle(raw: String): Pair<String, String> {
        var t = Neon.plain(raw).replace(Regex("^(?:4K-)?[A-Za-z0-9+]{1,5}\\s+-\\s+"), "")
        if (series) while (true) { val n = t.replace(Regex("\\s*\\([A-Za-z]{2,3}\\)\\s*$"), ""); if (n == t) break; t = n }     // trailing country tags: "(TW)"
        var year = ""
        Regex("\\((\\d{4})\\)\\s*$").find(t)?.let { year = it.groupValues[1]; t = t.substring(0, it.range.first) }
        return t.replace(Regex("\\s+"), " ").trim().ifEmpty { raw } to year
    }

    private val keepUpper = setOf("4K", "UFC", "WWE", "IMDB", "HEVC", "TV", "UK", "US", "DC", "HD", "UHD", "MMA")
    /** Provider categories are SHOUTED and cluttered ("EN - KIDS ⁴ᴷ ³⁸⁴⁰ᴾ"): show them calmly. */
    fun catLabel(raw: String): String {
        val s = Neon.plain(raw).replace(Regex("^EN\\s*-\\s*"), "").replace(Regex("\\(MULTI(-SUBS)?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b3840P\\b", RegexOption.IGNORE_CASE), "").replace(Regex("\\s+"), " ").trim()
        return s.split(' ').joinToString(" ") { w ->
            if (w.uppercase() in keepUpper) w.uppercase() else if (w.any { it.isLetter() }) w.lowercase().replaceFirstChar { it.uppercase() } else w
        }.ifEmpty { raw }
    }

    // ---- lists
    fun movies(catId: String): List<Movie> = when (catId) {
        NEW -> newest; CONT -> continueList(); FIND -> searchRes
        else -> byCat[catId] ?: emptyList()
    }
    fun movie(id: String) = byId[id]
    fun catName(id: String) = when (id) { NEW -> "Recently added"; CONT -> "Continue watching"; FIND -> if (searchQ.isEmpty()) "Find" else "“$searchQ”"; else -> cats.firstOrNull { it.id == id }?.label ?: "" }
    companion object { const val NEW = "__new"; const val CONT = "__cont"; const val FIND = "__find" }

    @Volatile private var normCache: List<Pair<Movie, String>>? = null
    private fun norms(): List<Pair<Movie, String>> = normCache ?: synchronized(this) { normCache ?: byId.values.map { it to normText(it.title) }.also { normCache = it } }
    fun warm() { if (ready) norms() }
    fun runSearch(q: String) {
        searchQ = q.trim(); val nq = normText(searchQ)
        if (nq.isEmpty()) { searchRes = emptyList(); return }
        val toks = nq.split(' ')
        searchRes = norms().asSequence().filter { (_, n) -> toks.all { n.contains(it) } }
            .sortedWith(compareBy({ (_, n) -> if (n == nq) 0 else if (n.startsWith(nq)) 1 else 2 }, { (m, _) -> -m.added }))
            .map { it.first }.distinctBy { it.title + "|" + it.year }.take(300).toList()
    }

    // ---- resume positions: id -> "pos,dur,ts" (ms, ms, epoch s)
    protected val prog: MutableMap<String, String> by lazy {
        val m = HashMap<String, String>()
        try { val o = JSONObject(sp.getString("prog", "{}") ?: "{}"); o.keys().forEach { m[it] = o.getString(it) } } catch (e: Exception) {}
        m
    }
    fun progress(id: String): Pair<Long, Long>? = prog[id]?.split(",")?.let { if (it.size == 3) Pair(it[0].toLongOrNull() ?: 0L, it[1].toLongOrNull() ?: 0L) else null }
    open fun fraction(id: String): Float = progress(id)?.let { (p, d) -> if (d > 0) (p.toFloat() / d).coerceIn(0f, 1f) else 0f } ?: 0f
    open fun saveProgress(m: Movie, pos: Long, dur: Long) {
        if (dur <= 0 || pos < 0) return
        if (pos > dur * 0.95 || dur - pos < 90_000) prog.remove(m.id)           // finished
        else if (pos < 30_000) { if (!prog.containsKey(m.id)) return }           // barely started: do not clutter the list
        else prog[m.id] = "$pos,$dur,${System.currentTimeMillis() / 1000}"
        if (prog.size > 80) prog.entries.sortedBy { it.value.substringAfterLast(',').toLongOrNull() ?: 0L }.take(prog.size - 80).forEach { prog.remove(it.key) }
        sp.edit().putString("prog", JSONObject(prog as Map<*, *>).toString()).apply()
    }
    fun forget(id: String) { if (prog.remove(id) != null) sp.edit().putString("prog", JSONObject(prog as Map<*, *>).toString()).apply() }
    open fun continueList(): List<Movie> = prog.entries.sortedByDescending { it.value.substringAfterLast(',').toLongOrNull() ?: 0L }.mapNotNull { byId[it.key] }

    /** Plot, genre, cast… for the detail card. */
    private val infoCache = HashMap<String, VodInfo>()
    suspend fun info(m: Movie): VodInfo? = withContext(Dispatchers.IO) {
        infoCache[m.id]?.let { return@withContext it }
        for (attempt in 0..1) try {
            if (attempt > 0) kotlinx.coroutines.delay(1200)
            val c = open(api("get_vod_info") + "&vod_id=${m.id}")
            val txt = try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
            val o = JSONObject(txt).optJSONObject("info") ?: return@withContext null
            fun s(k: String) = if (o.isNull(k)) "" else o.optString(k, "").trim()
            val dur = s("duration").ifEmpty { o.optInt("duration_secs", 0).let { if (it > 0) "${it / 3600}h ${it % 3600 / 60}m" else "" } }
            return@withContext VodInfo(s("plot").ifEmpty { s("description") }, s("genre"), dur, s("cast").ifEmpty { s("actors") }.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString(", "), s("director")).also { infoCache[m.id] = it }
        } catch (e: Exception) { }
        null
    }
}

// ====================================================================== UI

/** One poster in the grid: rounded poster, optional 4K chip and progress bar, title + year/rating underneath. */
class PosterCard(ctx: Context, private val pw: Int, private val ph: Int) : LinearLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val chip = TextView(ctx)
    val titleTv = TextView(ctx); val metaTv = TextView(ctx)
    var fraction = 0f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    init {
        orientation = VERTICAL; isFocusable = true; isClickable = true; clipChildren = false; clipToPadding = false
        setWillNotDraw(false)
        val side = (7 * d).toInt()
        setPadding(side, (6 * d).toInt(), side, 0)
        val frame = FrameLayout(ctx)
        img.apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#17275E"), Color.parseColor("#0B1233"))).apply { cornerRadius = 10 * d }
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 10 * d) } }
            clipToOutline = true
        }
        frame.addView(img, FrameLayout.LayoutParams(pw, ph))
        chip.apply { text = "4K"; setTextColor(Neon.BG0); textSize = 11f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding((6 * d).toInt(), (1 * d).toInt(), (6 * d).toInt(), (1 * d).toInt())
            background = GradientDrawable().apply { setColor(Neon.YELLOW); cornerRadius = 6 * d }; visibility = GONE }
        frame.addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, (6 * d).toInt(), (6 * d).toInt(), 0) })
        addView(frame, LayoutParams(pw, ph))
        titleTv.apply { setTextColor(Neon.TEXT); textSize = 13f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(0, (7 * d).toInt(), 0, 0) }
        metaTv.apply { setTextColor(Neon.withAlpha(Neon.TEXT, 205)); textSize = 12f; setSingleLine(); setPadding(0, (1 * d).toInt(), 0, (4 * d).toInt()) }
        addView(titleTv, LayoutParams(pw, -2)); addView(metaTv, LayoutParams(pw, -2))
        setOnFocusChangeListener { _, f -> focusChanged(f) }
    }
    fun focusChanged(f: Boolean) {
        invalidate()
    }

    fun bind(m: Movie, fraction: Float) {
        this.fraction = fraction
        titleTv.text = m.title
        metaTv.text = listOfNotNull(m.year.ifEmpty { null }, if (m.rating > 0.1f) "★ " + String.format("%.1f", m.rating) else null).joinToString("  ·  ")
        chip.visibility = if (m.is4k) VISIBLE else GONE
        if (m.poster.isEmpty()) { img.dispose(); img.setImageDrawable(null) }
        else img.load(m.poster) { size(300, 450); crossfade(160) }
        invalidate()
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        val l = paddingLeft.toFloat(); val t = paddingTop.toFloat()
        if (fraction > 0f) {
            val bh = 4 * d; val by = t + ph - 10 * d
            bar.color = Neon.withAlpha(Color.BLACK, 150); box.set(l + 8 * d, by, l + pw - 8 * d, by + bh); c.drawRoundRect(box, bh / 2, bh / 2, bar)
            bar.color = Neon.YELLOW; box.right = box.left + (box.width()) * fraction; c.drawRoundRect(box, bh / 2, bh / 2, bar)
        }
        if (isFocused) {
            val sw = 3 * d; ring.strokeWidth = sw; ring.color = Neon.YELLOW
            val o = 3 * d; box.set(l - o + sw / 2, t - o + sw / 2, l + pw + o - sw / 2, t + ph + o - sw / 2); c.drawRoundRect(box, 13 * d, 13 * d, ring)
        }
    }
    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) { performClick(); return true }
        return super.onKeyUp(keyCode, event)
    }
}

/** Movies: categories on the left, poster grid on the right, detail card on top. Uses normal D-pad focus. */
class MoviesView(ctx: Context, private val vod: VodRepo, val cb: Cb) : FrameLayout(ctx) {
    interface Cb { fun play(m: Movie, fromStart: Boolean); fun search(); fun close(); fun loadInfo(m: Movie, done: (VodInfo?) -> Unit); fun loadSeries(m: Movie, done: (SeriesInfo?) -> Unit) {} }

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val cols = 5
    private val catRv = RecyclerView(ctx); private val grid = RecyclerView(ctx)
    private val headTitle = TextView(ctx); private val headCount = TextView(ctx); private val state = TextView(ctx)
    private val catItems = ArrayList<VCat>()
    private var activeCat = ""
    private var items: List<Movie> = emptyList()
    private var pw = 0; private var ph = 0
    private var selectJob: Runnable? = null
    private var detail: FrameLayout? = null
    private val content = LinearLayout(ctx)
    private var returnPos = -1
    private var lastGridPos = 0                 // poster that had focus last, so RIGHT from the categories returns to it

    private val catAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = catItems.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val row = LinearLayout(p.context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
                setPadding(dp(14), dp(9), dp(12), dp(9))
                background = StateListDrawable().apply {
                    fun rr(c: Int, s: Int, w: Float) = GradientDrawable().apply { setColor(c); cornerRadius = dp(12).toFloat(); setStroke((w * d).toInt(), s) }
                    addState(intArrayOf(android.R.attr.state_focused), rr(Color.parseColor("#172863"), Neon.YELLOW, 2.5f))
                    addState(intArrayOf(android.R.attr.state_selected), rr(Color.parseColor("#142053"), Neon.withAlpha(Neon.CYAN, 170), 1.5f))
                    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                }
            }
            val n = TextView(p.context).apply { setTextColor(Neon.TEXT); textSize = 17f; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
            val c = TextView(p.context).apply { setTextColor(Neon.MUTED); textSize = 13f; setPadding(dp(8), 0, 0, 0) }
            row.addView(n, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(c)
            row.layoutParams = RecyclerView.LayoutParams(-1, -2)
            return object : RecyclerView.ViewHolder(row) {}.also { vh -> row.tag = arrayOf(n, c) }
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val it = catItems[i]; @Suppress("UNCHECKED_CAST") val v = h.itemView.tag as Array<TextView>
            v[0].text = it.label; v[1].text = if (it.count > 0) fmtCount(it.count) else ""
            h.itemView.isSelected = it.id == activeCat
            v[0].setTextColor(if (it.id == activeCat) Neon.CYAN else Neon.TEXT)
            h.itemView.setOnClickListener { _ -> if (it.id == VodRepo.FIND) cb.search() else { select(it.id); focusGrid() } }
            h.itemView.setOnFocusChangeListener { _, f ->
                if (f) { selectJob?.let { r -> removeCallbacks(r) }; val r = Runnable { select(it.id) }; selectJob = r; postDelayed(r, 220) }
            }
            h.itemView.setOnKeyListener { _, code, ev ->
                if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && ev.action == android.view.KeyEvent.ACTION_DOWN && it.id == VodRepo.FIND && vod.searchRes.isEmpty()) { cb.search(); true }
                else if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && ev.action == android.view.KeyEvent.ACTION_DOWN && items.isNotEmpty()) {
                    selectJob?.let { r -> removeCallbacks(r) }
                    if (it.id != activeCat) select(it.id)
                    focusGridAt(if (it.id == activeCat) lastGridPos else 0); true
                } else false
            }
        }
    }
    private fun fmtCount(n: Int) = if (n >= 1000) String.format("%,d", n) else n.toString()

    private val gridAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val card = PosterCard(p.context, pw, ph)
            card.layoutParams = RecyclerView.LayoutParams(pw + dp(14), -2)
            return object : RecyclerView.ViewHolder(card) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val m = items[i]; val card = h.itemView as PosterCard
            card.bind(m, vod.fraction(m.id))
            card.setOnClickListener { returnPos = h.bindingAdapterPosition; openDetail(m) }
            card.setOnKeyListener { _, code, ev ->
                if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT && ev.action == android.view.KeyEvent.ACTION_DOWN && h.bindingAdapterPosition % cols == 0) { focusActiveCat(); true } else false
            }
            card.setOnFocusChangeListener { v, f -> (v as PosterCard).focusChanged(f); if (f) lastGridPos = h.bindingAdapterPosition }
        }
    }

    init {
        background = Neon.Backdrop(); isClickable = true
        val margin = dp(48); val sw = resources.displayMetrics.widthPixels
        val catW = dp(262); val gap = dp(16)
        val gridW = sw - 2 * margin - catW - gap
        val cellW = gridW / cols
        pw = cellW - dp(14); ph = (pw * 1.5f).toInt()

        content.orientation = LinearLayout.HORIZONTAL; content.clipChildren = false
        // left column
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(ctx).apply { text = vod.label; setTextColor(Neon.TEXT); textSize = 30f; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); setPadding(dp(14), 0, 0, dp(2)) })
        left.addView(TextView(ctx).apply { text = "ALL CATEGORIES"; setTextColor(Neon.CYAN); textSize = 12f; letterSpacing = 0.2f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(dp(14), dp(10), 0, dp(6)) })
        catRv.apply { layoutManager = LinearLayoutManager(ctx); adapter = catAdapter; itemAnimator = null; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = true; setPadding(0, 0, dp(6), dp(40)); isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(40)); isFocusable = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS }
        left.addView(catRv, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(left, LinearLayout.LayoutParams(catW, -1))
        // right column
        val right = FrameLayout(ctx).apply { clipChildren = false }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(dp(7), 0, 0, dp(8)) }
        headTitle.apply { setTextColor(Neon.TEXT); textSize = 22f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setSingleLine() }
        headCount.apply { setTextColor(Neon.MUTED); textSize = 14f; setPadding(dp(12), 0, 0, dp(3)) }
        head.addView(headTitle); head.addView(headCount)
        col.addView(head, LinearLayout.LayoutParams(-1, dp(52)))
        grid.apply { layoutManager = GridLayoutManager(ctx, cols); adapter = gridAdapter; itemAnimator = null; overScrollMode = View.OVER_SCROLL_NEVER; clipChildren = true; clipToPadding = true; setPadding(0, dp(4), 0, dp(40)); isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(40)); isFocusable = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS; setHasFixedSize(true); setItemViewCacheSize(10) }
        col.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        right.addView(col, LayoutParams(-1, -1))
        state.apply { setTextColor(Neon.MUTED); textSize = 18f; gravity = Gravity.CENTER; visibility = GONE }
        right.addView(state, LayoutParams(-2, -2, Gravity.CENTER))
        content.addView(right, LinearLayout.LayoutParams(gridW, -1).apply { leftMargin = gap })
        addView(content, LayoutParams(-1, -1).apply { setMargins(margin, dp(26), margin, 0) })
    }

    // ---- state
    fun showMessage(msg: String?) { state.text = msg ?: ""; state.visibility = if (msg == null) GONE else VISIBLE }

    /** (Re)build the category list from the repository and select something sensible. */
    fun rebuild() {
        val list = ArrayList<VCat>()
        val cont = vod.continueList().size
        if (cont > 0) list += VCat(VodRepo.CONT, "Continue watching", cont)
        list += VCat(VodRepo.NEW, "Recently added", vod.newest.size)
        list += VCat(VodRepo.FIND, if (vod.searchQ.isEmpty()) "Find…" else "Find · ${vod.searchQ}", vod.searchRes.size)
        list += vod.cats
        val same = list == catItems
        catItems.clear(); catItems.addAll(list)
        if (!same) catAdapter.notifyDataSetChanged()
        if (activeCat.isEmpty() || catItems.none { it.id == activeCat }) activeCat = catItems.first().id
        select(activeCat, force = true)
    }

    private fun select(id: String, force: Boolean = false) {
        if (id == activeCat && !force) return
        val old = catItems.indexOfFirst { it.id == activeCat }
        activeCat = id
        val now = catItems.indexOfFirst { it.id == id }
        if (old >= 0) catAdapter.notifyItemChanged(old); if (now >= 0) catAdapter.notifyItemChanged(now)
        items = vod.movies(id); lastGridPos = 0
        gridAdapter.notifyDataSetChanged()
        (grid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(0, 0)
        headTitle.text = vod.catName(id)
        headCount.text = if (items.isEmpty()) "" else "${fmtCount(items.size)} ${vod.unit}"
        showMessage(if (items.isNotEmpty()) null else when (id) {
            VodRepo.FIND -> if (vod.searchQ.isEmpty()) "Press OK or ▶ to search by title" else "No ${if (vod.unit == "films") "film" else "series"} matches “${vod.searchQ}”"
            else -> "Nothing here yet" })
    }

    fun showSearchResults() { rebuild(); val i = catItems.indexOfFirst { it.id == VodRepo.FIND }; select(VodRepo.FIND, force = true); if (vod.searchRes.isNotEmpty()) focusGrid() else catRv.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() }

    fun focusDefault() {
        val i = catItems.indexOfFirst { it.id == activeCat }.coerceAtLeast(0)
        catRv.scrollToPosition(i)
        catRv.post { catRv.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() ?: catRv.post { catRv.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() } }
    }
    private fun focusGrid() { focusGridAt(0) }
    private fun focusGridAt(pos0: Int) {
        val pos = pos0.coerceIn(0, (items.size - 1).coerceAtLeast(0)); if (items.isEmpty()) return
        val lm = grid.layoutManager as GridLayoutManager
        if (pos < lm.findFirstVisibleItemPosition() || pos > lm.findLastVisibleItemPosition()) lm.scrollToPositionWithOffset(pos, dp(4))
        grid.post { (grid.findViewHolderForAdapterPosition(pos) ?: run { lm.scrollToPositionWithOffset(pos, dp(4)); null })?.itemView?.requestFocus() ?: grid.post { grid.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() } }
    }
    private fun focusActiveCat() {
        val i = catItems.indexOfFirst { it.id == activeCat }.coerceAtLeast(0)
        val lm = catRv.layoutManager as LinearLayoutManager
        if (i < lm.findFirstVisibleItemPosition() || i > lm.findLastVisibleItemPosition()) lm.scrollToPositionWithOffset(i, dp(120))
        catRv.post { catRv.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() ?: catRv.post { catRv.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() } }
    }

    /** FF/REW: jump a few rows in whichever list has focus. */
    fun pageJump(down: Boolean) {
        val gp = (0 until grid.childCount).map { grid.getChildAt(it) }.firstOrNull { it.hasFocus() }?.let { grid.getChildAdapterPosition(it) } ?: -1
        if (gp >= 0) {
            val to = (gp + (if (down) 3 else -3) * cols).coerceIn(0, items.size - 1)
            (grid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(to, dp(8))
            grid.post { grid.findViewHolderForAdapterPosition(to)?.itemView?.requestFocus() }
        } else {
            val cp = (0 until catRv.childCount).map { catRv.getChildAt(it) }.firstOrNull { it.hasFocus() }?.let { catRv.getChildAdapterPosition(it) } ?: return
            val to = (cp + if (down) 6 else -6).coerceIn(0, catItems.size - 1)
            catRv.scrollToPosition(to); catRv.post { catRv.findViewHolderForAdapterPosition(to)?.itemView?.requestFocus() }
        }
    }

    // ---- detail
    fun openDetail(m: Movie) {
        detail?.let { removeView(it) }            // never stack panels (e.g. returning from playback)
        val p: FrameLayout = if (vod is SeriesRepo) SeriesPanel(context, m, vod, cb) else DetailPanel(context, m, vod, cb, this)
        detail = p
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        addView(p, LayoutParams(-1, -1)); p.alpha = 0f; p.animate().alpha(1f).setDuration(180).start()
        if (p is SeriesPanel) cb.loadSeries(m) { si -> if (detail === p) p.bind(si) }
        else if (p is DetailPanel) { p.focusPrimary(); cb.loadInfo(m) { info -> if (detail === p) p.bindInfo(info) } }
    }
    /** Returns true when it consumed BACK. */
    fun handleBack(): Boolean { if (detail != null) { closeDetail(); return true }; return false }
    fun closeDetail() {
        val p = detail ?: return; detail = null
        removeView(p); content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        // progress bars / "Continue watching" may have changed
        val keep = returnPos
        if (activeCat == VodRepo.CONT) { items = vod.movies(activeCat); gridAdapter.notifyDataSetChanged() } else gridAdapter.notifyItemRangeChanged(0, items.size)
        val cont = vod.continueList().size
        if ((catItems.firstOrNull()?.id == VodRepo.CONT) != (cont > 0)) rebuild()
if (items.isNotEmpty()) focusGridAt(keep) else focusDefault()
    }
    val detailOpen get() = detail != null
}

/** The film card: poster, title, facts, plot, Play / Resume. */
class DetailPanel(ctx: Context, val m: Movie, vod: VodRepo, cb: MoviesView.Cb, private val owner: MoviesView) : FrameLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val facts = TextView(ctx); private val genre = TextView(ctx); private val plot = TextView(ctx); private val people = TextView(ctx)
    private val primary: NeonRow
    init {
        background = Neon.Backdrop(); isClickable = true
        if (m.poster.isNotEmpty()) addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.22f; load(m.poster) { size(24, 36) } }, LayoutParams(-1, -1))   // tiny image upscaled = free blur
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val pw = dp(210); val ph = dp(315)
        val poster = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#17275E"), Color.parseColor("#0B1233"))).apply { cornerRadius = 14 * d }
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 14 * d) } }
            clipToOutline = true
            if (m.poster.isNotEmpty()) load(m.poster) { size(420, 630); crossfade(true) }
        }
        row.addView(poster, LinearLayout.LayoutParams(pw, ph))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), 0, 0, 0) }
        col.addView(TextView(ctx).apply { text = m.title; setTextColor(Neon.TEXT); textSize = 30f; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        facts.apply { setTextColor(Neon.CYAN); textSize = 15f; setPadding(0, dp(6), 0, 0); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        genre.apply { setTextColor(Neon.MUTED); textSize = 14f; setPadding(0, dp(2), 0, 0); visibility = GONE }
        plot.apply { setTextColor(Neon.TEXT); alpha = 0.85f; textSize = 15f; maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.12f); setPadding(0, dp(12), 0, 0); text = "Loading…" }
        people.apply { setTextColor(Neon.MUTED); textSize = 13f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(10), 0, 0); visibility = GONE }
        col.addView(facts); col.addView(genre); col.addView(plot); col.addView(people)
        col.addView(View(ctx), LinearLayout.LayoutParams(1, dp(14)))
        val pr = vod.progress(m.id)?.takeIf { it.first > 30_000 }
        primary = NeonRow(ctx, if (pr != null) "Resume" else "Play", if (pr != null) "from ${fmtTime(pr.first)}${if (pr.second > 0) "  /  ${fmtTime(pr.second)}" else ""}" else "Start watching")
        primary.setOnClickListener { cb.play(m, false) }
        col.addView(primary, LinearLayout.LayoutParams(dp(380), -2))
        if (pr != null) {
            val again = NeonRow(ctx, "Start over", "")
            again.valueTv.visibility = GONE; again.minimumHeight = dp(60)
            again.setOnClickListener { cb.play(m, true) }
            col.addView(again, LinearLayout.LayoutParams(dp(380), -2).apply { topMargin = dp(8) })
        }
        col.addView(Neon.keyHint(ctx, "BACK", "to return"))
        col.minimumHeight = ph
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        addView(row, LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(72), 0, dp(72), 0) })
        facts.text = listOfNotNull(m.year.ifEmpty { null }, if (m.rating > 0.1f) "★ " + String.format("%.1f", m.rating) else null, if (m.is4k) "4K" else null).joinToString("   ·   ")
    }
    fun focusPrimary() { primary.post { primary.requestFocus() } }
    fun bindInfo(i: VodInfo?) {
        if (i == null) { plot.text = "Details are unavailable right now."; return }
        plot.text = i.plot.takeIf { it.isNotEmpty() } ?: "No description from the provider."
        val dur = Regex("^(\\d+):(\\d{2}):(\\d{2})$").find(i.duration)?.let { (if (it.groupValues[1].toInt() > 0) "${it.groupValues[1].toInt()} h " else "") + "${it.groupValues[2]} min" } ?: i.duration
        val f = listOfNotNull(m.year.ifEmpty { null }, dur.ifEmpty { null }, if (m.rating > 0.1f) "★ " + String.format("%.1f", m.rating) else null, if (m.is4k) "4K" else null)
        facts.text = f.joinToString("   ·   ")
        if (i.genre.isNotEmpty()) { genre.text = i.genre; genre.visibility = VISIBLE }
        val p = listOfNotNull(i.director.takeIf { it.isNotEmpty() }?.let { "Director  $it" }, i.cast.takeIf { it.isNotEmpty() }?.let { "Cast  $it" }, "File  ${m.ext.uppercase()}").joinToString("\n")
        if (p.isNotEmpty()) { people.text = p; people.visibility = VISIBLE }
    }
}

/** Bottom bar shown while a film plays: title, progress, time, key hints. Auto-hides (managed by the activity). */
class VodBar(ctx: Context) : FrameLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val title = TextView(ctx); private val time = TextView(ctx); private val hint = TextView(ctx); private val techTv = TextView(ctx)
    private var frac = 0f; private var paused = false
    var note = ""
    var tech = ""
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = View(ctx)
    init {
        setWillNotDraw(false)
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.argb(150, 3, 6, 18), Color.argb(240, 3, 6, 18)))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((48 * d).toInt(), (110 * d).toInt(), (48 * d).toInt(), (40 * d).toInt()) }
        title.apply { setTextColor(Neon.TEXT); textSize = 22f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setSingleLine() }
        time.apply { setTextColor(Neon.TEXT); textSize = 15f; setPadding(0, (4 * d).toInt(), 0, 0) }
        hint.apply { setTextColor(Neon.withAlpha(Neon.TEXT, 220)); textSize = 17f; setPadding(0, (6 * d).toInt(), 0, 0); text = "◀ ▶  10 s     ▲ ▼  1 min     OK  timeline / pause     ≡  audio & subtitles     BACK  stop" }
        col.addView(title)
        col.addView(track, LinearLayout.LayoutParams(-1, (14 * d).toInt()).apply { topMargin = (10 * d).toInt() })
        techTv.apply { setTextColor(Neon.CYAN); textSize = 16f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(0, (2 * d).toInt(), 0, 0) }
        col.addView(time); col.addView(techTv); col.addView(hint)
        addView(col, LayoutParams(-1, -2, Gravity.BOTTOM))
        isClickable = false; isFocusable = false
    }
    fun update(name: String, pos: Long, dur: Long, paused: Boolean) {
        title.text = name; this.paused = paused
        frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        time.text = (if (paused) "❚❚  " else "▶  ") + fmtTime(pos) + (if (dur > 0) "  /  " + fmtTime(dur) + "     " + fmtTime(dur - pos) + " left" else "") + (if (note.isNotEmpty()) "          $note" else "")
        techTv.text = tech
        track.invalidate(); invalidate()
    }
    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        if (track.height == 0) return
        val loc = IntArray(2); track.getLocationInWindow(loc); val me = IntArray(2); getLocationInWindow(me)
        val l = (loc[0] - me[0]).toFloat(); val t = (loc[1] - me[1]).toFloat() + track.height / 2f - 2 * d; val w = track.width.toFloat(); val h = 4 * d
        p.color = Neon.withAlpha(Color.WHITE, 90); c.drawRoundRect(l, t - 2 * d, l + w, t + h + 2 * d, h / 2, h / 2, p)
        p.color = Neon.YELLOW; c.drawRoundRect(l, t, l + w * frac, t + h, h / 2, h / 2, p)
        c.drawCircle(l + w * frac, t + h / 2, 7 * d, p)
    }
}
