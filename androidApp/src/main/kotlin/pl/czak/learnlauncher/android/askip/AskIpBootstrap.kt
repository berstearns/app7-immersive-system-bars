package pl.czak.learnlauncher.android.askip

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.czak.learnlauncher.android.BuildConfig
import pl.czak.learnlauncher.resolver.AndroidAskIpCipher
import pl.czak.learnlauncher.resolver.AndroidEd25519
import pl.czak.learnlauncher.resolver.AppEndpoints
import pl.czak.learnlauncher.resolver.AskIpCache
import pl.czak.learnlauncher.resolver.AskIpCacheStore
import pl.czak.learnlauncher.resolver.AskIpDocumentVerifier
import pl.czak.learnlauncher.resolver.AskIpFetcher
import pl.czak.learnlauncher.resolver.AskIpResolver
import pl.czak.learnlauncher.resolver.AskIpSource
import pl.czak.learnlauncher.resolver.ResolvedEndpoints
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * ask-ip composition root for app7 (Android). Never blocks the UI: [current]
 * starts as the baked BuildConfig endpoints and is swapped when resolution
 * completes. `BuildConfig.ASKIP_URLS` / `ASKIP_PUBKEYS` / `ASKIP_CIPHER_KEY`
 * are late-bound gradle properties, empty by default.
 *
 * The existing baked BACKEND_URL / QUEUE_HOST / QUEUE_PORT literals are DEMOTED
 * to the last-resort tier here (see [bakedEndpoints]) — they are no longer read
 * directly by the download/sync paths, only used when neither a signed document
 * nor a cached one is available.
 *
 * app7 consumes the SAME frozen AppEndpoints wire schema as app11 (ported
 * byte-for-byte, package line only), so one signed document serves both apps.
 */
object AskIpBootstrap {

    val current = mutableStateOf(
        ResolvedEndpoints(
            source = AskIpSource.BAKED,
            endpoints = bakedEndpoints()["app7"] ?: AppEndpoints(),
            issuedAt = null,
        )
    )

    /** Backend URL to use right now (resolver > cache > baked). Prefers the
     *  ordered backend list's first entry (B2). */
    fun backendUrl(): String =
        current.value.endpoints.orderedBackendUrls().firstOrNull()
            ?: BuildConfig.BACKEND_URL

    /** app7's admit/attach endpoint (:7071) if the document supplied one;
     *  null means AdmissionClient derives it from the backend host as before. */
    fun admitUrl(): String? = current.value.endpoints.admitUrl

    /** Queue host/port for the TCP sync path (resolver > baked). */
    fun queueHost(): String = current.value.endpoints.queueHost ?: BuildConfig.QUEUE_HOST
    fun queuePort(): Int = current.value.endpoints.queuePort ?: BuildConfig.QUEUE_PORT

    fun start(context: Context, scope: CoroutineScope) {
        val urls = BuildConfig.ASKIP_URLS.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val keys = parsePubkeys(BuildConfig.ASKIP_PUBKEYS)
        val cipherKey = try {
            Base64.decode(BuildConfig.ASKIP_CIPHER_KEY.trim(), Base64.DEFAULT)
        } catch (_: Exception) {
            ByteArray(0)
        }
        // Documents are encrypted: without a 32-byte key we cannot decrypt any
        // document, so stay on baked rather than fetch-and-fail every time.
        if (urls.isEmpty() || keys.isEmpty() || cipherKey.size != 32) return
        val cache = AskIpCache(FileCacheStore(File(context.filesDir, "askip-lkg.json")))
        val resolver = AskIpResolver(
            bakedUrls = urls,
            bakedEndpoints = bakedEndpoints(),
            verifier = AskIpDocumentVerifier(keys, AndroidEd25519, AndroidAskIpCipher(cipherKey)),
            cache = cache,
            fetcher = HttpFetcher,
        )
        scope.launch(Dispatchers.IO) {
            // Post cached last-known-good IMMEDIATELY (file read, no network) so
            // the UI never waits on resolver timeouts; the network resolve then
            // upgrades the state when it completes.
            try {
                cache.load()?.apps?.get("app7")?.let { cachedEndpoints ->
                    current.value = ResolvedEndpoints(
                        AskIpSource.CACHE, cachedEndpoints, cache.load()?.issuedAt,
                    )
                }
            } catch (_: Exception) {
                // keep baked
            }
            try {
                current.value = resolver.resolve("app7")
            } catch (_: Exception) {
                // keep cached/baked
            }
        }
    }

    /** ';'-separated "key_id:base64(raw 32-byte key)". */
    private fun parsePubkeys(spec: String): Map<String, ByteArray> =
        spec.split(';').mapNotNull { entry ->
            val idx = entry.indexOf(':')
            if (idx <= 0) return@mapNotNull null
            try {
                entry.substring(0, idx).trim() to
                    Base64.decode(entry.substring(idx + 1).trim(), Base64.DEFAULT)
            } catch (_: Exception) {
                null
            }
        }.toMap()

    private fun bakedEndpoints(): Map<String, AppEndpoints> = mapOf(
        "app7" to AppEndpoints(
            backendUrl = BuildConfig.BACKEND_URL.takeIf { it.isNotEmpty() },
            queueHost = BuildConfig.QUEUE_HOST.takeIf { it.isNotEmpty() },
            queuePort = BuildConfig.QUEUE_PORT.takeIf { it > 0 },
        ),
    )

    /** Private app storage — last-known-good document cache. */
    private class FileCacheStore(private val file: File) : AskIpCacheStore {
        override fun read(): String? = try {
            if (file.exists()) file.readText() else null
        } catch (_: Exception) {
            null
        }

        override fun write(value: String) {
            try {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(value)
                if (!tmp.renameTo(file)) file.writeText(value)
            } catch (_: Exception) {
            }
        }
    }

    private object HttpFetcher : AskIpFetcher {
        override suspend fun fetch(url: String): ByteArray = withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.instanceFollowRedirects = true
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode} from $url")
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }
    }
}
