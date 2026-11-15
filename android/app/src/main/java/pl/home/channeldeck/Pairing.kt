package pl.home.channeldeck

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * "Click to approve" pairing. Home Assistant asks, the stick shows a 4-digit code and Approve/Deny, and only after OK on the remote does
 * Home Assistant get the token. The code is computed from a random value of each side, so both screens show the same digits only when
 * they belong to the same request: a stranger's request shows different digits than the ones in your Home Assistant.
 */
class Pairing {
    class Req(val id: String, val ha: String, val sn: String, val created: Long) { @Volatile var status = "pending"; @Volatile var token: String? = null
        val code get() = codeOf(ha, sn) }

    @Volatile var cur: Req? = null
    private var lastAsk = 0L

    /** Returns the new request, or null when one is already waiting / asked too fast. */
    @Synchronized fun start(ha: String, now: Long = System.currentTimeMillis()): Req? {
        val c = cur
        if (c != null && c.status == "pending" && now - c.created < TTL) return null
        if (now - lastAsk < 3000) return null
        lastAsk = now
        return Req(rnd(8), ha, rnd(8), now).also { cur = it }
    }

    @Synchronized fun poll(id: String, ha: String, now: Long = System.currentTimeMillis()): JSONObject {
        val c = cur
        if (c == null || c.id != id || !MessageDigest.isEqual(c.ha.toByteArray(), ha.toByteArray())) return JSONObject().put("status", "unknown")
        if (c.status == "pending" && now - c.created >= TTL) c.status = "expired"
        val o = JSONObject().put("status", c.status)
        if (c.status == "approved") o.put("token", c.token)
        return o
    }

    @Synchronized fun decide(r: Req, approve: Boolean, token: String) {
        if (cur !== r || r.status != "pending") return
        if (approve) { r.token = token; r.status = "approved" } else r.status = "denied"
    }

    companion object {
        const val TTL = 120_000L
        fun codeOf(ha: String, sn: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest((ha + sn).toByteArray())
            val n = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
            return "%04d".format(n % 10000)
        }
        private fun rnd(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
