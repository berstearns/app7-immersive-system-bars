package pl.czak.learnlauncher.data.download

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Attach-IP admission client for the download capability gate.
 *
 * The client mints NOTHING: it POSTs an attach-my-IP request to the admit
 * service and the SERVER records and checks the (userid, ip) pair. No
 * native symbol, no client-held secret — anything an APK holds is
 * extractable, so all checking is server-side. The userid travels as a
 * plain, non-secret header on download requests; a server running pure
 * IP-pair checking simply ignores it.
 */
object AdmissionClient {

    /** Admit service port on the backend host (host comes from BACKEND_URL). */
    const val ADMIT_PORT = 7071

    /** Non-secret userid header attached to gated download requests. */
    const val USER_HEADER = "X-App7-User"

    private const val TAG = "AdmissionClient"

    /**
     * Resolves the admit endpoint. PREFERS the resolver-supplied [admitUrl]
     * (ask-ip AppEndpoints.admit_url) verbatim when present; otherwise DERIVES
     * it from the backend host (same behavior as before ask-ip). Logs which
     * path was taken — this is the silent-wrong-host fix: relocating admit off
     * the content host is now a signed-document change, not a rebuild.
     */
    fun resolveAdmitUrl(baseUrl: String, admitUrl: String?): URL {
        if (!admitUrl.isNullOrEmpty()) {
            Log.i(TAG, "admit endpoint: resolver-supplied admit_url=$admitUrl")
            return URL(admitUrl)
        }
        val derived = deriveAdmitUrl(baseUrl)
        Log.i(TAG, "admit endpoint: derived from backend host -> $derived (no admit_url in document)")
        return derived
    }

    /** Derives the admit endpoint from the backend base URL (fallback path). */
    fun deriveAdmitUrl(baseUrl: String): URL {
        val base = URL(baseUrl)
        return URL(base.protocol, base.host, ADMIT_PORT, "/admit")
    }

    /**
     * POSTs an attach-my-IP request for [userId] — creates the (user_id, source
     * IP) pair. NO capability is sent; the server needs none. Returns an
     * [AttachResult] carrying the server's OWN outcome (stored pair on success,
     * named error on failure) so a caller can show a real reason instead of a
     * silent failure. [admitUrl] is the resolver-supplied admit endpoint
     * (null → derive from [baseUrl] host).
     */
    suspend fun attachIp(baseUrl: String, userId: String, admitUrl: String? = null): AttachResult =
        withContext(Dispatchers.IO) {
            val conn = resolveAdmitUrl(baseUrl, admitUrl).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                // Wire key "user_id" — the field the admit server reads.
                val body = JSONObject().put("user_id", userId).toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val obj = runCatching { JSONObject(text) }.getOrNull()
                if (code in 200..299) {
                    val uid = obj?.optString("user_id")?.takeIf { it.isNotEmpty() } ?: userId
                    val ip = obj?.optString("ip").orEmpty()
                    AttachResult(true, if (ip.isNotEmpty()) "$uid @ $ip" else "IP added")
                } else {
                    val reason = obj?.optString("error")?.takeIf { it.isNotEmpty() } ?: "HTTP $code"
                    AttachResult(false, reason)
                }
            } catch (e: Exception) {
                AttachResult(false, "network error: ${e.message ?: "unreachable"}")
            } finally {
                conn.disconnect()
            }
        }
}

/** Outcome of an attach POST — [detail] is the stored pair (ok) or the server's
 *  named reason / a local network error (not ok), for surfacing to the user. */
data class AttachResult(val ok: Boolean, val detail: String)
