/* permutation.c — C projection of PageBubbleOrdering.isValidPermutation.
 *
 * Haskell:
 *   isValidPermutation indices n =
 *     length indices == n && sort indices == [0 .. n - 1]
 *
 * Implementation choice: instead of sorting (O(n log n) + alloc), use a
 * bitset over [0, domain_n) to detect duplicates and out-of-range values
 * in a single pass.  No malloc — caller owns the input array.  We do
 * allocate a small fixed-size bitset on the stack, supporting up to
 * APP7_PERM_MAX_N indices.  Inputs exceeding that are conservatively
 * rejected (return 0).
 */
#include "app7_domain.h"
#include <string.h>   /* memset */

/* Cap chosen for app7's domain: PageBubbleOrdering operates over the
 * bubble count of a single page — well under 256 in practice.  If a
 * future feature exceeds this, bump this constant AND add a round_trip
 * case at the new boundary. */
#define APP7_PERM_MAX_N 256

int app7_is_valid_permutation(const int *indices, size_t n, int domain_n) {
    /* Guard rails.  Haskell's `length indices == n` is structural; in C
     * the caller asserts `n` matches the array length.  We add the
     * `n == domain_n` check from the Haskell algebra and reject
     * pathological domain_n values. */
    if (domain_n < 0)                       return 0;
    if ((size_t)domain_n != n)              return 0;
    if (n == 0)                             return 1;   /* empty IS valid for n=0 */
    if (n > APP7_PERM_MAX_N)                return 0;   /* exceeds bitset cap */
    if (indices == NULL)                    return 0;

    unsigned char seen[APP7_PERM_MAX_N];
    memset(seen, 0, n);

    for (size_t i = 0; i < n; ++i) {
        const int v = indices[i];
        if (v < 0 || v >= domain_n) return 0;   /* out of range */
        if (seen[v])                return 0;   /* duplicate */
        seen[v] = 1;
    }
    /* All values in [0, domain_n) seen exactly once → valid permutation. */
    return 1;
}
