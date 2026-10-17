package pl.czak.learnlauncher.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import pl.czak.learnlauncher.data.db.AppDatabase
import pl.czak.learnlauncher.data.db.entity.PageBubbleOrderingEntity
import pl.czak.learnlauncher.data.repository.PageBubbleOrderingRepository
import pl.czak.learnlauncher.domain.model.PageBubbleOrdering

class AndroidPageBubbleOrderingRepository(
    private val db: AppDatabase,
) : PageBubbleOrderingRepository {

    private val intListSerializer = ListSerializer(Int.serializer())

    private fun composeId(userId: String, comicId: String, imageId: String): String =
        "$userId|$comicId|$imageId"

    private fun encodeIndices(indices: List<Int>): String =
        Json.encodeToString(intListSerializer, indices)

    private fun decodeIndices(payload: String): List<Int> =
        Json.decodeFromString(intListSerializer, payload)

    private fun PageBubbleOrderingEntity.toModel(): PageBubbleOrdering = PageBubbleOrdering(
        userId = userId,
        comicId = comicId,
        imageId = imageId,
        originalIndices = decodeIndices(originalIndicesJson),
        updatedAt = updatedAt,
    )

    private fun PageBubbleOrdering.toEntity(): PageBubbleOrderingEntity = PageBubbleOrderingEntity(
        id = composeId(userId, comicId, imageId),
        userId = userId,
        comicId = comicId,
        imageId = imageId,
        originalIndicesJson = encodeIndices(originalIndices),
        updatedAt = updatedAt,
    )

    override suspend fun getForPage(
        userId: String,
        comicId: String,
        imageId: String,
    ): PageBubbleOrdering? = withContext(Dispatchers.IO) {
        db.pageBubbleOrderingDao()
            .getById(composeId(userId, comicId, imageId))
            ?.toModel()
    }

    override suspend fun save(ordering: PageBubbleOrdering) {
        withContext(Dispatchers.IO) {
            db.pageBubbleOrderingDao().upsert(ordering.toEntity())
        }
    }

    override suspend fun deleteForPage(userId: String, comicId: String, imageId: String) {
        withContext(Dispatchers.IO) {
            db.pageBubbleOrderingDao().deleteById(composeId(userId, comicId, imageId))
        }
    }

    override suspend fun getUnsynced(): List<PageBubbleOrdering> = withContext(Dispatchers.IO) {
        db.pageBubbleOrderingDao().getUnsynced().map { it.toModel() }
    }

    override suspend fun markSynced(orderings: List<PageBubbleOrdering>) {
        if (orderings.isEmpty()) return
        val ids = orderings.map { composeId(it.userId, it.comicId, it.imageId) }
        withContext(Dispatchers.IO) {
            db.pageBubbleOrderingDao().markSynced(ids)
        }
    }
}
