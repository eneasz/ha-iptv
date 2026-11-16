package pl.home.channeldeck

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Tracks
import coil.dispose
import coil.load
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private lateinit var repo: Repo

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var overlay: LinearLayout
    private lateinit var hint: TextView
    private lateinit var banner: TextView
    private lateinit var status: TextView
    private lateinit var bufferOrb: OrbView
    /** While the status line is just "…" (buffering), show the splash orb, small, instead of text. */
    private val dotsTick: Runnable = object : Runnable {
        override fun run() {
            val buffering = status.visibility == View.VISIBLE && status.text.toString() == "…"
            if (buffering) { if (bufferOrb.visibility != View.VISIBLE) { bufferOrb.visibility = View.VISIBLE; bufferOrb.bringToFront() }; status.alpha = 0f }
            else { if (bufferOrb.visibility == View.VISIBLE) bufferOrb.visibility = View.GONE; status.alpha = 1f }
            status.postDelayed(this, 120)
        }
    }
    private lateinit var groupRv: RecyclerView
    private lateinit var regionRv: RecyclerView
    private lateinit var regionAdapter: RowAdapter<Region>
    private var browseRegion = ""
    private var moveRegion = false
    private var moveSnapshotR: List<Region> = emptyList()
    private val regionGroup = HashMap<String, String>()   // last group viewed per region
    private lateinit var chanRv: RecyclerView
    private lateinit var groupAdapter: RowAdapter<Group>
    private lateinit var chanAdapter: RowAdapter<Channel>
    private var player: ExoPlayer? = null

    private var browseGroup: String = ""          // group whose channels are listed
    private var playing: Channel? = null
    private var triedAltFormat = false
    private var netRetries = 0
    private var sampleJob: Job? = null
    private var picture = "ok"          // ok | black | frozen (sampled from the video surface)
    private var recoveries = 0          // automatic restarts of the current channel after a stall/freeze
    private var retryJob: Job? = null
    private var movingIdx = -1
    private var moveSnapshot: List<Group> = emptyList()
    private var dataReady = false
    private lateinit var splash: SplashView
    private lateinit var vod: VodRepo
    private lateinit var moviesView: MoviesView
    private lateinit var seriesView: MoviesView
    private lateinit var ser: SeriesRepo
    private lateinit var curRepo: VodRepo; private lateinit var curView: MoviesView; private lateinit var playRepo: VodRepo
    private lateinit var vodBar: VodBar
    private var vodPlaying: Movie? = null
    private var vodJob: Job? = null
    private var vodBarJob: Job? = null
    private var vodRetries = 0
    private var vodLoading = false
    private lateinit var home: HomeView
    private var settingsPanel: SettingsPanel? = null
    private lateinit var updater: Updater
    private var updateOffered = false
    private var updating = false
    private var splashAt = 0L
    private var hideJob: Job? = null
    private var bannerJob: Job? = null
    private lateinit var infoPanel: InfoPanel
    private lateinit var detailPanel: InfoPanel
    private var infoJob: Job? = null
    private var epgJob: Job? = null
    private var detailJob: Job? = null
    private var measureJob: Job? = null
    private var tech: Tech? = null
    private var measuring = false
    private var playEpg: List<Prog>? = null
    private var playEpgLoading = false
    private var detailCh: Channel? = null
    private var apiServer: ApiServer? = null
    private var playCtx = ""                    // group (or favourites) the playing channel was chosen from; zap stays inside it
    private var stopped = false
    private var resumed = false
    private var lastError: String? = null
    private var errId = ""
    private var pendingCmd: JSONObject? = null  // command that arrived before the channel list was ready
    private var surveyJob: Job? = null
    private var survey: Triple<String, Int, Int>? = null   // group id, done, total
    private var boundaryJob: Job? = null
    private val stateLock = Object()
    private var stateVer = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- lifecycle
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)
        repo = Repo(prefs, filesDir)
        vod = VodRepo(this, prefs); ser = SeriesRepo(this, prefs); curRepo = vod; playRepo = vod
        vod.warmProgress(); ser.warmProgress()
        prefs.sync.onChange = { changed() }
        prefs.sync.onRemote = { keys -> handleRemote(keys) }
        buildUi()
        applyIntentConfig(intent)
        apiServer = ApiServer(8765, apiHost).also { it.start() }
        advertise()
        startFlow()
        handleIntentCmd(intent)
        updater = Updater(this)
        scope.launch {   // quiet background check shortly after start; never interrupts playback
            kotlinx.coroutines.delay(25_000)
            if (updater.dueForAutoCheck()) try { updater.check()?.let { announceUpdate(it) } } catch (e: Exception) { }
        }
    }

    // ---------------------------------------------------------------- self update
    /** Found by the background check: ask on Home/Settings, otherwise only a short banner so a running channel is not covered. */
    private fun announceUpdate(r: Updater.Release) {
        if (updateOffered || updating) return
        if ((::home.isInitialized && home.visibility == View.VISIBLE) || settingsPanel != null) offerUpdate(r)
        else flashBanner("Update ${r.version} available  ·  Home > Settings > Software update")
    }

    private fun offerUpdate(r: Updater.Release) {
        updateOffered = true
        val notes = r.notes.lines().filter { it.isNotBlank() }.take(8).joinToString("\n") { it.replace("**", "") }.take(700)
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Update available: ${r.version}")
            .setMessage("You have ${updater.installed}.\n\n$notes")
            .setPositiveButton("Install now") { _, _ -> startUpdate(r) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startUpdate(r: Updater.Release, status: (String) -> Unit = { flashBanner(it) }) {
        if (updating) return
        if (!updater.mayInstall()) { status("Allow “Install unknown apps” for HA IPTV on the screen that opened, press BACK, then try again"); return }
        updating = true
        scope.launch {
            try {
                status("Downloading ${r.version}…")
                var shown = -10
                val f = updater.download(r) { p -> if (p >= shown + 10) { shown = p; scope.launch { status("Downloading ${r.version}…  $p%") } } }
                status("Installing ${r.version} – confirm on the screen that appears")
                updater.install(f) { err -> scope.launch { updating = false; status(err ?: "Updated") } }
            } catch (e: Exception) { updating = false; status("Update failed: ${e.message}") }
        }
    }

    override fun onResume() { super.onResume(); resumed = true; changed() }
    override fun onPause() { super.onPause(); resumed = false; changed() }

    /** adb am start -n pl.home.channeldeck/.MainActivity --es server http://h:p --es user U --es pass P */
    private fun applyIntentConfig(i: android.content.Intent?): Boolean {
        i?.getStringExtra("token")?.let { prefs.token = it; i.removeExtra("token") }
        val s = i?.getStringExtra("server") ?: return false
        val u = i.getStringExtra("user") ?: return false
        val p = i.getStringExtra("pass") ?: return false
        prefs.server = s; prefs.user = u; prefs.pass = p
        i.removeExtra("pass"); return true
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent); setIntent(intent)
        if (applyIntentConfig(intent)) { dataReady = false; playing = null; startFlow() }
        handleIntentCmd(intent)
    }

    /** am start ... --es cmd play --es query "News One" [--es group PL] | cmd zap --ei delta 1 | cmd group --es group ... | cmd stop|pause|resume|info|home */
    private fun handleIntentCmd(i: android.content.Intent?) {
        val cmd = i?.getStringExtra("cmd") ?: return
        val j = JSONObject().put("cmd", cmd)
        for (k in listOf("channel_id", "query", "group", "region", "id")) i.getStringExtra(k)?.let { j.put(k, it) }
        if (i.hasExtra("delta")) j.put("delta", i.getIntExtra("delta", 1))
        if (i.hasExtra("play")) j.put("play", i.getBooleanExtra("play", false))
        if (i.hasExtra("seconds")) j.put("seconds", i.getIntExtra("seconds", 6))
        i.removeExtra("cmd")
        if (dataReady) runCommand(j) else pendingCmd = j
    }

    override fun onStop() { super.onStop(); player?.pause() }
    override fun onStart() { super.onStart(); player?.play() }
    override fun onDestroy() { try { nsdListener?.let { nsd?.unregisterService(it) } } catch (e: Exception) { }; apiServer?.stop(); scope.cancel(); player?.release(); player = null; super.onDestroy() }

    private fun startFlow() {
        if (!prefs.configured) { showSetup(); return }
        loadData()
    }

    // ---------------------------------------------------------------- UI
    private fun rowBg(): StateListDrawable {
        fun rr(c: Int, stroke: Int, w: Float) = GradientDrawable().apply { setColor(c); cornerRadius = dp(14).toFloat(); setStroke((w * resources.displayMetrics.density).toInt(), stroke) }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rr(Color.parseColor("#172863"), Neon.YELLOW, 2.5f))
            addState(intArrayOf(android.R.attr.state_selected), rr(Color.parseColor("#142053"), Neon.withAlpha(Neon.CYAN, 170), 1.5f))
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        playerView = layoutInflater.inflate(R.layout.player_view, null) as PlayerView
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        banner = TextView(this).apply {
            setTextColor(Neon.TEXT); textSize = 22f; setPadding(dp(22), dp(12), dp(22), dp(12))
            background = GradientDrawable().apply { setColor(Color.parseColor("#F00B1330")); cornerRadius = dp(26).toFloat(); setStroke(dp(2), Neon.withAlpha(Neon.YELLOW, 200)) }
            visibility = View.GONE
        }
        root.addView(banner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(40), dp(30), 0, 0) })

        infoPanel = InfoPanel(this).apply { visibility = View.GONE }
        root.addView(infoPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(40), 0, dp(40), dp(36)) })

        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 24f; gravity = Gravity.CENTER
            setPadding(dp(60), dp(20), dp(60), dp(20))
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        bufferOrb = OrbView(this).apply { energy = 0.7f; glow = 1f; visibility = View.GONE }
        root.addView(bufferOrb, FrameLayout.LayoutParams(dp(110), dp(110), Gravity.CENTER))
        // buffering: the splash orb, small (see dotsTick)
        status.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { status.post(dotsTick) }
            override fun onViewDetachedFromWindow(v: View) { status.removeCallbacks(dotsTick) }
        })

        // ----- overlay: groups | channels
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#FA0B1330"), Color.parseColor("#FC04060F"))); visibility = View.GONE
        }
        val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        regionAdapter = RowAdapter(
            label = { if (it.id == Repo.FAV) "Favs" else it.name.removePrefix("● ").removePrefix("🔍 ") }, glyph = { when (it.id) { Repo.LIVE -> "live"; Repo.SEARCH -> "find"; Repo.FAV -> "star"; else -> null } },
            sub = { if (it.id == Repo.SEARCH) (if (repo.searchRes.isEmpty()) "" else repo.searchRes.size.toString()) else if (it.id == Repo.LIVE || it.id == Repo.FAV) it.channels.toString() else it.groups.toString() },
            onClick = { onRegionClick(it) }, onLong = { startMove(it, true) }, onFocus = { onRegionFocus(it) })
        regionRv = rv(regionAdapter)
        groupAdapter = RowAdapter(
            label = { repo.shortName(it).removePrefix("★ ") }, glyph = { if (it.id == Repo.FAV) "star" else null }, sub = { it.count.toString() },
            onClick = { onGroupClick(it) }, onLong = { startMove(it) }, onFocus = { onGroupFocus(it) })
        chanAdapter = RowAdapter(
            label = { it.name }, sub = { it.num.toString() }, fav = { repo.isFav(it.id) },
            onClick = { onChannelClick(it) }, onLong = { favHold }, onFocus = { onChannelFocus(it) },
            icon = { it.icon }, tag = { offTag(it) ?: Quality.rowTag(it.name, prefs.measured(it.id)) })
        groupRv = rv(groupAdapter)
        chanRv = rv(chanAdapter)
        cols.addView(titled("REGIONS", regionRv), LinearLayout.LayoutParams(0, -1, 0.20f))
        cols.addView(View(this).apply { setBackgroundColor(Neon.withAlpha(Neon.BLUE, 70)) }, LinearLayout.LayoutParams(dp(1), -1))
        cols.addView(titled("GROUPS", groupRv), LinearLayout.LayoutParams(0, -1, 0.33f))
        cols.addView(View(this).apply { setBackgroundColor(Neon.withAlpha(Neon.BLUE, 70)) }, LinearLayout.LayoutParams(dp(1), -1))
        cols.addView(titled("CHANNELS", chanRv), LinearLayout.LayoutParams(0, -1, 0.47f))
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, 0) }
        head.addView(OrbView(this).apply { energy = 0.2f }, LinearLayout.LayoutParams(dp(48), dp(48)))
        head.addView(TextView(this).apply { text = "Live TV"; setTextColor(Neon.TEXT); textSize = 26f; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); setPadding(dp(14), 0, 0, 0) })
        overlay.addView(head, LinearLayout.LayoutParams(-1, -2))
        val colsBox = FrameLayout(this)
        colsBox.addView(cols, FrameLayout.LayoutParams(-1, -1))
        colsBox.addView(View(this).apply {
            isClickable = false; isFocusable = false
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.parseColor("#F004060F")))
        }, FrameLayout.LayoutParams(-1, dp(40), Gravity.BOTTOM))
        overlay.addView(colsBox, LinearLayout.LayoutParams(-1, 0, 1f))
        detailPanel = InfoPanel(this)
        overlay.addView(detailPanel, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(6)) })
        hint = TextView(this).apply {
            setTextColor(Neon.MUTED); textSize = 15f
            setPadding(0, dp(8), 0, dp(6))
        }
        overlay.addView(hint, LinearLayout.LayoutParams(-1, -2))
        overlay.setPadding(dp(48), dp(16), dp(48), dp(8))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setHint()
        home = HomeView(this, onLive = { openLive() }, onResume = { resumeFromHome() },
            onMovies = { openMovies() }, onSeries = { openSeries() }, onSettings = { openSettings() }, onOther = { continueOther() }).apply { visibility = View.GONE }
        root.addView(home, FrameLayout.LayoutParams(-1, -1))
        fun mkView(repo: VodRepo) = MoviesView(this, repo, object : MoviesView.Cb {
            override fun play(m: Movie, fromStart: Boolean) { playVod(m, fromStart) }
            override fun search() { askText("Search ${repo.label.lowercase()}", "${repo.unit} title   ·   press OK to type", repo.searchQ, "Find ${repo.unit} across all categories") { q -> searchMovies(q) } }
            override fun close() { closeMovies() }
            override fun loadInfo(m: Movie, done: (VodInfo?) -> Unit) { scope.launch { done(repo.info(m)) } }
            override fun loadSeries(m: Movie, done: (SeriesInfo?) -> Unit) { scope.launch { done(ser.seriesInfo(m)) } }
        }).apply { visibility = View.GONE }
        moviesView = mkView(vod); seriesView = mkView(ser); curView = moviesView
        root.addView(moviesView, FrameLayout.LayoutParams(-1, -1))
        root.addView(seriesView, FrameLayout.LayoutParams(-1, -1))
        vodBar = VodBar(this).apply { visibility = View.GONE }
        root.addView(vodBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        splash = SplashView(this); splashAt = android.os.SystemClock.uptimeMillis()
        root.addView(splash, FrameLayout.LayoutParams(-1, -1))
    }

    private fun titled(title: String, rv: RecyclerView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = title; setTextColor(Neon.CYAN); textSize = 13f; letterSpacing = 0.2f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(dp(20), dp(16), dp(20), dp(8))
        })
        addView(rv, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun rv(a: RecyclerView.Adapter<*>) = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(context); adapter = a; itemAnimator = null
        setPadding(dp(8), 0, dp(8), dp(8)); clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isFocusable = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    private fun setHint(moving: Boolean = false) {
        val s = if (moving) "MOVING GROUP   ▲▼ move   ◀ top   ▶ bottom   OK save   BACK cancel"
        else "OK open  ·  hold OK: favourite / move  ·  PLAY: check live  ·  BACK close"
        val sp = android.text.SpannableString(s)
        for (k in listOf("OK", "PLAY", "BACK")) { var i = s.indexOf(k)
            while (i >= 0) { sp.setSpan(android.text.style.ForegroundColorSpan(Neon.CYAN), i, i + k.length, 0)
                sp.setSpan(android.text.style.StyleSpan(Typeface.BOLD), i, i + k.length, 0); i = s.indexOf(k, i + k.length) } }
        hint.text = sp
    }

    // ---------------------------------------------------------------- setup (first run)
    private val pairing = Pairing()
    private var pairPanel: View? = null
    private fun showPairRequest(r: Pairing.Req) {
        pairPanel?.let { root.removeView(it) }
        fun close(approve: Boolean) {
            pairing.decide(r, approve, prefs.token)
            if (approve) { prefs.paired = true; prefs.pairOpenUntil = 0L; if (setupForm != null && !prefs.configured) setupApproved() else flashBanner("Home Assistant connected") }
            pairPanel?.let { root.removeView(it) }; pairPanel = null
        }
        val box = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0A1018")); isClickable = true; clipChildren = false }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false; clipToPadding = false
            background = GradientDrawable().apply { setColor(Color.parseColor("#141C27")); cornerRadius = dp(24).toFloat() }
            setPadding(dp(70), dp(34), dp(70), dp(40))
        }
        fun t(x: String, sz: Float, c: Int, bold: Boolean = false) = TextView(this).apply { text = x; textSize = sz; setTextColor(c); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6)); if (bold) setTypeface(typeface, Typeface.BOLD) }
        card.addView(t("Home Assistant wants to connect", 28f, Color.WHITE))
        card.addView(t(r.code.substring(0, 2) + "   " + r.code.substring(2), 110f, Neon.CYAN).apply { typeface = Typeface.MONOSPACE })
        card.addView(t("Approve only if Home Assistant shows the same code.", 26f, Color.WHITE, true))
        card.addView(t("This code is valid for 2 minutes.", 19f, Color.parseColor("#B8C0CC")))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(22), 0, 0); clipChildren = false; clipToPadding = false }
        fun big(label: String, fill: Int, edge: Int) = Button(this).apply { text = label; textSize = 26f; setPadding(dp(10), dp(14), dp(10), dp(14)); styleButton(this, fill, edge) }
        val no = big("✕  Deny", GREY, Color.parseColor("#5A6A80")).apply { setOnClickListener { close(false) } }
        val ok = big("Approve (2)", Color.parseColor("#1F7A4D"), Color.parseColor("#3FBF86")).apply { isEnabled = false; setOnClickListener { close(true) } }
        row.addView(no, LinearLayout.LayoutParams(dp(200), dp(66)).apply { rightMargin = dp(36) }); row.addView(ok, LinearLayout.LayoutParams(dp(200), dp(66)))
        card.addView(row)
        box.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        pairPanel = box; root.addView(box, FrameLayout.LayoutParams(-1, -1)); no.requestFocus()
        scope.launch {                                              // a stray OK press must not approve: Approve unlocks after 1.5 s
            kotlinx.coroutines.delay(750); ok.text = "Approve (1)"
            kotlinx.coroutines.delay(750); ok.text = "✓  Approve"; ok.isEnabled = true
        }
        scope.launch { kotlinx.coroutines.delay(Pairing.TTL); if (pairPanel === box) { pairing.poll(r.id, r.ha); pairPanel?.let { root.removeView(it) }; pairPanel = null } }
    }

    private var setupForm: FrameLayout? = null
    private var setupJob: Job? = null
    private val dim = Color.parseColor("#C0C8D4")

    private fun showSetup() { status.visibility = View.GONE
        val f = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0E141C")); setPadding(dp(96), dp(54), dp(96), dp(54)); clipChildren = false; clipToPadding = false }
        setupForm = f; root.addView(f, FrameLayout.LayoutParams(-1, -1)); setupChoice() }

    private fun txt(t: String, sz: Float, c: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sz; setTextColor(c); if (bold) setTypeface(typeface, Typeface.BOLD) }
    private fun screen(scroll: Boolean = false, build: LinearLayout.() -> Unit): LinearLayout {
        setupJob?.cancel()
        val f = setupForm ?: return LinearLayout(this)
        f.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; build() }
        if (scroll) f.addView(android.widget.ScrollView(this).apply { isFillViewport = true; clipChildren = false; clipToPadding = false; isVerticalScrollBarEnabled = false
            addView(col, FrameLayout.LayoutParams(-1, -2)) }, FrameLayout.LayoutParams(-1, -1))
        else f.addView(col, FrameLayout.LayoutParams(-1, -1))
        return col
    }
    /** Rounded box with a thick yellow focus ring (and a dark gap) – used by every focusable thing in the setup flow. Colours stay fixed; only ring + size signal focus. */
    private fun ringBg(fill: Int, edge: Int, locked: Boolean = false): StateListDrawable {
        fun box(c: Int, stroke: Int, w: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(12).toFloat(); if (w > 0) setStroke(dp(w), stroke) }
        val ring = android.graphics.drawable.LayerDrawable(arrayOf(box(Neon.YELLOW, 0, 0), box(Color.parseColor("#0D141C"), 0, 0), box(fill, edge, 2))).apply {
            setLayerInset(1, dp(4), dp(4), dp(4), dp(4)); setLayerInset(2, dp(6), dp(6), dp(6), dp(6)) }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), ring)
            addState(intArrayOf(-android.R.attr.state_enabled), box(Color.parseColor("#3A4656"), Color.parseColor("#4A566B"), 2))
            addState(intArrayOf(), box(fill, edge, 2))
        }
    }
    private fun styleButton(b: Button, fill: Int, edge: Int) {
        b.background = ringBg(fill, edge); b.isAllCaps = false; b.stateListAnimator = null
        b.setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(Color.parseColor("#99FFFFFF"), Color.WHITE)))
        b.setOnFocusChangeListener { v, f -> v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(120).start() }
    }
    private val BLUE = Color.parseColor("#1F4FD8"); private val GREY = Color.parseColor("#2A3442")
    private fun btn(label: String, primary: Boolean, go: () -> Unit) = Button(this).apply {
        text = label; textSize = if (primary) 24f else 18f; setOnClickListener { go() }
        setPadding(dp(34), dp(14), dp(34), dp(14))
        if (primary) styleButton(this, BLUE, Color.parseColor("#6D8CFF")) else styleButton(this, GREY, Color.parseColor("#5A6A80"))
    }

    /** 1: how do you want to set this TV up? */
    private fun setupChoice() { screen {
        addView(txt("Set up this TV", 38f, bold = true))
        addView(txt("How do you want to connect this TV?", 24f, dim).apply { setPadding(0, dp(6), 0, dp(36)) })
        val ha = btn("Set up with Home Assistant", true) { setupWaiting() }
        addView(ha, LinearLayout.LayoutParams(-2, -2))
        addView(txt("Recommended · no typing on the TV", 21f, Color.parseColor("#D5DBE5")).apply { setPadding(dp(6), dp(8), 0, dp(40)) })
        addView(btn("Enter provider details on this TV", false) { setupManual() }, LinearLayout.LayoutParams(-2, -2))
        addView(txt("This TV is called “${prefs.sync.deviceName}”.", 21f, Color.parseColor("#C9D1DC")).apply { setPadding(0, dp(40), 0, 0) })
        ha.requestFocus()
    } }

    /** 2: Home Assistant path – what to do over there. Hints appear if nothing happens. */
    private fun setupWaiting() { var hint: TextView? = null
        screen {
            addView(LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                val dot = txt("●", 30f, Neon.CYAN).apply { setPadding(0, 0, dp(16), 0) }
                android.animation.ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.25f).apply { duration = 900; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE; start() }
                addView(dot); addView(txt("Waiting for Home Assistant", 34f, Neon.CYAN, true)) })
            addView(txt("1.  On your phone or computer, open Home Assistant.\n2.  Go to Settings, then Devices & services.\n3.  Find “HA IPTV – ${prefs.sync.deviceName}” and click Configure.", 24f).apply { setLineSpacing(0f, 1.25f); setPadding(0, dp(24), 0, dp(14)) })
            addView(txt("When a code appears here and in Home Assistant, check that they match, then press Approve with your remote.", 21f, dim))
            hint = txt("", 20f, Neon.YELLOW).apply { setPadding(0, dp(22), 0, 0) }; addView(hint)
            addView(View(this@MainActivity), LinearLayout.LayoutParams(1, 0, 1f))
            addView(txt("Details: ${localIp() ?: "no network"} · port 8765", 16f, dim).apply { setPadding(0, 0, 0, dp(14)) })
            val back = btn("Back", false) { setupChoice() }; addView(back, LinearLayout.LayoutParams(-2, -2)); back.requestFocus()
        }
        val ip = localIp() ?: "this TV's IP"
        setupJob = scope.launch {
            kotlinx.coroutines.delay(60_000); hint?.text = "Not seeing it? Make sure Home Assistant is on the same Wi-Fi as this TV."
            kotlinx.coroutines.delay(120_000); hint?.text = "Still nothing? In Home Assistant choose Add integration › HA IPTV and enter the IP $ip. Leave the token empty."
        }
    }

    /** 3: typing the provider login with the remote – kept as small as possible. */
    private fun setupManual() { screen(scroll = true) {
        addView(btn("‹ Back", false) { setupChoice() }, LinearLayout.LayoutParams(-2, -2))
        addView(txt("Enter provider details", 30f, bold = true).apply { setPadding(0, dp(6), 0, dp(0)) })
        addView(txt("Your IPTV provider's server address, username and password.", 19f, dim).apply { setPadding(0, 0, 0, dp(4)) })
        fun field(label: String, hint: String, pw: Boolean = false): EditText {
            addView(txt(label, 19f, Color.parseColor("#D5DBE5")).apply { setPadding(dp(4), dp(4), 0, dp(1)) })
            return EditText(this@MainActivity).apply {
                this.hint = hint; setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#8A94A3")); textSize = 22f; setSingleLine()
                background = ringBg(Color.parseColor("#1B2430"), Color.parseColor("#3A4656")); setPadding(dp(16), dp(6), dp(16), dp(6))
                inputType = InputType.TYPE_CLASS_TEXT or (if (pw) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI)
                addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        val s = field("Server address", "http://host:port"); val u = field("Username", "username"); val p = field("Password", "password", true)
        addView(android.widget.CheckBox(this@MainActivity).apply { text = "Show password"; setTextColor(dim); textSize = 20f
            background = ringBg(Color.TRANSPARENT, Color.TRANSPARENT).also { }; setPadding(dp(10), dp(8), dp(14), dp(8))
            buttonTintList = android.content.res.ColorStateList.valueOf(Neon.CYAN)
            setOnCheckedChangeListener { _, on -> p.inputType = InputType.TYPE_CLASS_TEXT or (if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD); p.setSelection(p.text.length) } },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        val save = btn("Save", true) {
            prefs.server = s.text.toString().trim(); prefs.user = u.text.toString().trim(); prefs.pass = p.text.toString().trim()
            if (prefs.configured) finishSetup() }
        save.isEnabled = false
        val w = object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { save.isEnabled = s.text.isNotBlank() && u.text.isNotBlank() && p.text.isNotBlank() }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        }
        s.addTextChangedListener(w); u.addTextChangedListener(w); p.addTextChangedListener(w)
        addView(save, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        s.requestFocus()
    } }

    /** After Approve, while Home Assistant still asks for the name: the TV must not look stuck. */
    private fun setupApproved() { screen {
        addView(txt("Connected to Home Assistant", 38f, Neon.CYAN, true))
        addView(txt("Finish setting up on your phone or computer.\nIf Home Assistant asks for your provider login, type it there.\nThis screen updates by itself – you can put the remote down.", 24f, Color.WHITE).apply { setLineSpacing(0f, 1.25f); setPadding(0, dp(24), 0, dp(30)) })
        val manual = btn("Enter provider details on this TV instead", false) { setupManual() }
        addView(manual, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(18) })
        addView(btn("Cancel setup", false) {
            prefs.token = java.security.SecureRandom().let { r -> ByteArray(12).also { r.nextBytes(it) }.joinToString("") { "%02x".format(it) } }; prefs.paired = false
            setupChoice() }, LinearLayout.LayoutParams(-2, -2))
    } }

    private fun finishSetup() { setupJob?.cancel(); setupForm?.let { root.removeView(it) }; setupForm = null; loadData() }

    private fun localIp(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is java.net.Inet4Address }?.hostAddress
    } catch (e: Exception) { null }

    /** Announce this stick on the LAN so Home Assistant offers it for set-up by itself. */
    private var nsd: android.net.nsd.NsdManager? = null
    private var nsdListener: android.net.nsd.NsdManager.RegistrationListener? = null
    private fun advertise() {
        try {
            val info = android.net.nsd.NsdServiceInfo().apply {
                serviceName = "HA IPTV " + prefs.sync.deviceId.take(4); serviceType = "_haiptv._tcp."; port = 8765
                setAttribute("id", prefs.sync.deviceId); setAttribute("name", prefs.sync.deviceName); setAttribute("app", "0.6")
            }
            val l = object : android.net.nsd.NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: android.net.nsd.NsdServiceInfo) {}
                override fun onRegistrationFailed(i: android.net.nsd.NsdServiceInfo, e: Int) {}
                override fun onServiceUnregistered(i: android.net.nsd.NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: android.net.nsd.NsdServiceInfo, e: Int) {}
            }
            nsd = (getSystemService(NSD_SERVICE) as android.net.nsd.NsdManager).also { it.registerService(info, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, l) }
            nsdListener = l
        } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------- data
    private fun loadData() {
        splash.setStatus("Loading channels…")
        scope.launch {
            val cached = repo.loadCache()
            if (cached) applyData(firstTime = true)
            try {
                repo.refresh()
                applyData(firstTime = !cached)
            } catch (e: Exception) {
                if (!cached) splash.setStatus("Could not load channels\n${e.message}")
            }
        }
    }

    /** Rebuild the lists; on first call restore last group/channel and start playing. */
    private fun applyData(firstTime: Boolean) {
        if (repo.groups.isEmpty()) { splash.setStatus("No channels returned by provider"); return }
        dataReady = true
        refreshLists()
        if (firstTime) {
            val veryFirst = prefs.lastChannel.isEmpty()
            val gid = repo.groups.firstOrNull { it.id == prefs.lastGroup }?.id ?: repo.groups.first { !Repo.pseudo(it.id) }.id
            browseGroup = gid
            val last = repo.channel(prefs.lastChannel)?.takeIf { repo.inGroup(gid, it.id) }
            val start = last ?: repo.channels(gid).firstOrNull()
            showChannels(gid); playCtx = gid
            scope.launch(Dispatchers.Default) { repo.warm() }
            scope.launch { kotlinx.coroutines.delay(4000); if (!vod.ready) vod.loadCache(); if (!ser.ready) ser.loadCache(); updateHomeCounts() }
            val pc = pendingCmd; pendingCmd = null
            if (pc != null) {                                       // a command from HA plays straight away
                dismissSplash(); runCommand(pc)
                // a command that plays nothing (or fails) must never leave an empty screen behind
                scope.launch { kotlinx.coroutines.delay(2500); if (playing == null && vodPlaying == null && home.visibility != View.VISIBLE && overlay.visibility != View.VISIBLE && moviesView.visibility != View.VISIBLE && seriesView.visibility != View.VISIBLE && settingsPanel == null) showHome() }
            }
            else { val wait = (1900 - (android.os.SystemClock.uptimeMillis() - splashAt)).coerceAtLeast(0); scope.launch { kotlinx.coroutines.delay(wait); if (playing == null) showHome() else dismissSplash() } }
        } else {
            groupAdapter.activeIdx = repo.groups.indexOfFirst { it.id == browseGroup }
            showChannels(browseGroup)
        }
    }

    private fun showChannels(gid: String) {
        browseGroup = gid
        repo.groups.firstOrNull { it.id == gid }?.let { g ->
            regionGroup[g.region] = gid
            if (g.region != browseRegion) {
                browseRegion = g.region; groupAdapter.setAll(repo.groupsIn(g.region))
                val oldR = regionAdapter.activeIdx; val newR = regionAdapter.items.indexOfFirst { it.id == g.region }
                regionAdapter.activeIdx = newR
                if (oldR in regionAdapter.items.indices) regionAdapter.notifyItemChanged(oldR)
                if (newR >= 0 && newR != oldR) regionAdapter.notifyItemChanged(newR)
            }
        }
        val list = repo.channels(gid)
        chanAdapter.setAll(list)
        chanAdapter.activeIdx = list.indexOfFirst { it.id == playing?.id }
        // rebind just the two affected rows: a full notifyDataSetChanged() would reset D-pad focus
        val old = groupAdapter.activeIdx
        val idx = groupAdapter.items.indexOfFirst { it.id == gid }
        groupAdapter.activeIdx = idx
        if (old >= 0 && old < groupAdapter.items.size) groupAdapter.notifyItemChanged(old)
        if (idx >= 0 && idx != old) groupAdapter.notifyItemChanged(idx)
    }

    // ---------------------------------------------------------------- playback
    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(this, Mp2RenderersFactory(this))
        // this stick has 1.7 GB: the default buffer (up to ~190 MB) gets a 4K stream killed by the low-memory killer
        .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 40_000, 2_500, 5_000).setTargetBufferBytes(48 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
        .build().also { pl ->
            player = pl; playerView.player = pl
            pl.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (vodPlaying != null) { vodError(error); return }
                    val c2 = playing ?: return
                    // Network hiccups (provider allows 1 connection; a just-zapped stream may still be closing): retry with backoff first
                    if (error.errorCode in 2000..2999 && error.errorCode != PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND && netRetries < 4) {
                        val wait = longArrayOf(1500, 3000, 5000, 8000)[netRetries]; netRetries++
                        status.visibility = View.VISIBLE; status.text = "Connection problem – retrying ($netRetries/4)…"
                        retryJob?.cancel()
                        retryJob = scope.launch { kotlinx.coroutines.delay(wait); if (playing?.id == c2.id) play(c2, fmt = prefs.format, retry = true) }
                        return
                    }
                    if (!triedAltFormat) {
                        triedAltFormat = true
                        play(c2, fmt = if (prefs.format == "m3u8") "ts" else "m3u8")
                    } else { status.visibility = View.VISIBLE; status.text = "Can't play ${c2.name}\n${error.errorCodeName}"; lastError = error.errorCodeName; errId = c2.id; changed() }
                }
                override fun onTracksChanged(tracks: Tracks) { readTech(tracks) }
                override fun onIsPlayingChanged(isPlaying: Boolean) { changed() }
                override fun onPlaybackStateChanged(state: Int) {
                    changed()
                    if (state == Player.STATE_ENDED && vodPlaying != null) { vodFinished(); return }
                    if (state == Player.STATE_READY) {
                        status.visibility = View.GONE
                        val cur = playing
                        if (cur != null && triedAltFormat) { prefs.format = if (prefs.format == "m3u8") "ts" else "m3u8"; triedAltFormat = false }
                    } else if (state == Player.STATE_BUFFERING) { status.visibility = View.VISIBLE; status.text = "…" }
                }
            })
    }

    private fun play(c: Channel, banner: Boolean = false, fmt: String = prefs.format, ctx: String? = null, retry: Boolean = false) {
        playing = c; stopped = false; lastError = null
        dismissSplash(); home.visibility = View.GONE; settingsPanel?.let { root.removeView(it); settingsPanel = null }
        if (ctx != null) playCtx = ctx else if (playCtx.isEmpty() || !repo.inGroup(playCtx, c.id)) playCtx = c.groupId
        tech = null; measuring = true; playEpg = null; playEpgLoading = c.epgId.isNotEmpty()
        measureJob?.cancel(); epgJob?.cancel()
        prefs.lastChannel = c.id; prefs.lastGroup = if (playCtx == Repo.SEARCH || playCtx == Repo.LIVE) c.groupId else playCtx
        if (fmt == prefs.format && !retry) { triedAltFormat = false; netRetries = 0; retryJob?.cancel() }
        if (!retry) recoveries = 0
        sampleJob?.cancel(); if (picture != "ok") { picture = "ok"; changed() }
        val p = ensurePlayer()
        p.setMediaItem(MediaItem.fromUri(repo.streamUrl(c, fmt))); p.prepare(); p.playWhenReady = true
        chanAdapter.activeIdx = repo.channels(browseGroup).indexOfFirst { it.id == c.id }
        chanAdapter.notifyDataSetChanged()
        if (banner) showInfo()
        playSince = System.currentTimeMillis(); publishNp()
        changed()
        epgJob = scope.launch {
            val l = try { repo.epg(c) } catch (e: Exception) { emptyList() }
            if (playing?.id != c.id) return@launch
            playEpg = l; playEpgLoading = false; refreshInfo()
        }
    }

    // ---------------------------------------------------------------- info card
    private fun groupName(c: Channel) = repo.groups.firstOrNull { it.id == c.groupId }?.name ?: ""
    private fun stateOf(c: Channel, l: List<Prog>?, loading: Boolean) = when {
        loading -> EpgState.LOADING
        c.epgId.isEmpty() -> EpgState.NO_GUIDE
        l.isNullOrEmpty() -> EpgState.EMPTY
        else -> EpgState.OK
    }

    private fun refreshInfo() {
        val c = playing ?: return
        infoPanel.bind(c, groupName(c), stateOf(c, playEpg, playEpgLoading), playEpg ?: emptyList(), tech, measuring)
        if (overlay.visibility == View.VISIBLE && detailCh?.id == c.id) bindDetail(c)
        changed()
        val now = System.currentTimeMillis() / 1000
        val next = playEpg?.flatMap { listOf(it.start, it.stop) }?.filter { it > now }?.minOrNull()
        boundaryJob?.cancel()
        if (next != null) boundaryJob = scope.launch { kotlinx.coroutines.delay((next - now + 1) * 1000); refreshInfo() }
    }

    private fun showInfo() {
        refreshInfo(); infoPanel.visibility = View.VISIBLE
        infoJob?.cancel(); infoJob = scope.launch { kotlinx.coroutines.delay(9000); infoPanel.visibility = View.GONE }
    }

    private fun bindDetail(c: Channel) {
        detailCh = c
        if (c.id == playing?.id) detailPanel.bind(c, groupName(c), stateOf(c, playEpg, playEpgLoading), playEpg ?: emptyList(), tech, measuring)
        else {
            val m = prefs.measured(c.id)
            val t = m?.let { Tech(it[0], it[1], it[2], "", "", 0, it[1]) }
            detailPanel.bind(c, groupName(c), EpgState.LOADING.takeIf { c.epgId.isNotEmpty() && detailEpg[c.id] == null } ?: stateOf(c, detailEpg[c.id], false),
                detailEpg[c.id] ?: emptyList(), t, false)
        }
    }
    private val detailEpg = HashMap<String, List<Prog>>()

    private fun onChannelFocus(pos: Int) {
        val c = chanAdapter.items.getOrNull(pos) ?: return
        bindDetail(c)
        detailJob?.cancel()
        if (c.epgId.isNotEmpty() && c.id != playing?.id && detailEpg[c.id] == null) detailJob = scope.launch {
            kotlinx.coroutines.delay(350)                       // debounce while scrolling
            val l = try { repo.epg(c) } catch (e: Exception) { emptyList() } ?: emptyList()
            detailEpg[c.id] = l
            if (detailCh?.id == c.id) bindDetail(c)
        }
    }

    // ---------------------------------------------------------------- stream measurement
    private fun readTech(tr: Tracks) {
        var vf: androidx.media3.common.Format? = null; var af: androidx.media3.common.Format? = null; var maxH = 0
        for (g in tr.groups) for (i in 0 until g.length) {
            if (g.type == C.TRACK_TYPE_VIDEO) { maxH = maxOf(maxH, g.getTrackFormat(i).height); if (g.isTrackSelected(i)) vf = g.getTrackFormat(i) }
            if (g.type == C.TRACK_TYPE_AUDIO && g.isTrackSelected(i)) af = g.getTrackFormat(i)
        }
        val v = vf ?: return
        if (v.width <= 0 || v.height <= 0) return
        val declared = if (v.frameRate > 0) Math.round(v.frameRate) else 0
        tech = Tech(v.width, v.height, declared, Quality.vcodec(v.sampleMimeType, v.codecs), Quality.acodec(af?.sampleMimeType), af?.channelCount?.takeIf { it > 0 } ?: 0, maxH)
        val c = playing ?: return
        prefs.setMeasured(c.id, v.width, v.height, declared)
        chanAdapter.notifyDataSetChanged()
        refreshInfo()
        startFpsMeasure(c)
        startPictureWatch(c)
    }

    /**
     * Detects a channel that plays but shows nothing useful: samples a tiny copy of the video every 2 s.
     * Black = every pixel almost black for ~20 s; frozen = identical picture for ~30 s (a still slate is also reported, it is still worth knowing).
     */
    private fun startPictureWatch(c: Channel) {
        sampleJob?.cancel()
        sampleJob = scope.launch {
            val tv = playerView.videoSurfaceView as? android.view.TextureView ?: return@launch
            var dark = 0; var same = 0; var last = 0L; var stuck = 0; var lastPos = -1L; var good = 0; var buffering = 0
            while (true) {
                kotlinx.coroutines.delay(2000)
                val p = player ?: return@launch
                if (playing?.id != c.id) return@launch
                if (!p.isPlaying) {
                    dark = 0; same = 0; stuck = 0
                    // stuck start: the player keeps "buffering" (decoder never starts) -> retry on the other stream format
                    buffering = if (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING) buffering + 1 else 0
                    if (buffering >= 6) { if (recover(c, "not starting", alt = true)) return@launch else buffering = 0 }
                    continue
                }
                buffering = 0
                // anti-freeze: playback "running" but the position does not advance (decoder/audio stall) -> restart the stream
                val pos = p.currentPosition
                stuck = if (pos == lastPos) stuck + 1 else 0; lastPos = pos
                if (stuck >= 4) { if (recover(c, "stalled")) return@launch else stuck = 0 }
                val bmp = try { tv.getBitmap(48, 27) } catch (e: Exception) { null } ?: continue
                val px = IntArray(48 * 27); bmp.getPixels(px, 0, 48, 0, 0, 48, 27); bmp.recycle()
                var maxL = 0; var h = 1125899906842597L
                for (v in px) { val l = ((v shr 16 and 255) * 3 + (v shr 8 and 255) * 6 + (v and 255)) / 10; if (l > maxL) maxL = l; h = 31 * h + (v and 0xF8F8F8) }
                dark = if (maxL < 24) dark + 1 else 0
                good = if (maxL >= 24) good + 1 else 0
                if (good == 3) prefs.setPicture(c.id, "ok")
                if (dark == 10) { prefs.setPicture(c.id, "black"); chanAdapter.notifyDataSetChanged() }
                same = if (h == last) same + 1 else 0; last = h
                if (same >= 8 && dark == 0 && recoveries < 2) { if (recover(c, "frozen")) return@launch }
                val now = when { dark >= 10 -> "black"; same >= 8 && dark == 0 -> "frozen"; else -> "ok" }
                if (now != picture) {
                    picture = now; changed()
                    if (now == "black") flashBanner("⚠ Black picture – the channel may be off air")
                    if (now == "frozen") flashBanner("⚠ Picture frozen")
                }
            }
        }
    }

    /** Restart the current channel after a stall/freeze (max 3 times per channel, then the problem is reported instead). */
    private fun recover(c: Channel, why: String, alt: Boolean = false): Boolean {
        if (playing?.id != c.id || recoveries >= 3) return false
        recoveries++
        flashBanner("Stream $why – restarting ($recoveries/3)")
        // a stream that never starts is retried on the other format (ts <-> m3u8); only the first try, then back to the usual one
        val fmt = if (alt && recoveries == 1) (if (prefs.format == "m3u8") "ts" else "m3u8") else prefs.format
        play(c, fmt = fmt, retry = true)
        return true
    }

    /** Frame rate is often not declared in TS/HLS: count rendered frames over a few seconds. */
    private fun startFpsMeasure(c: Channel) {
        measureJob?.cancel()
        measureJob = scope.launch {
            val p = player ?: return@launch
            val dc = p.videoDecoderCounters ?: return@launch
            kotlinx.coroutines.delay(2500); dc.ensureUpdated(); val n1 = dc.renderedOutputBufferCount; val t1 = System.nanoTime()
            kotlinx.coroutines.delay(6000); dc.ensureUpdated(); val n2 = dc.renderedOutputBufferCount; val t2 = System.nanoTime()
            if (playing?.id != c.id || n2 <= n1) return@launch
            val fps = Math.round((n2 - n1) * 1e9 / (t2 - t1)).toInt()
            val cur = tech ?: return@launch
            val snapped = intArrayOf(24, 25, 30, 50, 60).minByOrNull { Math.abs(it - fps) }?.takeIf { Math.abs(it - fps) <= 3 } ?: fps
            tech = cur.copy(fps = snapped); measuring = false
            prefs.setMeasured(c.id, cur.w, cur.h, snapped); refreshInfo()
        }
    }

    private fun zap(delta: Int) {
        val cur = playing ?: return
        var list = repo.channels(playCtx); if (list.none { it.id == cur.id }) list = repo.channels(cur.groupId)
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == cur.id }
        play(list[(i + delta + list.size) % list.size], banner = true)
    }

    private fun flashBanner(text: String) {
        banner.text = text; banner.visibility = View.VISIBLE; banner.elevation = dp(24).toFloat(); banner.bringToFront()
        bannerJob?.cancel()
        bannerJob = scope.launch { kotlinx.coroutines.delay(3500); banner.visibility = View.GONE }
    }

    // ---------------------------------------------------------------- overlay + lists
    private fun showOverlay(focusGroups: Boolean) {
        if (!dataReady) return
        focusLockUntil = android.os.SystemClock.uptimeMillis() + 700
        overlay.visibility = View.VISIBLE; infoPanel.visibility = View.GONE
        detailPanel.visibility = View.GONE   // no now-playing / EPG bar under the regions, groups and channels lists
        refreshLists()   // picks up new ● Live entries
        playing?.let { if (it.groupId == browseGroup) bindDetail(it) }
        val gi = groupAdapter.items.indexOfFirst { it.id == browseGroup }.coerceAtLeast(0)
        val ci = chanAdapter.items.indexOfFirst { it.id == playing?.id }.coerceAtLeast(0)
        if (focusGroups) { navRv = groupRv; navPos = gi; focusPos(groupRv, gi) } else { navRv = chanRv; navPos = ci; focusPos(chanRv, ci) }
        scrollTo(groupRv, gi); scrollTo(chanRv, ci); scrollTo(regionRv, regionAdapter.activeIdx.coerceAtLeast(0))
        resetHide()
    }

    // ---------------------------------------------------------------- home / splash / settings
    private fun dismissSplash() {
        if (!::splash.isInitialized || splash.visibility != View.VISIBLE) return
        splash.animate().alpha(0f).setDuration(380).withEndAction { splash.visibility = View.GONE; splash.alpha = 1f }.start()
    }
    private fun showHome() {
        hideOverlay(); infoPanel.visibility = View.GONE
        player?.pause()
        val c = playing ?: repo.channel(prefs.lastChannel)
        home.setResume(c?.name, c?.let { groupName(it) }, c?.icon)
        refreshOtherCard()
        publishNp()
        updateHomeCounts()
        home.live.sub = if (dataReady) "${repo.groups.count { !Repo.pseudo(it.id) }} groups" else ""
        home.visibility = View.VISIBLE; home.alpha = 0f; home.animate().alpha(1f).setDuration(320).start()
        dismissSplash(); banner.bringToFront(); home.focusDefault(); changed()
        if (::updater.isInitialized) updater.available?.let { announceUpdate(it) }
    }
    private fun openLive() {
        home.visibility = View.GONE
        (playing ?: repo.channel(prefs.lastChannel))?.let { c -> browseGroup = if (repo.groups.any { it.id == playCtx }) playCtx else c.groupId }
        showOverlay(false)
    }
    private fun resumeFromHome() {
        val c = playing ?: repo.channel(prefs.lastChannel)
        if (c == null) { openLive(); return }
        home.visibility = View.GONE
        if (playing != null && !stopped) player?.play() else play(c, banner = true, ctx = prefs.lastGroup.takeIf { repo.inGroup(it, c.id) })
    }
    private fun openSettings() {
        val fmt = NeonRow(this, "Stream format", prefs.format.uppercase() + "  ·  OK to switch (m3u8 ↔ ts)")
        fmt.setOnClickListener { prefs.format = if (prefs.format == "m3u8") "ts" else "m3u8"; fmt.set(prefs.format.uppercase() + "  ·  OK to switch (m3u8 ↔ ts)") }
        val refresh = NeonRow(this, "Refresh channel list", "Reload groups and channels from your provider")
        refresh.setOnClickListener { scope.launch { refresh.set("Refreshing…"); try { repo.refresh(); applyData(false); refresh.set("Updated just now") } catch (e: Exception) { refresh.set("Failed: ${e.message}") } } }
        val tok = prefs.token
        val api = NeonRow(this, "Home Assistant API", "Port 8765  ·  token •••• ${tok.takeLast(4)}  ·  the full token goes into the HA integration setup")
        val sy = prefs.sync
        fun yieldText() = if (sy.yieldToOthers) "On  ·  playing here stops when another stick starts (one stream per account)" else "Off  ·  every stick plays on its own"
        val name = NeonRow(this, "This stick's name", "${sy.deviceName}  ·  OK to rename (shown on your other sticks)")
        name.setOnClickListener {
            closeSettings()
            askText("Name of this stick", "e.g. Living room", sy.deviceName, "Shown on your other sticks, next to what is playing here", onCancel = { openSettings() }) { v ->
                if (v.isNotBlank()) { sy.deviceName = v; publishNp(); changed() }
                openSettings()
            }
        }
        val yieldRow = NeonRow(this, "Hand over playback", yieldText())
        yieldRow.setOnClickListener { sy.yieldToOthers = !sy.yieldToOthers; yieldRow.set(yieldText()) }
        fun haText(): String {
            val last = apiServer?.lastContact ?: 0L; val ago = (System.currentTimeMillis() - last) / 1000
            return when {
                !prefs.paired -> "Not paired  ·  add this TV in Home Assistant (Settings > Devices > Add, it shows up as discovered)"
                last == 0L -> "Paired  ·  waiting for Home Assistant to get in touch"
                ago < 90 -> "Paired  ·  connected now"
                ago < 3600 -> "Paired  ·  last contact ${ago / 60} min ago"
                else -> "Paired  ·  no contact for ${ago / 3600} h  ·  is Home Assistant running?"
            }
        }
        val haRow = NeonRow(this, "Home Assistant", haText())
        val pair = NeonRow(this, "Allow pairing again", "OK to let a Home Assistant pair with this TV for 5 minutes")
        pair.setOnClickListener { prefs.pairOpenUntil = System.currentTimeMillis() + 300_000; pair.set("Open for 5 minutes – now click Configure in Home Assistant") }
        val build = try { @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionCode } catch (e: Exception) { 0 }
        val about = NeonRow(this, "About", "HA IPTV version ${updater.installed} (build $build)  ·  a player only. It provides no channels, playlists or content – you bring your own subscription.")
        about.valueTv.setSingleLine(false)
        val upd = NeonRow(this, "Software update", "Version ${updater.installed}  ·  OK to check for a new version")
        upd.valueTv.setSingleLine(false)
        upd.setOnClickListener {
            if (updating) return@setOnClickListener
            val known = updater.available
            if (known != null) { startUpdate(known) { upd.set(it) }; return@setOnClickListener }
            scope.launch {
                upd.set("Checking…")
                try {
                    val r = updater.check()
                    if (r == null) upd.set("Version ${updater.installed}  ·  up to date")
                    else { upd.set("Version ${r.version} is available  ·  OK to download and install"); }
                } catch (e: Exception) { upd.set("Could not check: ${e.message}") }
            }
        }
        updater.available?.let { upd.set("Version ${it.version} is available  ·  OK to download and install") }
        val panel = SettingsPanel(this, listOf(fmt, refresh, name, yieldRow, haRow, pair, upd, api, about)) { }
        settingsPanel = panel; root.addView(panel, FrameLayout.LayoutParams(-1, -1)); banner.bringToFront()
    }
    private fun closeSettings() { settingsPanel?.let { root.removeView(it) }; settingsPanel = null; home.settings.requestFocus() }

    private fun hideOverlay() { overlay.visibility = View.GONE; hideJob?.cancel() }
    private fun resetHide() { hideJob?.cancel(); hideJob = scope.launch { kotlinx.coroutines.delay(15000); if (movingIdx < 0) { if (playing == null) showHome() else hideOverlay() } } }
    private fun scrollTo(rv: RecyclerView, pos: Int) { (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, dp(120)) }

    private fun skipHeader(rv: RecyclerView, pos: Int, d: Int = 1): Int {
        if (rv !== chanRv) return pos
        var p = pos; val n = chanAdapter.items.size
        while (p in 0 until n && chanAdapter.items[p].isHeader) p += d
        return if (p in 0 until n) p else pos
    }

    private fun focusPos(rv: RecyclerView, pos0: Int, tries: Int = 30) {
        val pos = skipHeader(rv, pos0)
        scrollTo(rv, pos)
        rv.post {
            val h = rv.findViewHolderForAdapterPosition(pos)
            if (h != null) h.itemView.requestFocus() else if (tries > 0) rv.postDelayed({ focusPos(rv, pos0, tries - 1) }, 40)
        }
    }

    /** While the overlay is (re)opening, lists get rebuilt and Android parks focus on the first row; ignore that so it cannot change what we browse. */
    private var focusLockUntil = 0L
    private fun focusLocked() = android.os.SystemClock.uptimeMillis() < focusLockUntil
    private fun onRegionFocus(pos: Int) {
        if (movingIdx >= 0 || focusLocked()) return
        val r = regionAdapter.items.getOrNull(pos) ?: return
        if (r.id == browseRegion || (r.id == Repo.SEARCH && repo.searchRes.isEmpty())) return
        regionRv.post {
            if (movingIdx >= 0 || r.id == browseRegion || focusLocked()) return@post
            val gs = repo.groupsIn(r.id); val target = gs.firstOrNull { it.id == regionGroup[r.id] } ?: gs.firstOrNull() ?: return@post
            showChannels(target.id)
        }
    }

    private fun promptSearch() = askText("Search channels", "channel name   ·   press OK to type", repo.searchQ, "Find channels across all groups", onCancel = { resetHide() }) { doSearch(it) }

    /** Full-screen neon text prompt (used by channel and movie search). */
    private fun askText(title: String, hintText: String, initial: String, sub: String, onCancel: () -> Unit = {}, onSubmit: (String) -> Unit) {
        val dlg = android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        val et = EditText(this).apply {
            setSingleLine(); hint = hintText; setText(initial); setSelectAllOnFocus(true); textSize = 26f
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH; setTextColor(Neon.TEXT); setHintTextColor(Color.parseColor("#A9B6E0"))
            setPadding(dp(24), dp(16), dp(24), dp(16))
            fun frame(f: Boolean) = GradientDrawable().apply { setColor(Color.parseColor(if (f) "#172863" else "#0F1A42")); cornerRadius = dp(18).toFloat(); if (f) setStroke(dp(3), Neon.YELLOW) else setStroke(dp(1), Neon.withAlpha(Neon.BLUE, 130)) }
            background = frame(false)
            setOnFocusChangeListener { _, f -> background = frame(f) }
        }
        val go = NeonRow(this, "Search", sub).apply { setOnClickListener { dlg.dismiss(); onSubmit(et.text.toString()) } }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(16)) }
        head.addView(OrbView(this).apply { energy = 0.2f }, LinearLayout.LayoutParams(dp(56), dp(56)))
        head.addView(TextView(this).apply { text = title; setTextColor(Neon.TEXT); textSize = 30f; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); setPadding(dp(14), 0, 0, 0) })
        col.addView(head); col.addView(et, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        col.addView(go, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        col.addView(Neon.keyHint(this, "BACK", "to cancel"))
        val frame = FrameLayout(this).apply { background = Neon.Backdrop(); addView(col, FrameLayout.LayoutParams(dp(640), -2, Gravity.CENTER)) }
        dlg.setContentView(frame)
        et.setOnEditorActionListener { _, _, _ -> dlg.dismiss(); onSubmit(et.text.toString()); true }
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.setOnCancelListener { onCancel() }
        dlg.show(); hideJob?.cancel()
    }

    // ---------------------------------------------------------------- movies & series (Xtream VOD)
    private fun updateHomeCounts() {
        if (!::home.isInitialized) return
        if (vod.ready) { home.movies.sub = String.format("%,d films", vod.total); home.movies.invalidate() }
        if (ser.ready) { home.series.sub = String.format("%,d series", ser.total); home.series.invalidate() }
    }
    private val loadingRepos = HashSet<VodRepo>()
    private fun openMovies() = openSection(vod, moviesView)
    private fun openSeries() = openSection(ser, seriesView)
    private fun openSection(repo: VodRepo, view: MoviesView) {
        if (!prefs.configured) return
        curRepo = repo; curView = view
        home.visibility = View.GONE; view.visibility = View.VISIBLE; view.alpha = 0f; view.animate().alpha(1f).setDuration(240).start()
        if (repo.ready) { view.rebuild(); view.focusDefault(); if (repo.cacheAgeMs() > 12 * 3600_000L) refreshSection(repo, view, quiet = true) }
        else {
            view.showMessage("Loading ${repo.label.lowercase()}…")
            scope.launch {
                val cached = repo.loadCache()
                if (cached) { view.showMessage(null); view.rebuild(); view.focusDefault(); scope.launch(Dispatchers.Default) { repo.warm() } }
                if (!cached || repo.cacheAgeMs() > 12 * 3600_000L) refreshSection(repo, view, quiet = cached)
            }
        }
    }
    private fun refreshSection(repo: VodRepo, view: MoviesView, quiet: Boolean) {
        if (!loadingRepos.add(repo)) return
        scope.launch {
            val what = repo.label.lowercase()
            try {
                if (!quiet) view.showMessage("Downloading the $what list…\nthis takes a moment the first time")
                repo.refresh { n -> if (!quiet) runOnUiThread { view.showMessage("Downloading the $what list…\n${String.format("%,d", n)} ${repo.unit} so far") } }; view.showMessage(null)
                if (view.visibility == View.VISIBLE && vodPlaying == null && !view.detailOpen) { view.rebuild(); if (!quiet) view.focusDefault() }
                scope.launch(Dispatchers.Default) { repo.warm() }
                updateHomeCounts()
            } catch (e: Exception) { if (!repo.ready) view.showMessage("Could not load $what\n${e.message}") }
            loadingRepos.remove(repo)
        }
    }
    private fun closeMovies() {
        val wasSeries = curRepo === ser
        curView.visibility = View.GONE
        showHome(); (if (wasSeries) home.series else home.movies).requestFocus()
    }
    private fun searchMovies(q: String) {
        val repo = curRepo; val view = curView
        scope.launch {
            withContext(Dispatchers.Default) { repo.runSearch(q) }
            if (repo.searchQ.isNotEmpty() && repo.searchRes.isEmpty()) flashBanner("No ${if (repo === ser) "series" else "film"} matches “${repo.searchQ}”")
            view.showSearchResults()
        }
    }
    private fun activeSection(): MoviesView? =
        if (::moviesView.isInitialized && moviesView.visibility == View.VISIBLE) moviesView
        else if (::seriesView.isInitialized && seriesView.visibility == View.VISIBLE) seriesView else null

    private fun playVod(m: Movie, fromStart: Boolean, startAt: Long? = null) {
        playRepo = if (m.ep) ser else vod
        val resume = startAt ?: if (fromStart) 0L else playRepo.progress(m.id)?.first?.takeIf { it > 30_000 } ?: 0L
        // one provider connection: stop whatever live channel was open, and forget it as "now playing"
        sampleJob?.cancel(); measureJob?.cancel(); epgJob?.cancel(); retryJob?.cancel(); surveyJob?.cancel()
        playing = null; stopped = false; vodRetries = 0
        moviesView.visibility = View.GONE; seriesView.visibility = View.GONE; overlay.visibility = View.GONE; infoPanel.visibility = View.GONE
        // small-RAM sticks: hand the poster bitmaps back before a 4K decode starts
        try { coil.Coil.imageLoader(this).memoryCache?.clear() } catch (e: Exception) {}
        startVod(m, resume)
        changed()
    }
    private fun startVod(m: Movie, startMs: Long) {
        vodPlaying = m; tech = null; playSince = System.currentTimeMillis()
        val p = ensurePlayer()
        p.setMediaItem(MediaItem.fromUri(playRepo.movieUrl(m)), startMs); p.prepare(); p.playWhenReady = true
        status.visibility = View.VISIBLE; status.text = "…"
        showVodBar(true)
        publishNp(pos = startMs)
        vodJob?.cancel()
        vodJob = scope.launch {
            var n = 0
            while (true) {
                kotlinx.coroutines.delay(1000)
                val pl = player ?: return@launch; val mv = vodPlaying ?: return@launch
                if (pl.playbackState == Player.STATE_READY) {
                    if (++n % 5 == 0) { playRepo.saveProgress(mv, pl.currentPosition, pl.duration); publishNp(quiet = true) }
                    if (vodBar.visibility == View.VISIBLE) vodBarUpdate(mv.title, pl.currentPosition, pl.duration, !pl.isPlaying)
                }
            }
        }
    }
    private fun vodError(error: PlaybackException) {
        val m = vodPlaying ?: return
        val pos = player?.currentPosition ?: 0L
        if (error.errorCode in 2000..2999 && error.errorCode != PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND && vodRetries < 3) {
            vodRetries++
            status.visibility = View.VISIBLE; status.text = "Connection problem – retrying ($vodRetries/3)…"
            retryJob?.cancel()
            retryJob = scope.launch { kotlinx.coroutines.delay(2000L * vodRetries); if (vodPlaying?.id == m.id) startVod(m, pos) }
            return
        }
        status.visibility = View.VISIBLE; status.text = "Can't play ${m.title}\n${error.errorCodeName}\nBACK to return"
    }
    private fun vodFinished() {
        val m = vodPlaying ?: return
        playRepo.saveProgress(m, 1, 1)         // finished: drops it from "Continue watching"
        val next = if (m.ep) ser.nextAfter(m) else null
        if (next != null) { playVod(next, false); return }       // binge: straight into the next episode
        stopVod(reopen = false)
    }
    private fun stopVod(reopen: Boolean = true) {
        val m = vodPlaying ?: return
        closeTracks()
        player?.let { if (reopen && it.playbackState == Player.STATE_READY) playRepo.saveProgress(m, it.currentPosition, it.duration) }
        publishNp(stoppedVod = m)
        player?.let { it.stop(); it.clearMediaItems() }
        vodJob?.cancel(); vodBarJob?.cancel(); retryJob?.cancel()
        vodPlaying = null; vodBar.visibility = View.GONE; status.visibility = View.GONE
        val view = if (m.ep) seriesView else moviesView
        curView = view; curRepo = if (m.ep) ser else vod
        view.visibility = View.VISIBLE
        val card = if (m.ep) ser.movie(m.cat) else m
        if (reopen && card != null) view.openDetail(card) else { view.closeDetail(); view.focusDefault() }
        changed()
    }
    private fun showVodBar(keep: Boolean = false) {
        val m = vodPlaying ?: return; val p = player ?: return
        vodBarUpdate(m.title, p.currentPosition, p.duration, !p.isPlaying)
        vodBar.visibility = View.VISIBLE; vodBar.bringToFront(); status.bringToFront()
        vodBarJob?.cancel()
        vodBarJob = scope.launch { kotlinx.coroutines.delay(if (keep) 5000 else 4000); if (player?.isPlaying == true) vodBar.visibility = View.GONE else vodBarJob = null }
    }
    // ---- audio / subtitle picker (MENU while a film plays)
    private var trackPanel: FrameLayout? = null
    private fun closeTracks() { trackPanel?.let { root.removeView(it) }; trackPanel = null }
    private fun showTracks() {
        val p = player ?: return
        val ctx = this
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun heading(t: String) = TextView(ctx).apply { text = t; setTextColor(Neon.CYAN); textSize = 14f; letterSpacing = 0.2f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(dp(6), dp(14), 0, dp(8)) }
        fun lang(f: androidx.media3.common.Format, n: Int): String {
            val l = f.language
            val name = if (l.isNullOrEmpty() || l == "und") "Track $n" else java.util.Locale(l).getDisplayLanguage(java.util.Locale.ENGLISH).replaceFirstChar { it.uppercase() }
            return (f.label?.takeIf { it.isNotBlank() && !it.equals(name, true) }?.let { "$name · $it" } ?: name)
        }
        var first: View? = null; var audioPick: View? = null; var inSubs = false; var n = 0
        fun add(title: String, value: String, selected: Boolean, action: () -> Unit) {
            val r = NeonRow(ctx, (if (selected) "✓  " else "     ") + title, value)
            if (value.isEmpty()) r.valueTv.visibility = View.GONE
            r.minimumHeight = dp(58)
            r.setOnClickListener { action(); closeTracks() }
            col.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            if (first == null) first = r
            if (selected && audioPick == null && n > 0 && !inSubs) audioPick = r
        }
        val groups = p.currentTracks.groups
        col.addView(heading("AUDIO"))
        for (g in groups) if (g.type == C.TRACK_TYPE_AUDIO) for (i in 0 until g.length) {
            n++; val f = g.getTrackFormat(i)
            add(lang(f, n), listOfNotNull(Quality.acodec(f.sampleMimeType).takeIf { it.isNotEmpty() }, f.channelCount.takeIf { it > 0 }?.let { "$it ch" }).joinToString(" · "), g.isTrackSelected(i)) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, listOf(i))).build()
            }
        }
        if (n == 0) col.addView(TextView(ctx).apply { text = "Only one audio track"; setTextColor(Neon.MUTED); textSize = 16f; setPadding(dp(6), 0, 0, dp(6)) })
        col.addView(heading("SUBTITLES")); inSubs = true
        val textOn = !p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) && groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        add("Off", "", !textOn) { p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
        var t = 0
        for (g in groups) if (g.type == C.TRACK_TYPE_TEXT) for (i in 0 until g.length) {
            t++; val f = g.getTrackFormat(i)
            add(lang(f, t), "", textOn && g.isTrackSelected(i)) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, listOf(i))).build()
            }
        }
        col.addView(Neon.keyHint(ctx, "BACK", "to close"))
        val sv = android.widget.ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(col) }
        val panel = FrameLayout(ctx).apply {
            setBackgroundColor(Color.argb(235, 4, 8, 22)); isClickable = true
            addView(sv, FrameLayout.LayoutParams(dp(520), -1, Gravity.CENTER).apply { topMargin = dp(36); bottomMargin = dp(36) })
        }
        trackPanel = panel; root.addView(panel, FrameLayout.LayoutParams(-1, -1)); vodBar.visibility = View.GONE; vodBarJob?.cancel()
        panel.post { (audioPick ?: first)?.requestFocus() }
    }
    /** "4K · H.265 · 3840×2076 · E-AC-3 5.1" for the film bar. */
    private fun vodTechLine(): String {
        val t = tech ?: return ""
        val ch = when (t.ach) { 0 -> ""; 1 -> "mono"; 2 -> "2.0"; 6 -> "5.1"; 8 -> "7.1"; else -> "${t.ach} ch" }
        return listOf(Quality.short(Quality.actual(t.w, t.h)), t.vcodec, "${t.w}×${t.h}" + (if (t.fps > 0) " ${t.fps}fps" else ""),
            listOf(t.acodec, ch).filter { it.isNotEmpty() }.joinToString(" ")).filter { it.isNotEmpty() }.joinToString("   ·   ")
    }
    private fun vodBarUpdate(name: String, pos: Long, dur: Long, paused: Boolean) { vodBar.tech = vodTechLine(); vodBar.update(name, pos, dur, paused) }
    private val clearNote = Runnable { vodBar.note = ""; player?.let { p -> vodPlaying?.let { vodBarUpdate(it.title, p.currentPosition, p.duration, !p.isPlaying) } } }
    private fun vodKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val p = player ?: return super.dispatchKeyEvent(e)
        if (trackPanel != null) { if (e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_MENU) { closeTracks(); return true }; return super.dispatchKeyEvent(e) }
        fun seek(ms: Long) {
            val dur = if (p.duration > 0) p.duration else Long.MAX_VALUE / 4
            p.seekTo((p.currentPosition + ms).coerceIn(0L, (dur - 2000).coerceAtLeast(0L)))
            val a = Math.abs(ms) / 1000; vodBar.note = (if (ms > 0) "+" else "−") + (if (a >= 120) "${a / 60} min" else if (a >= 60) "1 min" else "$a s")
            vodBar.removeCallbacks(clearNote); vodBar.postDelayed(clearNote, 1200); showVodBar()
        }
        val accel = 1 + minOf(e.repeatCount / 3, 8)
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> seek(-10_000L * accel)
            KeyEvent.KEYCODE_DPAD_RIGHT -> seek(10_000L * accel)
            KeyEvent.KEYCODE_DPAD_UP -> seek(60_000L * accel)
            KeyEvent.KEYCODE_DPAD_DOWN -> seek(-60_000L * accel)
            KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-30_000L * accel)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(30_000L * accel)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (e.repeatCount == 0) { if (vodBar.visibility != View.VISIBLE) showVodBar() else { if (p.isPlaying) p.pause() else p.play(); showVodBar() } } }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (e.repeatCount == 0) { if (p.isPlaying) p.pause() else p.play(); showVodBar() } }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p.play(); showVodBar() }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.pause(); showVodBar() }
            KeyEvent.KEYCODE_MENU -> showTracks()
            KeyEvent.KEYCODE_BACK -> stopVod()
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }

    private fun doSearch(q: String) {
        flashBanner("Searching…")
        scope.launch {
            kotlinx.coroutines.withContext(Dispatchers.Default) { repo.runSearch(q) }
            showSearchResult(q)
        }
    }

    private fun showSearchResult(q: String) {
        if (repo.searchRes.isEmpty()) { flashBanner(if (q.isBlank()) "Search cleared" else "No channel matches “${q.trim()}”"); refreshLists(); resetHide(); return }
        browseGroup = Repo.SEARCH; refreshLists()
        flashBanner("${repo.searchRes.size} channels for “${repo.searchQ}”")
        focusPos(chanRv, 0); resetHide()
    }

    private fun onRegionClick(pos: Int) {
        if (movingIdx >= 0) return
        val r = regionAdapter.items.getOrNull(pos) ?: return
        if (r.id == Repo.SEARCH) { promptSearch(); return }
        if (r.id != browseRegion) onRegionFocus(pos)
        regionRv.postDelayed({ focusPos(groupRv, groupAdapter.items.indexOfFirst { it.id == browseGroup }.coerceAtLeast(0)) }, 60)
    }

    private fun onGroupFocus(pos: Int) {
        if (movingIdx >= 0 || focusLocked()) return
        val g = groupAdapter.items.getOrNull(pos) ?: return
        // focus can arrive mid-layout; RecyclerView forbids notifyDataSetChanged there
        if (g.id != browseGroup) groupRv.post { if (movingIdx < 0 && g.id != browseGroup && !focusLocked()) showChannels(g.id) }
    }

    private fun onGroupClick(pos: Int) {
        if (movingIdx >= 0) return
        val g = groupAdapter.items.getOrNull(pos) ?: return
        prefs.lastGroup = g.id
        showChannels(g.id)
        val i = chanAdapter.items.indexOfFirst { it.id == playing?.id }.let { if (it < 0) 0 else it }
        focusPos(chanRv, i)
    }

    private fun onChannelClick(pos: Int) {
        val c = chanAdapter.items.getOrNull(pos) ?: return
        play(c, banner = true, ctx = browseGroup); hideOverlay()
    }

    // ---------------------------------------------------------------- move groups
    private fun startMove(pos: Int, region: Boolean = false): Boolean {
        if (movingIdx >= 0) return true
        if (region && Repo.pseudo(regionAdapter.items.getOrNull(pos)?.id ?: "")) return true     // favourites/search stay on top
        moveRegion = region; movingIdx = pos
        if (region) { moveSnapshotR = regionAdapter.items.toList(); regionAdapter.movingIdx = pos; regionAdapter.notifyItemChanged(pos) }
        else { moveSnapshot = groupAdapter.items.toList(); groupAdapter.movingIdx = pos; groupAdapter.notifyItemChanged(pos) }
        setHint(true); hideJob?.cancel()
        return true
    }

    private fun moveTo(target: Int) {
        val n = if (moveRegion) regionAdapter.items.size else groupAdapter.items.size
        val from = movingIdx; val to = target.coerceIn(if (moveRegion) regionAdapter.items.count { Repo.pseudo(it.id) } else 0, n - 1)
        if (from < 0 || to == from) return
        if (moveRegion) { regionAdapter.move(from, to); regionAdapter.movingIdx = to; regionAdapter.notifyItemChanged(to); focusPos(regionRv, to) }
        else { groupAdapter.move(from, to); groupAdapter.movingIdx = to; groupAdapter.notifyItemChanged(to); focusPos(groupRv, to) }
        movingIdx = to
    }

    private fun endMove(save: Boolean) {
        val keep = movingIdx
        if (moveRegion) { if (save) repo.saveRegionOrder(regionAdapter.items.toList()) else regionAdapter.setAll(moveSnapshotR); regionAdapter.movingIdx = -1 }
        else { if (save) repo.saveRegionGroups(browseRegion, groupAdapter.items.toList()) else groupAdapter.setAll(moveSnapshot); groupAdapter.movingIdx = -1 }
        movingIdx = -1
        regionAdapter.activeIdx = regionAdapter.items.indexOfFirst { it.id == browseRegion }
        groupAdapter.activeIdx = groupAdapter.items.indexOfFirst { it.id == browseGroup }
        regionAdapter.notifyDataSetChanged(); groupAdapter.notifyDataSetChanged(); setHint(false); resetHide()
        if (moveRegion) focusPos(regionRv, if (save) keep else regionAdapter.activeIdx.coerceAtLeast(0))
        else focusPos(groupRv, if (save) keep else groupAdapter.activeIdx.coerceAtLeast(0))
    }

    // ---------------------------------------------------------------- keys
    private var navRv: RecyclerView? = null; private var navPos = -1
    private var favHold = false
    private fun focusedIn(rv: RecyclerView): Int {
        val f = currentFocus ?: return -1
        return if (f.parent === rv) rv.getChildAdapterPosition(f) else -1
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        if (vodPlaying != null) return vodKey(e)
        val sec = activeSection()
        if (sec != null) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (k == KeyEvent.KEYCODE_BACK) { if (!sec.handleBack()) sec.cb.close(); return true }
                if (!sec.detailOpen && (k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || k == KeyEvent.KEYCODE_MEDIA_REWIND || k == KeyEvent.KEYCODE_PAGE_DOWN || k == KeyEvent.KEYCODE_PAGE_UP || k == KeyEvent.KEYCODE_CHANNEL_DOWN || k == KeyEvent.KEYCODE_CHANNEL_UP)) {
                    sec.pageJump(k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || k == KeyEvent.KEYCODE_PAGE_DOWN || k == KeyEvent.KEYCODE_CHANNEL_DOWN); return true
                }
            }
            return super.dispatchKeyEvent(e)
        }
        if (e.action == KeyEvent.ACTION_DOWN && (settingsPanel != null || (::home.isInitialized && home.visibility == View.VISIBLE) || (::splash.isInitialized && splash.visibility == View.VISIBLE))) {
            if (k == KeyEvent.KEYCODE_BACK && settingsPanel != null) { closeSettings(); return true }
            if (k == KeyEvent.KEYCODE_BACK && splash.visibility == View.VISIBLE) return true
            return super.dispatchKeyEvent(e)     // Home/Settings use normal D-pad focus; BACK on Home leaves the app
        }
        val isOk = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
        if (e.action != KeyEvent.ACTION_DOWN) {
            if (isOk && favHold) { favHold = false; return true }     // swallow the release that ends a favourite-hold
            return super.dispatchKeyEvent(e)
        }
        if (isOk && overlay.visibility == View.VISIBLE && movingIdx < 0) {
            // hold OK on a channel = toggle favourite (detected from key repeats: more reliable than the system long-press)
            if (e.repeatCount == 0) favHold = false
            val cp = focusedIn(chanRv)
            if (cp >= 0 && e.repeatCount >= 1) { if (!favHold) { favHold = true; toggleFavAt(cp) }; return true }
        }
        if (surveyJob?.isActive == true && k == KeyEvent.KEYCODE_BACK) { stopSurvey(); return true }
        if (overlay.visibility == View.VISIBLE) resetHide()

        // ---- move mode owns the remote
        if (movingIdx >= 0) {
            when (k) {
                KeyEvent.KEYCODE_DPAD_UP -> moveTo(movingIdx - 1)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveTo(movingIdx + 1)
                KeyEvent.KEYCODE_DPAD_LEFT -> moveTo(0)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveTo(100000)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (e.repeatCount == 0) endMove(true)
                KeyEvent.KEYCODE_BACK -> endMove(false)
            }
            return true
        }

        if (overlay.visibility == View.VISIBLE) {
            when (k) {
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    val down = k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || k == KeyEvent.KEYCODE_CHANNEL_DOWN || k == KeyEvent.KEYCODE_PAGE_DOWN
                    for (rv in listOf(regionRv, groupRv, chanRv)) {
                        val cur = focusedIn(rv); if (cur < 0) continue
                        val n = rv.adapter?.itemCount ?: 0
                        if (n > 0) { val to = (cur + if (down) 8 else -8).coerceIn(0, n - 1); focusPos(rv, to); scrollTo(rv, to) }
                        break
                    }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    val gp = focusedIn(groupRv)
                    val gid = if (gp >= 0) groupAdapter.items.getOrNull(gp)?.id ?: browseGroup else browseGroup
                    if (surveyJob?.isActive == true) { flashBanner("A check is already running"); return true }
                    if (Repo.pseudo(gid) && gid == Repo.LIVE) { flashBanner("Pick a group or search results to check"); return true }
                    val n = repo.channels(gid).size
                    hideOverlay(); startSurvey(gid, 6); flashBanner("Checking $n channels for a live picture – BACK to stop")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // Up/Down never leave the list you are in and never wrap: stop at the first/last row.
                    // Position is tracked here (not only via currentFocus) because a fast scroll can recycle the focused row.
                    val d = if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                    var rv: RecyclerView? = null; var p = -1
                    for (r in listOf(regionRv, groupRv, chanRv)) { val q = focusedIn(r); if (q >= 0) { rv = r; p = q; break } }
                    if (rv == null) { rv = navRv; p = navPos }
                    if (rv != null && p >= 0) {
                        var t = (p + d).coerceIn(0, ((rv.adapter?.itemCount ?: 1) - 1).coerceAtLeast(0))
                        if (rv === chanRv) { val s = skipHeader(rv, t, d); t = if (chanAdapter.items.getOrNull(s)?.isHeader == true) p else s }
                        navRv = rv; navPos = t
                        val h = rv.findViewHolderForAdapterPosition(t)
                        if (h != null) h.itemView.requestFocus() else focusPos(rv, t)
                        resetHide()
                        return true
                    }
                }
                KeyEvent.KEYCODE_BACK -> {
                    // BACK steps back one view: first to the group that is playing, then to the picture
                    if (playing != null && browseGroup != playCtx && repo.groups.any { it.id == playCtx }) { browseGroup = playCtx; showOverlay(false) }
                    else showHome()
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    val rp = focusedIn(regionRv); val gp = focusedIn(groupRv)
                    if (rp >= 0) startMove(rp, true) else if (gp >= 0) startMove(gp) else scope.launch {
                        flashBanner("Reloading…"); try { repo.refresh(); applyData(false); flashBanner("Channel list updated") }
                        catch (ex: Exception) { flashBanner("Reload failed: ${ex.message}") }
                    }
                    return true
                }
            }
            return super.dispatchKeyEvent(e)
        }

        // ---- full-screen video
        when (k) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { showOverlay(true); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO ->
                { if (infoPanel.visibility == View.VISIBLE && k != KeyEvent.KEYCODE_INFO) { infoPanel.visibility = View.GONE; showOverlay(false) } else showInfo(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU -> { showOverlay(false); return true }
            KeyEvent.KEYCODE_BACK -> {
                // from the picture BACK returns to the list you came from (leave the app with HOME)
                if (infoPanel.visibility == View.VISIBLE) { infoPanel.visibility = View.GONE; return true }
                if (dataReady) { if (playing != null && repo.groups.any { it.id == playCtx }) browseGroup = playCtx; showOverlay(false); return true }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { player?.let { if (it.isPlaying) it.pause() else it.play() }; return true }
        }
        return super.dispatchKeyEvent(e)
    }

    // ---------------------------------------------------------------- sync with the other sticks (through Home Assistant)
    private var playSince = 0L        // when this stick last started or resumed playing

    private fun activelyPlaying(): Boolean {
        val p = player ?: return false
        return resumed && p.playWhenReady && (vodPlaying != null || (playing != null && !stopped))
    }

    /** Tell the other sticks what is on here (kept as the "np:<device>" entry, so they can offer to continue it). */
    private fun publishNp(quiet: Boolean = false, pos: Long? = null, stoppedVod: Movie? = null) {
        val sy = prefs.sync; val p = player
        val m = stoppedVod ?: vodPlaying; val c = playing
        val state = when {
            stoppedVod != null -> "stopped"
            m == null && (c == null || stopped || p == null) -> "stopped"
            p == null || !resumed || !p.playWhenReady -> "paused"
            else -> "playing"
        }
        val o = JSONObject().put("name", sy.deviceName).put("state", state).put("since", playSince)
        if (m != null) {
            val pp = pos ?: p?.currentPosition ?: 0L; val dd = p?.duration?.takeIf { it > 0 } ?: 0L
            o.put("kind", if (m.ep) "episode" else "movie").put("id", m.id).put("title", m.title).put("ext", m.ext).put("poster", m.poster)
                .put("pos", pp).put("dur", dd)
            if (m.ep) o.put("series", m.cat).put("sub", ser.movie(m.cat)?.title ?: "")
        } else if (c != null) {
            o.put("kind", "live").put("id", c.id).put("title", c.name).put("sub", groupName(c)).put("g", playCtx).put("icon", c.icon)
        } else return
        sy.put("np:${sy.deviceId}", o.toString(), quiet = quiet)
    }

    private fun otherNps(): List<Triple<String, JSONObject, Long>> {
        val me = "np:${prefs.sync.deviceId}"; val now = System.currentTimeMillis()
        return prefs.sync.entries("np:").filter { it.first != me && now - it.second.t < 12 * 3600_000L }.mapNotNull { (k, e) ->
            try {
                val o = JSONObject(e.value)
                val done = o.optLong("dur") > 0 && o.optLong("pos") > o.optLong("dur") * 0.95      // finished, nothing to continue
                if (done) null else Triple(k.removePrefix("np:"), o, e.t)
            } catch (x: Exception) { null }
        }
    }

    private fun npSub(o: JSONObject): String {
        val where = "From " + o.optString("name", "another TV")
        val what = when (o.optString("kind")) {
            "live" -> o.optString("sub")
            else -> (o.optString("sub").takeIf { it.isNotEmpty() }?.plus("  ·  ") ?: "") + fmtTime(o.optLong("pos")) + (o.optLong("dur").takeIf { it > 0 }?.let { " of ${fmtTime(it)}" } ?: "")
        }
        val live = o.optString("state") == "playing"
        return "$where" + (if (live) "  ·  playing now" else "") + "  ·  $what"
    }

    /** "Continue" card on Home: the most recent thing from another stick. */
    private fun refreshOtherCard() {
        // only when the other stick's item is more recent than what happened here
        val mine = prefs.sync.time("np:${prefs.sync.deviceId}")
        val n = otherNps().maxByOrNull { it.third }?.takeIf { it.third > mine }
        if (n == null) { home.setOther(null, null); return }
        home.setOther(n.second.optString("title"), npSub(n.second), n.second.optString("icon").ifEmpty { n.second.optString("poster") })
    }

    /** Play what another stick was playing, from where it was. Returns the title, or null when there is nothing. */
    private fun continueOther(from: String? = null): String? {
        val n = otherNps().filter { from == null || it.first == from }.maxByOrNull { it.third } ?: return null
        val o = n.second
        when (o.optString("kind")) {
            "live" -> {
                val ch = repo.channel(o.optString("id")) ?: return null
                home.visibility = View.GONE; hideOverlay()
                play(ch, banner = true, ctx = o.optString("g").takeIf { repo.inGroup(it, ch.id) })
            }
            else -> {
                val ep = o.optString("kind") == "episode"
                val m = Movie(o.optString("id"), o.optString("title", "?"), "", if (ep) o.optString("series") else "", o.optString("poster"), 0f,
                    o.optString("ext", "mkv").ifEmpty { "mkv" }, 0L, false, ep)
                home.visibility = View.GONE; hideOverlay()
                val saved = (if (ep) ser else vod).progress(m.id)?.first ?: 0L
                playVod(m, false, maxOf(o.optLong("pos"), saved).takeIf { it > 30_000 } ?: 0L)
            }
        }
        return o.optString("title")
    }

    /** Entries arrived from another stick. */
    private fun handleRemote(keys: List<String>) {
        var lists = false; var card = false
        for (k in keys) when {
            k.startsWith("fav:") -> { prefs.favsChanged(); lists = true }
            k.startsWith("order:") -> { repo.reapplyOrder(); lists = true }
            k == "last:live" -> card = true
            k.startsWith("vod:") -> vod.applyRemote(k)
            k.startsWith("ser") -> ser.applyRemote(k)
            k.startsWith("np:") && k != "np:${prefs.sync.deviceId}" -> { card = true; yieldIfTakenOver(k) }
        }
        // the lists and the Movies/Series screens rebuild when they are next opened, so nothing steals focus now
        if (lists && dataReady && overlay.visibility != View.VISIBLE) { /* picked up by showOverlay() */ }
        if (card && ::home.isInitialized && home.visibility == View.VISIBLE) {
            val c = playing ?: repo.channel(prefs.lastChannel)
            home.setResume(c?.name, c?.let { groupName(it) }, c?.icon); refreshOtherCard()
        }
    }

    /** Another stick started playing after we did: the account allows one stream, so give way. */
    private fun yieldIfTakenOver(key: String) {
        if (!prefs.sync.yieldToOthers || !activelyPlaying()) return
        val o = try { JSONObject(prefs.sync.get(key) ?: return) } catch (e: Exception) { return }
        if (o.optString("state") != "playing" || o.optLong("since") <= playSince) return
        val who = o.optString("name", "another TV")
        if (vodPlaying != null) stopVod(reopen = false) else { player?.stop(); stopped = true; publishNp() }
        showHome(); flashBanner("Now playing on $who")
        changed()
    }

    // ---------------------------------------------------------------- HA API (local HTTP) + commands
    private fun changed() { synchronized(stateLock) { stateVer++; stateLock.notifyAll() } }

    private fun <T> onUi(f: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return f()
        val t = FutureTask(Callable { f() }); runOnUiThread(t); return t.get(15, TimeUnit.SECONDS)
    }

    private fun groupDisplay(gid: String) = repo.groups.firstOrNull { it.id == gid }?.name ?: ""
    private fun techVerdict(c: Channel, t: Tech?) = Quality.verdict(c.name, t)

    private fun chanJson(c: Channel): JSONObject {
        val m = prefs.measured(c.id)
        val o = JSONObject().put("id", c.id).put("num", c.num).put("name", c.name).put("icon", c.icon)
            .put("group_id", c.groupId).put("group", groupDisplay(c.groupId)).put("region", repo.regionOfGroup(c.groupId)).put("epg_id", c.epgId).put("fav", repo.isFav(c.id))
            .put("picture", prefs.picture(c.id)?.let { JSONObject().put("state", it.first).put("age", System.currentTimeMillis() / 1000 - it.second) } ?: JSONObject.NULL)
        o.put("claimed", Quality.claimed(c.name).let { if (it == 0) JSONObject.NULL else Quality.short(it) })
        if (m != null) {
            val t = Tech(m[0], m[1], m[2], "", "", 0, m[1]); val v = techVerdict(c, t)
            o.put("measured", JSONObject().put("w", m[0]).put("h", m[1]).put("fps", m[2]).put("label", Quality.short(Quality.actual(m[0], m[1])))
                .put("mismatch", v != null && v.color == Quality.RED))
        }
        return o
    }

    private fun progJson(p: Prog?) = if (p == null) JSONObject.NULL else JSONObject().put("title", p.title).put("desc", p.desc).put("start", p.start).put("stop", p.stop)

    private fun stateJson(): JSONObject {
        val o = JSONObject().put("v", stateVer).put("app", "0.6").put("ready", dataReady).put("configured", prefs.configured).put("name", prefs.sync.deviceName).put("server_time", System.currentTimeMillis() / 1000)
        val c = playing; val p = player; val vm = vodPlaying
        val power = when {
            !resumed -> "background"
            vm != null && p != null -> if (!p.playWhenReady) "paused" else if (p.isPlaying) "playing" else "buffering"
            c == null || stopped || p == null -> "idle"
            !p.playWhenReady -> "paused"; p.isPlaying -> "playing"; else -> "buffering"
        }
        o.put("power", power).put("error", lastError ?: JSONObject.NULL).put("picture", picture)
        o.put("groups", repo.groups.size).put("channels", repo.groups.filter { !Repo.pseudo(it.id) }.sumOf { it.count })
        if (c != null) {
            o.put("channel", chanJson(c)).put("group_id", playCtx).put("group", groupDisplay(playCtx)).put("region", repo.regionOfGroup(playCtx))
            val t = tech
            if (t != null) {
                val v = techVerdict(c, t)
                o.put("tech", JSONObject().put("w", t.w).put("h", t.h).put("fps", t.fps).put("vcodec", t.vcodec).put("acodec", t.acodec).put("ach", t.ach)
                    .put("max_h", t.maxH).put("label", Quality.label(Quality.actual(t.w, t.h))).put("short", Quality.short(Quality.actual(t.w, t.h)))
                    .put("mismatch", v != null && v.color == Quality.RED).put("verdict", v?.text ?: ""))
            }
            val now = System.currentTimeMillis() / 1000
            val l = playEpg ?: emptyList()
            val cur = l.firstOrNull { it.start <= now && now < it.stop }
            val nxt = l.firstOrNull { it.start > now }
            o.put("epg_state", stateOf(c, playEpg, playEpgLoading).name.lowercase())
            o.put("now", progJson(cur)).put("next", progJson(nxt))
        }
        if (vm != null) o.put("vod", JSONObject().put("kind", if (vm.ep) "episode" else "movie").put("id", vm.id).put("title", vm.title).put("ext", vm.ext).put("poster", vm.poster)
            .put("series", if (vm.ep) vm.cat else "").put("pos", p?.currentPosition ?: 0L).put("dur", p?.duration?.takeIf { it > 0 } ?: 0L))
        val sy = prefs.sync
        o.put("sync", JSONObject().put("device", sy.deviceId).put("name", sy.deviceName).put("epoch", sy.epoch).put("seq", sy.currentSeq()).put("yield", sy.yieldToOthers))
        survey?.let { o.put("survey", JSONObject().put("group", groupDisplay(it.first)).put("group_id", it.first).put("done", it.second).put("total", it.third)) }
        return o
    }

    private val apiHost = object : ApiHost {
        override fun token() = prefs.token
        override fun version() = synchronized(stateLock) { stateVer }
        override fun waitChange(since: Int, timeoutMs: Long) {
            synchronized(stateLock) { val end = System.currentTimeMillis() + timeoutMs
                while (stateVer == since && System.currentTimeMillis() < end) stateLock.wait(maxOf(1, end - System.currentTimeMillis())) }
        }
        override fun state() = onUi { stateJson() }
        override fun groups() = onUi { JSONArray().also { a -> repo.groups.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("count", it.count).put("region", it.region)) } } }
        override fun regions() = onUi { JSONArray().also { a -> repo.regions.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("groups", it.groups).put("channels", it.channels)) } } }
        override fun channels(group: String) = onUi { JSONArray().also { a -> repo.channels(group).take(3000).forEach { a.put(chanJson(it)) } } }
        override fun search(q: String, limit: Int, group: String?) = onUi { JSONArray().also { a -> repo.search(q, limit.coerceIn(1, 100), group).forEach { a.put(chanJson(it)) } } }
        override fun epg(ids: List<String>, limit: Int): JSONObject {
            val chans = onUi { ids.mapNotNull { repo.channel(it) } }
            val out = JSONObject()
            runBlocking(Dispatchers.IO) {
                for (c in chans) {
                    val l = try { repo.epg(c, limit.coerceIn(1, 30)) } catch (e: Exception) { null } ?: emptyList()
                    out.put(c.id, JSONArray().also { a -> l.forEach { a.put(progJson(it)) } })
                }
            }
            return out
        }
        override fun quality(group: String) = onUi {
            JSONArray().also { a -> repo.channels(group).forEach { c ->
                val m = prefs.measured(c.id)
                val v = m?.let { techVerdict(c, Tech(it[0], it[1], it[2], "", "", 0, it[1])) }
                a.put(chanJson(c).put("verdict", when { m == null -> "unknown"; v != null && v.color == Quality.RED -> "mismatch"; else -> "ok" }).put("verdict_text", v?.text ?: ""))
            } }
        }
        override fun command(c: JSONObject) = onUi { runCommand(c) }
        override fun syncDelta(since: Long): JSONObject {
            val sy = prefs.sync
            return JSONObject().put("device", sy.deviceId).put("name", sy.deviceName).put("epoch", sy.epoch).put("seq", sy.currentSeq())
                .put("entries", sy.delta(since))
        }
        override fun provision(): JSONObject = JSONObject().put("configured", prefs.configured)
            .put("server", prefs.server).put("user", prefs.user).put("pass", prefs.pass)
        override fun setProvision(c: JSONObject): JSONObject = onUi {
            if (prefs.configured) return@onUi JSONObject().put("ok", false).put("error", "already set up")
            val sv = c.optString("server").trim(); val us = c.optString("user").trim(); val pw = c.optString("pass").trim()
            if (sv.isEmpty() || us.isEmpty() || pw.isEmpty()) return@onUi JSONObject().put("ok", false).put("error", "server, user and pass are required")
            prefs.server = sv; prefs.user = us; prefs.pass = pw
            finishSetup(); JSONObject().put("ok", true)
        }
        override fun pairRequest(ha: String, pub: String): JSONObject {
            if (ha.length < 8 || !Pairing.validPub(pub)) return JSONObject().put("ok", false).put("error", "bad request")   // old integrations (no key) are refused: the token must be encrypted
            if (!prefs.pairingOpen()) return JSONObject().put("ok", false).put("error", "closed")
            val r = pairing.start(ha, pub) ?: return JSONObject().put("ok", false).put("error", "busy")
            onUi { showPairRequest(r) }
            return JSONObject().put("ok", true).put("id", r.id).put("snonce", r.sn).put("spub", r.spub).put("name", prefs.sync.deviceName)
        }
        override fun pairPoll(id: String, ha: String): JSONObject = pairing.poll(id, ha)
        override fun syncMerge(entries: JSONObject): JSONObject {
            val sy = prefs.sync
            val n = sy.merge(entries, skipKey = "np:${sy.deviceId}").size       // nobody else may rewrite what is playing here
            return JSONObject().put("ok", true).put("applied", n).put("seq", sy.currentSeq())
        }
    }

    private fun runCommand(j: JSONObject): JSONObject {
        val ok = JSONObject().put("ok", true)
        fun err(m: String) = JSONObject().put("ok", false).put("error", m)
        if (!dataReady && j.optString("cmd") !in setOf("refresh", "config")) { pendingCmd = j; return JSONObject().put("ok", true).put("queued", true) }
        return when (val cmd = j.optString("cmd")) {
            "play" -> {
                val gh = j.optString("group").takeIf { it.isNotEmpty() }
                val ch = j.optString("channel_id").takeIf { it.isNotEmpty() }?.let { repo.channel(it) }
                    ?: j.optString("query").takeIf { it.isNotEmpty() }?.let { q -> repo.search(q, 1, gh).firstOrNull() ?: repo.search(q, 1).firstOrNull() }
                if (ch == null) return err("no matching channel")
                val ctx = gh?.let { repo.resolveGroup(it)?.id }?.takeIf { repo.inGroup(it, ch.id) }
                hideOverlay(); play(ch, banner = true, ctx = ctx); ok.put("channel", chanJson(ch))
            }
            "zap" -> { zap(j.optInt("delta", 1)); ok }
            "group" -> {
                val g = repo.resolveGroup(j.optString("group").ifEmpty { j.optString("id") }) ?: return err("no matching group")
                browseGroup = g.id; showChannels(g.id); prefs.lastGroup = g.id
                if (j.optBoolean("play", false)) repo.channels(g.id).firstOrNull()?.let { hideOverlay(); play(it, banner = true, ctx = g.id) }
                changed(); ok.put("group", g.name)
            }
            "region" -> {
                val r = repo.resolveRegion(j.optString("region").ifEmpty { j.optString("id") }) ?: return err("no matching region")
                val gs = repo.groupsIn(r.id); val g = gs.firstOrNull { it.id == regionGroup[r.id] } ?: gs.firstOrNull() ?: return err("region is empty")
                showChannels(g.id); prefs.lastGroup = g.id
                if (j.optBoolean("play", false)) repo.channels(g.id).firstOrNull()?.let { hideOverlay(); play(it, banner = true, ctx = g.id) }
                changed(); ok.put("region", r.name).put("group", g.name)
            }
            "stop" -> {
                if (vodPlaying != null) stopVod(reopen = false)
                else { player?.stop(); stopped = true; infoPanel.visibility = View.GONE; publishNp() }
                changed(); ok
            }
            "pause" -> { player?.pause(); publishNp(); changed(); ok }
            "resume" -> { if (stopped && vodPlaying == null) playing?.let { play(it, banner = true) } else { player?.play(); playSince = System.currentTimeMillis() }; publishNp(); changed(); ok }
            "vod" -> {
                val id = j.optString("id"); if (id.isEmpty()) return err("id missing")
                val ep = j.optString("kind") == "episode"
                val m = Movie(id, j.optString("title", "?"), "", if (ep) j.optString("series") else "", j.optString("poster"), 0f, j.optString("ext", "mkv").ifEmpty { "mkv" }, 0L, false, ep)
                hideOverlay(); home.visibility = View.GONE
                playVod(m, false, if (j.has("pos_ms")) j.getLong("pos_ms") else null); ok.put("title", m.title)
            }
            "continue" -> continueOther(j.optString("from").takeIf { it.isNotEmpty() })?.let { ok.put("title", it) } ?: err("nothing to continue")
            "config" -> {
                if (j.has("name")) prefs.sync.deviceName = j.getString("name")
                if (j.has("yield")) prefs.sync.yieldToOthers = j.getBoolean("yield")
                publishNp(quiet = true); changed(); ok
            }
            "info" -> { showInfo(); ok }
            "home" -> { moveTaskToBack(true); ok }
            "refresh" -> { scope.launch { try { repo.refresh(); applyData(false); changed() } catch (e: Exception) { flashBanner("Reload failed: ${e.message}") } }; ok }
            "favourite" -> {
                val id = j.optString("channel_id").ifEmpty { playing?.id ?: "" }
                if (repo.channel(id) == null) return err("unknown channel")
                repo.setFav(id, if (j.has("on")) j.getBoolean("on") else null); refreshLists(); changed(); ok.put("fav", repo.isFav(id))
            }
            "survey" -> {
                val g = repo.resolveGroup(j.optString("group").ifEmpty { playCtx }) ?: return err("no matching group")
                startSurvey(g.id, j.optInt("seconds", 6)); ok.put("group", g.name).put("total", repo.channels(g.id).size)
            }
            "survey_stop" -> { stopSurvey(); ok }
            else -> err("unknown command '$cmd'")
        }
    }

    private fun refreshLists() {
        if (repo.groups.none { it.id == browseGroup }) browseGroup = repo.groups.first { !Repo.pseudo(it.id) }.id
        val g = repo.groups.first { it.id == browseGroup }
        browseRegion = g.region
        // only touch the adapters when something changed: notifyDataSetChanged() drops D-pad focus
        val rs = repo.regions; if (regionAdapter.items != rs) regionAdapter.setAll(rs)
        regionAdapter.activeIdx = regionAdapter.items.indexOfFirst { it.id == browseRegion }
        val gs = repo.groupsIn(browseRegion); if (groupAdapter.items != gs) groupAdapter.setAll(gs)
        groupAdapter.activeIdx = groupAdapter.items.indexOfFirst { it.id == browseGroup }
        showChannels(browseGroup)
    }

    private fun toggleFavAt(pos: Int): Boolean {
        val c = chanAdapter.items.getOrNull(pos) ?: return false
        val now = !repo.isFav(c.id)
        repo.setFav(c.id, now); refreshLists(); changed()
        flashBanner(if (now) "★  Added to Favourites  ·  ${c.name}" else "☆  Removed from Favourites  ·  ${c.name}")
        focusPos(chanRv, pos.coerceAtMost(chanAdapter.items.size - 1).coerceAtLeast(0))
        return true
    }

    // ---------------------------------------------------------------- quality survey
    /** Tunes each channel of a group in turn and records the real resolution (one stream at a time: the account allows one). */
    private fun startSurvey(gid: String, secs: Int) {
        surveyJob?.cancel()
        val list = repo.channels(gid); if (list.isEmpty()) return
        val back = playing; val backCtx = playCtx
        surveyJob = scope.launch {
            try {
                survey = Triple(gid, 0, list.size); changed()
                list.forEachIndexed { i, ch ->
                    banner.text = "Quality survey ${i + 1}/${list.size} · ${ch.name} · BACK to stop"; banner.visibility = View.VISIBLE; bannerJob?.cancel()
                    play(ch, ctx = gid)
                    var waited = 0
                    while (tech == null && errId != ch.id && waited < 15000) { kotlinx.coroutines.delay(250); waited += 250 }
                    if (errId == ch.id) prefs.setPicture(ch.id, "error")
                    else {
                        var shown = false; val dwell = secs.coerceIn(2, 30) * 1000L / 3
                        repeat(3) { kotlinx.coroutines.delay(dwell); if (maxLuma() >= 24) shown = true }
                        prefs.setPicture(ch.id, if (shown) "ok" else "black")
                    }
                    survey = Triple(gid, i + 1, list.size); changed()
                }
            } finally {
                survey = null; banner.visibility = View.GONE
                val liveN = list.count { prefs.picture(it.id)?.first == "ok" }; val offN = list.count { prefs.picture(it.id)?.first.let { s -> s == "black" || s == "error" } }
                back?.let { play(it, ctx = backCtx) }
                refreshLists(); flashBanner("Check done: $liveN live, $offN off air – see ● Live")
                changed()
            }
        }
    }
    /** Brightest pixel (0-255) of a tiny copy of the current video frame, -1 if unavailable. */
    private fun maxLuma(): Int {
        val tv = playerView.videoSurfaceView as? android.view.TextureView ?: return -1
        val bmp = try { tv.getBitmap(48, 27) } catch (e: Exception) { null } ?: return -1
        val px = IntArray(48 * 27); bmp.getPixels(px, 0, 48, 0, 0, 48, 27); bmp.recycle()
        var m = 0; for (v in px) { val l = ((v shr 16 and 255) * 3 + (v shr 8 and 255) * 6 + (v and 255)) / 10; if (l > m) m = l }
        return m
    }

    private fun offTag(c: Channel): Pair<String, Int>? {
        val p = prefs.picture(c.id) ?: return null
        val fresh = System.currentTimeMillis() / 1000 - p.second < 6 * 3600
        return if (fresh && (p.first == "black" || p.first == "error")) Pair("OFF", Color.parseColor("#FF9F0A")) else null
    }

    private fun stopSurvey() { surveyJob?.cancel(); surveyJob = null }

    // ---------------------------------------------------------------- adapter
    inner class RowAdapter<T>(
        val label: (T) -> String, val sub: (T) -> String,
        val onClick: (Int) -> Unit, val onLong: ((Int) -> Boolean)?, val onFocus: ((Int) -> Unit)?,
        val icon: ((T) -> String)? = null, val tag: ((T) -> Pair<String, Int>?)? = null,
        val fav: ((T) -> Boolean)? = null, val glyph: ((T) -> String?)? = null
    ) : RecyclerView.Adapter<RowAdapter<T>.VH>() {
        var items: MutableList<T> = ArrayList()
        var activeIdx = -1
        var movingIdx = -1
        /** Updates rows in place (never notifyDataSetChanged) so a list that holds D-pad focus keeps it. */
        fun setAll(l: List<T>) {
            val old = items; val nw = ArrayList(l)
            var p = 0; while (p < old.size && p < nw.size && old[p] == nw[p]) p++
            var sfx = 0; while (sfx < old.size - p && sfx < nw.size - p && old[old.size - 1 - sfx] == nw[nw.size - 1 - sfx]) sfx++
            val oldMid = old.size - p - sfx; val newMid = nw.size - p - sfx
            items = nw
            val common = minOf(oldMid, newMid)
            if (common > 0) notifyItemRangeChanged(p, common)
            if (oldMid > newMid) notifyItemRangeRemoved(p + common, oldMid - newMid)
            else if (newMid > oldMid) notifyItemRangeInserted(p + common, newMid - oldMid)
            if (nw.isNotEmpty()) notifyItemRangeChanged(0, nw.size)   // rows also show state that is not in the item (stars, OFF tags)
        }
        fun move(from: Int, to: Int) { val x = items.removeAt(from); items.add(to, x); notifyItemMoved(from, to) }

        inner class VH(v: LinearLayout, val name: TextView, val count: TextView, val logo: ImageView?, val tagView: TextView, val star: TextView) : RecyclerView.ViewHolder(v)

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val row = LinearLayout(p.context).apply {
                orientation = LinearLayout.HORIZONTAL; isFocusable = true; isClickable = true
                background = rowBg(); setPadding(dp(16), dp(10), dp(16), dp(10))
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }
            }
            val logo = if (icon != null) ImageView(p.context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(3), dp(3), dp(3), dp(3))
                background = GradientDrawable().apply { setColor(Neon.withAlpha(Color.WHITE, 235)); cornerRadius = dp(8).toFloat() }
            } else null
            if (logo != null) row.addView(logo, LinearLayout.LayoutParams(dp(54), dp(40)).apply { rightMargin = dp(12); gravity = Gravity.CENTER_VERTICAL })
            val tagTv = TextView(p.context).apply { textSize = 14f; setTypeface(null, Typeface.BOLD); setPadding(dp(10), 0, 0, 0) }
            val n = TextView(p.context).apply { setTextColor(Neon.TEXT); textSize = 20f; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }
            val c = TextView(p.context).apply { setTextColor(Neon.MUTED); textSize = 16f; setPadding(dp(12), 0, 0, 0) }
            val starTv = TextView(p.context).apply { text = "★"; setTextColor(Color.parseColor("#FFD60A")); textSize = 24f; setPadding(0, 0, dp(10), 0); visibility = View.GONE }
            row.addView(starTv, LinearLayout.LayoutParams(-2, -2))
            row.addView(n, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(tagTv, LinearLayout.LayoutParams(-2, -2)); row.addView(c, LinearLayout.LayoutParams(-2, -2))
            row.gravity = Gravity.CENTER_VERTICAL
            val vh = VH(row, n, c, logo, tagTv, starTv)
            row.setOnClickListener { val i = vh.bindingAdapterPosition; if (i >= 0) onClick(i) }
            row.setOnLongClickListener { val i = vh.bindingAdapterPosition; i >= 0 && onLong?.invoke(i) == true }
            row.setOnFocusChangeListener { _, f -> if (f) { val i = vh.bindingAdapterPosition; if (i >= 0) onFocus?.invoke(i) } }
            return vh
        }

        override fun onBindViewHolder(h: VH, i: Int) {
            val it = items[i]
            h.name.text = (if (i == movingIdx) "⇅  " else "") + Neon.plain(label(it)); h.count.text = sub(it)
            h.itemView.isSelected = i == activeIdx
            h.star.visibility = if (fav?.invoke(it) == true) View.VISIBLE else if (fav != null) View.INVISIBLE else View.GONE
            val tg = tag?.invoke(it); h.tagView.text = tg?.first ?: ""; h.tagView.visibility = if (tg == null) View.GONE else View.VISIBLE; if (tg != null) h.tagView.setTextColor(tg.second)
            val url = icon?.invoke(it)
            if (h.logo != null) { h.logo.alpha = 1f; h.logo.background = GradientDrawable().apply { setColor(if (url.isNullOrEmpty()) Color.parseColor("#1B2650") else Neon.withAlpha(Color.WHITE, 235)); cornerRadius = dp(8).toFloat() }; if (!url.isNullOrEmpty()) h.logo.load(url) { size(108, 80); crossfade(false); listener(onError = { _, _ -> h.logo.background = GradientDrawable().apply { setColor(Color.parseColor("#1B2650")); cornerRadius = dp(8).toFloat() }; h.logo.setImageDrawable(InitialsDrawable(Neon.plain(label(it)).removePrefix("PL: ").take(2).uppercase(), dp(40))) }) } else { h.logo.dispose(); h.logo.setImageDrawable(InitialsDrawable(Neon.plain(label(it)).removePrefix("PL: ").take(2).uppercase(), dp(40))) } }
            val hdr = (it as? Channel)?.isHeader == true
            h.itemView.isFocusable = !hdr; h.itemView.isClickable = !hdr
            if (hdr) { h.logo?.visibility = View.GONE; h.count.visibility = View.GONE; h.itemView.background = null; h.name.setTextColor(Neon.CYAN); h.name.textSize = 15f; h.name.letterSpacing = 0.15f; h.star.visibility = View.GONE; h.tagView.visibility = View.GONE }
            else { h.logo?.visibility = View.VISIBLE; h.count.visibility = View.VISIBLE; h.name.setTextColor(Neon.TEXT); h.name.textSize = 20f; h.name.letterSpacing = 0f }
            h.name.setCompoundDrawablesRelativeWithIntrinsicBounds(glyph?.invoke(it)?.let { g -> GlyphDrawable(g, dp(22)) }, null, null, null); h.name.compoundDrawablePadding = dp(12)
            h.name.setTypeface(null, if (i == activeIdx || i == movingIdx) Typeface.BOLD else Typeface.NORMAL)
            h.itemView.setBackgroundColor(Color.TRANSPARENT)
            if (!hdr) h.itemView.background = if (i == movingIdx) GradientDrawable().apply { setColor(Color.parseColor("#F2994A")); cornerRadius = dp(8).toFloat() } else rowBg()
        }

        override fun getItemCount() = items.size
    }
}

/** Small drawn icons for the pseudo regions (system emoji ignored our colours and looked out of place). */
class GlyphDrawable(private val kind: String, private val size: Int) : android.graphics.drawable.Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Neon.CYAN }
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun draw(c: Canvas) {
        val s = size.toFloat(); p.strokeWidth = s * 0.11f
        if (kind == "star") {
            p.style = Paint.Style.FILL; p.color = Color.parseColor("#FFD60A")
            val path = Path(); val cx = s / 2; val cy = s * 0.53f; val ro = s * 0.46f; val ri = ro * 0.42f
            for (i in 0 until 10) { val r = if (i % 2 == 0) ro else ri; val a = Math.PI * i / 5 - Math.PI / 2
                val x = (cx + r * Math.cos(a)).toFloat(); val y = (cy + r * Math.sin(a)).toFloat(); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            path.close(); c.drawPath(path, p); return
        }
        if (kind == "live") {
            p.style = Paint.Style.STROKE; c.drawCircle(s / 2, s / 2, s * 0.40f, p)
            p.style = Paint.Style.FILL; c.drawCircle(s / 2, s / 2, s * 0.17f, p)
        } else {
            p.style = Paint.Style.STROKE; c.drawCircle(s * 0.42f, s * 0.42f, s * 0.28f, p)
            c.drawLine(s * 0.63f, s * 0.63f, s * 0.90f, s * 0.90f, p)
        }
    }
    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(f: ColorFilter?) { p.colorFilter = f }
    @Deprecated("", ReplaceWith("PixelFormat.TRANSLUCENT")) override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Empty-logo placeholder: the channel's first two letters on a dark tile. */
class InitialsDrawable(private val text: String, private val h: Int) : android.graphics.drawable.Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Neon.MUTED; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    override fun draw(c: Canvas) { p.textSize = bounds.height() * 0.5f; c.drawText(text, bounds.exactCenterX(), bounds.exactCenterY() + p.textSize * 0.35f, p) }
    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(f: ColorFilter?) {}
    @Deprecated("", ReplaceWith("PixelFormat.TRANSLUCENT")) override fun getOpacity() = PixelFormat.TRANSLUCENT
}
