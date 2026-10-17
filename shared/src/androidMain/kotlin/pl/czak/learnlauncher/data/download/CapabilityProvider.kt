package pl.czak.learnlauncher.data.download

import android.content.Context
import android.content.SharedPreferences

/**
 * Supplies the download capability for the Authorization header on
 * GET /download/<file>.zip. The value is a bare `<user>.<exp>.<mac>` triple:
 * the server passes the header RAW into download_authz.check, which reads
 * user_id = token.split(".", 1)[0] — so there is NO "Bearer " prefix.
 *
 * No minting and no signing secret ever enter the APK. A pre-minted capability
 * is injected out-of-band (there is no issuance endpoint today), so this port
 * only READS a stored value; it never creates one.
 */
interface CapabilityProvider {
    /** The provisioned capability, or null if none has been stored. */
    suspend fun capability(): String?
}

/**
 * Reads the capability from SharedPreferences ("comic_capability" / "capability").
 * Returns null when unset or blank, so the download path keeps its current
 * header-less behaviour (server replies 401 no-capability) rather than sending
 * an empty header — the failure mode stays diagnosable, not a new one.
 */
class PrefsCapabilityProvider(context: Context) : CapabilityProvider {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("comic_capability", Context.MODE_PRIVATE)

    override suspend fun capability(): String? =
        prefs.getString("capability", null)?.takeIf { it.isNotEmpty() }
            ?: systemPropertyCapability()

    /**
     * Fallback for the e2e harness ONLY.
     *
     * `launchApp: { clearState: true }` in flows/02-download-comic.yaml wipes app
     * data, so a capability injected into SharedPreferences BEFORE the flow is
     * destroyed by the flow's own first step — decided 20260729T0735Z: the prefs
     * file was verified present at injection and absent after the run. App data is
     * therefore not a viable injection point, and the flow is pinned.
     *
     * A `debug.*` system property is settable from adb without root, survives
     * `pm clear`, and holds no secret: the value is a short-lived bearer
     * capability, never the signing secret. In production this reads empty and the
     * provider falls through to null exactly as before.
     *
     * NOTE: Android caps property values at 91 characters; a capability is 84.
     */
    private fun systemPropertyCapability(): String? = try {
        Runtime.getRuntime().exec(arrayOf("getprop", "debug.app7.capability"))
            .inputStream.bufferedReader().use { it.readText() }
            .trim().takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}
