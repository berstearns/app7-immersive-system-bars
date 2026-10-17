package pl.czak.learnlauncher.resolver

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android AES-256-GCM decryptor (B1) via `javax.crypto` — no new dependency.
 *
 * Wire format: `nonce(12) ‖ ciphertext ‖ tag(16)` (javax appends the GCM tag to
 * the ciphertext). `key` is the raw 32-byte symmetric key baked in the APK as
 * `BuildConfig.ASKIP_CIPHER_KEY` (base64). doFinal throws AEADBadTagException on
 * a wrong key or tampered ciphertext — which the resolver treats as rejection.
 */
class AndroidAskIpCipher(private val key: ByteArray) : AskIpCipher {

    init {
        require(key.size == 32) { "ask-ip cipher key must be 32 bytes, got ${key.size}" }
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        require(sealed.size > NONCE_LEN + TAG_LEN) { "sealed payload too short" }
        val nonce = sealed.copyOfRange(0, NONCE_LEN)
        val ct = sealed.copyOfRange(NONCE_LEN, sealed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        return cipher.doFinal(ct)
    }

    private companion object {
        const val NONCE_LEN = 12
        const val TAG_BITS = 128
        const val TAG_LEN = 16
    }
}
