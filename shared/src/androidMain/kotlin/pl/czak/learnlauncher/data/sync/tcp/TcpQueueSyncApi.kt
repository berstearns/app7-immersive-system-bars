package pl.czak.learnlauncher.data.sync.tcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import pl.czak.learnlauncher.data.model.UnifiedPayload
import pl.czak.learnlauncher.data.model.UnifiedTables
import pl.czak.learnlauncher.data.sync.model.SubmitResult
import pl.czak.learnlauncher.data.sync.model.SyncEnvelope
import pl.czak.learnlauncher.data.sync.ports.PayloadUploader
import pl.czak.learnlauncher.data.sync.ports.RemoteAuthProvider
import java.io.IOException

class TcpQueueSyncApi(
    private val host: String,
    private val port: Int,
    private val auth: RemoteAuthProvider,
    private val client: QueueTcpClient = QueueTcpClient(host, port),
) : PayloadUploader {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override suspend fun submit(envelope: SyncEnvelope): SubmitResult {
        val token = auth.headers()["Authorization"]?.removePrefix("Bearer ")
            ?: return SubmitResult.Fail("missing auth", permanent = true)
        return try {
            val chunks = chunkPayload(envelope.payload)
            for (chunk in chunks) {
                val chunkJson: JsonElement = json.encodeToJsonElement(chunk)
                client.submitIngest(chunkJson, bearerToken = token)
            }
            val perTable = countRows(envelope.payload.tables)
            SubmitResult.Ok(
                acceptedRows = perTable.values.sum(),
                perTable = perTable,
            )
        } catch (e: QueueSubmitException) {
            SubmitResult.Fail(e.message ?: "Queue submit failed", permanent = true)
        } catch (e: IOException) {
            SubmitResult.Retry("Network error: ${e.message ?: e::class.simpleName}")
        } catch (e: Exception) {
            SubmitResult.Fail(
                "Sync failed: ${e.message ?: e::class.simpleName}",
                permanent = true,
            )
        }
    }

    // ────────────────────────────────────────────────────────────
    // Chunking
    // ────────────────────────────────────────────────────────────

    internal fun chunkPayload(payload: UnifiedPayload): List<UnifiedPayload> {
        val serialized = json.encodeToString(payload)
        if (serialized.toByteArray(Charsets.UTF_8).size <= QueueProtocol.SAFE_CHUNK_BYTES) {
            return listOf(payload)
        }
        val totalRows = rowCount(payload.tables)
        if (totalRows <= 1) {
            return listOf(payload)
        }
        val (left, right) = splitTables(payload.tables)
        return chunkPayload(payload.copy(tables = left)) +
            chunkPayload(payload.copy(tables = right))
    }

    private fun splitTables(t: UnifiedTables): Pair<UnifiedTables, UnifiedTables> {
        fun <T> halves(xs: List<T>): Pair<List<T>, List<T>> {
            if (xs.size <= 1) return xs to emptyList()
            val mid = xs.size / 2
            return xs.subList(0, mid).toList() to xs.subList(mid, xs.size).toList()
        }

        val (s1, s2) = halves(t.sessionEvents)
        val (a1, a2) = halves(t.annotationRecords)
        val (c1, c2) = halves(t.chatMessages)
        val (p1, p2) = halves(t.pageInteractions)
        val (l1, l2) = halves(t.appLaunchRecords)
        val (se1, se2) = halves(t.settingsChanges)
        val (rt1, rt2) = halves(t.regionTranslations)

        val left = UnifiedTables(
            sessionEvents = s1,
            annotationRecords = a1,
            chatMessages = c1,
            pageInteractions = p1,
            appLaunchRecords = l1,
            settingsChanges = se1,
            regionTranslations = rt1,
        )
        val right = UnifiedTables(
            sessionEvents = s2,
            annotationRecords = a2,
            chatMessages = c2,
            pageInteractions = p2,
            appLaunchRecords = l2,
            settingsChanges = se2,
            regionTranslations = rt2,
        )
        return left to right
    }

    private fun rowCount(t: UnifiedTables): Int =
        t.sessionEvents.size + t.annotationRecords.size + t.chatMessages.size +
            t.pageInteractions.size + t.appLaunchRecords.size + t.settingsChanges.size +
            t.regionTranslations.size

    private fun countRows(t: UnifiedTables): Map<String, Int> = mapOf(
        "session_events" to t.sessionEvents.size,
        "annotation_records" to t.annotationRecords.size,
        "chat_messages" to t.chatMessages.size,
        "page_interactions" to t.pageInteractions.size,
        "app_launch_records" to t.appLaunchRecords.size,
        "settings_changes" to t.settingsChanges.size,
        "region_translations" to t.regionTranslations.size,
    )
}
