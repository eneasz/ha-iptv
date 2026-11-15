package pl.home.channeldeck

import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class Ep(val id: String, val season: Int, val num: Int, val title: String, val ext: String, val secs: Int)
data class SeriesInfo(val plot: String, val genre: String, val cast: String, val director: String, val seasons: List<Pair<Int, List<Ep>>>) {
    val flat: List<Ep> by lazy { seasons.flatMap { it.second } }
}

/** Xtream series: the same list/category/search machinery as movies, plus episodes, "watched" marks and next-episode logic. */
class SeriesRepo(ctx: Context, prefs: Prefs) : VodRepo(ctx, prefs, true) {
    private val infoCache = HashMap<String, SeriesInfo>()
    private val watched: MutableSet<String> by lazy { HashSet(sp.getStringSet("watched", emptySet()) ?: emptySet()) }
    /** series id -> "episodeId|epoch seconds": where you last were in each show. */
    private val last: MutableMap<String, String> by lazy {
        val m = HashMap<String, String>()
        try { val o = JSONObject(sp.getString("last", "{}") ?: "{}"); o.keys().forEach { m[it] = o.getString(it) } } catch (e: Exception) {}
        m
    }

    fun isWatched(epId: String) = epId in watched
    private fun persist() { sp.edit().putStringSet("watched", HashSet(watched)).putString("last", JSONObject(last as Map<*, *>).toString()).apply() }

    override fun saveProgress(m: Movie, pos: Long, dur: Long) {
        super.saveProgress(m, pos, dur)
        if (!m.ep || dur <= 0) return
        val fin = pos > dur * 0.95 || dur - pos < 90_000
        if (fin) watched.add(m.id)
        if (fin || pos >= 30_000) {
            last[m.cat] = "${m.id}|${System.currentTimeMillis() / 1000}"
            if (last.size > 60) last.entries.sortedBy { it.value.substringAfter('|').toLongOrNull() ?: 0L }.take(last.size - 60).forEach { last.remove(it.key) }
            persist()
        }
    }
    /** A show's progress bar = how far into the episode you last watched. */
    override fun fraction(id: String): Float = last[id]?.substringBefore('|')?.let { super.fraction(it) } ?: 0f
    override fun continueList(): List<Movie> = last.entries.sortedByDescending { it.value.substringAfter('|').toLongOrNull() ?: 0L }.mapNotNull { movie(it.key) }

    private fun epTitle(raw: String, num: Int): String {
        val t = Neon.plain(raw).replace(Regex("^.*?S\\d+\\s?E\\d+\\s*-?\\s*", RegexOption.IGNORE_CASE), "").trim()
        return t.ifEmpty { "Episode $num" }
    }

    suspend fun seriesInfo(m: Movie): SeriesInfo? = withContext(Dispatchers.IO) {
        infoCache[m.id]?.let { return@withContext it }
        for (attempt in 0..1) try {
            if (attempt > 0) kotlinx.coroutines.delay(1200)
            val c = open(api("get_series_info") + "&series_id=${m.id}")
            val txt = try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
            val root = JSONObject(txt); val o = root.optJSONObject("info") ?: JSONObject()
            fun s(k: String) = if (o.isNull(k)) "" else o.optString(k, "").trim()
            val seasons = ArrayList<Pair<Int, List<Ep>>>()
            root.optJSONObject("episodes")?.let { eps ->
                for (k in eps.keys()) {
                    val sn = k.toIntOrNull() ?: continue; val arr = eps.optJSONArray(k) ?: continue
                    val list = ArrayList<Ep>()
                    for (i in 0 until arr.length()) {
                        val e = arr.optJSONObject(i) ?: continue
                        val num = e.optInt("episode_num", i + 1)
                        list.add(Ep(e.optString("id"), sn, num, epTitle(e.optString("title"), num), e.optString("container_extension", "mkv").ifEmpty { "mkv" }, e.optJSONObject("info")?.optInt("duration_secs", 0) ?: 0))
                    }
                    if (list.isNotEmpty()) seasons.add(sn to list.sortedBy { it.num })
                }
            }
            seasons.sortBy { it.first }
            return@withContext SeriesInfo(s("plot"), s("genre"), s("cast").split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString(", "), s("director"), seasons).also { infoCache[m.id] = it }
        } catch (e: Exception) { }
        null
    }

    /** Where to continue: (episode, "resume" | "next" | "first" | "again"). */
    fun upNext(info: SeriesInfo, sid: String): Pair<Ep, String>? {
        val flat = info.flat; if (flat.isEmpty()) return null
        val lid = last[sid]?.substringBefore('|'); val i = flat.indexOfFirst { it.id == lid }
        if (i < 0) return flat[0] to "first"
        val e = flat[i]
        if ((progress(e.id)?.first ?: 0L) > 30_000) return e to "resume"
        return flat.getOrNull(i + 1)?.let { it to "next" } ?: (e to "again")
    }

    /** An episode as a playable item. */
    fun toMovie(series: Movie, e: Ep) = Movie(e.id, "${series.title}   ·   S${e.season} E${e.num}   ${e.title}", series.year, series.id, series.poster, series.rating, e.ext, 0L, series.is4k, true)
    fun nextAfter(m: Movie): Movie? {
        val info = infoCache[m.cat] ?: return null; val sm = movie(m.cat) ?: return null
        val flat = info.flat; val i = flat.indexOfFirst { it.id == m.id }
        return flat.getOrNull(i + 1)?.takeIf { i >= 0 }?.let { toMovie(sm, it) }
    }
}

/** The show card: poster + facts on top, season tabs, episode list. */
class SeriesPanel(ctx: Context, val m: Movie, private val ser: SeriesRepo, private val cb: MoviesView.Cb) : FrameLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val facts = TextView(ctx); private val plot = TextView(ctx); private val people = TextView(ctx)
    private val chips = LinearLayout(ctx); private val chipScroll = HorizontalScrollView(ctx)
    private val epRv = RecyclerView(ctx); private val msg = TextView(ctx)
    private var info: SeriesInfo? = null
    private var season = 1
    private var eps: List<Ep> = emptyList()
    private var upId: String? = null; private var upKind = ""

    private fun ring(fill: Int, stroke: Int, w: Float, r: Int = 12) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(r).toFloat(); setStroke((w * d).toInt(), stroke) }
    private fun mins(secs: Int) = if (secs <= 0) "" else if (secs >= 3600) "${secs / 3600} h ${secs % 3600 / 60} min" else "${(secs + 30) / 60} min"

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = eps.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val c = p.context
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
                setPadding(dp(16), dp(6), dp(16), dp(6))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), ring(Color.parseColor("#172863"), Neon.YELLOW, 2.5f))
                    addState(intArrayOf(), ring(Neon.withAlpha(Color.parseColor("#101A48"), 150), Color.TRANSPARENT, 0f))
                }
            }
            val num = TextView(c).apply { setTextColor(Neon.CYAN); textSize = 16f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
            val title = TextView(c).apply { setTextColor(Neon.TEXT); textSize = 17f; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
            val sub = TextView(c).apply { textSize = 13f; setSingleLine() }
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(sub) }
            val tick = TextView(c).apply { text = "✓"; setTextColor(Quality.GREEN); textSize = 20f; visibility = View.GONE }
            row.addView(num, LinearLayout.LayoutParams(dp(64), -2)); row.addView(col, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(tick)
            row.layoutParams = RecyclerView.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(5) }
            row.tag = arrayOf(num, title, sub, tick)
            return object : RecyclerView.ViewHolder(row) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val e = eps[i]; @Suppress("UNCHECKED_CAST") val v = h.itemView.tag as Array<TextView>
            v[0].text = "E${e.num}"; v[1].text = e.title
            val pr = ser.progress(e.id)?.takeIf { it.first > 30_000 }
            val dur = mins(e.secs)
            val (txt, col) = when {
                pr != null -> ("Resume from ${fmtTime(pr.first)}" + if (dur.isNotEmpty()) "   ·   $dur" else "") to Neon.YELLOW
                e.id == upId && upKind == "next" -> ("Up next" + if (dur.isNotEmpty()) "   ·   $dur" else "") to Neon.YELLOW
                else -> dur to Neon.MUTED
            }
            v[2].text = txt; v[2].setTextColor(col)
            v[3].visibility = if (ser.isWatched(e.id) && pr == null) View.VISIBLE else View.GONE
            h.itemView.setOnClickListener { cb.play(ser.toMovie(m, e), false) }
        }
    }

    init {
        background = Neon.Backdrop(); isClickable = true
        if (m.poster.isNotEmpty()) addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.22f; load(m.poster) { size(24, 36) } }, LayoutParams(-1, -1))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val poster = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#17275E"), Color.parseColor("#0B1233"))).apply { cornerRadius = 14 * d }
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 14 * d) } }
            clipToOutline = true
            if (m.poster.isNotEmpty()) load(m.poster) { size(360, 540); crossfade(true) }
        }
        row.addView(poster, LinearLayout.LayoutParams(dp(170), dp(255)))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), 0, 0, 0) }
        col.addView(TextView(ctx).apply { text = m.title; setTextColor(Neon.TEXT); textSize = 28f; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END })
        facts.apply { setTextColor(Neon.CYAN); textSize = 15f; setPadding(0, dp(4), 0, 0); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }
        plot.apply { setTextColor(Neon.TEXT); alpha = 0.85f; textSize = 14f; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.1f); setPadding(0, dp(8), 0, 0); text = "Loading…" }
        people.apply { setTextColor(Neon.MUTED); textSize = 13f; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0); visibility = View.GONE }
        col.addView(facts); col.addView(plot); col.addView(people)
        chipScroll.apply { isHorizontalScrollBarEnabled = false; visibility = View.GONE; clipToPadding = false; setPadding(0, dp(3), 0, dp(3)); addView(chips) }
        chips.orientation = LinearLayout.HORIZONTAL
        col.addView(chipScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        epRv.apply { layoutManager = LinearLayoutManager(ctx); adapter = this@SeriesPanel.adapter; itemAnimator = null; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = true; setPadding(0, dp(3), 0, dp(24)); isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(30)); isFocusable = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS }
        col.addView(epRv, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        row.addView(col, LinearLayout.LayoutParams(0, -1, 1f))
        addView(row, LayoutParams(-1, -1).apply { setMargins(dp(64), dp(32), dp(64), dp(10)) })
        msg.apply { setTextColor(Neon.MUTED); textSize = 17f; visibility = View.GONE }
        addView(msg, LayoutParams(-2, -2, Gravity.CENTER).apply { topMargin = dp(120); leftMargin = dp(160) })
        facts.text = listOfNotNull(m.year.ifEmpty { null }, if (m.rating > 0.1f) "★ " + String.format("%.1f", m.rating) else null, if (m.is4k) "4K" else null).joinToString("   ·   ")
    }

    private fun seasonName(n: Int) = if (n == 0) "Specials" else "Season $n"
    private fun selectSeason(n: Int) {
        season = n; eps = info?.seasons?.firstOrNull { it.first == n }?.second ?: emptyList()
        adapter.notifyDataSetChanged(); (epRv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0)
        for (i in 0 until chips.childCount) chips.getChildAt(i).isSelected = (chips.getChildAt(i).tag == n)
    }

    fun bind(si: SeriesInfo?) {
        if (si == null) { plot.text = "Details are unavailable right now."; return }
        info = si
        plot.text = si.plot.ifEmpty { "No description from the provider." }
        val n = si.seasons.size; val total = si.flat.size
        facts.text = listOfNotNull(m.year.ifEmpty { null }, if (m.rating > 0.1f) "★ " + String.format("%.1f", m.rating) else null, si.genre.takeIf { it.isNotEmpty() }?.split(',')?.take(2)?.joinToString(", ") { it.trim() },
            if (n > 0) "$n season${if (n > 1) "s" else ""}" else null, if (total > 0) "$total episodes" else null, if (m.is4k) "4K" else null).joinToString("   ·   ")
        val p = listOfNotNull(si.cast.takeIf { it.isNotEmpty() }?.let { "Cast  $it" }).joinToString("")
        if (p.isNotEmpty()) { people.text = p; people.visibility = View.VISIBLE }
        if (total == 0) { msg.text = "The provider lists no episodes for this show"; msg.visibility = View.VISIBLE; return }
        val up = ser.upNext(si, m.id); upId = up?.first?.id; upKind = up?.second ?: ""
        season = up?.first?.season ?: si.seasons.first().first
        chips.removeAllViews()
        if (n > 1) {
            chipScroll.visibility = View.VISIBLE
            for ((sn, _) in si.seasons) {
                val chip = TextView(context).apply {
                    text = seasonName(sn); setTextColor(Neon.TEXT); textSize = 15f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setPadding(dp(18), dp(8), dp(18), dp(8)); isFocusable = true; isClickable = true; tag = sn
                    background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), ring(Color.parseColor("#172863"), Neon.YELLOW, 2.5f, 20))
                        addState(intArrayOf(android.R.attr.state_selected), ring(Color.parseColor("#142053"), Neon.withAlpha(Neon.CYAN, 190), 1.5f, 20))
                        addState(intArrayOf(), ring(Neon.withAlpha(Color.parseColor("#101A48"), 150), Color.TRANSPARENT, 0f, 20))
                    }
                    setOnFocusChangeListener { _, f -> if (f && season != sn) selectSeason(sn) }
                    setOnClickListener { epRv.post { epRv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() } }
                }
                chips.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            }
        }
        selectSeason(season)
        val idx = eps.indexOfFirst { it.id == upId }.coerceAtLeast(0)
        (epRv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 0)
        epRv.post { epRv.post { epRv.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus() } }
    }
}
