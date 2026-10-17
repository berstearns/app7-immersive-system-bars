// ═══════════════════════════════════════════════════════════════════════
//  permutation — PageBubbleOrdering.isValidPermutation.
//
//  Haskell (App7Domain.hs):
//      isValidPermutation indices n =
//        length indices == n && sort indices == [0 .. n - 1]
//
//  C projection (domain-core/src/permutation.c) is the parity LAW:
//  instead of sorting it does a single O(n) pass over a stack-resident
//  seen-buffer capped at APP7_PERM_MAX_N = 256.  Inputs with
//  n > 256 are conservatively REJECTED (return false) — that cap is an
//  observable semantic of the shipped binary and is replicated here.
//
//  NOTE on app7_box_contains (header §4): the C exports it from
//  region.c, NOT permutation.c — its Rust port belongs to region.rs
//  and is deliberately absent from this module.
// ═══════════════════════════════════════════════════════════════════════

/// Cap chosen for app7's domain: PageBubbleOrdering operates over the
/// bubble count of a single page — well under 256 in practice.  If a
/// future feature exceeds this, bump this constant AND add a round_trip
/// case at the new boundary.  (Mirror of APP7_PERM_MAX_N in permutation.c.)
pub const PERM_MAX_N: usize = 256;

// ── ALGEBRAIC CORE ──────────────────────────────────────────────────────

/// True iff `indices` is a permutation of `{0, .., domain_n - 1}` AND
/// `indices.len() == domain_n`.
///
/// Total function; guard order mirrors the C exactly:
///   1. `domain_n < 0`                 → false
///   2. `domain_n as usize != len`     → false  (Haskell's length check)
///   3. `len == 0`                     → true   (empty IS valid for n = 0)
///   4. `len > PERM_MAX_N`             → false  (seen-buffer cap)
///   5. any value out of `[0, domain_n)` or duplicated → false
pub fn is_valid_permutation(indices: &[i32], domain_n: i32) -> bool {
    if domain_n < 0 {
        return false;
    }
    let n = indices.len();
    // Safe cast: domain_n >= 0 here, so it fits in usize on all targets.
    if domain_n as usize != n {
        return false;
    }
    if n == 0 {
        return true; // empty IS valid for n = 0
    }
    if n > PERM_MAX_N {
        return false; // exceeds seen-buffer cap
    }

    let mut seen = [false; PERM_MAX_N];
    for &v in indices {
        if v < 0 || v >= domain_n {
            return false; // out of range
        }
        let vi = v as usize; // v in [0, domain_n) == [0, n) <= PERM_MAX_N
        if seen[vi] {
            return false; // duplicate
        }
        seen[vi] = true;
    }
    // All values in [0, domain_n) seen exactly once → valid permutation.
    true
}

// ── FFI PROJECTION (app7_domain.h §5) ───────────────────────────────────

pub mod ffi {
    use super::{is_valid_permutation, PERM_MAX_N};

    /// int app7_is_valid_permutation(const int *indices, size_t n, int domain_n);
    ///
    /// Returns 1 / 0 (FFI boolean).  Guard ORDER matches the C source:
    /// the NULL check comes AFTER the n == 0 early-accept, so
    /// (NULL, 0, 0) → 1 exactly as in C.
    #[no_mangle]
    pub extern "C" fn app7_is_valid_permutation(
        indices: *const i32,
        n: usize,
        domain_n: i32,
    ) -> i32 {
        // Replicate the C guard sequence verbatim so NULL handling and
        // early returns are bit-exact.
        if domain_n < 0 {
            return 0;
        }
        if domain_n as usize != n {
            return 0;
        }
        if n == 0 {
            return 1; // empty IS valid for n = 0 (even with indices == NULL)
        }
        if n > PERM_MAX_N {
            return 0; // exceeds seen-buffer cap
        }
        if indices.is_null() {
            return 0;
        }

        // SAFETY: indices is non-NULL and (per the ABI contract in
        // app7_domain.h §5) points to at least `n` caller-owned ints,
        // read-only for the duration of the call.
        let slice = unsafe { core::slice::from_raw_parts(indices, n) };
        is_valid_permutation(slice, domain_n) as i32
    }
}

// ── TESTS — ported verbatim from round_trip.c + Test.hs ─────────────────

#[cfg(test)]
mod tests {
    use super::ffi::app7_is_valid_permutation;
    use super::is_valid_permutation;

    // round_trip.c "[PageBubbleOrdering — isValidPermutation]" block,
    // oracle values preserved verbatim.
    #[test]
    fn round_trip_c_oracle() {
        let p_012 = [0, 1, 2];
        let p_201 = [2, 0, 1];
        let p_01 = [0, 1];
        let p_013 = [0, 1, 3];
        let p_001 = [0, 0, 1];
        let p_0123 = [0, 1, 2, 3];

        // "[0,1,2] / 3 valid"
        assert_eq!(app7_is_valid_permutation(p_012.as_ptr(), 3, 3), 1);
        // "[2,0,1] / 3 valid (shuffled)"
        assert_eq!(app7_is_valid_permutation(p_201.as_ptr(), 3, 3), 1);
        // "[] / 0 valid (empty)" — NULL pointer, exactly as the C test
        assert_eq!(
            app7_is_valid_permutation(core::ptr::null(), 0, 0),
            1
        );
        // "[0,1] / 3 invalid (count mismatch)"
        assert_eq!(app7_is_valid_permutation(p_01.as_ptr(), 2, 3), 0);
        // "[0,1,3] / 3 invalid (gap at 2)"
        assert_eq!(app7_is_valid_permutation(p_013.as_ptr(), 3, 3), 0);
        // "[0,0,1] / 3 invalid (duplicate)"
        assert_eq!(app7_is_valid_permutation(p_001.as_ptr(), 3, 3), 0);
        // "[0,1,2,3] / 3 invalid (overflow)"
        assert_eq!(app7_is_valid_permutation(p_0123.as_ptr(), 4, 3), 0);
    }

    // Test.hs "[PageBubbleOrdering — isValidPermutation]" block against
    // the algebraic core directly.
    #[test]
    fn haskell_oracle() {
        // "[0,1,2] / 3 valid"
        assert!(is_valid_permutation(&[0, 1, 2], 3));
        // "[2,0,1] / 3 valid (shuffled)"
        assert!(is_valid_permutation(&[2, 0, 1], 3));
        // "[] / 0 valid (empty)"
        assert!(is_valid_permutation(&[], 0));
        // "[0,1] / 3 invalid (count mismatch)"
        assert!(!is_valid_permutation(&[0, 1], 3));
        // "[0,1,3] / 3 invalid (gap at 2)"
        assert!(!is_valid_permutation(&[0, 1, 3], 3));
        // "[0,0,1] / 3 invalid (duplicate)"
        assert!(!is_valid_permutation(&[0, 0, 1], 3));
        // "[0,1,2,3] / 3 invalid (overflow)"
        assert!(!is_valid_permutation(&[0, 1, 2, 3], 3));
    }

    // C edge-case semantics not covered by the shared oracles.
    #[test]
    fn c_edge_cases() {
        // Negative domain_n rejected before anything else.
        assert!(!is_valid_permutation(&[], -1));
        assert_eq!(app7_is_valid_permutation(core::ptr::null(), 0, -1), 0);
        // NULL with n > 0 (and n == domain_n) → 0, NULL check after n==0.
        assert_eq!(app7_is_valid_permutation(core::ptr::null(), 3, 3), 0);
        // n == 0 but domain_n != 0 → count-mismatch rejection.
        assert!(!is_valid_permutation(&[], 3));
        // Seen-buffer cap: exactly PERM_MAX_N is accepted, one past is not.
        let at_cap: Vec<i32> = (0..256).collect();
        assert!(is_valid_permutation(&at_cap, 256));
        let past_cap: Vec<i32> = (0..257).collect();
        assert!(!is_valid_permutation(&past_cap, 257));
    }
}
