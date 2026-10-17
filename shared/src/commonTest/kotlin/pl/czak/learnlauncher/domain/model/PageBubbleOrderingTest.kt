package pl.czak.learnlauncher.domain.model

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class PageBubbleOrderingTest {

    @Test
    fun emptyIsValidWhenNIsZero() {
        assertTrue(PageBubbleOrdering.isValidPermutation(emptyList(), 0))
    }

    @Test
    fun emptyIsInvalidWhenNIsPositive() {
        assertFalse(PageBubbleOrdering.isValidPermutation(emptyList(), 3))
    }

    @Test
    fun identityIsValid() {
        assertTrue(PageBubbleOrdering.isValidPermutation(listOf(0, 1, 2, 3), 4))
    }

    @Test
    fun reversedIsValid() {
        assertTrue(PageBubbleOrdering.isValidPermutation(listOf(3, 2, 1, 0), 4))
    }

    @Test
    fun arbitraryPermutationIsValid() {
        assertTrue(PageBubbleOrdering.isValidPermutation(listOf(2, 0, 3, 1), 4))
    }

    @Test
    fun duplicateIsInvalid() {
        assertFalse(PageBubbleOrdering.isValidPermutation(listOf(0, 1, 1, 2), 4))
    }

    @Test
    fun missingIndexIsInvalid() {
        assertFalse(PageBubbleOrdering.isValidPermutation(listOf(0, 1, 2), 4))
    }

    @Test
    fun outOfRangeIsInvalid() {
        assertFalse(PageBubbleOrdering.isValidPermutation(listOf(0, 1, 2, 4), 4))
    }

    @Test
    fun extraIndicesAreInvalid() {
        assertFalse(PageBubbleOrdering.isValidPermutation(listOf(0, 1, 2, 3, 4), 4))
    }
}
