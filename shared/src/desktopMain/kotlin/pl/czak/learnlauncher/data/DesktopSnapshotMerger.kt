package pl.czak.learnlauncher.data

import pl.czak.learnlauncher.data.sync.MergeStats
import pl.czak.learnlauncher.data.sync.SnapshotMerger
import pl.czak.learnlauncher.data.sync.model.RemoteLearnerSnapshot

/**
 * Wires the cross-device alignment pull (see
 * `app7-ideas-for-later/cross-device-learner-records-alignment-pull.md`)
 * to the desktop in-memory repositories. Each per-table call is additive
 * with content-based dedup, mirroring the long-standing
 * `upsertScenesFromRemote` pattern.
 *
 * Annotation geometry is implicitly validated by the existing
 * `App7Native.*` delegates inside [pl.czak.learnlauncher.domain.model.CropSelection]
 * et al. on every read — no extra binary call needed at merge time.
 */
class DesktopSnapshotMerger(
    private val annotationRepository: InMemoryAnnotationRepository,
    private val chatRepository: InMemoryChatRepository,
    private val learnerDataRepository: InMemoryLearnerDataRepository,
    private val pageBubbleOrderingRepository: InMemoryPageBubbleOrderingRepository,
) : SnapshotMerger {

    override suspend fun merge(snapshot: RemoteLearnerSnapshot): Map<String, MergeStats> {
        val sessionEventsAdded = learnerDataRepository.upsertSessionEventsFromRemote(snapshot.sessionEvents)
        val annotationsAdded = annotationRepository.upsertAnnotationsFromRemote(snapshot.annotations)
        val chatAdded = chatRepository.upsertChatMessagesFromRemote(snapshot.chatMessages)
        val interactionsAdded = learnerDataRepository.upsertPageInteractionsFromRemote(snapshot.pageInteractions)
        val launchesAdded = learnerDataRepository.upsertAppLaunchesFromRemote(snapshot.appLaunches)
        val settingsAdded = learnerDataRepository.upsertSettingsChangesFromRemote(snapshot.settingsChanges)
        val orderingsAdded = pageBubbleOrderingRepository.upsertOrderingsFromRemote(snapshot.pageBubbleOrderings)
        // region_translations are addressed by textual id; not currently
        // surfaced through the in-memory desktop store. They round-trip
        // through the AndroidTranslationRepository on phone targets.

        return linkedMapOf(
            "session_events" to MergeStats(added = sessionEventsAdded),
            "annotation_records" to MergeStats(added = annotationsAdded),
            "chat_messages" to MergeStats(added = chatAdded),
            "page_interactions" to MergeStats(added = interactionsAdded),
            "app_launch_records" to MergeStats(added = launchesAdded),
            "settings_changes" to MergeStats(added = settingsAdded),
            "page_bubble_orderings" to MergeStats(added = orderingsAdded),
        )
    }
}
