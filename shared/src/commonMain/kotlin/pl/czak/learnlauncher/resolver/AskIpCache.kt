package pl.czak.learnlauncher.resolver

import kotlinx.serialization.json.Json

/** Platform storage for the last-known-good payload (PRIVATE app storage). */
interface AskIpCacheStore {
    fun read(): String?
    fun write(value: String)
}

/**
 * Last-known-good cache with rollback protection: a payload is only accepted
 * into the cache if its `issued_at` is STRICTLY greater than the cached one,
 * so a replayed older (but validly signed) document can never roll the client
 * back to a retired backend.
 */
class AskIpCache(private val store: AskIpCacheStore) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): AskIpPayload? = try {
        store.read()?.let { json.decodeFromString<AskIpPayload>(it) }
    } catch (_: Exception) {
        null
    }

    /** @return true if stored; false if rejected by rollback protection. */
    fun offer(payload: AskIpPayload): Boolean {
        val cached = load()
        if (cached != null && payload.issuedAt <= cached.issuedAt) return false
        store.write(json.encodeToString(AskIpPayload.serializer(), payload))
        return true
    }
}

/** In-memory store (tests, previews). */
class InMemoryCacheStore : AskIpCacheStore {
    private var value: String? = null
    override fun read(): String? = value
    override fun write(value: String) { this.value = value }
}
