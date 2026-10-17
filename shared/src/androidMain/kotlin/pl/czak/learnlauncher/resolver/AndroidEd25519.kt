package pl.czak.learnlauncher.resolver

import org.bouncycastle.math.ec.rfc8032.Ed25519

/**
 * Android Ed25519 via BouncyCastle's static RFC 8032 implementation — no
 * provider registration, works on every minSdk (java.security "Ed25519" is
 * not available on older Android).
 */
object AndroidEd25519 : Ed25519Verify {
    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != Ed25519.PUBLIC_KEY_SIZE) return false
        if (signature.size != Ed25519.SIGNATURE_SIZE) return false
        return try {
            Ed25519.verify(signature, 0, publicKey, 0, message, 0, message.size)
        } catch (_: Exception) {
            false
        }
    }
}
