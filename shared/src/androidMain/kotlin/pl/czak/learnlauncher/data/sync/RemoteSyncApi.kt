package pl.czak.learnlauncher.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pl.czak.learnlauncher.data.sync.model.SubmitResult
import pl.czak.learnlauncher.data.sync.model.SyncEnvelope
import pl.czak.learnlauncher.data.sync.ports.PayloadUploader
import pl.czak.learnlauncher.data.sync.ports.RemoteAuthProvider
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class RemoteSyncApi(
    private val baseUrl: String,
    private val auth: RemoteAuthProvider,
) : PayloadUploader {
    private val json = Json { encodeDefaults = true }

    override suspend fun submit(envelope: SyncEnvelope): SubmitResult = withContext(Dispatchers.IO) {
        val token = auth.headers()["Authorization"]?.removePrefix("Bearer ")
            ?: return@withContext SubmitResult.Fail("missing auth", permanent = true)
        val url = URL("$baseUrl/sync/upload")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.outputStream.use { os ->
                os.write(json.encodeToString(envelope.payload).toByteArray())
            }
            val code = conn.responseCode
            when {
                code == 200 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val respJson = org.json.JSONObject(body)
                    val countsJson = respJson.optJSONObject("counts")
                    val perTable = mutableMapOf<String, Int>()
                    countsJson?.keys()?.forEach { key -> perTable[key] = countsJson.getInt(key) }
                    SubmitResult.Ok(
                        acceptedRows = perTable.values.sum(),
                        perTable = perTable,
                    )
                }
                code == 401 -> SubmitResult.Fail("Session expired", permanent = true)
                code in 400..499 -> SubmitResult.Fail("Request rejected by server", permanent = true)
                code in 500..599 -> SubmitResult.Retry("Server error")
                else -> SubmitResult.Fail("Unexpected response $code", permanent = true)
            }
        } catch (e: IOException) {
            SubmitResult.Retry("Network error")
        } finally {
            conn.disconnect()
        }
    }
}
