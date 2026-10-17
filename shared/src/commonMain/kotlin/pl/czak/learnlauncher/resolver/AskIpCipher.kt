package pl.czak.learnlauncher.resolver

/**
 * Decrypts an ask-ip document payload (B1).
 *
 * Injected like [AskIpFetcher] — a `fun interface`, not `expect`/`actual` — so
 * the platform crypto stays in `androidMain` and tests supply their own.
 *
 * Input `sealed` is `nonce ‖ AES-256-GCM(k_app, plaintext)` (12-byte nonce
 * prefix, 128-bit tag). Returns the plaintext payload JSON bytes, or THROWS on
 * any authentication/format failure — a caller MUST treat a throw as a rejected
 * document, never as an empty payload.
 *
 * This is obfuscation-grade confidentiality: it keeps the backend addresses out
 * of reach of anyone who does NOT hold the APK (the web public reading the
 * unauthenticated bootstrap URL). It does NOT defend against an APK holder, who
 * is trusted by the threat model. The Ed25519 signature is the separate,
 * retained layer that defends against a hostile HOST even if k_app leaks.
 */
fun interface AskIpCipher {
    /** @throws Exception on wrong key, tampered ciphertext, or bad framing. */
    fun decrypt(sealed: ByteArray): ByteArray
}

/** Cipher that always throws — the correct injection where decryption is not
 *  wired (e.g. the desktop composition root, which R1 excluded). */
object UnavailableAskIpCipher : AskIpCipher {
    override fun decrypt(sealed: ByteArray): ByteArray =
        throw UnsupportedOperationException("ask-ip cipher not configured on this platform")
}
