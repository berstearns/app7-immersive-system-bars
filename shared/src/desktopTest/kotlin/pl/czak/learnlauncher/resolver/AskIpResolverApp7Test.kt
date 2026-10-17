package pl.czak.learnlauncher.resolver

import kotlinx.coroutines.runBlocking
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ask-ip tests for the app7 CONSUMER. Same encrypt-then-sign discipline and the
 * SAME frozen AppEndpoints wire schema app11 uses (ported byte-for-byte, package
 * line only) — a document valid for app11 is valid for app7. THROWAWAY keys per
 * run; payload JSON is hand-built so no serialiser output is ever sealed/signed.
 *
 * Cases map to the dispatch's T1–T6, each resolving "app7":
 *   T1 valid signed+encrypted doc      -> apps["app7"] endpoints returned
 *   T2 wrong cipher key                -> rejected, falls through to baked
 *   T3 tampered ciphertext (GCM)       -> rejected as decrypt, not parse
 *   T4 admit_url present               -> surfaced verbatim to the composition root
 *   T5 admit_url null                  -> null (AdmissionClient then derives from host)
 *   T6 backend_urls ordered            -> first tried, second on failure
 */
class AskIpResolverApp7Test {

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private fun rawPub(kp: KeyPair): ByteArray =
        kp.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }
    private val rawPublicKey = rawPub(keyPair)
    private val b64 = Base64.getEncoder()
    private val rng = SecureRandom()
    private val cipherKey = ByteArray(32).also { rng.nextBytes(it) }
    private val wrongCipherKey = ByteArray(32).also { rng.nextBytes(it) }

    private fun seal(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return nonce + c.doFinal(plaintext)
    }

    private fun cipherFor(key: ByteArray) = AskIpCipher { sealed ->
        val nonce = sealed.copyOfRange(0, 12)
        val ct = sealed.copyOfRange(12, sealed.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.doFinal(ct)
    }

    private fun sign(kp: KeyPair, bytes: ByteArray): ByteArray =
        Signature.getInstance("Ed25519").run { initSign(kp.private); update(bytes); sign() }

    private fun envelope(
        payloadJson: String,
        keyId: String = "test-key",
        signWith: KeyPair = keyPair,
        sealKey: ByteArray = cipherKey,
        tamperBeforeSign: (ByteArray) -> ByteArray = { it },
        tamperAfterSign: (ByteArray) -> ByteArray = { it },
    ): ByteArray {
        val signed = tamperBeforeSign(seal(sealKey, payloadJson.encodeToByteArray()))
        val sig = sign(signWith, signed)
        val shipped = tamperAfterSign(signed)
        return """{"schema_version":1,"key_id":"$keyId","payload_b64":"${
            b64.encodeToString(shipped)
        }","sig_b64":"${b64.encodeToString(sig)}"}""".encodeToByteArray()
    }

    /** app7 payload; `app7` object is injectable to vary backend_urls/admit_url. */
    private fun payloadJson(
        issuedAt: Long,
        app7: String = """{"backend_urls":["http://198.51.100.7:8080"],"queue_host":"198.51.100.7","queue_port":9999}""",
    ) = """
        {"schema_version":1,"issued_at":$issuedAt,"resolvers":[],
         "apps":{"app7":$app7,
                 "app11":{"backend_url":"http://198.51.100.1:8812","fence_url":"http://198.51.100.1:8811"}}}
    """.trimIndent()

    private val verifier = AskIpDocumentVerifier(
        mapOf("test-key" to rawPublicKey), JvmEd25519, cipherFor(cipherKey),
    )
    private val baked = mapOf(
        "app7" to AppEndpoints(backendUrl = "http://baked.invalid:8080", queueHost = "baked.invalid", queuePort = 9999),
    )

    private fun resolver(
        fetcher: AskIpFetcher,
        cache: AskIpCache = AskIpCache(InMemoryCacheStore()),
        verifier: AskIpDocumentVerifier = this.verifier,
        bakedUrls: List<String> = listOf("http://bootstrap-a.invalid/doc", "http://bootstrap-b.invalid/doc"),
    ) = AskIpResolver(bakedUrls, baked, verifier, cache, fetcher, shuffle = { it })

    // T1 — valid signed+encrypted doc → apps["app7"] endpoints returned.
    @Test
    fun validDocReturnsApp7Endpoints() = runBlocking {
        val doc = envelope(payloadJson(100))
        val r = resolver({ doc }).resolve("app7")
        assertEquals(AskIpSource.RESOLVER, r.source)
        assertEquals("http://198.51.100.7:8080", r.endpoints.orderedBackendUrls().first())
        assertEquals("198.51.100.7", r.endpoints.queueHost)
        assertEquals(9999, r.endpoints.queuePort)
        assertEquals(100L, r.issuedAt)
    }

    // T2 — wrong cipher key → GCM decrypt fails → REJECTED (distinct reason), baked.
    @Test
    fun wrongCipherKeyRejected() = runBlocking {
        val doc = envelope(payloadJson(100)) // sealed with the correct key
        val wrongVerifier = AskIpDocumentVerifier(
            mapOf("test-key" to rawPublicKey), JvmEd25519, cipherFor(wrongCipherKey),
        )
        val v = wrongVerifier.verify(doc)
        assertTrue(v is VerifyResult.Rejected && v.reason.startsWith("decrypt failed"),
            "wrong key must reject at decrypt, not parse: $v")
        assertEquals(AskIpSource.BAKED, resolver({ doc }, verifier = wrongVerifier).resolve("app7").source)
    }

    // T3 — ciphertext byte tampered but re-signed → GCM tag fails → REJECTED.
    @Test
    fun tamperedCiphertextRejectedByGcm() = runBlocking {
        val doc = envelope(payloadJson(100), tamperBeforeSign = { sealed ->
            sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        })
        val v = verifier.verify(doc)
        assertTrue(v is VerifyResult.Rejected && v.reason.startsWith("decrypt failed"),
            "GCM tag failure must reject as decrypt, not parse: $v")
        assertEquals(AskIpSource.BAKED, resolver({ doc }).resolve("app7").source)
    }

    // T4 — admit_url present → surfaced verbatim (AdmissionClient uses it, no derive).
    @Test
    fun admitUrlPresentSurfaced() = runBlocking {
        val app7 = """{"backend_urls":["http://198.51.100.7:8080"],
                       "admit_url":"http://admit.example:7071/admit",
                       "queue_host":"198.51.100.7","queue_port":9999}"""
        val doc = envelope(payloadJson(120, app7 = app7))
        val r = resolver({ doc }).resolve("app7")
        assertEquals(AskIpSource.RESOLVER, r.source)
        assertEquals("http://admit.example:7071/admit", r.endpoints.admitUrl,
            "admit_url must reach the composition root verbatim so AdmissionClient uses it")
    }

    // T5 — admit_url null → null surfaced (AdmissionClient derives from backend host).
    @Test
    fun admitUrlNullDerives() = runBlocking {
        val doc = envelope(payloadJson(130)) // default app7 object has no admit_url
        val r = resolver({ doc }).resolve("app7")
        assertEquals(AskIpSource.RESOLVER, r.source)
        assertNull(r.endpoints.admitUrl, "no admit_url → null → AdmissionClient falls back to host-derivation")
    }

    // T6 — backend_urls ordered: first chosen; on failure the second is tried.
    @Test
    fun backendUrlsOrderedFailover() = runBlocking {
        val app7 = """{"backend_urls":["http://dead.invalid:8080","http://live.invalid:8080"],
                       "queue_host":"198.51.100.7","queue_port":9999}"""
        val doc = envelope(payloadJson(300, app7 = app7))
        val res = resolver({ doc })
        val resolved = res.resolve("app7")
        assertEquals(
            listOf("http://dead.invalid:8080", "http://live.invalid:8080"),
            resolved.endpoints.orderedBackendUrls(),
            "ordered list surfaced in document order",
        )
        val probed = mutableListOf<String>()
        val picked = res.selectBackend(resolved.endpoints) { url ->
            probed += url
            url == "http://live.invalid:8080"
        }
        assertEquals("http://live.invalid:8080", picked, "dead first → second tried")
        assertEquals(listOf("http://dead.invalid:8080", "http://live.invalid:8080"), probed,
            "probed in order, stopping at the first alive")
    }
}
