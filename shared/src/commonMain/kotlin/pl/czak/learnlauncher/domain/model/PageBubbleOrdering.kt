package pl.czak.learnlauncher.domain.model

data class PageBubbleOrdering(
    val userId: String,
    val comicId: String,
    val imageId: String,
    val originalIndices: List<Int>,
    val updatedAt: Long,
) {
    companion object {
        /**
         * Routes through `App7Native.isValidPermutation` on JVM
         * (Android + Desktop) so every permutation about to be
         * persisted has passed the binary's check (rule: *never store
         * long-term anything unverified*).  iOS stub throws
         * `NotImplementedError` → falls back to pure-Kotlin set check
         * (out-of-scope platform).  Other throwables propagate; a
         * broken `.so` crashes the call site rather than silently
         * accepting an unverified permutation.
         */
        fun isValidPermutation(indices: List<Int>, n: Int): Boolean =
            try {
                pl.czak.learnlauncher.domain.native.App7Native
                    .isValidPermutation(indices.toIntArray(), n)
            } catch (_: NotImplementedError) {
                indices.size == n && indices.toSet() == (0 until n).toSet()
            }
    }
}
