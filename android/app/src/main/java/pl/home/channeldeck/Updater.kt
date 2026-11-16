package pl.home.channeldeck

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from the GitHub releases of this project. The release workflow attaches `ha-iptv-app-<versionName>.apk`; this compares that
 * version with the installed one, downloads the file and hands it to Android's PackageInstaller (the system asks for one confirmation).
 * Updates only install if the new APK is signed with the same key as the installed app (Android enforces this).
 */
class Updater(private val ctx: Context) {
    data class Release(val version: String, val notes: String, val url: String, val size: Long)

    private val sp = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)
    val installed: String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0" } catch (e: Exception) { "0" }
    /** A newer release found by the last check, if any. */
    @Volatile var available: Release? = null

    /** Startup check is throttled so we are polite to the (rate limited) GitHub API: at most once every 6 hours. */
    fun dueForAutoCheck() = System.currentTimeMillis() - sp.getLong("lastCheck", 0L) > 6 * 3600_000L

    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json"); c.setRequestProperty("User-Agent", "ha-iptv-app")
        try {
            if (c.responseCode != 200) throw IllegalStateException("GitHub answered ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            sp.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
            val assets = j.optJSONArray("assets")
            var rel: Release? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val m = Regex("""ha-iptv-app-(.+)\.apk""").matchEntire(a.optString("name")) ?: continue
                rel = Release(m.groupValues[1], j.optString("body"), a.optString("browser_download_url"), a.optLong("size"))
            }
            available = rel?.takeIf { newer(it.version, installed) }
            available
        } finally { c.disconnect() }
    }

    suspend fun download(r: Release, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val f = File(dir, "app.apk")
        val c = URL(r.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000; c.setRequestProperty("User-Agent", "ha-iptv-app")
        try {
            if (c.responseCode != 200) throw IllegalStateException("download failed (${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
            var done = 0L; var last = -1
            c.inputStream.use { i -> f.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; progress(p) } }
                }
            } }
            if (r.size > 0 && f.length() != r.size) throw IllegalStateException("incomplete download")
            f
        } finally { c.disconnect() }
    }

    /** True if Android lets this app install packages; otherwise opens the system switch for it and returns false. */
    fun mayInstall(): Boolean {
        if (Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()) return true
        try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        return false
    }

    /** Hands the file to the system installer. [onResult] gets null when the system takes over (app restarts on success) or an error text. */
    fun install(file: File, onResult: (String?) -> Unit) {
        val pi = ctx.packageManager.packageInstaller
        val action = "${ctx.packageName}.INSTALL_RESULT"
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { c.startActivity(it) }
                    }
                    PackageInstaller.STATUS_SUCCESS -> { try { ctx.unregisterReceiver(this) } catch (e: Exception) { }; onResult(null) }
                    else -> {
                        try { ctx.unregisterReceiver(this) } catch (e: Exception) { }
                        val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $st"
                        onResult(
                            if (st == PackageInstaller.STATUS_FAILURE_CONFLICT || msg.contains("signature", true) || msg.contains("INCOMPATIBLE", true))
                                "The new version is signed with a different key than this app, so Android refuses to update it. Reinstall it once with adb (uninstall first); later updates will work."
                            else if (st == PackageInstaller.STATUS_FAILURE_ABORTED) "Update cancelled"
                            else "Install failed: $msg")
                    }
                }
            }
        }
        ContextCompat.registerReceiver(ctx, rcv, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
            pi.openSession(id).use { s ->
                file.inputStream().use { i -> s.openWrite("app", 0, file.length()).use { o -> i.copyTo(o); s.fsync(o) } }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(ctx, id, Intent(action).setPackage(ctx.packageName), flags).intentSender)
            }
        } catch (e: Exception) {
            try { ctx.unregisterReceiver(rcv) } catch (x: Exception) { }
            onResult("Install failed: ${e.message}")
        }
    }

    companion object {
        const val REPO = "eneasz/ha-iptv"
        /** True when version [a] is newer than [b] (numeric, dot separated; non-numeric parts count as 0). */
        fun newer(a: String, b: String): Boolean {
            val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }; val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
            return false
        }
    }
}
