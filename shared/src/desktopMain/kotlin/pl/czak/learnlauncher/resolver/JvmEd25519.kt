package pl.czak.learnlauncher.resolver

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * JVM Ed25519 (JDK 15+). Wraps the raw 32-byte public key in the fixed
 * X.509 SubjectPublicKeyInfo prefix for AlgorithmIdentifier id-Ed25519.
 */
object JvmEd25519 : Ed25519Verify {
    // SEQUENCE(SEQUENCE(OID 1.3.101.112), BIT STRING(0x00 || 32-byte key))
    private val SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32) return false
        return try {
            val spec = X509EncodedKeySpec(SPKI_PREFIX + publicKey)
            val key = KeyFactory.getInstance("Ed25519").generatePublic(spec)
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        } catch (_: Exception) {
            false
        }
    }
}
