//! Annotation pool + sampler — algebraic port of
//! domain-core/src/annotation_pool.c (C semantics are LAW, bit-exact).
//!
//! Cascade source of truth: domain-haskell/App7Domain.hs
//! (`defaultAnnotationPool`, §7).  Adding / reordering / renaming a pool
//! entry here MUST be mirrored in App7Domain.hs in the same commit.
//!
//! The RNG is xoshiro256** (Blackman & Vigna 2018), seeded from a single
//! u64 via splitmix64 (4 words).  Parity with Kotlin's Random is asserted
//! at the SEMANTIC level (k distinct indices in [0, pool_size),
//! deterministic per seed), not byte-for-byte — but parity with the C
//! projection IS byte-for-byte: sample(seed=42, k=3) == [1, 15, 16].
//!
//! Layering (lib.rs conventions):
//!   * ALGEBRAIC CORE — ADTs + total functions, no unsafe, no raw pointers.
//!   * `pub mod ffi`  — exact app7_domain.h §6 projection; all unsafe here.

use crate::error::DomainError;

// ═══════════════════════════════════════════════════════════════════════
//  ALGEBRAIC CORE
// ═══════════════════════════════════════════════════════════════════════

/// Number of entries in the default annotation pool.
/// Mirror of C `POOL_SIZE` and Haskell `defaultAnnotationPoolSize` (== 17).
pub const POOL_SIZE: usize = 17;

/// One (label, display) pair of the pool.  Strings are NUL-terminated
/// static program data (`b"understood\0"`) so the FFI layer can hand them
/// to C without allocating — DO NOT strip the terminator from the fields.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PoolEntry {
    /// Machine-readable label, NUL-terminated (e.g. `b"understood\0"`).
    pub label: &'static [u8],
    /// Human-readable display text, NUL-terminated (e.g. `b"Got it\0"`).
    pub display: &'static [u8],
}

impl PoolEntry {
    /// Label without the trailing NUL (for algebraic comparisons).
    #[inline]
    pub fn label_bytes(&self) -> &'static [u8] {
        &self.label[..self.label.len() - 1]
    }

    /// Display text without the trailing NUL.
    #[inline]
    pub fn display_bytes(&self) -> &'static [u8] {
        &self.display[..self.display.len() - 1]
    }
}

/// The pool data — lock-step with C `POOL_LABELS` / `POOL_DISPLAYS` and
/// Haskell `defaultAnnotationPool`, same order, byte for byte.
static POOL: [PoolEntry; POOL_SIZE] = [
    PoolEntry { label: b"understood\0",         display: b"Got it\0" },
    PoolEntry { label: b"partially\0",          display: b"Partial\0" },
    PoolEntry { label: b"not_understood\0",     display: b"Didn't get it\0" },
    PoolEntry { label: b"need_re_read\0",       display: b"Re-read\0" },
    PoolEntry { label: b"key_idiom\0",          display: b"Idiom\0" },
    PoolEntry { label: b"vocabulary\0",         display: b"Vocab\0" },
    PoolEntry { label: b"grammar\0",            display: b"Grammar\0" },
    PoolEntry { label: b"surprising\0",         display: b"Surprising\0" },
    PoolEntry { label: b"funny\0",              display: b"Funny\0" },
    PoolEntry { label: b"sad\0",                display: b"Sad\0" },
    PoolEntry { label: b"agree\0",              display: b"Agree\0" },
    PoolEntry { label: b"disagree\0",           display: b"Disagree\0" },
    PoolEntry { label: b"confused\0",           display: b"Confused\0" },
    PoolEntry { label: b"follow_up_question\0", display: b"Question\0" },
    PoolEntry { label: b"character_intro\0",    display: b"Character\0" },
    PoolEntry { label: b"plot_beat\0",          display: b"Plot beat\0" },
    PoolEntry { label: b"callback\0",           display: b"Callback\0" },
];

/// Total accessor: `Some(entry)` for `i < POOL_SIZE`, `None` past the end.
/// (Negative indexes cannot be expressed in `usize`; the FFI layer maps
/// C's `index < 0` into the `None` arm before converting.)
#[inline]
pub fn entry(i: usize) -> Option<PoolEntry> {
    POOL.get(i).copied()
}

// ── splitmix64 — spreads one u64 seed into the 4-word xoshiro state ─────
// Bit-exact with C: unsigned arithmetic wraps → wrapping_add/wrapping_mul.

struct SplitMix64 {
    state: u64,
}

impl SplitMix64 {
    #[inline]
    fn next(&mut self) -> u64 {
        self.state = self.state.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }
}

// ── xoshiro256** state + transition ─────────────────────────────────────
// Spec: https://prng.di.unimi.it/xoshiro256starstar.c (public domain).

struct Xoshiro256StarStar {
    s: [u64; 4],
}

impl Xoshiro256StarStar {
    /// C `xoshiro_seed`: 4 consecutive splitmix64 draws.  seed=0 is fine
    /// (splitmix64(0 + 0x9E37...) is non-zero, avoiding the all-zero state).
    fn from_seed(seed: u64) -> Self {
        let mut sm = SplitMix64 { state: seed };
        Xoshiro256StarStar {
            s: [sm.next(), sm.next(), sm.next(), sm.next()],
        }
    }

    /// C `xoshiro_next`, verbatim: rotl via `rotate_left`, `s[1] * 5` and
    /// `* 9` wrap, `s[1] << 17` wraps (bits shifted out drop, as in C).
    fn next(&mut self) -> u64 {
        let result = self.s[1].wrapping_mul(5).rotate_left(7).wrapping_mul(9);
        let t = self.s[1].wrapping_shl(17);
        self.s[2] ^= self.s[0];
        self.s[3] ^= self.s[1];
        self.s[1] ^= self.s[2];
        self.s[0] ^= self.s[3];
        self.s[2] ^= t;
        self.s[3] = self.s[3].rotate_left(45);
        result
    }
}

// ── Sampler ─────────────────────────────────────────────────────────────

/// Result of a sample: up to `POOL_SIZE` distinct indices, stack-resident
/// (the C sampler does no malloc; neither do we).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SampledIndices {
    indices: [usize; POOL_SIZE],
    len: usize,
}

impl SampledIndices {
    #[inline]
    pub fn as_slice(&self) -> &[usize] {
        &self.indices[..self.len]
    }

    #[inline]
    pub fn len(&self) -> usize {
        self.len
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.len == 0
    }
}

/// Fisher-Yates partial shuffle over `[0, POOL_SIZE)`, exactly as C
/// `app7_sample_annotation_indices`:
///
///   * `k <= 0`  → the algebra names it: `Err(InvalidSampleSize)`
///                 (the FFI projection collapses this to the C return 0);
///   * `k > 17`  → clamped to 17 (NOT an error, matching C);
///   * per step: `j = i + (next() % range)` with the modulo in u64, then
///     swap `scratch[i] <-> scratch[j]` and emit `scratch[i]` — the emit
///     happens AFTER the swap, per the C code.
///
/// Deterministic per seed; `sample_indices(42, 3)` yields `[1, 15, 16]`.
pub fn sample_indices(seed: u64, k: i32) -> Result<SampledIndices, DomainError> {
    if k <= 0 {
        return Err(DomainError::InvalidSampleSize);
    }
    let k = (k as usize).min(POOL_SIZE); // C: if (k > POOL_SIZE) k = POOL_SIZE;

    let mut scratch: [usize; POOL_SIZE] = [0; POOL_SIZE];
    for (i, slot) in scratch.iter_mut().enumerate() {
        *slot = i;
    }

    let mut rng = Xoshiro256StarStar::from_seed(seed);

    let mut out = SampledIndices {
        indices: [0; POOL_SIZE],
        len: k,
    };
    for i in 0..k {
        // Pick j uniformly in [i, POOL_SIZE).  Modulo bias is negligible
        // for a 17-element range from a 64-bit RNG (comment ports from C).
        let range = (POOL_SIZE - i) as u64;
        let j = i + (rng.next() % range) as usize;
        scratch.swap(i, j);
        out.indices[i] = scratch[i];
    }
    Ok(out)
}

// ═══════════════════════════════════════════════════════════════════════
//  FFI PROJECTION — app7_domain.h §6, byte-for-byte
// ═══════════════════════════════════════════════════════════════════════

pub mod ffi {
    use super::{entry, sample_indices, POOL_SIZE};
    use core::ffi::c_char;

    /// `int app7_annotation_pool_size(void);` — always 17.
    #[no_mangle]
    pub extern "C" fn app7_annotation_pool_size() -> i32 {
        POOL_SIZE as i32
    }

    /// `const char *app7_annotation_pool_label(int index);`
    /// NULL on out-of-bounds (index < 0 or >= 17), exactly as C.
    #[no_mangle]
    pub extern "C" fn app7_annotation_pool_label(index: i32) -> *const c_char {
        if index < 0 {
            return core::ptr::null();
        }
        match entry(index as usize) {
            Some(e) => e.label.as_ptr() as *const c_char,
            None => core::ptr::null(),
        }
    }

    /// `const char *app7_annotation_pool_display(int index);`
    /// NULL on out-of-bounds, exactly as C.
    #[no_mangle]
    pub extern "C" fn app7_annotation_pool_display(index: i32) -> *const c_char {
        if index < 0 {
            return core::ptr::null();
        }
        match entry(index as usize) {
            Some(e) => e.display.as_ptr() as *const c_char,
            None => core::ptr::null(),
        }
    }

    /// `int app7_sample_annotation_indices(uint64_t seed, int k, int *out_indices);`
    /// Returns the count written: min(k, 17), or 0 if k <= 0 or NULL out.
    #[no_mangle]
    pub extern "C" fn app7_sample_annotation_indices(
        seed: u64,
        k: i32,
        out_indices: *mut i32,
    ) -> i32 {
        // C: if (k <= 0 || out_indices == NULL) return 0;
        if k <= 0 || out_indices.is_null() {
            return 0;
        }
        match sample_indices(seed, k) {
            Ok(sample) => {
                let slice = sample.as_slice();
                for (i, &idx) in slice.iter().enumerate() {
                    // SAFETY: out_indices is non-NULL and the header
                    // contract requires room for min(k, pool_size) ints;
                    // slice.len() == min(k, 17) <= that bound.
                    unsafe {
                        *out_indices.add(i) = idx as i32;
                    }
                }
                slice.len() as i32
            }
            // Unreachable with k > 0, but keep the projection total:
            // any algebraic failure is the C "wrote nothing" return.
            Err(_) => 0,
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  TESTS — ported from domain-core/test/round_trip.c ("[AnnotationPool +
//  sampler (Phase 2b)]" block) and domain-haskell/Test.hs
//  ("[DefaultAnnotationPool ...]" block).  C oracle values verbatim.
// ═══════════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::ffi::{
        app7_annotation_pool_display, app7_annotation_pool_label,
        app7_annotation_pool_size, app7_sample_annotation_indices,
    };
    use super::*;
    use core::ffi::{c_char, CStr};

    fn cstr_bytes(p: *const c_char) -> &'static [u8] {
        assert!(!p.is_null(), "expected non-NULL string");
        unsafe { CStr::from_ptr(p) }.to_bytes()
    }

    // ── round_trip.c: pool data ─────────────────────────────────────────

    #[test]
    fn pool_size_is_17() {
        assert_eq!(app7_annotation_pool_size(), 17);
    }

    #[test]
    fn pool_first_and_last_entries() {
        // pool[0].label = understood / pool[0].display = Got it
        assert_eq!(cstr_bytes(app7_annotation_pool_label(0)), b"understood");
        assert_eq!(cstr_bytes(app7_annotation_pool_display(0)), b"Got it");
        // pool[16].label = callback / pool[16].display = Callback
        assert_eq!(cstr_bytes(app7_annotation_pool_label(16)), b"callback");
        assert_eq!(cstr_bytes(app7_annotation_pool_display(16)), b"Callback");
    }

    #[test]
    fn pool_out_of_bounds_is_null() {
        // pool[-1] is NULL / pool[17] is NULL
        assert!(app7_annotation_pool_label(-1).is_null());
        assert!(app7_annotation_pool_label(17).is_null());
        assert!(app7_annotation_pool_display(-1).is_null());
        assert!(app7_annotation_pool_display(17).is_null());
    }

    // ── round_trip.c: sampler ───────────────────────────────────────────

    #[test]
    fn sample_42_3_through_ffi() {
        let mut out_a = [0i32; 3];
        let mut out_b = [0i32; 3];
        let mut out_c = [0i32; 3];
        let n_a = app7_sample_annotation_indices(42u64, 3, out_a.as_mut_ptr());
        let _ = app7_sample_annotation_indices(42u64, 3, out_b.as_mut_ptr());
        let _ = app7_sample_annotation_indices(43u64, 3, out_c.as_mut_ptr());

        // sample(42,3) returns 3
        assert_eq!(n_a, 3);
        // THE ORACLE — bit-exact with the C sampler:
        assert_eq!(out_a, [1, 15, 16], "sample(seed=42, k=3) must be [1, 15, 16]");
        // sample is deterministic for same seed
        assert_eq!(out_a, out_b);
        // sample differs across seeds (42 vs 43)
        assert_ne!(out_a, out_c);
        // sample indices all in [0, 17)
        assert!(out_a.iter().all(|&i| (0..17).contains(&i)));
        // sample indices all distinct
        assert!(out_a[0] != out_a[1] && out_a[1] != out_a[2] && out_a[0] != out_a[2]);
    }

    #[test]
    fn sample_k_over_pool_size_caps_at_17_full_permutation() {
        // k > pool_size is capped at pool_size; result is a full permutation.
        let mut out = [0i32; 20];
        let n = app7_sample_annotation_indices(7u64, 20, out.as_mut_ptr());
        assert_eq!(n, 17, "sample(seed, 20) caps at 17");
        let mut seen = [false; 17];
        for &v in &out[..n as usize] {
            assert!((0..17).contains(&v), "all in range");
            assert!(!seen[v as usize], "no duplicates");
            seen[v as usize] = true;
        }
    }

    #[test]
    fn sample_nonpositive_k_returns_0() {
        let mut out = [0i32; 5];
        assert_eq!(app7_sample_annotation_indices(99u64, 0, out.as_mut_ptr()), 0);
        assert_eq!(app7_sample_annotation_indices(99u64, -1, out.as_mut_ptr()), 0);
    }

    #[test]
    fn sample_null_out_returns_0() {
        assert_eq!(
            app7_sample_annotation_indices(42u64, 3, core::ptr::null_mut()),
            0
        );
    }

    // ── Test.hs: [DefaultAnnotationPool] block (against the algebra) ────

    #[test]
    fn hs_pool_size_and_boundary_entries() {
        // pool size = 17
        assert_eq!(POOL_SIZE, 17);
        // first entry = (understood, Got it)
        let first = entry(0).unwrap();
        assert_eq!(first.label_bytes(), b"understood");
        assert_eq!(first.display_bytes(), b"Got it");
        // last entry = (callback, Callback)
        let last = entry(POOL_SIZE - 1).unwrap();
        assert_eq!(last.label_bytes(), b"callback");
        assert_eq!(last.display_bytes(), b"Callback");
        // past the end is None
        assert_eq!(entry(POOL_SIZE), None);
    }

    #[test]
    fn hs_all_labels_and_displays_distinct() {
        for i in 0..POOL_SIZE {
            for j in (i + 1)..POOL_SIZE {
                let (a, b) = (entry(i).unwrap(), entry(j).unwrap());
                assert_ne!(a.label_bytes(), b.label_bytes(), "labels distinct");
                assert_ne!(a.display_bytes(), b.display_bytes(), "displays distinct");
            }
        }
    }

    // ── Algebraic-core seams (Rust-only, no C counterpart) ──────────────

    #[test]
    fn algebra_rejects_nonpositive_k_with_invalid_sample_size() {
        assert_eq!(sample_indices(99, 0), Err(DomainError::InvalidSampleSize));
        assert_eq!(sample_indices(99, -1), Err(DomainError::InvalidSampleSize));
    }

    #[test]
    fn algebra_matches_oracle() {
        let s = sample_indices(42, 3).unwrap();
        assert_eq!(s.as_slice(), &[1, 15, 16]);
    }
}
