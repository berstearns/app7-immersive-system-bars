package pl.czak.learnlauncher.data.sync.adapter

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import pl.czak.learnlauncher.data.model.UnifiedPayload

/**
 * Shared helper for the Supabase + Turso adapters: turn a [UnifiedPayload]
 * into a per-table list of JSON row objects, with these transforms applied:
 *   - drop the local-only `synced` field
 *   - inject `device_id` and `user_id` from the envelope (single source of truth)
 * Column names are already snake_case via @SerialName on the record classes.
 */
internal object PayloadShredder {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    data class TableRows(val table: String, val rows: List<JsonObject>)

    fun shred(payload: UnifiedPayload, deviceId: String, userId: String?): List<TableRows> {
        val t = payload.tables
        return listOf(
            "session_events" to encodeList(t.sessionEvents),
            "annotation_records" to encodeList(t.annotationRecords),
            "chat_messages" to encodeList(t.chatMessages),
            "page_interactions" to encodeList(t.pageInteractions),
            "app_launch_records" to encodeList(t.appLaunchRecords),
            "settings_changes" to encodeList(t.settingsChanges),
            "region_translations" to encodeList(t.regionTranslations),
            "app_sessions" to encodeList(t.appSessions),
            "comic_sessions" to encodeList(t.comicSessions),
            "chapter_sessions" to encodeList(t.chapterSessions),
            "page_sessions" to encodeList(t.pageSessions),
            "page_bubble_orderings" to encodeList(t.pageBubbleOrderings),
        ).map { (table, raw) ->
            TableRows(
                table = table,
                rows = raw.map { row -> projectRow(row, deviceId, userId) },
            )
        }
    }

    private inline fun <reified T> encodeList(items: List<T>): List<JsonObject> {
        if (items.isEmpty()) return emptyList()
        val arr = json.parseToJsonElement(json.encodeToString(items)) as JsonArray
        return arr.map { it.jsonObject }
    }

    private fun projectRow(row: JsonObject, deviceId: String, userId: String?): JsonObject =
        buildJsonObject {
            row.entries.forEach { (k, v) ->
                if (k == "synced") return@forEach
                put(k, v)
            }
            put("device_id", JsonPrimitive(deviceId))
            put("user_id", userId?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        }

    fun stringify(value: JsonElement): String = json.encodeToString(value)
}
