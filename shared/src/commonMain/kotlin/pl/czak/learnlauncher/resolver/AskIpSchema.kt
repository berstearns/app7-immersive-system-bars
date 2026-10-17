package pl.czak.learnlauncher.resolver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ask-ip signed-document schema, v1.
 *
 * The document on the wire is [AskIpEnvelope]; the Ed25519 signature is over the
 * EXACT bytes obtained by base64-decoding [AskIpEnvelope.payloadB64]. Those bytes
 * are `nonce ‖ AES-256-GCM(k_app, payload.json)` — encrypt-then-sign. After the
 * signature is verified over those exact bytes they are decrypted, and the
 * payload JSON is parsed FROM THE PLAINTEXT — never re-serialised. (v1 stays v1:
 * no client carries it yet, so redefining the payload bytes is free.)
 *
 * The payload is keyed per app (app7 consumes this contract too):
 *   app7  -> backend_urls[] (:8080), queue_host + queue_port (:9999)
 *   app11 -> backend_urls[], fence_urls[]
 * Unknown fields are ignored (forward compatibility); absent fields are null.
 */
@Serializable
data class AskIpEnvelope(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("key_id") val keyId: String,
    @SerialName("payload_b64") val payloadB64: String,
    @SerialName("sig_b64") val sigB64: String,
)

@Serializable
data class AskIpPayload(
    @SerialName("schema_version") val schemaVersion: Int,
    /** Unix seconds. Monotonicity is the cache's rollback protection. */
    @SerialName("issued_at") val issuedAt: Long,
    /**
     * Additional resolver-document URLs. These may ADD to or REORDER the
     * client's bootstrap list but can NEVER remove a baked entry: the baked
     * list is a permanent non-removable floor (see AskIpResolver.effectiveResolvers).
     */
    @SerialName("resolvers") val resolvers: List<String> = emptyList(),
    @SerialName("apps") val apps: Map<String, AppEndpoints> = emptyMap(),
)

@Serializable
data class AppEndpoints(
    /** Ordered failover list — try in order (B2). Preferred over the singular. */
    @SerialName("backend_urls") val backendUrls: List<String> = emptyList(),
    @SerialName("fence_urls") val fenceUrls: List<String> = emptyList(),
    /** Deprecated singular fields; retained so an older payload still parses. */
    @SerialName("backend_url") val backendUrl: String? = null,
    @SerialName("fence_url") val fenceUrl: String? = null,
    @SerialName("queue_host") val queueHost: String? = null,
    @SerialName("queue_port") val queuePort: Int? = null,
    /**
     * app7's admit/attach endpoint (:7071). Optional and absent-null: app11
     * never populates it. app7 PREFERS it when present, else derives it from the
     * backend_url host as it does today. Baking the slot now means relocating
     * admit off the content host later is a document change, not a silent
     * wrong-host attach followed by a second rebuild.
     */
    @SerialName("admit_url") val admitUrl: String? = null,
) {
    /** Ordered backends: the list if present, else the deprecated singular. */
    fun orderedBackendUrls(): List<String> =
        backendUrls.ifEmpty { listOfNotNull(backendUrl) }

    /** Ordered fence URLs: the list if present, else the deprecated singular. */
    fun orderedFenceUrls(): List<String> =
        fenceUrls.ifEmpty { listOfNotNull(fenceUrl) }
}

const val ASKIP_SCHEMA_VERSION = 1
