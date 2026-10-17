package pl.czak.learnlauncher.domain.navigation

import pl.czak.learnlauncher.data.model.ImageItem

/**
 * Phase 3b migration: this class is now a thin delegating wrapper over
 * [NativeImageSequenceNavigator] (which routes through `App7Native` →
 * `libapp7_domain.so`).  The pure-Kotlin transition logic is retained
 * only as a fallback for platforms with no native loader (iOS), per
 * `feedback_haskell_domain_done_gate`.
 *
 * On Android + Desktop every navigation event flows through the
 * compiled binary; iOS continues to execute the original Kotlin logic.
 *
 * **Resource management**: native paths allocate a small C-side struct.
 * Call [close] from `onDestroy` (Android) / `exitApplication` (Desktop).
 */
class ImageSequenceNavigator {

    // **HARD-FAIL POLICY** (feedback_haskell_domain_done_gate): see the
    // matching block in SessionTimeTracker.kt.  Only NotImplementedError
    // (the iOS stub signal) is caught — any other failure to construct
    // the native wrapper propagates out and crashes the app, because
    // silently routing through pure-Kotlin code would violate the
    // "every persisted/synced value passed through the binary" mandate.
    private val native: NativeImageSequenceNavigator? by lazy(LazyThreadSafetyMode.NONE) {
        try { NativeImageSequenceNavigator() } catch (_: NotImplementedError) { null }
    }

    // Fallback Kotlin state — used only when [native] is null (iOS).
    private var images: List<ImageItem> = emptyList()
    private var index = -1
    private var chapterNames: List<String> = emptyList()
    private var chapterStartIndices: List<Int> = emptyList()

    fun close() { native?.close() }

    fun initialize(allImages: List<ImageItem>) {
        val n = native
        if (n != null) { n.initialize(allImages); return }
        images = allImages.sortedWith(compareBy({ it.chapter }, { it.filename }))
        index = -1
        buildChapterIndex()
    }

    private fun buildChapterIndex() {
        val names = mutableListOf<String>()
        val starts = mutableListOf<Int>()
        var lastChapter = ""
        for ((i, img) in images.withIndex()) {
            if (img.chapter != lastChapter) {
                names.add(img.chapter)
                starts.add(i)
                lastChapter = img.chapter
            }
        }
        chapterNames = names
        chapterStartIndices = starts
    }

    fun next(): ImageItem? {
        val n = native
        if (n != null) return n.next()
        if (images.isEmpty()) return null
        if (index < images.size - 1) {
            index++
            return images[index]
        }
        return null
    }

    fun previous(): ImageItem? {
        val n = native
        if (n != null) return n.previous()
        if (images.isEmpty()) return null
        if (index > 0) {
            index--
            return images[index]
        }
        return null
    }

    fun current(): ImageItem? {
        val n = native
        if (n != null) return n.current()
        if (images.isEmpty() || index < 0 || index >= images.size) return null
        return images[index]
    }

    fun nextChapter(): ImageItem? {
        val n = native
        if (n != null) return n.nextChapter()
        if (images.isEmpty() || chapterStartIndices.isEmpty()) return null
        val ci = currentChapterIndex()
        if (ci < chapterStartIndices.size - 1) {
            index = chapterStartIndices[ci + 1]
            return images[index]
        }
        return null
    }

    fun previousChapter(): ImageItem? {
        val n = native
        if (n != null) return n.previousChapter()
        if (images.isEmpty() || chapterStartIndices.isEmpty()) return null
        val ci = currentChapterIndex()
        if (ci > 0) {
            index = chapterStartIndices[ci - 1]
            return images[index]
        }
        return null
    }

    fun goToChapter(chapterIdx: Int): ImageItem? {
        val n = native
        if (n != null) return n.goToChapter(chapterIdx)
        if (chapterIdx < 0 || chapterIdx >= chapterStartIndices.size) return null
        index = chapterStartIndices[chapterIdx]
        return images[index]
    }

    val chapterCount: Int
        get() = native?.chapterCount ?: chapterNames.size

    fun getChapterNames(): List<String> = native?.getChapterNames() ?: chapterNames

    val canGoForward: Boolean
        get() = native?.canGoForward
            ?: (images.isNotEmpty() && index < images.size - 1)

    val canGoBack: Boolean
        get() = native?.canGoBack ?: (index > 0)

    val canGoNextChapter: Boolean
        get() = native?.canGoNextChapter
            ?: (chapterStartIndices.isNotEmpty() && currentChapterIndex() < chapterStartIndices.size - 1)

    val canGoPreviousChapter: Boolean
        get() = native?.canGoPreviousChapter ?: (currentChapterIndex() > 0)

    val currentChapterName: String
        get() = native?.currentChapterName ?: run {
            if (chapterNames.isEmpty() || index < 0) "" else chapterNames[currentChapterIndex()]
        }

    val position: Int
        get() = native?.position ?: index

    val size: Int
        get() = native?.size ?: images.size

    fun restorePosition(savedIndex: Int): ImageItem? {
        val n = native
        if (n != null) return n.restorePosition(savedIndex)
        if (images.isEmpty() || savedIndex < 0 || savedIndex >= images.size) return null
        index = savedIndex
        return images[index]
    }

    fun goToImageId(imageId: String): ImageItem? {
        val n = native
        if (n != null) return n.goToImageId(imageId)
        val idx = images.indexOfFirst { it.id == imageId }
        if (idx < 0) return null
        index = idx
        return images[index]
    }

    private fun currentChapterIndex(): Int {
        if (index < 0 || chapterStartIndices.isEmpty()) return 0
        var lo = 0
        var hi = chapterStartIndices.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (chapterStartIndices[mid] <= index) lo = mid else hi = mid - 1
        }
        return lo
    }
}
