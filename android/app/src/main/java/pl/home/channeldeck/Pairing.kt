package pl.home.channeldeck

import org.json.JSONObject
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * "Click to approve" pairing. Home Assistant asks, the stick shows a 4-digit code and Approve/Deny, and only after OK on the remote does
 * Home Assistant get the token. The code is computed from a random value of each side, so both screens show the same digits only when
 * they belong to the same request: a stranger's request shows different digits than the ones in your Home Assistant.
 *
 * The token never travels in the clear: both sides make a throw-away P-256 key, agree on a secret (ECDH) and the token is sent AES-GCM
 * encrypted with it. The code also covers both public keys, so someone sitting in the middle (two different key exchanges) would make the
 * two screens show different digits.
 */
class Pairing {
    class Req(val id: String, val ha: String, val haPub: String, val sn: String, val spub: String, private val key: ByteArray, val created: Long) {
        @Volatile var status = "pending"; @Volatile var enc: Pair<String, String>? = null
        val code get() = codeOf(ha, haPub, sn, spub)
        fun seal(token: String): Pair<String, String> {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(id.toByteArray())
            return b64(iv) to b64(c.doFinal(token.toByteArray()))
        }
    }

    @Volatile var cur: Req? = null
    private var lastAsk = 0L

    /** Returns the new request, or null when one is already waiting / asked too fast. */
    @Synchronized fun start(ha: String, haPub: String, now: Long = System.currentTimeMillis()): Req? {
        val c = cur
        if (c != null && c.status == "pending" && now - c.created < TTL) return null
        if (now - lastAsk < 3000) return null
        val peer = try { Base64.decode(haPub, Base64.NO_WRAP) } catch (e: Exception) { return null }
        if (peer.size != 65 || peer[0] != 4.toByte()) return null
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val mine = kp.public.encoded.let { it.copyOfRange(it.size - 65, it.size) }
        val secret = try {
            val pk = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(SPKI + peer))
            KeyAgreement.getInstance("ECDH").apply { init(kp.private); doPhase(pk, true) }.generateSecret()
        } catch (e: Exception) { return null }                                   // not a point on the curve
        val key = MessageDigest.getInstance("SHA-256").apply { update("haiptv-pair-v1".toByteArray()); update(secret); update(peer); update(mine) }.digest()
        lastAsk = now
        return Req(rnd(8), ha, haPub, rnd(8), b64(mine), key, now).also { cur = it }
    }

    @Synchronized fun poll(id: String, ha: String, now: Long = System.currentTimeMillis()): JSONObject {
        val c = cur
        if (c == null || c.id != id || !MessageDigest.isEqual(c.ha.toByteArray(), ha.toByteArray())) return JSONObject().put("status", "unknown")
        if (c.status == "pending" && now - c.created >= TTL) c.status = "expired"
        val o = JSONObject().put("status", c.status)
        if (c.status == "approved") c.enc?.let { o.put("iv", it.first).put("ct", it.second) }
        return o
    }

    @Synchronized fun decide(r: Req, approve: Boolean, token: String) {
        if (cur !== r || r.status != "pending") return
        if (approve) { r.enc = r.seal(token); r.status = "approved" } else r.status = "denied"
    }

    companion object {
        const val TTL = 120_000L
        /** ASN.1 header that turns a raw P-256 point into an X.509 public key Java can import. */
        private val SPKI = "3059301306072a8648ce3d020106082a8648ce3d030107034200".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        fun validPub(pub: String) = try { Base64.decode(pub, Base64.NO_WRAP).let { it.size == 65 && it[0] == 4.toByte() } } catch (e: Exception) { false }
        private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        fun codeOf(ha: String, haPub: String, sn: String, spub: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest((ha + haPub + sn + spub).toByteArray())
            val n = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
            return "%04d".format(n % 10000)
        }
        private fun rnd(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
