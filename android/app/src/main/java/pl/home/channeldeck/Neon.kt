package pl.home.channeldeck

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Colour system: deep navy ground, electric blue structure, neon yellow = focus (and the orb's highlight). */
object Neon {
    /** "[ BACK ] to cancel" – outlined key chip followed by a muted label. */
    fun keyHint(ctx: Context, key: String, label: String): View {
        val d = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, (12 * d).toInt(), 0, 0) }
        row.addView(TextView(ctx).apply {
            text = key; setTextColor(TEXT); textSize = 13f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = 0.1f
            setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 8 * d; setStroke((1.5f * d).toInt(), withAlpha(CYAN, 200)); setColor(withAlpha(BLUE, 40)) }
        })
        row.addView(TextView(ctx).apply { text = label; setTextColor(MUTED); textSize = 15f; setPadding((10 * d).toInt(), 0, 0, 0) })
        return row
    }
    val BG0 = Color.parseColor("#04060F")
    val BG1 = Color.parseColor("#0B1330")
    val BLUE = Color.parseColor("#2563FF")
    val CYAN = Color.parseColor("#22B8FF")
    val YELLOW = Color.parseColor("#F4FF1F")
    val TEXT = Color.parseColor("#F2F6FF")
    val MUTED = Color.parseColor("#9FB0D6")
    fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    /** Provider names use superscript letters ("ᴴᴰ", "ᴿᴬᵂ"); show them as plain caps, and drop a leading "PL|" style prefix. */
    fun plain(s: String): String {
        val m = mapOf('ᴴ' to 'H', 'ᴰ' to 'D', 'ᴿ' to 'R', 'ᴬ' to 'A', 'ᵂ' to 'W', 'ᵁ' to 'U', 'ⱽ' to 'V', 'ᴵ' to 'I', 'ᴾ' to 'P', 'ᴸ' to 'L', 'ᴮ' to 'B', 'ᴼ' to 'O', 'ʸ' to 'y', 'ᵀ' to 'T',
            'ʰ' to 'h', 'ᵉ' to 'e', 'ᵛ' to 'v', 'ᶜ' to 'c', 'ˢ' to 's', '⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4', '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9')
        return s.map { m[it] ?: it }.joinToString("").replace(Regex("^[A-Za-z]{2,3}\\s*\\|\\s*"), "").replace(Regex("^#{2,}\\s*|\\s*#{2,}$"), "").trim()
    }

    /** Dark gradient + two static soft glows, built once. Cheap, and gives the flat navy some depth. */
    class Backdrop : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onBoundsChange(b: Rect) {}
        override fun draw(c: Canvas) {
            val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
            p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(BG1, BG0), null, Shader.TileMode.CLAMP); c.drawRect(bounds, p)
            p.shader = RadialGradient(w * 0.12f, h * 0.05f, w * 0.45f, withAlpha(BLUE, 40), Color.TRANSPARENT, Shader.TileMode.CLAMP); c.drawRect(bounds, p)
            p.shader = RadialGradient(w * 0.92f, h * 0.98f, w * 0.40f, withAlpha(CYAN, 22), Color.TRANSPARENT, Shader.TileMode.CLAMP); c.drawRect(bounds, p)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(f: ColorFilter?) {}
        @Deprecated("", ReplaceWith("PixelFormat.OPAQUE")) override fun getOpacity() = PixelFormat.OPAQUE
    }
}

/**
 * Voice-assistant style orb: calm, near-circular layered rings (low-harmonic waves), a slowly rotating blue/cyan/yellow sweep
 * and a breathing core glow. [energy] 0..1: ~0.2 idle, 1.0 loading. [kick] gives a short ripple (e.g. on focus change).
 */
class OrbView(ctx: Context) : View(ctx) {
    var energy = 0.4f
        set(v) { field = v.coerceIn(0f, 1f) }
    /** 1 = normal; >1 adds extra halo passes around the rings (used by the splash). */
    var glow = 1f
    private var boost = 0f
    private var t = 0f
    private var phase = 0f
    private var animator: ValueAnimator? = null
    private val d = ctx.resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val m = Matrix()
    private var sweep: SweepGradient? = null
    private var lastNs = 0L
    private val colors = intArrayOf(Neon.BLUE, Neon.CYAN, Color.parseColor("#3A8CFF"), Neon.YELLOW, Neon.YELLOW, Color.parseColor("#3A8CFF"), Neon.CYAN, Neon.BLUE)
    private var bornAt = 0L

    // radius factor, harmonic, wave speed, rotation (deg/s), stroke (dp), alpha
    private data class Ring(val r: Float, val k: Int, val ws: Float, val rot: Float, val w: Float, val a: Float)
    private val rings = listOf(
        Ring(0.84f, 2, 0.30f, 45f, 3.6f, 1f), Ring(0.74f, 3, -0.26f, -36f, 3.0f, 0.85f),
        Ring(0.64f, 2, 0.22f, 30f, 2.6f, 0.7f), Ring(0.54f, 3, -0.18f, -24f, 2.2f, 0.55f)
    )

    fun kick() { boost = 1f }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bornAt = System.nanoTime()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener {
                val now = System.nanoTime(); val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceAtMost(0.1f); lastNs = now
                t += dt; phase += dt * (0.5f + 1.4f * (energy + 0.4f * boost)); boost = (boost - dt * 1.6f).coerceAtLeast(0f); invalidate()
            }
            start()
        }
    }
    override fun onDetachedFromWindow() { animator?.cancel(); animator = null; lastNs = 0L; super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); if (v != VISIBLE) lastNs = 0L }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sweep = SweepGradient(w / 2f, h / 2f, colors, floatArrayOf(0f, 0.28f, 0.39f, 0.46f, 0.54f, 0.61f, 0.72f, 1f))
        glowPaint.shader = RadialGradient(w / 2f, h / 2f, minOf(w, h) / 2f * 0.95f,
            intArrayOf(Neon.withAlpha(Neon.BLUE, 95), Neon.withAlpha(Neon.BLUE, 30), Color.TRANSPARENT), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val fade = ((System.nanoTime() - bornAt) / 1e9f / 0.35f).coerceIn(0f, 1f)          // 350 ms fade-in on first show
        val cx = width / 2f; val cy = height / 2f; val base = minOf(width, height) / 2f * 0.96f
        val breathe = 1f + 0.035f * (0.5f - 0.5f * cos(t * 2f * PI.toFloat() / 3.2f))        // 3.2 s period, +3.5 %
        c.save(); c.scale(breathe, breathe, cx, cy)
        glowPaint.alpha = (255 * fade * (0.75f + 0.25f * (breathe - 1f) / 0.035f)).toInt().coerceIn(0, 255)
        c.drawCircle(cx, cy, base * 1.02f, glowPaint)
        val amp = base * (0.012f + 0.030f * energy + 0.03f * boost)                           // outer ~1-4 % of R: reads as a circle, not a blob
        rings.forEachIndexed { i, ring ->
            val sw = sweep ?: return@forEachIndexed
            m.setRotate(t * ring.rot * (0.35f + 0.65f * energy), cx, cy); sw.setLocalMatrix(m)
            ringPaint.shader = sw
            path.reset()
            val n = 96; val r0 = base * ring.r
            for (j in 0..n) {
                val th = (j.toFloat() / n) * 2f * PI.toFloat()
                val wv = sin(ring.k * th + phase * ring.ws * 3f + i) * 0.75f + sin((ring.k + 1) * th - phase * ring.ws * 2f) * 0.25f
                val r = r0 + amp * (0.6f + 0.15f * i) * wv
                val x = cx + r * cos(th); val y = cy + r * sin(th)
                if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            val passes = if (glow > 1f) listOf(ring.w * 9f to 0.05f * glow, ring.w * 5f to 0.10f * glow, ring.w * 2.6f to 0.22f * glow, ring.w to 1f)
                         else listOf(ring.w * 3f to 0.12f, ring.w to 1f)
            for ((wd, al) in passes) {                   // wide faint pass = glow, no blur filter
                ringPaint.strokeWidth = wd * d; ringPaint.alpha = (255 * al * ring.a * fade).toInt().coerceIn(0, 255)
                c.drawPath(path, ringPaint)
            }
        }
        c.restore()
    }
}

/** Full-screen loading splash: orb, wordmark, one pulsing status line. */
class SplashView(ctx: Context) : FrameLayout(ctx) {
    val orb = OrbView(ctx).apply { energy = 1f; glow = 2.2f }
    val title = TextView(ctx)
    val status = TextView(ctx)
    private val d = ctx.resources.displayMetrics.density
    private val shownAt = System.currentTimeMillis()
    private val slow = Runnable { if (visibility == VISIBLE && status.text.toString().startsWith("Loading")) status.text = "Still loading…  the first start can take a while" }
    private var pulse: ValueAnimator? = null
    init {
        background = Neon.Backdrop()
        isClickable = true
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(orb, LinearLayout.LayoutParams((250 * d).toInt(), (250 * d).toInt()))
        title.apply {
            text = "HA IPTV"; setTextColor(Neon.TEXT); textSize = 30f; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            letterSpacing = 0.4f; gravity = Gravity.CENTER; setPadding(0, (22 * d).toInt(), 0, (8 * d).toInt())
        }
        status.apply { setTextColor(Neon.MUTED); textSize = 20f; gravity = Gravity.CENTER; minLines = 2 }
        col.addView(title); col.addView(status)
        addView(col, LayoutParams(-2, -2, Gravity.CENTER))
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(slow, 8000)
        pulse = ValueAnimator.ofFloat(0.8f, 1f).apply { duration = 1500; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
            addUpdateListener { status.alpha = it.animatedValue as Float }; start() }
    }
    override fun onDetachedFromWindow() { removeCallbacks(slow); pulse?.cancel(); super.onDetachedFromWindow() }
    fun setStatus(s: String) { status.text = s }
}

private fun dp(c: Context, v: Float) = v * c.resources.displayMetrics.density

/**
 * Launcher tile. The view includes [HALO] dp of padding on every side so the pre-baked focus bloom and the 1.06x lift are never clipped.
 */
class TileView(ctx: Context, val kind: String, val label: String, var sub: String, var soon: Boolean = false) : View(ctx) {
    companion object { const val HALO = 16f }
    private val d = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val card = RectF()
    private var bloom: Bitmap? = null
    private val dst = DecelerateInterpolator(); private val acc = AccelerateInterpolator()
    var onFocused: (() -> Unit)? = null
    init {
        isFocusable = true; isClickable = true
        setOnFocusChangeListener { _, f ->
            animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(if (f) 180 else 140).setInterpolator(if (f) dst else acc).start()
            if (f) onFocused?.invoke(); invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val pad = HALO * d
        card.set(pad, pad, w - pad, h - pad)
        // bake the focus bloom once: a blurred yellow rounded rect drawn into a bitmap (blur is fine on a bitmap canvas, never per frame)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val bc = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7 * d; color = Neon.withAlpha(Neon.YELLOW, 150); maskFilter = BlurMaskFilter(11 * d, BlurMaskFilter.Blur.NORMAL) }
        bc.drawRoundRect(card, 24 * d, 24 * d, p)
        bloom?.recycle(); bloom = b
    }

    override fun onDraw(c: Canvas) {
        val f = isFocused; val w = width.toFloat(); val h = height.toFloat(); val rad = 24 * d
        if (f) bloom?.let { c.drawBitmap(it, 0f, 0f, null) }
        fill.shader = LinearGradient(0f, card.top, 0f, card.bottom,
            if (f) intArrayOf(Color.parseColor("#172863"), Color.parseColor("#0E1A47")) else intArrayOf(Color.parseColor("#101C45"), Color.parseColor("#0A1130")), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(card, rad, rad, fill)
        // lit-glass top highlight
        line.shader = null; line.strokeWidth = 1f * d; line.color = Neon.withAlpha(Color.WHITE, if (f) 40 else 22)
        c.drawLine(card.left + rad, card.top + 1.5f * d, card.right - rad, card.top + 1.5f * d, line)
        // outline: inset by half the stroke so it is never cut at the corners
        val sw = (if (f) 3f else 1.2f) * d
        line.strokeWidth = sw; line.color = if (f) Neon.YELLOW else Neon.withAlpha(Neon.BLUE, 140)
        val r2 = RectF(card.left + sw / 2, card.top + sw / 2, card.right - sw / 2, card.bottom - sw / 2); c.drawRoundRect(r2, rad - sw / 2, rad - sw / 2, line)

        val accent = if (f) Neon.YELLOW else Neon.CYAN
        val cy = card.top + card.height() * (if (f) 0.34f else 0.36f)
        drawIcon(c, w / 2f, cy, 62 * d, accent, if (soon && !f) 0.55f else 1f)

        txt.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); txt.textAlign = Paint.Align.CENTER
        txt.color = Neon.TEXT; txt.textSize = 23f * d
        val ty = card.top + card.height() * 0.73f - (if (f) 6 * d else 0f)
        c.drawText(label, w / 2f, ty, txt)
        txt.typeface = Typeface.DEFAULT; txt.textSize = 15f * d; txt.color = Neon.MUTED
        c.drawText(sub, w / 2f, ty + 24 * d, txt)
        if (soon) {
            val pw = 58 * d; val ph = 26 * d
            val pr = RectF(card.right - pw - 14 * d, card.top + 14 * d, card.right - 14 * d, card.top + 14 * d + ph)
            fill.shader = null; fill.color = Neon.withAlpha(Neon.YELLOW, 36); c.drawRoundRect(pr, ph / 2, ph / 2, fill)
            txt.textSize = 13f * d; txt.color = Color.parseColor("#B8C4E8"); txt.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            c.drawText("SOON", pr.centerX(), pr.centerY() + 4.5f * d, txt)
        }
    }

    private fun drawIcon(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, alpha: Float) {
        line.shader = null; line.color = color; line.alpha = (255 * alpha).toInt(); line.strokeWidth = 3.4f * d
        val h = s / 2
        when (kind) {
            "live" -> {
                val r = RectF(cx - h, cy - h * 0.68f, cx + h, cy + h * 0.5f); c.drawRoundRect(r, 8 * d, 8 * d, line)
                c.drawLine(cx - h * 0.45f, cy + h * 0.5f + 12 * d, cx + h * 0.45f, cy + h * 0.5f + 12 * d, line)
                c.drawLine(cx, cy + h * 0.5f, cx, cy + h * 0.5f + 12 * d, line)
                val p = Path().apply { moveTo(cx - h * 0.16f, cy - h * 0.3f); lineTo(cx + h * 0.28f, cy - h * 0.06f); lineTo(cx - h * 0.16f, cy + h * 0.18f); close() }
                c.drawPath(p, line)
            }
            "movies" -> {
                val r = RectF(cx - h, cy - h * 0.62f, cx + h, cy + h * 0.62f); c.drawRoundRect(r, 8 * d, 8 * d, line)
                c.drawLine(cx - h * 0.62f, r.top, cx - h * 0.62f, r.bottom, line); c.drawLine(cx + h * 0.62f, r.top, cx + h * 0.62f, r.bottom, line)
                for (i in 0..2) { val y = r.top + (i + 0.9f) * r.height() / 3.4f
                    c.drawLine(cx - h * 0.86f, y, cx - h * 0.78f, y, line); c.drawLine(cx + h * 0.78f, y, cx + h * 0.86f, y, line) }
            }
            "series" -> {
                for (i in 2 downTo 0) { val o = i * 9 * d
                    val r = RectF(cx - h * 0.8f + o, cy - h * 0.55f - o, cx + h * 0.8f + o, cy + h * 0.45f - o)
                    line.alpha = ((255 - i * 70) * alpha).toInt(); c.drawRoundRect(r, 7 * d, 7 * d, line) }
            }
            else -> {
                c.drawCircle(cx, cy, h * 0.28f, line); c.drawCircle(cx, cy, h * 0.62f, line)
                for (i in 0 until 8) { val a = i * PI.toFloat() / 4
                    c.drawLine(cx + cos(a) * h * 0.72f, cy + sin(a) * h * 0.72f, cx + cos(a) * h * 0.95f, cy + sin(a) * h * 0.95f, line) }
            }
        }
        line.alpha = 255
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) { performClick(); return true }
        return super.onKeyUp(keyCode, event)
    }
}

/**
 * Wide focusable row. Draws its own rounded background + inset outline (constant padding, so text never shifts on focus).
 * [big] = hero style used for "Resume": logo at left, play button at right.
 */
class NeonRow(ctx: Context, val title: String, var value: String, val big: Boolean = false) : LinearLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    val titleTv = TextView(ctx); val valueTv = TextView(ctx)
    val logo = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
        setWillNotDraw(false); clipChildren = false
        val hp = (24 * d).toInt()
        setPadding(hp, 0, if (big) (96 * d).toInt() else hp, 0)
        minimumHeight = ((if (big) 112 else 76) * d).toInt()
        setPadding(hp, (12 * d).toInt(), if (big) (96 * d).toInt() else hp, (12 * d).toInt())
        if (big) {
            logo.background = android.graphics.drawable.GradientDrawable().apply { setColor(Neon.withAlpha(Color.WHITE, 235)); cornerRadius = 14 * d }
            logo.setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            addView(logo, LayoutParams((76 * d).toInt(), (76 * d).toInt()).apply { rightMargin = (20 * d).toInt() })
        }
        val col = LinearLayout(ctx).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        titleTv.apply { text = title; setTextColor(Neon.TEXT); textSize = if (big) 28f else 21f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setSingleLine() }
        valueTv.apply { text = value; setTextColor(Neon.MUTED); textSize = if (big) 20f else 16f; setPadding(0, (2 * d).toInt(), 0, 0) }
        col.addView(titleTv); col.addView(valueTv)
        addView(col, LayoutParams(0, -2, 1f))
        setOnFocusChangeListener { _, _ -> invalidate() }   // no scale: it clipped the ring and shifted the text
    }
    fun set(v: String) { value = v; valueTv.text = v }

    override fun onDraw(c: Canvas) {
        val f = isFocused; val rad = 20 * d
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        fill.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, if (f) intArrayOf(Color.parseColor("#172863"), Color.parseColor("#0F1B49")) else intArrayOf(Color.parseColor("#0F1A42"), Color.parseColor("#0A1130")), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, rad, rad, fill)
        val sw = (if (f) 3f else 1.2f) * d
        line.strokeWidth = sw; line.color = if (f) Neon.YELLOW else Neon.withAlpha(Neon.BLUE, 130)
        val r2 = RectF(sw / 2, sw / 2, width - sw / 2, height - sw / 2); c.drawRoundRect(r2, rad - sw / 2, rad - sw / 2, line)
        if (big) { // play button
            val cx = width - 52 * d; val cy = height / 2f; val r = 28 * d
            if (f) { fill.shader = null; fill.color = Neon.YELLOW; c.drawCircle(cx, cy, r, fill) }
            else { line.strokeWidth = 2f * d; line.color = Neon.CYAN; c.drawCircle(cx, cy, r, line) }
            val tri = Path().apply { moveTo(cx - 8 * d, cy - 12 * d); lineTo(cx + 13 * d, cy); lineTo(cx - 8 * d, cy + 12 * d); close() }
            fill.shader = null; fill.color = if (f) Neon.BG0 else Neon.CYAN; c.drawPath(tri, fill)
        }
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) { performClick(); return true }
        return super.onKeyUp(keyCode, event)
    }
}

/** Home: orb + wordmark + clock, four tiles, resume hero. Everything sits on one 48dp margin / 3-gap grid. */
class HomeView(ctx: Context, val onLive: () -> Unit, val onResume: () -> Unit, val onSoon: (String) -> Unit, val onSettings: () -> Unit) : FrameLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    val orb = OrbView(ctx).apply { energy = 0.2f }
    private val clock = TextView(ctx); private val date = TextView(ctx)
    val live = TileView(ctx, "live", "Live TV", "…")
    val movies = TileView(ctx, "movies", "Movies", "Not available yet", soon = true)
    val series = TileView(ctx, "series", "Series", "Not available yet", soon = true)
    val settings = TileView(ctx, "settings", "Settings", "Format · API · About")
    val resume = NeonRow(ctx, "Resume", "", big = true)
    private val tick = object : Runnable { override fun run() { updateClock(); postDelayed(this, 20_000) } }

    init {
        background = Neon.Backdrop(); isClickable = true; clipChildren = false; clipToPadding = false
        val margin = (48 * d).toInt()
        val sw = resources.displayMetrics.widthPixels
        val content = sw - 2 * margin
        val gap = (12 * d).toInt(); val halo = (TileView.HALO * d).toInt()
        val tileW = (content - 3 * gap) / 4

        // header: orb left edge and clock right edge sit exactly on the content margins
        val hdr = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hdr.addView(orb, LinearLayout.LayoutParams((104 * d).toInt(), (104 * d).toInt()))
        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * d).toInt(), 0, 0, 0) }
        words.addView(TextView(context).apply { text = "HA IPTV"; setTextColor(Neon.TEXT); textSize = 26f; letterSpacing = 0.3f; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        words.addView(TextView(context).apply { text = "Live TV for Home Assistant"; setTextColor(Neon.MUTED); textSize = 15f })
        hdr.addView(words)
        hdr.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        val tc = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        clock.apply { setTextColor(Neon.TEXT); textSize = 36f; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); gravity = Gravity.END }
        date.apply { setTextColor(Neon.MUTED); textSize = 15f; gravity = Gravity.END }
        tc.addView(clock); tc.addView(date); hdr.addView(tc)
        addView(hdr, LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(margin, (22 * d).toInt(), margin, 0) })

        // tiles: each view carries `halo` padding, so neighbours overlap by 2*halo - gap (negative margin keeps the visible gap = 12dp)
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        val tiles = listOf(live, movies, series, settings)
        tiles.forEachIndexed { i, t ->
            row.addView(t, LinearLayout.LayoutParams(tileW + 2 * halo, (224 * d).toInt() + 2 * halo).apply { if (i > 0) leftMargin = gap - 2 * halo })
            t.onFocused = { orb.kick() }
        }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        col.addView(row, LinearLayout.LayoutParams(content + 2 * halo, -2).apply { leftMargin = -halo })
        col.addView(resume, LinearLayout.LayoutParams(content, -2).apply { topMargin = (14 * d).toInt() })
        addView(col, LayoutParams(content, -2, Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL).apply { topMargin = (68 * d).toInt() })

        live.setOnClickListener { onLive() }
        movies.setOnClickListener { onSoon("Movies") }
        series.setOnClickListener { onSoon("Series") }
        settings.setOnClickListener { onSettings() }
        resume.setOnClickListener { onResume() }
        updateClock()
    }

    private fun updateClock() {
        val c = java.util.Calendar.getInstance()
        val s = String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
        clock.text = SpannableString(s).apply { setSpan(ForegroundColorSpan(Neon.TEXT), 2, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        date.text = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault()).format(c.time)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    fun setResume(title: String?, sub: String?, icon: String? = null) {
        resume.visibility = if (title == null) View.GONE else View.VISIBLE
        if (title != null) {
            resume.titleTv.text = Neon.plain(title); resume.set(sub?.let { Neon.plain(it) } ?: "")
            if (!icon.isNullOrEmpty()) { resume.logo.visibility = View.VISIBLE; resume.logo.load(icon) } else resume.logo.visibility = View.GONE
        }
    }
    fun focusDefault() { (if (resume.visibility == View.VISIBLE) resume else live).requestFocus() }
}

/** Settings panel shown over Home. */
class SettingsPanel(ctx: Context, rows: List<NeonRow>, val onClose: () -> Unit) : FrameLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    init {
        background = Neon.Backdrop(); isClickable = true
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, (16 * d).toInt()) }
        head.addView(OrbView(ctx).apply { energy = 0.2f }, LinearLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt()))
        head.addView(TextView(ctx).apply { text = "Settings"; setTextColor(Neon.TEXT); textSize = 30f; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); setPadding((14 * d).toInt(), 0, 0, 0) })
        col.addView(head)
        rows.forEach { col.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * d).toInt() }) }
        col.addView(Neon.keyHint(ctx, "BACK", "to return"))
        addView(col, LayoutParams((640 * d).toInt(), -2, Gravity.CENTER))
        rows.firstOrNull()?.let { post { it.requestFocus() } }
    }
}
