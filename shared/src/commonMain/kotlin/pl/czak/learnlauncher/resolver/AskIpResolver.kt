package pl.czak.learnlauncher.resolver

/** How the returned endpoints were obtained (surfaced in UI for Maestro). */
enum class AskIpSource { RESOLVER, CACHE, BAKED }

data class ResolvedEndpoints(
    val source: AskIpSource,
    val endpoints: AppEndpoints,
    /** issued_at of the winning payload; null for BAKED. */
    val issuedAt: Long?,
)

/** Platform fetch: return the raw response bytes or throw. Short timeout. */
fun interface AskIpFetcher {
    suspend fun fetch(url: String): ByteArray
}

/**
 * The eternal ask-ip resolver client.
 *
 * Bootstrap-list semantics — THE BAKED LIST IS A PERMANENT NON-REMOVABLE FLOOR:
 * a validly-signed document may ADD resolvers or REORDER by putting its own
 * list first, but can NEVER subtract a baked entry. The effective list is
 * (document resolvers, in document order) followed by every baked entry not
 * already listed. A signed document with a typo host, a decommissioned bucket,
 * or an empty `resolvers` array therefore degrades to exactly the baked
 * behavior instead of bricking discovery.
 *
 * Fallback order on resolve():
 *   every effective resolver URL (first validly-signed document wins)
 *   -> last-known-good cache -> baked endpoints.
 */
class AskIpResolver(
    /** Build-time bound bootstrap URLs (gradle properties). Never mutated. */
    private val bakedUrls: List<String>,
    /** Absolute last resort, from existing BuildConfig fields. */
    private val bakedEndpoints: Map<String, AppEndpoints>,
    private val verifier: AskIpDocumentVerifier,
    private val cache: AskIpCache,
    private val fetcher: AskIpFetcher,
    /** Shuffles within the two tiers; injectable for deterministic tests. */
    private val shuffle: (List<String>) -> List<String> = { it.shuffled() },
) {

    /**
     * B2 failover ACROSS backends (not just across documents): try each backend
     * URL in document order and return the first for which [isAlive] holds.
     * Returns null only if the list is empty or every backend is dead — the
     * caller then keeps its previous/baked address. A dead first backend no
     * longer requires a fresh signed document.
     */
    suspend fun selectBackend(
        endpoints: AppEndpoints,
        isAlive: suspend (String) -> Boolean,
    ): String? {
        for (url in endpoints.orderedBackendUrls()) {
            if (isAlive(url)) return url
        }
        return null
    }

    /** Document order first; every baked entry always retained (floor), deduped. */
    fun effectiveResolvers(cachedPayload: AskIpPayload?): List<String> {
        val fromDocument = cachedPayload?.resolvers.orEmpty()
        val floor = bakedUrls.filter { it !in fromDocument }
        return shuffle(fromDocument) + shuffle(floor)
    }

    suspend fun resolve(appId: String): ResolvedEndpoints {
        val cached = cache.load()

        for (url in effectiveResolvers(cached)) {
            val bytes = try {
                fetcher.fetch(url)
            } catch (_: Exception) {
                continue
            }
            when (val result = verifier.verify(bytes)) {
                is VerifyResult.Ok -> {
                    cache.offer(result.payload) // rollback-protected
                    val endpoints = result.payload.apps[appId] ?: continue
                    return ResolvedEndpoints(AskIpSource.RESOLVER, endpoints, result.payload.issuedAt)
                }
                is VerifyResult.Rejected -> continue
            }
        }

        cached?.apps?.get(appId)?.let {
            return ResolvedEndpoints(AskIpSource.CACHE, it, cached.issuedAt)
        }

        return ResolvedEndpoints(
            AskIpSource.BAKED,
            bakedEndpoints[appId] ?: AppEndpoints(),
            issuedAt = null,
        )
    }
}
