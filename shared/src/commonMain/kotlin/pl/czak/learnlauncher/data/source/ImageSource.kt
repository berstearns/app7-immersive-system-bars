package pl.czak.learnlauncher.data.source

import pl.czak.learnlauncher.data.model.ImageItem
import pl.czak.learnlauncher.data.model.RegionTranslation
import pl.czak.learnlauncher.domain.model.ConversationBox

interface ImageDataSource {
    fun getImageItems(): List<ImageItem>
    fun getConversationBoxes(imageId: String): List<ConversationBox>
    fun loadImageBytes(filename: String, chapter: String = ""): ByteArray?
    fun getTokensForBubble(imageId: String, bubbleIndex: Int): List<ConversationBox> = emptyList()
    /**
     * Eagerly load `<comicDir>/<chapter>/translated_pages/img_NNN.json` for every
     * chapter the data source knows about. Returns a map keyed by *qualified*
     * imageId (`<chapterPath>/<localImageId>`) → bubbleIndex → RegionTranslation.
     * Default no-op for data sources that don't have a file backing.
     */
    fun loadTranslatedPagesIfAny(): Map<String, Map<Int, RegionTranslation>> = emptyMap()
}
