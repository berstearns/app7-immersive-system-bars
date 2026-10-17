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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pl.czak.learnlauncher.data.sync.model.RemoteAnnotationRecord
import pl.czak.learnlauncher.data.sync.model.RemoteAppLaunchRecord
import pl.czak.learnlauncher.data.sync.model.RemoteChatMessageRecord
import pl.czak.learnlauncher.data.sync.model.RemoteLearnerSnapshot
import pl.czak.learnlauncher.data.sync.model.RemotePageBubbleOrderingRecord
import pl.czak.learnlauncher.data.sync.model.RemotePageInteractionRecord
import pl.czak.learnlauncher.data.sync.model.RemoteRegionTranslationRecord
import pl.czak.learnlauncher.data.sync.model.RemoteSceneRecord
import pl.czak.learnlauncher.data.sync.model.RemoteSessionEventRecord
import pl.czak.learnlauncher.data.sync.model.RemoteSettingsChangeRecord

class TursoDownloaderAdapter(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val authToken: String,
) {
    /**
     * F16 probe: one round trip returning a per-table change watermark for the
     * six small per-user tables — `max(rowid)` via an O(1) seek on the
     * `idx_<table>_user_id` index, except `page_bubble_orderings`, whose rows
     * are upserted in place, so it probes `max(updated_at)` (tiny table).
     * A moved watermark means "something changed for this user"; the caller
     * then runs the full [fetchAllForUser] pull. Blind spots, both accepted
     * because the app only ever INSERTs these tables server-side: in-place
     * UPDATEs don't move max(rowid), and deleting the max rows then
     * re-inserting REUSES their rowids (verified live — an idempotent
     * delete+reseed of the top rows leaves the watermark unmoved).
     * Returns null on any transport/shape failure so the caller degrades to
     * the unconditional pull (a broken probe must never suppress a sync).
     */
    suspend fun fetchSmallTableWatermarks(userId: String): Map<String, String>? {
        val args = List(SMALL_TABLES.size) { textArg(userId) }
        val rows = runSelect(WATERMARKS_SQL, args) { row ->
            row.text("t")?.let { table -> table to (row.text("m") ?: "0") }
        }
        if (rows.size != SMALL_TABLES.size) return null
        return rows.toMap()
    }

    /**
     * Fetch ONLY the user's annotation_records (one SELECT). Used by the Annotation Log's
     * on-open pull, which needs a fast round-trip — [fetchAllForUser] also pulls five other
     * small tables (thousands of rows for an active user) and is too slow for a UI spinner.
     */
    suspend fun fetchAnnotationsForUser(userId: String): List<RemoteAnnotationRecord> =
        runSelect(ANNOTATIONS_SQL, userId, ::parseAnnotationRow)

    /**
     * Scene annotations for the desktop pull-sync tick (`desktopApp` Main.kt). The caller
     * lives outside `:shared`, so a `:shared`-only caller search reports it as dead code.
     */
    suspend fun fetchScenes(userId: String): List<RemoteSceneRecord> =
        runSelect(SCENE_SELECT_SQL, userId, ::parseSceneRow)

    /**
     * Cross-device alignment pull, small tables only (see feature specs
     * `app7-ideas-for-later/cross-device-learner-records-alignment-pull.md` and
     * `app7-ideas-for-later/incremental-alignment-pull.md`).
     * Issues six per-table SELECTs scoped to a single `user_id` and
     * aggregates them into a [RemoteLearnerSnapshot]. The two large
     * append-only telemetry tables (`session_events`, `page_interactions`)
     * are deliberately NOT fetched here — they grew to tens of MB per pull
     * and OOM'd small-heap devices. Use [fetchSessionEventsAfter] /
     * [fetchPageInteractionsAfter] for those (rowid-cursored batches).
     * Each per-table fetch degrades to an empty list on transport failure —
     * partial snapshots are valid; the per-repo merge layer dedups additively.
     */
    suspend fun fetchAllForUser(userId: String): RemoteLearnerSnapshot {
        return RemoteLearnerSnapshot(
            userId = userId,
            sessionEvents = emptyList(),
            annotations = runSelect(ANNOTATIONS_SQL, userId, ::parseAnnotationRow),
            chatMessages = runSelect(CHAT_MESSAGES_SQL, userId, ::parseChatMessageRow),
            pageInteractions = emptyList(),
            appLaunches = runSelect(APP_LAUNCH_RECORDS_SQL, userId, ::parseAppLaunchRow),
            settingsChanges = runSelect(SETTINGS_CHANGES_SQL, userId, ::parseSettingsChangeRow),
            regionTranslations = runSelect(REGION_TRANSLATIONS_SQL, userId, ::parseRegionTranslationRow),
            pageBubbleOrderings = runSelect(PAGE_BUBBLE_ORDERINGS_SQL, userId, ::parsePageBubbleOrderingRow),
        )
    }

    /**
     * One rowid-cursored batch of a large append-only table. `maxRowid` is the
     * highest `rowid` seen in this batch (== the cursor to persist AFTER the
     * batch has been merged locally), or `afterRowid` when the batch is empty.
     * If the remote table is ever pruned/VACUUMed (rowids renumbered), clear
     * the persisted `align_cursor_*` keys to force a clean backfill — the
     * merge layer dedups, so re-pulls are harmless.
     */
    data class RowidBatch<T>(val rows: List<T>, val maxRowid: Long)

    suspend fun fetchSessionEventsAfter(userId: String, afterRowid: Long): RowidBatch<RemoteSessionEventRecord> =
        runBatchSelect(SESSION_EVENTS_INCR_SQL, userId, afterRowid, ::parseSessionEventRow)

    suspend fun fetchPageInteractionsAfter(userId: String, afterRowid: Long): RowidBatch<RemotePageInteractionRecord> =
        runBatchSelect(PAGE_INTERACTIONS_INCR_SQL, userId, afterRowid, ::parsePageInteractionRow)

    private suspend fun <T> runBatchSelect(
        sql: String,
        userId: String,
        afterRowid: Long,
        parser: (Map<String, JsonElement?>) -> T?,
    ): RowidBatch<T> {
        var maxRowid = afterRowid
        val rows = runSelect(
            sql = sql,
            args = listOf(textArg(userId), intArg(afterRowid)),
            parser = { row ->
                row.long("rid")?.let { rid -> if (rid > maxRowid) maxRowid = rid }
                parser(row)
            },
        )
        return RowidBatch(rows = rows, maxRowid = maxRowid)
    }

    private suspend fun <T> runSelect(
        sql: String,
        userId: String,
        parser: (Map<String, JsonElement?>) -> T?,
    ): List<T> = runSelect(sql, listOf(textArg(userId)), parser)

    private fun textArg(value: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("text"))
        put("value", JsonPrimitive(value))
    }

    // Hrana protocol encodes integer args as type "integer" with a string value.
    private fun intArg(value: Long): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("integer"))
        put("value", JsonPrimitive(value.toString()))
    }

    private suspend fun <T> runSelect(
        sql: String,
        args: List<JsonObject>,
        parser: (Map<String, JsonElement?>) -> T?,
    ): List<T> {
        val select = buildJsonObject {
            put("type", JsonPrimitive("execute"))
            put("stmt", buildJsonObject {
                put("sql", JsonPrimitive(sql))
                put("args", buildJsonArray { args.forEach { add(it) } })
            })
        }
        val body = buildJsonObject {
            put("requests", buildJsonArray {
                add(select)
                add(buildJsonObject { put("type", JsonPrimitive("close")) })
            })
        }

        val response: HttpResponse = try {
            httpClient.post("${baseUrl.trimEnd('/')}/v2/pipeline") {
                contentType(ContentType.Application.Json)
                headers { append("Authorization", "Bearer $authToken") }
                setBody(Json.encodeToString(JsonObject.serializer(), body))
            }
        } catch (_: Throwable) {
            return emptyList()
        }

        if (response.status != HttpStatusCode.OK) return emptyList()
        val raw = runCatching { response.bodyAsText() }.getOrNull() ?: return emptyList()
        val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
        val firstResult = root["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: return emptyList()
        val payload = firstResult["response"]?.jsonObject?.get("result")?.jsonObject ?: return emptyList()
        val cols = payload["cols"]?.jsonArray?.map { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: "" } ?: return emptyList()
        val rows = payload["rows"]?.jsonArray ?: return emptyList()

        return rows.mapNotNull { rowJson ->
            val cells = rowJson.jsonArray
            val indexed = cols.mapIndexed { i, name -> name to cells.getOrNull(i) }.toMap()
            parser(indexed)
        }
    }

    // ── row parsers ────────────────────────────────────────────────

    private fun parseSessionEventRow(row: Map<String, JsonElement?>): RemoteSessionEventRecord? {
        val deviceId = row.text("device_id") ?: return null
        val localId = row.long("local_id") ?: return null
        val eventType = row.text("event_type") ?: return null
        return RemoteSessionEventRecord(
            deviceId = deviceId,
            localId = localId,
            eventType = eventType,
            timestamp = row.long("timestamp") ?: 0L,
            durationMs = row.long("duration_ms"),
            comicId = row.text("comic_id") ?: "_no_comic_",
            chapterName = row.text("chapter_name"),
            pageId = row.text("page_id"),
            pageTitle = row.text("page_title"),
        )
    }

    private fun parseSceneRow(row: Map<String, JsonElement?>): RemoteSceneRecord? {
        val deviceId = row.text("device_id") ?: return null
        val localId = row.long("local_id") ?: return null
        val imageId = row.text("image_id") ?: return null
        return RemoteSceneRecord(
            deviceId = deviceId,
            localId = localId,
            imageId = imageId,
            boxIndex = row.long("box_index")?.toInt() ?: -1,
            boxX = row.float("box_x") ?: 0f,
            boxY = row.float("box_y") ?: 0f,
            boxWidth = row.float("box_width") ?: 0f,
            boxHeight = row.float("box_height") ?: 0f,
            label = row.text("label") ?: "scene",
            timestamp = row.long("timestamp") ?: 0L,
            tapX = row.float("tap_x") ?: 0f,
            tapY = row.float("tap_y") ?: 0f,
            regionType = row.text("region_type") ?: "CROP",
            parentBubbleIndex = row.long("parent_bubble_index")?.toInt(),
            tokenIndex = row.long("token_index")?.toInt(),
            comicId = row.text("comic_id"),
            userId = row.text("user_id"),
        )
    }

    private fun parseAnnotationRow(row: Map<String, JsonElement?>): RemoteAnnotationRecord? {
        val deviceId = row.text("device_id") ?: return null
        val localId = row.long("local_id") ?: return null
        val imageId = row.text("image_id") ?: return null
        return RemoteAnnotationRecord(
            deviceId = deviceId,
            localId = localId,
            imageId = imageId,
            boxIndex = row.long("box_index")?.toInt() ?: -1,
            boxX = row.float("box_x") ?: 0f,
            boxY = row.float("box_y") ?: 0f,
            boxWidth = row.float("box_width") ?: 0f,
            boxHeight = row.float("box_height") ?: 0f,
            label = row.text("label") ?: "",
            timestamp = row.long("timestamp") ?: 0L,
            tapX = row.float("tap_x") ?: 0f,
            tapY = row.float("tap_y") ?: 0f,
            regionType = row.text("region_type") ?: "BUBBLE",
            parentBubbleIndex = row.long("parent_bubble_index")?.toInt(),
            tokenIndex = row.long("token_index")?.toInt(),
            comicId = row.text("comic_id"),
        )
    }

    private fun parseChatMessageRow(row: Map<String, JsonElement?>): RemoteChatMessageRecord? {
        val deviceId = row.text("device_id") ?: return null
        val localId = row.long("local_id") ?: return null
        val sender = row.text("sender") ?: return null
        val text = row.text("text") ?: return null
        return RemoteChatMessageRecord(
            deviceId = deviceId,
            localId = localId,
            sender = sender,
            text = text,
            timestamp = row.long("timestamp") ?: 0L,
        )
    }

    private fun parsePageInteractionRow(row: Map<String, JsonElement?>): RemotePageInteractionRecord? {
        val deviceId = row.text("device_id") ?: return null
        val localId = row.long("local_id") ?: return null
        val interactionType = row.text("interaction_type") ?: return null
        return RemotePageInteractionRecord(
            deviceId = deviceId,
            localId = localId,
            interactionType = interactionType,
            timestamp = row.long("timestamp") ?: 0L,
            comicId = row.text("comic_id") ?: "_no_comic_",
            chapterName = row.text("chapter_name"),
            pageId = row.text("page_id"),
            normalizedX = row.float("normalized_x"),
            normalizedY = row.float("normalized_y"),
            hitResult = row.text("hit_result"),
        )
    }

    private fun parseAppLaunchRow(row: Map<String, JsonElement?>): RemoteAppLaunchRecord? {
        val deviceId = row.text("device_id") ?: return null
        val localId = row.long("local_id") ?: return null
        val packageName = row.text("package_name") ?: return null
        return RemoteAppLaunchRecord(
            deviceId = deviceId,
            localId = localId,
            packageName = packageName,
            timestamp = row.long("timestamp") ?: 0L,
            comicId = row.text("comic_id") ?: "_no_comic_",
            currentChapter = row.text("current_chapter"),
            currentPageId = row.text("current_page_id"),
        )
    }

    private fun parseSettingsChangeRow(row: Map<String, JsonElement?>): RemoteSettingsChangeRecord? {
        val deviceId = row.text("device_id") ?: return null
        val localId = row.long("local_id") ?: return null
        val settingKey = row.text("setting_key") ?: return null
        return RemoteSettingsChangeRecord(
            deviceId = deviceId,
            localId = localId,
            settingKey = settingKey,
            oldValue = row.text("old_value") ?: "",
            newValue = row.text("new_value") ?: "",
            timestamp = row.long("timestamp") ?: 0L,
        )
    }

    private fun parseRegionTranslationRow(row: Map<String, JsonElement?>): RemoteRegionTranslationRecord? {
        val id = row.text("id") ?: return null
        val imageId = row.text("image_id") ?: return null
        return RemoteRegionTranslationRecord(
            id = id,
            imageId = imageId,
            bubbleIndex = row.long("bubble_index")?.toInt() ?: 0,
            originalText = row.text("original_text") ?: "",
            meaningTranslation = row.text("meaning_translation") ?: "",
            literalTranslation = row.text("literal_translation") ?: "",
            sourceLanguage = row.text("source_language") ?: "ja",
            targetLanguage = row.text("target_language") ?: "en",
        )
    }

    private fun parsePageBubbleOrderingRow(row: Map<String, JsonElement?>): RemotePageBubbleOrderingRecord? {
        val userId = row.text("user_id") ?: return null
        val imageId = row.text("image_id") ?: return null
        return RemotePageBubbleOrderingRecord(
            userId = userId,
            comicId = row.text("comic_id") ?: "_no_comic_",
            imageId = imageId,
            originalIndicesCsv = row.text("original_indices") ?: "",
            updatedAt = row.long("updated_at") ?: 0L,
        )
    }

    private fun Map<String, JsonElement?>.text(key: String): String? {
        val v = this[key] ?: return null
        if (v is JsonNull) return null
        return (v as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull
            ?: (v as? JsonPrimitive)?.contentOrNull
    }

    private fun Map<String, JsonElement?>.long(key: String): Long? {
        val s = text(key) ?: return null
        return s.toLongOrNull()
    }

    private fun Map<String, JsonElement?>.float(key: String): Float? {
        val s = text(key) ?: return null
        return s.toFloatOrNull()
    }

    companion object {
        /** The six small per-user tables [fetchAllForUser] pulls, probe order = [WATERMARKS_SQL] order. */
        val SMALL_TABLES = listOf(
            "annotation_records",
            "chat_messages",
            "app_launch_records",
            "settings_changes",
            "region_translations",
            "page_bubble_orderings",
        )

        private const val WATERMARKS_SQL =
            "SELECT 'annotation_records' AS t, coalesce(max(rowid),0) AS m FROM annotation_records WHERE user_id = ? " +
                "UNION ALL SELECT 'chat_messages', coalesce(max(rowid),0) FROM chat_messages WHERE user_id = ? " +
                "UNION ALL SELECT 'app_launch_records', coalesce(max(rowid),0) FROM app_launch_records WHERE user_id = ? " +
                "UNION ALL SELECT 'settings_changes', coalesce(max(rowid),0) FROM settings_changes WHERE user_id = ? " +
                "UNION ALL SELECT 'region_translations', coalesce(max(rowid),0) FROM region_translations WHERE user_id = ? " +
                "UNION ALL SELECT 'page_bubble_orderings', coalesce(max(updated_at),0) FROM page_bubble_orderings WHERE user_id = ?"

        /** Rows per incremental batch. ~350 B/row ⇒ ≈ 0.7 MB JSON per batch. */
        const val INCR_BATCH_LIMIT = 2000

        private const val SESSION_EVENTS_INCR_SQL =
            "SELECT rowid AS rid, device_id, local_id, event_type, timestamp, duration_ms, " +
                "comic_id, chapter_name, page_id, page_title " +
                "FROM session_events WHERE user_id = ? AND rowid > ? " +
                "ORDER BY rowid LIMIT $INCR_BATCH_LIMIT"

        private const val PAGE_INTERACTIONS_INCR_SQL =
            "SELECT rowid AS rid, device_id, local_id, interaction_type, timestamp, comic_id, chapter_name, " +
                "page_id, normalized_x, normalized_y, hit_result " +
                "FROM page_interactions WHERE user_id = ? AND rowid > ? " +
                "ORDER BY rowid LIMIT $INCR_BATCH_LIMIT"

        private const val SCENE_SELECT_SQL =
            "SELECT device_id, local_id, image_id, box_index, box_x, box_y, box_width, box_height, " +
                "label, timestamp, tap_x, tap_y, region_type, parent_bubble_index, token_index, " +
                "comic_id, user_id FROM annotation_records WHERE user_id = ? AND label = 'scene'"

        private const val ANNOTATIONS_SQL =
            "SELECT device_id, local_id, image_id, box_index, box_x, box_y, box_width, box_height, " +
                "label, timestamp, tap_x, tap_y, region_type, parent_bubble_index, token_index, comic_id " +
                "FROM annotation_records WHERE user_id = ?"

        private const val CHAT_MESSAGES_SQL =
            "SELECT device_id, local_id, sender, text, timestamp " +
                "FROM chat_messages WHERE user_id = ?"

        private const val APP_LAUNCH_RECORDS_SQL =
            "SELECT device_id, local_id, package_name, timestamp, comic_id, current_chapter, current_page_id " +
                "FROM app_launch_records WHERE user_id = ?"

        private const val SETTINGS_CHANGES_SQL =
            "SELECT device_id, local_id, setting_key, old_value, new_value, timestamp " +
                "FROM settings_changes WHERE user_id = ?"

        private const val REGION_TRANSLATIONS_SQL =
            "SELECT id, image_id, bubble_index, original_text, meaning_translation, " +
                "literal_translation, source_language, target_language " +
                "FROM region_translations WHERE user_id = ?"

        private const val PAGE_BUBBLE_ORDERINGS_SQL =
            "SELECT user_id, comic_id, image_id, original_indices, updated_at " +
                "FROM page_bubble_orderings WHERE user_id = ?"
    }
}
