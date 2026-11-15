package pl.home.channeldeck

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import coil.dispose
import coil.load
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class EpgState { LOADING, NO_GUIDE, EMPTY, OK }

/** Channel card: logo, number/name, now + progress + next, and measured technical data. */
class InfoPanel(ctx: Context) : LinearLayout(ctx) {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val logo = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(6), dp(6), dp(6), dp(6)); background = rr("#22FFFFFF") }
    private val title = tv(26f, true)
    private val nowLine = tv(20f, false)
    private val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressDrawable.setTint(Neon.CYAN) }
    private val nextLine = tv(17f, false).apply { setTextColor(Neon.MUTED) }
    private val techLine = tv(17f, false)

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14)); background = GradientDrawable().apply { setColor(Color.parseColor("#F00B1330")); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Neon.withAlpha(Neon.BLUE, 150)) }
        addView(logo, LayoutParams(dp(92), dp(92)).apply { rightMargin = dp(18) })
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        col.addView(title); col.addView(nowLine)
        col.addView(bar, LayoutParams(-1, dp(8)).apply { topMargin = dp(6); bottomMargin = dp(6) })
        col.addView(nextLine); col.addView(techLine, LayoutParams(-2, -2).apply { topMargin = dp(6) })
        addView(col, LayoutParams(0, -2, 1f))
    }

    private fun rr(c: String) = GradientDrawable().apply { setColor(Color.parseColor(c)); cornerRadius = dp(12).toFloat() }
    private fun tv(sp: Float, bold: Boolean) = TextView(context).apply {
        textSize = sp; setTextColor(Neon.TEXT); setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    fun bind(ch: Channel, group: String, state: EpgState, progs: List<Prog>, tech: Tech?, measuring: Boolean) {
        if (ch.icon.isNotEmpty()) logo.load(ch.icon) { size(184, 184); crossfade(false) } else { logo.dispose(); logo.setImageDrawable(null) }
        title.text = "${ch.num}  ·  ${Neon.plain(ch.name)}"

        val now = System.currentTimeMillis() / 1000
        val cur = progs.firstOrNull { it.start <= now && now < it.stop }
        val nxt = progs.firstOrNull { it.start >= (cur?.stop ?: now) && it.start > now - 1 && it !== cur }
        when {
            state == EpgState.LOADING -> { nowLine.text = "Loading programme info…"; nowLine.setTextColor(Quality.GREY) }
            state == EpgState.NO_GUIDE -> { nowLine.text = "No programme guide for this channel"; nowLine.setTextColor(Quality.GREY) }
            cur == null -> { nowLine.text = if (state == EpgState.EMPTY) "No programme info right now" else "Programme data out of date"; nowLine.setTextColor(Quality.GREY) }
            else -> {
                val left = ((cur.stop - now) / 60).toInt().coerceAtLeast(0)
                nowLine.text = "Now  ${fmt(cur.start)}–${fmt(cur.stop)}   ${cur.title}   ·   ends in ${dur(left)}"
                nowLine.setTextColor(Color.WHITE)
                bar.progress = (((now - cur.start) * 1000) / (cur.stop - cur.start).coerceAtLeast(1)).toInt().coerceIn(0, 1000)
            }
        }
        bar.visibility = if (cur != null && state == EpgState.OK) View.VISIBLE else View.INVISIBLE
        nextLine.text = if (nxt != null && state == EpgState.OK) "Next  ${fmt(nxt.start)}   ${nxt.title}" else ""
        nextLine.visibility = if (nextLine.text.isEmpty()) View.GONE else View.VISIBLE

        val claim = Quality.claimed(ch.name)
        val sb = SpannableStringBuilder()
        fun add(t: String, c: Int, bold: Boolean = false) {
            val s = sb.length; sb.append(t); sb.setSpan(ForegroundColorSpan(c), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (tech == null) {
            add(if (measuring) "Measuring real quality…" else "Quality: not measured yet", Quality.GREY)
            if (claim > 0) add("   (name claims ${Quality.short(claim)})", Quality.GREY)
        } else {
            val a = Quality.actual(tech.w, tech.h)
            add("${tech.w}×${tech.h}  ${Quality.label(a)}", Color.WHITE, true)
            if (tech.fps > 0) add("  ·  ${tech.fps} fps", Color.WHITE)
            add("  ·  ${tech.vcodec}", Color.WHITE)
            if (tech.acodec.isNotEmpty()) add("  ·  ${tech.acodec}${if (tech.ach > 0) " ${chan(tech.ach)}" else ""}", Color.WHITE)
            if (tech.maxH > tech.h) add("  ·  best available ${tech.maxH}p", Quality.GREY)
            Quality.verdict(ch.name, tech)?.takeIf { it.text.isNotEmpty() }?.let { add("\n" + it.text, it.color, true) }
        }
        techLine.setSingleLine(false); techLine.maxLines = 3
        techLine.text = sb
        if (group.isNotEmpty()) title.contentDescription = group
    }

    private fun fmt(sec: Long) = tf.format(Date(sec * 1000))
    private fun dur(min: Int) = if (min >= 60) "${min / 60}h ${min % 60}min" else "$min min"
    private fun chan(n: Int) = when (n) { 1 -> "mono"; 2 -> "2.0"; 6 -> "5.1"; 8 -> "7.1"; else -> "${n}ch" }
}
