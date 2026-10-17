package pl.czak.learnlauncher.domain.navigation

import pl.czak.learnlauncher.data.model.ImageItem
import pl.czak.learnlauncher.domain.native.App7Native

/**
 * [ImageSequenceNavigator]-API-compatible implementation backed by the
 * compiled domain binary (`libapp7_domain.so` via [App7Native]).
 *
 * Kotlin owns the [ImageItem] list and the chapter names; the C side
 * tracks size, current index, and sorted chapter-start indices.  Each
 * navigation call yields a new index (Int); Kotlin looks up the
 * corresponding ImageItem in its own list — no ImageItem marshalling
 * crosses the JNI boundary.
 *
 * Caller must invoke [close] for explicit cleanup (or accept the leak,
 * reclaimed on process exit).
 */
class NativeImageSequenceNavigator {

    private val handle: Long = App7Native.navNew()
    private var images: List<ImageItem> = emptyList()
    private var chapterNames: List<String> = emptyList()
    private var closed = false

    fun initialize(allImages: List<ImageItem>) {
        // Match the Kotlin original: stable sort by (chapter, filename).
        val sorted = allImages.sortedWith(compareBy({ it.chapter }, { it.filename }))
        images = sorted

        val names = ArrayList<String>()
        val starts = ArrayList<Int>()
        var lastChapter = ""
        for ((i, img) in sorted.withIndex()) {
            if (img.chapter != lastChapter) {
                names.add(img.chapter)
                starts.add(i)
                lastChapter = img.chapter
            }
        }
        chapterNames = names

        App7Native.navInitialize(handle, sorted.size, starts.toIntArray())
    }

    fun next(): ImageItem?     = imageOrNull(App7Native.navNext(handle))
    fun previous(): ImageItem? = imageOrNull(App7Native.navPrevious(handle))

    fun current(): ImageItem? {
        val idx = App7Native.navPosition(handle)
        return imageOrNull(idx)
    }

    fun nextChapter(): ImageItem?     = imageOrNull(App7Native.navNextChapter(handle))
    fun previousChapter(): ImageItem? = imageOrNull(App7Native.navPreviousChapter(handle))

    fun goToChapter(chapterIdx: Int): ImageItem? =
        imageOrNull(App7Native.navGoToChapter(handle, chapterIdx))

    fun restorePosition(savedIndex: Int): ImageItem? =
        imageOrNull(App7Native.navRestorePosition(handle, savedIndex))

    fun goToImageId(imageId: String): ImageItem? {
        val idx = images.indexOfFirst { it.id == imageId }
        if (idx < 0) return null
        return imageOrNull(App7Native.navRestorePosition(handle, idx))
    }

    val chapterCount: Int get() = App7Native.navChapterCount(handle)
    fun getChapterNames(): List<String> = chapterNames

    val canGoForward: Boolean         get() = App7Native.navCanGoForward(handle)
    val canGoBack: Boolean            get() = App7Native.navCanGoBack(handle)
    val canGoNextChapter: Boolean     get() = App7Native.navCanGoNextChapter(handle)
    val canGoPreviousChapter: Boolean get() = App7Native.navCanGoPreviousChapter(handle)

    val currentChapterName: String
        get() {
            if (chapterNames.isEmpty()) return ""
            val idx = App7Native.navPosition(handle)
            if (idx < 0) return ""
            val ci = App7Native.navCurrentChapterIndex(handle)
            return chapterNames.getOrNull(ci) ?: ""
        }

    val position: Int get() = App7Native.navPosition(handle)
    val size: Int     get() = App7Native.navSize(handle)

    fun close() {
        if (!closed) {
            App7Native.navFree(handle)
            closed = true
        }
    }

    private fun imageOrNull(idx: Int): ImageItem? =
        if (idx < 0 || idx >= images.size) null else images[idx]
}
