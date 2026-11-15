package pl.home.channeldeck

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import kotlin.concurrent.thread

/** What the API needs from the app. Implementations must be callable from any thread. */
interface ApiHost {
    fun token(): String
    fun version(): Int
    fun waitChange(since: Int, timeoutMs: Long)
    fun state(): JSONObject
    fun groups(): JSONArray
    fun regions(): JSONArray
    fun channels(group: String): JSONArray
    fun search(q: String, limit: Int, group: String?): JSONArray
    fun epg(ids: List<String>, limit: Int): JSONObject
    fun quality(group: String): JSONArray
    fun command(c: JSONObject): JSONObject
    /** Entries changed after sequence `since` (0 = everything) plus who we are. */
    fun syncDelta(since: Long): JSONObject
    /** Merge entries pushed by Home Assistant. */
    fun syncMerge(entries: JSONObject): JSONObject
    /** The provider login, so Home Assistant can copy it to a new stick. Empty fields when not set up. */
    fun provision(): JSONObject
    /** Give a stick that is not set up yet its provider login (refused once it has one). */
    fun setProvision(c: JSONObject): JSONObject
    /** Start "click to approve" pairing (no token needed; the stick decides whether to listen). */
    fun pairRequest(ha: String, pub: String): JSONObject
    fun pairPoll(id: String, ha: String): JSONObject
}

/**
 * Tiny dependency-free HTTP/1.1 JSON server so Home Assistant can read state and send commands.
 * Auth: `Authorization: Bearer <token>`. GET /api/state?wait=<v> long-polls until the state version changes.
 */
class ApiServer(private val port: Int, private val host: ApiHost) {
    /** Time of the last request that carried the right token (Home Assistant polls all the time), 0 = never. */
    @Volatile var lastContact = 0L
    @Volatile private var running = false
    private var server: ServerSocket? = null

    fun start() {
        running = true
        thread(name = "api-accept", isDaemon = true) {
            var tries = 0
            while (running && server == null && tries++ < 20) {
                try { server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } } catch (e: Exception) { Thread.sleep(500) }
            }
            val s = server ?: return@thread
            while (running) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(name = "api-conn", isDaemon = true) { handle(c) }
            }
        }
    }

    fun stop() { running = false; try { server?.close() } catch (e: Exception) {}; server = null }

    private fun handle(sock: Socket) = sock.use { c ->
        try {
            c.soTimeout = 30000
            val inp = c.getInputStream()
            val head = readHead(inp) ?: return
            val lines = head.split("\r\n")
            val (method, target) = lines[0].split(" ").let { it[0] to it.getOrElse(1) { "/" } }
            val hdr = lines.drop(1).filter { ":" in it }.associate { it.substringBefore(":").trim().lowercase() to it.substringAfter(":").trim() }
            val len = hdr["content-length"]?.toIntOrNull() ?: 0
            val body = if (len > 0) String(readN(inp, len.coerceAtMost(4 shl 20)), Charsets.UTF_8) else ""
            val path = target.substringBefore('?')
            val q = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
                .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
            if (path == "/api/pair/request" && method == "POST") { reply(c, 200, JSONObject(body.ifBlank { "{}" }).let { host.pairRequest(it.optString("nonce"), it.optString("pub")) }); return }
            if (path == "/api/pair/poll") { reply(c, 200, host.pairPoll(q["id"] ?: "", q["nonce"] ?: "")); return }
            val auth = hdr["authorization"]?.removePrefix("Bearer ")?.trim() ?: ""
            if (!MessageDigest.isEqual(auth.toByteArray(), host.token().toByteArray())) { reply(c, 401, JSONObject().put("error", "unauthorized")); return }
            lastContact = System.currentTimeMillis()
            reply(c, 200, route(method, path, q, body))
        } catch (e: Exception) {
            try { reply(c, 500, JSONObject().put("error", e.message ?: e.javaClass.simpleName)) } catch (_: Exception) {}
        }
    }

    private fun route(method: String, path: String, q: Map<String, String>, body: String): Any = when {
        path == "/api/ping" -> JSONObject().put("ok", true)
        path == "/api/state" -> {
            val wait = q["wait"]?.toIntOrNull()
            if (wait != null) host.waitChange(wait, ((q["timeout"]?.toLongOrNull() ?: 25) * 1000).coerceAtMost(55000))
            host.state()
        }
        path == "/api/groups" -> host.groups()
        path == "/api/regions" -> host.regions()
        path == "/api/channels" -> host.channels(q["group"] ?: "")
        path == "/api/search" -> host.search(q["q"] ?: "", q["limit"]?.toIntOrNull() ?: 20, q["group"])
        path == "/api/epg" -> host.epg((q["ids"] ?: "").split(',').filter { it.isNotEmpty() }.take(60), q["limit"]?.toIntOrNull() ?: 12)
        path == "/api/quality" -> host.quality(q["group"] ?: "")
        path == "/api/sync" && method == "POST" -> host.syncMerge(JSONObject(body.ifBlank { "{}" }).optJSONObject("entries") ?: JSONObject())
        path == "/api/sync" -> host.syncDelta(q["since"]?.toLongOrNull() ?: 0L)
        path == "/api/provision" && method == "POST" -> host.setProvision(JSONObject(body.ifBlank { "{}" }))
        path == "/api/provision" -> host.provision()
        path == "/api/command" && method == "POST" -> host.command(JSONObject(body.ifBlank { "{}" }))
        else -> JSONObject().put("error", "not found: $path")
    }

    private fun reply(c: Socket, code: Int, json: Any) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val text = when (code) { 200 -> "OK"; 401 -> "Unauthorized"; else -> "Error" }
        c.getOutputStream().apply {
            write("HTTP/1.1 $code $text\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
    }

    private fun readHead(i: InputStream): String? {
        val b = ByteArrayOutputStream(); var last = 0
        while (b.size() < 16384) {
            val x = i.read(); if (x < 0) return null
            b.write(x); last = (last shl 8) or x
            if (last == 0x0D0A0D0A) return String(b.toByteArray(), Charsets.ISO_8859_1).trimEnd()
        }
        return null
    }
    private fun readN(i: InputStream, n: Int): ByteArray {
        val out = ByteArray(n); var off = 0
        while (off < n) { val r = i.read(out, off, n - off); if (r < 0) break; off += r }
        return out.copyOf(off)
    }
}
