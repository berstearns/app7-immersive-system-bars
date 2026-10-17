package pl.czak.learnlauncher.data.sync.adapter.turso

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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import pl.czak.learnlauncher.data.sync.adapter.PayloadShredder
import pl.czak.learnlauncher.data.sync.model.SubmitResult
import pl.czak.learnlauncher.data.sync.model.SyncEnvelope
import pl.czak.learnlauncher.data.sync.ports.PayloadUploader

/**
 * Turso uploader using the HTTP `/v2/pipeline` API (no native libsql dep).
 *
 * Wire format: a single POST sends a list of "execute" requests, each carrying
 * one parameterised SQL statement. We collapse the 11 syncable tables into one
 * pipeline request per non-empty row; one round-trip per envelope (modulo any
 * library-side framing).
 *
 *  - SQL: `INSERT OR IGNORE INTO {table} ({cols}) VALUES (?,…)` — idempotent
 *    against the table's PRIMARY KEY (device_id, local_id).
 *  - Auth: `Authorization: Bearer {authToken}` — long-lived per-DB JWT.
 *
 * Anti-corruption: HTTP / parse errors → [SubmitResult.Retry]/[SubmitResult.Fail].
 * Turso-specific JSON shapes never escape this file.
 */
class TursoUploaderAdapter(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val authToken: String,
) : PayloadUploader {

    override suspend fun submit(envelope: SyncEnvelope): SubmitResult {
        val byTable = PayloadShredder.shred(
            payload = envelope.payload,
            deviceId = envelope.deviceId,
            userId = envelope.userId,
        )

        val requests = mutableListOf<JsonObject>()
        val perTable = mutableMapOf<String, Int>()
        for ((table, rows) in byTable) {
            if (rows.isEmpty()) continue
            for (row in rows) {
                requests += executeRequest(table, row)
            }
            perTable[table] = rows.size
        }
        if (requests.isEmpty()) {
            return SubmitResult.Ok(acceptedRows = 0, perTable = emptyMap())
        }

        // A single closing "close" request keeps the per-call connection from
        // lingering server-side; matches the documented pipeline shape.
        val body = buildJsonObject {
            put("requests", buildJsonArray {
                requests.forEach { add(it) }
                add(buildJsonObject { put("type", JsonPrimitive("close")) })
            })
        }

        val response: HttpResponse = try {
            httpClient.post("${baseUrl.trimEnd('/')}/v2/pipeline") {
                contentType(ContentType.Application.Json)
                headers {
                    append("Authorization", "Bearer $authToken")
                }
                setBody(PayloadShredder.stringify(body))
            }
        } catch (e: Throwable) {
            return SubmitResult.Retry(
                reason = "turso transport: ${e.message ?: e::class.simpleName}",
            )
        }

        return classify(response, perTable)
    }

    private fun executeRequest(table: String, row: JsonObject): JsonObject {
        val columns = row.keys.toList()
        val placeholders = columns.joinToString(",") { "?" }
        val sql = "INSERT OR IGNORE INTO $table (${columns.joinToString(",")}) VALUES ($placeholders)"
        val args = buildJsonArray {
            columns.forEach { col -> add(toTursoArg(row[col])) }
        }
        return buildJsonObject {
            put("type", JsonPrimitive("execute"))
            put("stmt", buildJsonObject {
                put("sql", JsonPrimitive(sql))
                put("args", args)
            })
        }
    }

    private fun toTursoArg(v: JsonElement?): JsonObject {
        if (v == null || v is JsonNull) {
            return buildJsonObject { put("type", JsonPrimitive("null")) }
        }
        if (v is JsonPrimitive) {
            v.booleanOrNull?.let {
                return buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("value", JsonPrimitive(if (it) "1" else "0"))
                }
            }
            v.longOrNull?.let {
                return buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("value", JsonPrimitive(it.toString()))
                }
            }
            v.doubleOrNull?.let {
                return buildJsonObject {
                    put("type", JsonPrimitive("float"))
                    put("value", JsonPrimitive(it))
                }
            }
            return buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("value", JsonPrimitive(v.content))
            }
        }
        // Nested object/array — fall back to JSON text. The schema shouldn't
        // produce these but this keeps the adapter total.
        return buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("value", JsonPrimitive(PayloadShredder.stringify(v)))
        }
    }

    private suspend fun classify(
        response: HttpResponse,
        perTable: Map<String, Int>,
    ): SubmitResult {
        val code = response.status
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        if (code == HttpStatusCode.OK) {
            // Pipeline replies 200 even when an individual stmt errored — inspect
            // each result and map to Fail/Retry as appropriate.
            val parsed = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
            }.getOrNull()
            val results = parsed?.get("results")?.jsonArray
            if (results != null) {
                val errors = results.mapNotNull { it.jsonObject["error"]?.jsonObject }
                if (errors.isNotEmpty()) {
                    val first = errors.first()["message"]?.jsonPrimitive?.content ?: "unknown"
                    return SubmitResult.Fail(
                        reason = "turso stmt error: $first",
                        permanent = true,
                    )
                }
            }
            return SubmitResult.Ok(
                acceptedRows = perTable.values.sum(),
                perTable = perTable,
            )
        }
        return when {
            code == HttpStatusCode.Unauthorized || code == HttpStatusCode.Forbidden ->
                SubmitResult.Fail(reason = "turso auth rejected: ${code.value} $body", permanent = true)
            code.value == 429 ->
                SubmitResult.Retry(reason = "turso throttle: ${code.value}")
            code.value in 500..599 ->
                SubmitResult.Retry(reason = "turso 5xx: ${code.value}")
            code.value in 400..499 ->
                SubmitResult.Fail(reason = "turso rejected: ${code.value} $body", permanent = true)
            else ->
                SubmitResult.Fail(reason = "turso unexpected status: ${code.value}", permanent = true)
        }
    }
}
