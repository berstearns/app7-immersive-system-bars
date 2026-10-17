package pl.czak.learnlauncher.data

import java.util.concurrent.atomic.AtomicLong
import pl.czak.learnlauncher.currentTimeMillis
import pl.czak.learnlauncher.data.model.AppLaunchRecord
import pl.czak.learnlauncher.data.model.PageInteractionRecord
import pl.czak.learnlauncher.data.model.RegionTranslation
import pl.czak.learnlauncher.data.model.SessionEventRecord
import pl.czak.learnlauncher.data.model.SettingsChangeRecord
import pl.czak.learnlauncher.data.repository.*
import pl.czak.learnlauncher.data.sync.model.RemoteAnnotationRecord
import pl.czak.learnlauncher.data.sync.model.RemoteAppLaunchRecord
import pl.czak.learnlauncher.data.sync.model.RemoteChatMessageRecord
import pl.czak.learnlauncher.data.sync.model.RemotePageBubbleOrderingRecord
import pl.czak.learnlauncher.data.sync.model.RemotePageInteractionRecord
import pl.czak.learnlauncher.data.sync.model.RemoteSceneRecord
import pl.czak.learnlauncher.data.sync.model.RemoteSessionEventRecord
import pl.czak.learnlauncher.data.sync.model.RemoteSettingsChangeRecord
import pl.czak.learnlauncher.domain.model.ChatMessage
import pl.czak.learnlauncher.domain.model.ConversationBox
import pl.czak.learnlauncher.domain.model.PageBubbleOrdering
import pl.czak.learnlauncher.domain.model.RegionAnnotation
import pl.czak.learnlauncher.domain.model.RegionType
import pl.czak.learnlauncher.domain.session.CompletedSession

/**
 * Thread-safe in-memory annotation store. Synchronization is required because
 * the producer (UI / coroutine scope) and the consumer (sync ticker) run on
 * different threads.
 */
class InMemoryAnnotationRepository : AnnotationRepository {
    private val lock = Any()
    private val annotations = mutableListOf<RegionAnnotation>()
    private val syncedTimestamps = mutableSetOf<Long>()

    override suspend fun addAnnotation(annotation: RegionAnnotation) {
        synchronized(lock) { annotations.add(annotation) }
    }

    override suspend fun getAll(): List<RegionAnnotation> =
        synchronized(lock) { annotations.toList() }

    fun getUnsynced(): List<RegionAnnotation> = synchronized(lock) {
        annotations.filter { it.timestamp !in syncedTimestamps }.toList()
    }

    fun markSynced(timestamps: List<Long>) {
        synchronized(lock) { syncedTimestamps.addAll(timestamps) }
    }

    override suspend fun getForImage(imageId: String): List<RegionAnnotation> =
        synchronized(lock) { annotations.filter { it.imageId == imageId }.toList() }

    override suspend fun getTokenAnnotationsForBubble(
        imageId: String,
        bubbleIndex: Int,
    ): List<RegionAnnotation> = synchronized(lock) {
        annotations.filter { it.imageId == imageId && it.parentBubbleIndex == bubbleIndex }.toList()
    }

    fun upsertScenesFromRemote(remotes: List<RemoteSceneRecord>): Int = synchronized(lock) {
        var added = 0
        for (r in remotes) {
            val regionType = runCatching { RegionType.valueOf(r.regionType) }.getOrDefault(RegionType.CROP)
            val isDuplicate = annotations.any { existing ->
                existing.imageId == r.imageId &&
                    existing.label == r.label &&
                    existing.regionType == regionType &&
                    kotlin.math.abs(existing.boxX - r.boxX) < 0.0005f &&
                    kotlin.math.abs(existing.boxY - r.boxY) < 0.0005f &&
                    kotlin.math.abs(existing.boxWidth - r.boxWidth) < 0.0005f &&
                    kotlin.math.abs(existing.boxHeight - r.boxHeight) < 0.0005f
            }
            if (isDuplicate) continue
            annotations += RegionAnnotation(
                imageId = r.imageId,
                boxIndex = r.boxIndex,
                boxX = r.boxX,
                boxY = r.boxY,
                boxWidth = r.boxWidth,
                boxHeight = r.boxHeight,
                label = r.label,
                timestamp = r.timestamp,
                tapX = r.tapX,
                tapY = r.tapY,
                regionType = regionType,
                parentBubbleIndex = r.parentBubbleIndex,
                tokenIndex = r.tokenIndex,
            )
            syncedTimestamps += r.timestamp
            added += 1
        }
        added
    }

    /**
     * Cross-device alignment merge for annotation_records (all labels, not
     * just scenes). Mirrors [upsertScenesFromRemote]'s additive dedup by
     * geometry+label epsilon match, but accepts the broader
     * [RemoteAnnotationRecord] shape so non-scene annotations propagate
     * across devices.
     */
    fun upsertAnnotationsFromRemote(remotes: List<RemoteAnnotationRecord>): Int = synchronized(lock) {
        var added = 0
        for (r in remotes) {
            val regionType = runCatching { RegionType.valueOf(r.regionType) }.getOrDefault(RegionType.BUBBLE)
            val isDuplicate = annotations.any { existing ->
                existing.imageId == r.imageId &&
                    existing.label == r.label &&
                    existing.regionType == regionType &&
                    kotlin.math.abs(existing.boxX - r.boxX) < 0.0005f &&
                    kotlin.math.abs(existing.boxY - r.boxY) < 0.0005f &&
                    kotlin.math.abs(existing.boxWidth - r.boxWidth) < 0.0005f &&
                    kotlin.math.abs(existing.boxHeight - r.boxHeight) < 0.0005f
            }
            if (isDuplicate) continue
            annotations += RegionAnnotation(
                imageId = r.imageId,
                boxIndex = r.boxIndex,
                boxX = r.boxX,
                boxY = r.boxY,
                boxWidth = r.boxWidth,
                boxHeight = r.boxHeight,
                label = r.label,
                timestamp = r.timestamp,
                tapX = r.tapX,
                tapY = r.tapY,
                regionType = regionType,
                parentBubbleIndex = r.parentBubbleIndex,
                tokenIndex = r.tokenIndex,
            )
            syncedTimestamps += r.timestamp
            added += 1
        }
        added
    }
}

class InMemorySessionRepository : SessionRepository {
    private val sessions = mutableListOf<CompletedSession>()

    override suspend fun addSessions(completed: List<CompletedSession>) {
        sessions.addAll(completed)
    }

    override suspend fun getAll(): List<CompletedSession> = sessions.toList()
}

class InMemoryTokenRegionRepository : TokenRegionRepository {
    override fun getTokensForBubble(imageId: String, bubbleIndex: Int): List<ConversationBox> = emptyList()
    override fun hasTokens(imageId: String, bubbleIndex: Int): Boolean = false
}

class InMemoryTranslationRepository : TranslationRepository {
    private val translations = mutableListOf<RegionTranslation>()

    override suspend fun getTranslation(imageId: String, bubbleIndex: Int): RegionTranslation? =
        translations.find { it.imageId == imageId && it.bubbleIndex == bubbleIndex }
    override fun hasTranslation(imageId: String, bubbleIndex: Int): Boolean =
        translations.any { it.imageId == imageId && it.bubbleIndex == bubbleIndex }
    override suspend fun getTranslationsForImage(imageId: String): List<RegionTranslation> =
        translations.filter { it.imageId == imageId }
    override suspend fun getAll(): List<RegionTranslation> = translations.toList()
    override suspend fun saveTranslation(translation: RegionTranslation) { translations.add(translation) }

    override fun seedFromComic(translations: Map<String, Map<Int, RegionTranslation>>) {
        // De-dupe on (imageId, bubbleIndex): drop existing matching entries
        // before adding the new comic-scoped ones.
        val seedKeys = translations.flatMap { (imageId, perBubble) ->
            perBubble.keys.map { imageId to it }
        }.toSet()
        this.translations.removeAll { (it.imageId to it.bubbleIndex) in seedKeys }
        for ((_, perBubble) in translations) {
            this.translations.addAll(perBubble.values)
        }
        val nBubbles = translations.values.sumOf { it.size }
        val nTokens = translations.values.flatMap { it.values }.sumOf { it.tokenTranslations.size }
        println("[InMemoryTranslationRepository] seedFromComic: " +
            "images=${translations.size} bubbles=$nBubbles tokens=$nTokens")
    }
}

/**
 * Thread-safe chat store with sync tracking. Dedup key is `timestamp` to match
 * the AnnotationRepository convention (Long key, mark-by-key semantics).
 */
class InMemoryChatRepository : ChatRepository {
    private val lock = Any()
    private val messages = mutableListOf<ChatMessage>()
    private val syncedTimestamps = mutableSetOf<Long>()

    override suspend fun getAll(): List<ChatMessage> =
        synchronized(lock) { messages.toList() }

    override suspend fun save(message: ChatMessage) {
        synchronized(lock) { messages.add(message) }
    }

    fun getUnsynced(): List<ChatMessage> = synchronized(lock) {
        messages.filter { it.timestamp !in syncedTimestamps }.toList()
    }

    fun markSynced(timestamps: List<Long>) {
        synchronized(lock) { syncedTimestamps.addAll(timestamps) }
    }

    /**
     * Cross-device alignment merge for chat_messages. Dedup key:
     * `(sender, text, timestamp)`. Additive — no overwrites.
     */
    fun upsertChatMessagesFromRemote(remotes: List<RemoteChatMessageRecord>): Int = synchronized(lock) {
        var added = 0
        for (r in remotes) {
            val dup = messages.any {
                it.sender == r.sender && it.text == r.text && it.timestamp == r.timestamp
            }
            if (dup) continue
            messages += ChatMessage(
                sender = r.sender,
                text = r.text,
                timestamp = r.timestamp,
            )
            syncedTimestamps += r.timestamp
            added += 1
        }
        added
    }
}

/**
 * Captures the four telemetry event types the [LearnerDataRepository]
 * interface exposes. Mirrors the Android / Room-backed implementation in
 * [pl.czak.learnlauncher.data.AndroidLearnerDataRepository] so a desktop
 * session produces the same rows in Turso/Supabase as the phone would.
 *
 * Local-id assignment uses a single monotonic [AtomicLong] seeded from the
 * wall clock so ids are unique within a process and (across restarts) unique
 * per-table within a device. The PRIMARY KEY on the cloud side is
 * `(device_id, local_id)` — collisions across event types are harmless
 * (different tables) but within a table are not, so the seed must be
 * monotonic in time.
 */
class InMemoryLearnerDataRepository(
    private val currentComicId: () -> String = { "_no_comic_" },
) : LearnerDataRepository {

    private val lock = Any()
    private val idGen = AtomicLong(currentTimeMillis())

    private val sessionEvents = mutableListOf<SessionEventRecord>()
    private val pageInteractions = mutableListOf<PageInteractionRecord>()
    private val appLaunches = mutableListOf<AppLaunchRecord>()
    private val settingsChanges = mutableListOf<SettingsChangeRecord>()

    private val sessionEventsSynced = mutableSetOf<Long>()
    private val pageInteractionsSynced = mutableSetOf<Long>()
    private val appLaunchesSynced = mutableSetOf<Long>()
    private val settingsChangesSynced = mutableSetOf<Long>()

    // ── log* (producer side) ───────────────────────────────────────

    override suspend fun logSessionEvent(
        eventType: String,
        chapterName: String?,
        pageId: String?,
        pageTitle: String?,
    ) {
        val record = SessionEventRecord(
            localId = idGen.incrementAndGet(),
            eventType = eventType,
            timestamp = currentTimeMillis(),
            comicId = currentComicId(),
            chapterName = chapterName,
            pageId = pageId,
            pageTitle = pageTitle,
        )
        synchronized(lock) { sessionEvents.add(record) }
    }

    override suspend fun logPageInteraction(
        interactionType: String,
        chapterName: String?,
        pageId: String?,
        normalizedX: Float?,
        normalizedY: Float?,
        hitResult: String?,
    ) {
        val record = PageInteractionRecord(
            localId = idGen.incrementAndGet(),
            interactionType = interactionType,
            timestamp = currentTimeMillis(),
            comicId = currentComicId(),
            chapterName = chapterName,
            pageId = pageId,
            normalizedX = normalizedX,
            normalizedY = normalizedY,
            hitResult = hitResult,
        )
        synchronized(lock) { pageInteractions.add(record) }
    }

    override suspend fun logAppLaunch(
        packageName: String,
        currentChapter: String?,
        currentPageId: String?,
    ) {
        val record = AppLaunchRecord(
            localId = idGen.incrementAndGet(),
            packageName = packageName,
            timestamp = currentTimeMillis(),
            comicId = currentComicId(),
            currentChapter = currentChapter,
            currentPageId = currentPageId,
        )
        synchronized(lock) { appLaunches.add(record) }
    }

    override suspend fun logSettingsChange(setting: String, oldValue: String, newValue: String) {
        val record = SettingsChangeRecord(
            localId = idGen.incrementAndGet(),
            settingKey = setting,
            oldValue = oldValue,
            newValue = newValue,
            timestamp = currentTimeMillis(),
        )
        synchronized(lock) { settingsChanges.add(record) }
    }

    override suspend fun getTotalUnsyncedCount(): Int = synchronized(lock) {
        unsyncedCount(sessionEvents) { it.localId !in sessionEventsSynced } +
            unsyncedCount(pageInteractions) { it.localId !in pageInteractionsSynced } +
            unsyncedCount(appLaunches) { it.localId !in appLaunchesSynced } +
            unsyncedCount(settingsChanges) { it.localId !in settingsChangesSynced }
    }

    private inline fun <T> unsyncedCount(list: List<T>, predicate: (T) -> Boolean): Int =
        list.count(predicate)

    // ── snapshot / mark (sync side) ────────────────────────────────

    fun getUnsyncedSessionEvents(): List<SessionEventRecord> = synchronized(lock) {
        sessionEvents.filter { it.localId !in sessionEventsSynced }.toList()
    }
    fun markSessionEventsSynced(ids: List<Long>) = synchronized(lock) {
        sessionEventsSynced.addAll(ids)
    }

    fun getUnsyncedPageInteractions(): List<PageInteractionRecord> = synchronized(lock) {
        pageInteractions.filter { it.localId !in pageInteractionsSynced }.toList()
    }
    fun markPageInteractionsSynced(ids: List<Long>) = synchronized(lock) {
        pageInteractionsSynced.addAll(ids)
    }

    fun getUnsyncedAppLaunches(): List<AppLaunchRecord> = synchronized(lock) {
        appLaunches.filter { it.localId !in appLaunchesSynced }.toList()
    }
    fun markAppLaunchesSynced(ids: List<Long>) = synchronized(lock) {
        appLaunchesSynced.addAll(ids)
    }

    fun getUnsyncedSettingsChanges(): List<SettingsChangeRecord> = synchronized(lock) {
        settingsChanges.filter { it.localId !in settingsChangesSynced }.toList()
    }
    fun markSettingsChangesSynced(ids: List<Long>) = synchronized(lock) {
        settingsChangesSynced.addAll(ids)
    }

    // ── cross-device alignment merge (pull) ────────────────────────
    //
    // Dedup key per table is chosen to minimize false-merge risk while
    // staying schema-light. Local ids generated by [idGen] are *not* used
    // for dedup because the remote rows came from a different device and
    // would otherwise always be classified as new. See feature spec
    // `app7-ideas-for-later/cross-device-learner-records-alignment-pull.md`.

    fun upsertSessionEventsFromRemote(remotes: List<RemoteSessionEventRecord>): Int = synchronized(lock) {
        var added = 0
        for (r in remotes) {
            val dup = sessionEvents.any {
                it.eventType == r.eventType &&
                    it.timestamp == r.timestamp &&
                    it.comicId == r.comicId &&
                    it.chapterName == r.chapterName &&
                    it.pageId == r.pageId
            }
            if (dup) continue
            sessionEvents += SessionEventRecord(
                localId = idGen.incrementAndGet(),
                eventType = r.eventType,
                timestamp = r.timestamp,
                durationMs = r.durationMs,
                comicId = r.comicId,
                chapterName = r.chapterName,
                pageId = r.pageId,
                pageTitle = r.pageTitle,
                synced = true,
            )
            added += 1
        }
        added
    }

    fun upsertPageInteractionsFromRemote(remotes: List<RemotePageInteractionRecord>): Int = synchronized(lock) {
        var added = 0
        for (r in remotes) {
            val dup = pageInteractions.any {
                it.interactionType == r.interactionType &&
                    it.timestamp == r.timestamp &&
                    it.pageId == r.pageId &&
                    it.comicId == r.comicId
            }
            if (dup) continue
            pageInteractions += PageInteractionRecord(
                localId = idGen.incrementAndGet(),
                interactionType = r.interactionType,
                timestamp = r.timestamp,
                comicId = r.comicId,
                chapterName = r.chapterName,
                pageId = r.pageId,
                normalizedX = r.normalizedX,
                normalizedY = r.normalizedY,
                hitResult = r.hitResult,
                synced = true,
            )
            added += 1
        }
        added
    }

    fun upsertAppLaunchesFromRemote(remotes: List<RemoteAppLaunchRecord>): Int = synchronized(lock) {
        var added = 0
        for (r in remotes) {
            val dup = appLaunches.any {
                it.packageName == r.packageName &&
                    it.timestamp == r.timestamp &&
                    it.comicId == r.comicId
            }
            if (dup) continue
            appLaunches += AppLaunchRecord(
                localId = idGen.incrementAndGet(),
                packageName = r.packageName,
                timestamp = r.timestamp,
                comicId = r.comicId,
                currentChapter = r.currentChapter,
                currentPageId = r.currentPageId,
                synced = true,
            )
            added += 1
        }
        added
    }

    fun upsertSettingsChangesFromRemote(remotes: List<RemoteSettingsChangeRecord>): Int = synchronized(lock) {
        var added = 0
        for (r in remotes) {
            val dup = settingsChanges.any {
                it.settingKey == r.settingKey &&
                    it.timestamp == r.timestamp &&
                    it.oldValue == r.oldValue &&
                    it.newValue == r.newValue
            }
            if (dup) continue
            settingsChanges += SettingsChangeRecord(
                localId = idGen.incrementAndGet(),
                settingKey = r.settingKey,
                oldValue = r.oldValue,
                newValue = r.newValue,
                timestamp = r.timestamp,
                synced = true,
            )
            added += 1
        }
        added
    }
}

class InMemoryPageBubbleOrderingRepository : PageBubbleOrderingRepository {
    private val store = mutableMapOf<String, PageBubbleOrdering>()
    private val synced = mutableSetOf<String>()

    private fun key(userId: String, comicId: String, imageId: String) =
        "$userId|$comicId|$imageId"

    override suspend fun getForPage(userId: String, comicId: String, imageId: String): PageBubbleOrdering? =
        store[key(userId, comicId, imageId)]

    override suspend fun save(ordering: PageBubbleOrdering) {
        val k = key(ordering.userId, ordering.comicId, ordering.imageId)
        store[k] = ordering
        synced.remove(k)
    }

    override suspend fun deleteForPage(userId: String, comicId: String, imageId: String) {
        val k = key(userId, comicId, imageId)
        store.remove(k)
        synced.remove(k)
    }

    override suspend fun getUnsynced(): List<PageBubbleOrdering> =
        store.entries.filter { it.key !in synced }.map { it.value }

    override suspend fun markSynced(orderings: List<PageBubbleOrdering>) {
        orderings.forEach { synced.add(key(it.userId, it.comicId, it.imageId)) }
    }

    /**
     * Cross-device alignment merge for page_bubble_orderings. The composite
     * key `(user_id, comic_id, image_id)` is canonical, and the remote
     * `updated_at` is treated as last-write-wins: a strictly newer remote
     * overwrites the local entry, an older remote is skipped.
     */
    fun upsertOrderingsFromRemote(remotes: List<RemotePageBubbleOrderingRecord>): Int {
        var added = 0
        for (r in remotes) {
            val k = key(r.userId, r.comicId, r.imageId)
            val indices = r.originalIndicesCsv
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() }
            if (indices.isEmpty()) continue
            val existing = store[k]
            val accept = existing == null || r.updatedAt > existing.updatedAt
            if (!accept) continue
            store[k] = PageBubbleOrdering(
                userId = r.userId,
                comicId = r.comicId,
                imageId = r.imageId,
                originalIndices = indices,
                updatedAt = r.updatedAt,
            )
            synced += k
            added += 1
        }
        return added
    }
}
