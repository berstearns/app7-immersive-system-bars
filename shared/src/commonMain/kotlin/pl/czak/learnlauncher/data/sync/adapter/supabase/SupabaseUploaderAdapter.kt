package pl.czak.learnlauncher.data.sync.adapter.supabase

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import pl.czak.learnlauncher.data.sync.adapter.PayloadShredder
import pl.czak.learnlauncher.data.sync.model.SubmitResult
import pl.czak.learnlauncher.data.sync.model.SyncEnvelope
import pl.czak.learnlauncher.data.sync.ports.PayloadUploader

/**
 * Supabase REST (PostgREST) uploader.
 *
 * - Per syncable table, POST `{baseUrl}/rest/v1/{table}` with a JSON array of rows.
 * - `Prefer: resolution=ignore-duplicates,return=minimal` makes the insert idempotent
 *   on the table's PRIMARY KEY (device_id, local_id), and skips returning rows.
 * - Auth: anon key embedded in `apikey` + `Authorization: Bearer <anonKey>` headers.
 *   Safe to ship in clients — RLS on the schema is the actual guard.
 *
 * Anti-corruption: maps Ktor exceptions and HTTP status codes onto [SubmitResult].
 * No Supabase SDK types leak past this file.
 */
class SupabaseUploaderAdapter(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val anonKey: String,
) : PayloadUploader {

    override suspend fun submit(envelope: SyncEnvelope): SubmitResult {
        val byTable = PayloadShredder.shred(
            payload = envelope.payload,
            deviceId = envelope.deviceId,
            userId = envelope.userId,
        )
        val perTable = mutableMapOf<String, Int>()
        var accepted = 0

        for ((table, rows) in byTable) {
            if (rows.isEmpty()) continue
            val response: HttpResponse = try {
                postBatch(table, rows)
            } catch (e: Throwable) {
                return mapTransportFailure(e)
            }
            val classified = classify(response, table)
            if (classified != null) return classified
            perTable[table] = rows.size
            accepted += rows.size
        }

        return SubmitResult.Ok(
            acceptedRows = accepted,
            perTable = perTable,
        )
    }

    private suspend fun postBatch(table: String, rows: List<JsonObject>): HttpResponse {
        val url = "${baseUrl.trimEnd('/')}/rest/v1/$table"
        val body: JsonArray = buildJsonArray { rows.forEach { add(it) } }
        return httpClient.post(url) {
            contentType(ContentType.Application.Json)
            headers {
                append("apikey", anonKey)
                append("Authorization", "Bearer $anonKey")
                append("Prefer", "resolution=ignore-duplicates,return=minimal")
            }
            setBody(PayloadShredder.stringify(body))
        }
    }

    /** Returns non-null SubmitResult if the response represents a problem. */
    private suspend fun classify(response: HttpResponse, table: String): SubmitResult? {
        val code = response.status
        if (code == HttpStatusCode.OK || code == HttpStatusCode.Created || code.value == 204) {
            return null
        }
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        return when {
            code == HttpStatusCode.Unauthorized || code == HttpStatusCode.Forbidden ->
                SubmitResult.Fail(
                    reason = "supabase auth rejected on $table: ${code.value} $body",
                    permanent = true,
                )
            code == HttpStatusCode.RequestTimeout || code.value == 429 ->
                SubmitResult.Retry(reason = "supabase throttle on $table: ${code.value}")
            code.value in 500..599 ->
                SubmitResult.Retry(reason = "supabase 5xx on $table: ${code.value}")
            code.value in 400..499 ->
                SubmitResult.Fail(
                    reason = "supabase rejected $table: ${code.value} $body",
                    permanent = true,
                )
            else ->
                SubmitResult.Fail(
                    reason = "supabase unexpected status on $table: ${code.value}",
                    permanent = true,
                )
        }
    }

    private fun mapTransportFailure(e: Throwable): SubmitResult {
        val msg = e.message ?: e::class.simpleName ?: "unknown"
        return SubmitResult.Retry(reason = "supabase transport: $msg")
    }
}
