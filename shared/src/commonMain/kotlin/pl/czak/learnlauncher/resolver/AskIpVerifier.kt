package pl.czak.learnlauncher.resolver

import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Platform Ed25519 primitive. `publicKey` is the raw 32-byte key. */
fun interface Ed25519Verify {
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

sealed class VerifyResult {
    data class Ok(val payload: AskIpPayload, val payloadBytes: ByteArray) : VerifyResult()
    data class Rejected(val reason: String) : VerifyResult()
}

/**
 * Verifies an ask-ip document.
 *
 * Contract (UNCHANGED signature path): the signature is checked over the EXACT
 * bytes decoded from `payload_b64`. An object is never re-serialised for
 * verification — key order and whitespace change bytes, and a re-serialised
 * object is not the signed object.
 *
 * Pipeline: verify signature -> DECRYPT (B1: AES-256-GCM) -> parse JSON from the
 * plaintext. Decryption is inserted strictly BETWEEN verify and parse; the
 * cipher is injected (default [UnavailableAskIpCipher], which throws) exactly
 * like [AskIpFetcher], keeping platform crypto out of commonMain.
 */
class AskIpDocumentVerifier(
    /** key_id -> raw 32-byte Ed25519 public key. Accepts ≥2 entries (B3). */
    private val publicKeys: Map<String, ByteArray>,
    private val ed25519: Ed25519Verify,
    private val cipher: AskIpCipher = UnavailableAskIpCipher,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalEncodingApi::class)
    fun verify(documentBytes: ByteArray): VerifyResult {
        val envelope = try {
            json.decodeFromString<AskIpEnvelope>(documentBytes.decodeToString())
        } catch (e: Exception) {
            return VerifyResult.Rejected("envelope parse: ${e.message}")
        }
        if (envelope.schemaVersion > ASKIP_SCHEMA_VERSION) {
            return VerifyResult.Rejected("unsupported schema_version ${envelope.schemaVersion}")
        }
        val publicKey = publicKeys[envelope.keyId]
            ?: return VerifyResult.Rejected("unknown key_id '${envelope.keyId}'")
        val payloadBytes = try {
            Base64.decode(envelope.payloadB64)
        } catch (e: Exception) {
            return VerifyResult.Rejected("payload_b64 decode: ${e.message}")
        }
        val signature = try {
            Base64.decode(envelope.sigB64)
        } catch (e: Exception) {
            return VerifyResult.Rejected("sig_b64 decode: ${e.message}")
        }
        if (!ed25519.verify(publicKey, payloadBytes, signature)) {
            return VerifyResult.Rejected("bad signature for key_id '${envelope.keyId}'")
        }
        // Insert decryption BETWEEN verify and parse. A wrong key or tampered
        // ciphertext fails the GCM tag here and is a REJECTION distinct from a
        // parse error — the plaintext is never seen if authentication fails.
        val plaintext = try {
            cipher.decrypt(payloadBytes)
        } catch (e: Exception) {
            return VerifyResult.Rejected("decrypt failed: ${e.message}")
        }
        val payload = try {
            // Parse from the decrypted plaintext bytes.
            json.decodeFromString<AskIpPayload>(plaintext.decodeToString())
        } catch (e: Exception) {
            return VerifyResult.Rejected("payload parse: ${e.message}")
        }
        return VerifyResult.Ok(payload, plaintext)
    }
}
