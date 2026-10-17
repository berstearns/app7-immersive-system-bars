// ═══════════════════════════════════════════════════════════════════════
//  navigator — ImageSequenceNavigator (Phase 2b-C), algebraic core + FFI.
//
//  Replaces domain-core/src/navigator.c behind the UNCHANGED §8 ABI of
//  app7_domain.h.  Kotlin source of truth:
//    shared/src/commonMain/.../domain/navigation/ImageSequenceNavigator.kt
//
//  State: total size, current index (-1 = before any page selected),
//  copied chapter-start indices (sorted ascending per contract; the C —
//  and therefore this port — never validates that, and go_to_chapter /
//  next_chapter will happily install a contract-violating start value as
//  the position).  ImageItem data + chapter NAMES stay in Kotlin.
//
//  PARITY NOTES (C code is LAW — each preserved bit-exactly):
//  * `pos` stays a raw i32, NOT Option<usize>: with contract-violating
//    chapter_starts (e.g. negative values) the C's go_to_chapter sets and
//    RETURNS that raw value (`n->index = starts[i]; return n->index;`),
//    which an Option projection could not reproduce (-5 would collapse to
//    -1, and a later `next` from -5 would land on -4, not 0).
//  * `previous` from pos == -1 is REJECTED (C: `index > 0`), it does not
//    clamp to 0.  `next` from -1 lands on 0.
//  * current_chapter_index returns 0 (not -1) for pos == -1, for
//    chapter_count == 0, and for a NULL handle.  Binary search is the
//    exact C loop: largest i with starts[i] <= pos; mid = (lo+hi+1)/2.
//  * next_chapter from pos == -1 therefore jumps to starts[1] (chapter 0
//    is considered "current" before any page) — quirk preserved.
//  * go_to_chapter checks ONLY the chapter index range — NO size check
//    (works even when size == 0, unlike restore_position).
//  * can_go_back checks ONLY `pos > 0` — NO size check.
//  * initialize clamps negative total_size / chapter_count to 0 and
//    always resets pos to -1.  chapter_count is adopted even when the
//    starts pointer is NULL (C keeps the count but leaves the buffer
//    stale/indeterminate — see ffi note for the deterministic stand-in).
// ═══════════════════════════════════════════════════════════════════════

use alloc::vec::Vec;

use crate::error::DomainError;

// ─────────────────────────────────────────────────────────────────────────
//  ALGEBRAIC CORE — no unsafe, no raw pointers, no C types.
// ─────────────────────────────────────────────────────────────────────────

/// Navigator over an image sequence with optional chapter starts.
///
/// Invariants maintained by the API (matching the C struct's reachable
/// states): `size >= 0`; `chapter_starts.len() <= i32::MAX as usize`;
/// `pos` is -1 after (re)initialization, otherwise whatever the last
/// accepted move installed (a value in `0..size` for next/previous/
/// restore_position, or a raw chapter-start value for the chapter moves).
pub struct Navigator {
    size: i32,
    /// Current index; -1 = before first page.
    pos: i32,
    chapter_starts: Vec<i32>,
}

impl Navigator {
    /// Mirror of `app7_nav_new`: empty sequence, before-first position.
    pub const fn new() -> Self {
        Navigator {
            size: 0,
            pos: -1,
            chapter_starts: Vec::new(),
        }
    }

    /// Mirror of `app7_nav_initialize` with a real starts array (or an
    /// empty one for the "no chapter index" case).  Negative sizes clamp
    /// to 0 in the FFI layer before reaching here; clamped again for
    /// core-level totality.
    pub fn initialize(&mut self, total_size: i32, chapter_starts: &[i32]) {
        self.size = if total_size < 0 { 0 } else { total_size };
        self.pos = -1;
        self.chapter_starts.clear();
        self.chapter_starts.extend_from_slice(chapter_starts);
    }

    /// Degenerate C path: NULL starts pointer with positive count.  The C
    /// adopts the count but performs no copy, leaving the buffer's prior
    /// prefix in place and any grown tail indeterminate.  Deterministic
    /// stand-in: keep the prior prefix, zero-fill the tail.
    pub fn initialize_count_only(&mut self, total_size: i32, chapter_count: usize) {
        self.size = if total_size < 0 { 0 } else { total_size };
        self.pos = -1;
        self.chapter_starts.resize(chapter_count, 0);
    }

    #[inline]
    pub fn size(&self) -> i32 {
        self.size
    }

    /// Raw position; -1 = before first page.
    #[inline]
    pub fn position(&self) -> i32 {
        self.pos
    }

    #[inline]
    pub fn chapter_count(&self) -> i32 {
        self.chapter_starts.len() as i32
    }

    /// Largest chapter index whose start is <= pos; 0 when there are no
    /// chapters or pos < 0 (exact C binary search, including its answer
    /// of 0 when pos precedes starts[0]).
    pub fn current_chapter_index(&self) -> i32 {
        if self.chapter_starts.is_empty() || self.pos < 0 {
            return 0;
        }
        let mut lo: usize = 0;
        let mut hi: usize = self.chapter_starts.len() - 1;
        while lo < hi {
            let mid = (lo + hi + 1) / 2;
            if self.chapter_starts[mid] <= self.pos {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        lo as i32
    }

    /// Advance one page.  From -1 lands on 0.
    pub fn next(&mut self) -> Result<i32, DomainError> {
        if self.size == 0 {
            return Err(DomainError::EmptyImageSequence);
        }
        if self.pos < self.size - 1 {
            self.pos += 1;
            Ok(self.pos)
        } else {
            Err(DomainError::IndexOutOfRange)
        }
    }

    /// Step back one page.  Rejected unless pos > 0 (so from -1: rejected).
    pub fn previous(&mut self) -> Result<i32, DomainError> {
        if self.size == 0 {
            return Err(DomainError::EmptyImageSequence);
        }
        if self.pos > 0 {
            self.pos -= 1;
            Ok(self.pos)
        } else {
            Err(DomainError::IndexOutOfRange)
        }
    }

    /// Jump to the start of the chapter after the current one.
    pub fn next_chapter(&mut self) -> Result<i32, DomainError> {
        if self.size == 0 {
            return Err(DomainError::EmptyImageSequence);
        }
        if self.chapter_starts.is_empty() {
            return Err(DomainError::ChapterOutOfRange);
        }
        let ci = self.current_chapter_index() as usize;
        if ci < self.chapter_starts.len() - 1 {
            self.pos = self.chapter_starts[ci + 1];
            Ok(self.pos)
        } else {
            Err(DomainError::ChapterOutOfRange)
        }
    }

    /// Jump to the start of the chapter before the current one.
    pub fn previous_chapter(&mut self) -> Result<i32, DomainError> {
        if self.size == 0 {
            return Err(DomainError::EmptyImageSequence);
        }
        if self.chapter_starts.is_empty() {
            return Err(DomainError::ChapterOutOfRange);
        }
        let ci = self.current_chapter_index() as usize;
        if ci > 0 {
            self.pos = self.chapter_starts[ci - 1];
            Ok(self.pos)
        } else {
            Err(DomainError::ChapterOutOfRange)
        }
    }

    /// Jump to a chapter by index.  NOTE: like the C, only the chapter
    /// index is range-checked — there is deliberately NO size check.
    pub fn go_to_chapter(&mut self, chapter_idx: i32) -> Result<i32, DomainError> {
        if chapter_idx < 0 || chapter_idx as usize >= self.chapter_starts.len() {
            return Err(DomainError::ChapterOutOfRange);
        }
        self.pos = self.chapter_starts[chapter_idx as usize];
        Ok(self.pos)
    }

    /// Restore a previously saved position; must lie in `0..size`.
    pub fn restore_position(&mut self, saved_idx: i32) -> Result<i32, DomainError> {
        if self.size == 0 {
            return Err(DomainError::EmptyImageSequence);
        }
        if saved_idx < 0 || saved_idx >= self.size {
            return Err(DomainError::IndexOutOfRange);
        }
        self.pos = saved_idx;
        Ok(self.pos)
    }

    #[inline]
    pub fn can_go_forward(&self) -> bool {
        self.size != 0 && self.pos < self.size - 1
    }

    /// NOTE: like the C, no size check — pure `pos > 0`.
    #[inline]
    pub fn can_go_back(&self) -> bool {
        self.pos > 0
    }

    pub fn can_go_next_chapter(&self) -> bool {
        if self.chapter_starts.is_empty() {
            return false;
        }
        self.current_chapter_index() < self.chapter_count() - 1
    }

    pub fn can_go_previous_chapter(&self) -> bool {
        if self.chapter_starts.is_empty() {
            return false;
        }
        self.current_chapter_index() > 0
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  FFI PROJECTION — exact app7_domain.h §8 ABI.  All unsafe lives here.
// ─────────────────────────────────────────────────────────────────────────

pub mod ffi {
    use super::Navigator;
    use alloc::boxed::Box;

    /// Opaque handle type behind `App7Navigator *`.
    pub type App7Navigator = Navigator;

    /// `App7Navigator *app7_nav_new(void);`
    /// C returns NULL on malloc failure; under this crate's allocator an
    /// allocation failure aborts instead (see lib.rs runtime glue) — the
    /// NULL branch is unreachable, which only ever strengthens the
    /// "never observe a half-built handle" guarantee.
    #[no_mangle]
    pub extern "C" fn app7_nav_new() -> *mut App7Navigator {
        Box::into_raw(Box::new(Navigator::new()))
    }

    /// `void app7_nav_free(App7Navigator *n);` — NULL is a no-op.
    #[no_mangle]
    pub unsafe extern "C" fn app7_nav_free(n: *mut App7Navigator) {
        if n.is_null() {
            return;
        }
        drop(Box::from_raw(n));
    }

    /// `void app7_nav_initialize(App7Navigator *n, int total_size,
    ///                           const int *chapter_starts, int chapter_count);`
    #[no_mangle]
    pub unsafe extern "C" fn app7_nav_initialize(
        n: *mut App7Navigator,
        total_size: i32,
        chapter_starts: *const i32,
        chapter_count: i32,
    ) {
        if n.is_null() {
            return;
        }
        let nav = &mut *n;
        let size = if total_size < 0 { 0 } else { total_size };
        let count = if chapter_count < 0 { 0 } else { chapter_count };
        if count > 0 && !chapter_starts.is_null() {
            let starts = core::slice::from_raw_parts(chapter_starts, count as usize);
            nav.initialize(size, starts);
        } else if count > 0 {
            // C quirk: NULL pointer with positive count skips the memcpy
            // but still adopts the count (stale prefix / indeterminate
            // tail in the C buffer).  Deterministic projection: prior
            // prefix kept, tail zero-filled.
            nav.initialize_count_only(size, count as usize);
        } else {
            nav.initialize(size, &[]);
        }
    }

    /// `int app7_nav_size(App7Navigator *n);` — NULL → 0.
    #[no_mangle]
    pub unsafe extern "C" fn app7_nav_size(n: *mut App7Navigator) -> i32 {
        if n.is_null() {
            return 0;
        }
        (*n).size()
    }

    /// `int app7_nav_position(App7Navigator *n);` — NULL → -1.
    #[no_mangle]
    pub unsafe extern "C" fn app7_nav_position(n: *mut App7Navigator) -> i32 {
        if n.is_null() {
            return -1;
        }
        (*n).position()
    }

    /// `int app7_nav_chapter_count(App7Navigator *n);` — NULL → 0.
    #[no_mangle]
    pub unsafe extern "C" fn app7_nav_chapter_count(n: *mut App7Navigator) -> i32 {
        if n.is_null() {
            return 0;
        }
        (*n).chapter_count()
    }

    /// `int app7_nav_current_chapter_index(App7Navigator *n);` — NULL → 0.
    #[no_mangle]
    pub unsafe extern "C" fn app7_nav_current_chapter_index(n: *mut App7Navigator) -> i32 {
        if n.is_null() {
            return 0;
        }
        (*n).current_chapter_index()
    }

    /// Rejection projection: resulting index >= 0, or -1.
    #[inline]
    fn index_or_reject(r: Result<i32, crate::error::DomainError>) -> i32 {
        match r {
            Ok(i) => i,
            Err(_) => -1,
        }
    }

    /// `int app7_nav_next(App7Navigator *n);`
    #[no_mangle]
    pub unsafe extern "C" fn app7_nav_next(n: *mut App7Navigator) -> i32 {
        if n.is_null() {
            return -1;
        }
        index_or_reject((*n).next())
    }

    /// `int app7_nav_previous(App7Navigator *n);`
    #[no_mangle]
    pub unsafe extern "C" fn app7_nav_previous(n: *mut App7Navigator) -> i32 {
        if n.is_null() {
            return -1;
        }
        index_or_reject((*n).previous())
    }

    /// `int app7_nav_next_chapter(App7Navigator *n);`
    #[no_mangle]
    pub unsafe extern "C" fn app7_nav_next_chapter(n: *mut App7Navigator) -> i32 {
        if n.is_null() {
            return -1;
        }
        index_or_reject((*n).next_chapter())
    }

    /// `int app7_nav_previous_chapter(App7Navigator *n);`
    #[no_mangle]
    pub unsafe extern "C" fn app7_nav_previous_chapter(n: *mut App7Navigator) -> i32 {
        if n.is_null() {
            return -1;
        }
        index_or_reject((*n).previous_chapter())
    }

    /// `int app7_nav_go_to_chapter(App7Navigator *n, int chapter_idx);`
    #[no_mangle]
    pub unsafe extern "C" fn app7_nav_go_to_chapter(
        n: *mut App7Navigator,
        chapter_idx: i32,
    ) -> i32 {
        if n.is_null() {
            return -1;
        }
        index_or_reject((*n).go_to_chapter(chapter_idx))
    }

    /// `int app7_nav_restore_position(App7Navigator *n, int saved_idx);`
    #[no_mangle]
    pub unsafe extern "C" fn app7_nav_restore_position(
        n: *mut App7Navigator,
        saved_idx: i32,
    ) -> i32 {
        if n.is_null() {
            return -1;
        }
        index_or_reject((*n).restore_position(saved_idx))
    }

    /// `int app7_nav_can_go_forward(App7Navigator *n);` — 0 or 1.
    #[no_mangle]
    pub unsafe extern "C" fn app7_nav_can_go_forward(n: *mut App7Navigator) -> i32 {
        if n.is_null() {
            return 0;
        }
        (*n).can_go_forward() as i32
    }

    /// `int app7_nav_can_go_back(App7Navigator *n);` — 0 or 1.
    #[no_mangle]
    pub unsafe extern "C" fn app7_nav_can_go_back(n: *mut App7Navigator) -> i32 {
        if n.is_null() {
            return 0;
        }
        (*n).can_go_back() as i32
    }

    /// `int app7_nav_can_go_next_chapter(App7Navigator *n);` — 0 or 1.
    #[no_mangle]
    pub unsafe extern "C" fn app7_nav_can_go_next_chapter(n: *mut App7Navigator) -> i32 {
        if n.is_null() {
            return 0;
        }
        (*n).can_go_next_chapter() as i32
    }

    /// `int app7_nav_can_go_previous_chapter(App7Navigator *n);` — 0 or 1.
    #[no_mangle]
    pub unsafe extern "C" fn app7_nav_can_go_previous_chapter(n: *mut App7Navigator) -> i32 {
        if n.is_null() {
            return 0;
        }
        (*n).can_go_previous_chapter() as i32
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  TESTS — ports of domain-core/test/round_trip.c (navigator block,
//  lines 297-367; oracle values verbatim) and the navigator-relevant
//  DomainError assertions of domain-haskell/Test.hs.
// ─────────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::ffi::*;
    use super::*;
    use core::ptr;

    /// round_trip.c "[ImageSequenceNavigator (Phase 2b-C)]" — every
    /// assertion, same order, same oracle values.
    #[test]
    fn round_trip_c_navigator_block() {
        unsafe {
            let nv = app7_nav_new();
            // "nav_new returns non-NULL"
            assert!(!nv.is_null());

            // 10 images split into 3 chapters: [0..2], [3..5], [6..9]
            let starts = [0i32, 3, 6];
            app7_nav_initialize(nv, 10, starts.as_ptr(), 3);
            assert_eq!(app7_nav_size(nv), 10); // "size = 10"
            assert_eq!(app7_nav_chapter_count(nv), 3); // "chapter_count = 3"
            assert_eq!(app7_nav_position(nv), -1); // "position = -1 initially"
            assert_eq!(app7_nav_can_go_forward(nv), 1); // "can go forward initially"
            assert_eq!(app7_nav_can_go_back(nv), 0); // "cannot go back initially"

            let mut i = app7_nav_next(nv);
            assert_eq!(i, 0); // "next from -1 -> 0"
            i = app7_nav_next(nv);
            assert_eq!(i, 1); // "next -> 1"
            // "current_chapter_index at 1 = 0"
            assert_eq!(app7_nav_current_chapter_index(nv), 0);

            i = app7_nav_next_chapter(nv);
            assert_eq!(i, 3); // "next_chapter from ch0 -> idx 3 (start of ch1)"
            assert_eq!(app7_nav_current_chapter_index(nv), 1); // "current_chapter_index = 1"

            i = app7_nav_next_chapter(nv);
            assert_eq!(i, 6); // "next_chapter -> idx 6 (start of ch2)"
            i = app7_nav_next_chapter(nv);
            assert_eq!(i, -1); // "next_chapter at last chapter returns -1"

            i = app7_nav_previous_chapter(nv);
            assert_eq!(i, 3); // "previous_chapter -> idx 3"

            i = app7_nav_go_to_chapter(nv, 0);
            assert_eq!(i, 0); // "go_to_chapter(0) -> idx 0"
            i = app7_nav_go_to_chapter(nv, 99);
            assert_eq!(i, -1); // "go_to_chapter(99) returns -1 (OOB)"

            i = app7_nav_restore_position(nv, 5);
            assert_eq!(i, 5); // "restore_position(5) -> 5"
            i = app7_nav_restore_position(nv, -1);
            assert_eq!(i, -1); // "restore_position(-1) returns -1"
            i = app7_nav_restore_position(nv, 100);
            assert_eq!(i, -1); // "restore_position(100) returns -1"

            // After restore to 5, we're in chapter 1 (idx range 3..5).
            app7_nav_restore_position(nv, 5);
            // "at idx 5: current_chapter_index = 1"
            assert_eq!(app7_nav_current_chapter_index(nv), 1);
            // "at idx 5: can_go_forward = 1"
            assert_eq!(app7_nav_can_go_forward(nv), 1);
            // "at idx 5: can_go_next_chapter = 1"
            assert_eq!(app7_nav_can_go_next_chapter(nv), 1);

            app7_nav_restore_position(nv, 9);
            assert_eq!(app7_nav_can_go_forward(nv), 0); // "at idx 9: can_go_forward = 0"
            assert_eq!(app7_nav_can_go_back(nv), 1); // "at idx 9: can_go_back = 1"
            // "at idx 9: can_go_next_chapter = 0"
            assert_eq!(app7_nav_can_go_next_chapter(nv), 0);

            // Re-initialize with different chapter layout — should reset cleanly.
            let starts2 = [0i32];
            app7_nav_initialize(nv, 5, starts2.as_ptr(), 1);
            assert_eq!(app7_nav_size(nv), 5); // "re-init: size = 5"
            assert_eq!(app7_nav_chapter_count(nv), 1); // "re-init: chapter_count = 1"
            assert_eq!(app7_nav_position(nv), -1); // "re-init: position back to -1"

            app7_nav_free(nv);
            // "nav_free(NULL) is a no-op"
            app7_nav_free(ptr::null_mut());
        }
    }

    /// Test.hs lines 96 + 123-126: error-code lock-step for the variants
    /// this module's algebra uses (codes are the C ABI oracle).
    #[test]
    fn navigator_error_codes_lock_step() {
        assert_eq!(DomainError::NavigatorNotInitialized.code(), 8);
        assert_eq!(DomainError::EmptyImageSequence.code(), 9);
        assert_eq!(DomainError::IndexOutOfRange.code(), 10);
        assert_eq!(DomainError::ChapterOutOfRange.code(), 12);
    }

    /// Uninitialized (fresh) navigator: size 0 / pos -1 / everything
    /// rejected — matches C's app7_nav_new state exactly.
    #[test]
    fn uninitialized_navigator_rejects_everything() {
        unsafe {
            let nv = app7_nav_new();
            assert_eq!(app7_nav_size(nv), 0);
            assert_eq!(app7_nav_position(nv), -1);
            assert_eq!(app7_nav_chapter_count(nv), 0);
            assert_eq!(app7_nav_current_chapter_index(nv), 0);
            assert_eq!(app7_nav_next(nv), -1);
            assert_eq!(app7_nav_previous(nv), -1);
            assert_eq!(app7_nav_next_chapter(nv), -1);
            assert_eq!(app7_nav_previous_chapter(nv), -1);
            assert_eq!(app7_nav_go_to_chapter(nv, 0), -1);
            assert_eq!(app7_nav_restore_position(nv, 0), -1);
            assert_eq!(app7_nav_can_go_forward(nv), 0);
            assert_eq!(app7_nav_can_go_back(nv), 0);
            assert_eq!(app7_nav_can_go_next_chapter(nv), 0);
            assert_eq!(app7_nav_can_go_previous_chapter(nv), 0);
            app7_nav_free(nv);
        }
    }

    /// NULL handle to every function — exact C fallbacks.
    #[test]
    fn null_handle_fallbacks() {
        unsafe {
            let n: *mut App7Navigator = ptr::null_mut();
            app7_nav_initialize(n, 10, ptr::null(), 0); // no-op, no crash
            assert_eq!(app7_nav_size(n), 0);
            assert_eq!(app7_nav_position(n), -1);
            assert_eq!(app7_nav_chapter_count(n), 0);
            assert_eq!(app7_nav_current_chapter_index(n), 0);
            assert_eq!(app7_nav_next(n), -1);
            assert_eq!(app7_nav_previous(n), -1);
            assert_eq!(app7_nav_next_chapter(n), -1);
            assert_eq!(app7_nav_previous_chapter(n), -1);
            assert_eq!(app7_nav_go_to_chapter(n, 0), -1);
            assert_eq!(app7_nav_restore_position(n, 0), -1);
            assert_eq!(app7_nav_can_go_forward(n), 0);
            assert_eq!(app7_nav_can_go_back(n), 0);
            assert_eq!(app7_nav_can_go_next_chapter(n), 0);
            assert_eq!(app7_nav_can_go_previous_chapter(n), 0);
            app7_nav_free(n); // no-op
        }
    }

    /// pos == -1 quirks: previous is rejected (C: `index > 0`, not
    /// clamped to 0); next_chapter jumps to starts[1] because chapter 0
    /// counts as current; can_go_previous_chapter is 0, can_go_next_chapter
    /// is 1 whenever more than one chapter exists.
    #[test]
    fn before_first_page_quirks() {
        unsafe {
            let nv = app7_nav_new();
            let starts = [0i32, 3, 6];
            app7_nav_initialize(nv, 10, starts.as_ptr(), 3);

            assert_eq!(app7_nav_previous(nv), -1); // NOT 0
            assert_eq!(app7_nav_position(nv), -1); // unchanged
            assert_eq!(app7_nav_previous_chapter(nv), -1);
            assert_eq!(app7_nav_can_go_previous_chapter(nv), 0);
            assert_eq!(app7_nav_can_go_next_chapter(nv), 1);

            // next_chapter from -1 lands at starts[1] == 3, skipping ch0.
            assert_eq!(app7_nav_next_chapter(nv), 3);

            app7_nav_free(nv);
        }
    }

    /// Chapter boundaries: current_chapter_index at exact start indices
    /// and just before them.
    #[test]
    fn chapter_boundary_index() {
        let mut nav = Navigator::new();
        nav.initialize(10, &[0, 3, 6]);
        for (pos, expect) in [
            (0, 0),
            (2, 0),
            (3, 1), // boundary: starts[1] <= 3
            (5, 1),
            (6, 2), // boundary: starts[2] <= 6
            (9, 2),
        ] {
            nav.restore_position(pos).unwrap();
            assert_eq!(nav.current_chapter_index(), expect, "pos {}", pos);
        }
    }

    /// current_chapter_index answers 0 even when pos precedes starts[0]
    /// (contract-violating starts that don't begin at 0) — C binary
    /// search bottoms out at lo == 0.
    #[test]
    fn chapter_index_before_first_start() {
        let mut nav = Navigator::new();
        nav.initialize(10, &[5, 8]);
        nav.restore_position(2).unwrap();
        assert_eq!(nav.current_chapter_index(), 0);
    }

    /// Single chapter: both chapter moves rejected from anywhere.
    #[test]
    fn single_chapter_moves_rejected() {
        unsafe {
            let nv = app7_nav_new();
            let starts = [0i32];
            app7_nav_initialize(nv, 5, starts.as_ptr(), 1);
            app7_nav_restore_position(nv, 2);
            assert_eq!(app7_nav_next_chapter(nv), -1);
            assert_eq!(app7_nav_previous_chapter(nv), -1);
            assert_eq!(app7_nav_can_go_next_chapter(nv), 0);
            assert_eq!(app7_nav_can_go_previous_chapter(nv), 0);
            app7_nav_free(nv);
        }
    }

    /// go_to_chapter rejects negative indices too; and (C quirk) it has
    /// NO size check — with size 0 but chapters present it still moves.
    #[test]
    fn go_to_chapter_edges() {
        unsafe {
            let nv = app7_nav_new();
            let starts = [0i32, 3, 6];
            app7_nav_initialize(nv, 10, starts.as_ptr(), 3);
            assert_eq!(app7_nav_go_to_chapter(nv, -1), -1);
            assert_eq!(app7_nav_go_to_chapter(nv, 3), -1); // == count
            assert_eq!(app7_nav_go_to_chapter(nv, 2), 6);

            // size 0, chapters present (contract-violating init): the C
            // only checks the chapter index.
            let starts0 = [4i32];
            app7_nav_initialize(nv, 0, starts0.as_ptr(), 1);
            assert_eq!(app7_nav_go_to_chapter(nv, 0), 4);
            // ...and can_go_back has no size check either: pos 4 > 0.
            assert_eq!(app7_nav_can_go_back(nv), 1);
            // but next/previous/restore_position DO check size == 0.
            assert_eq!(app7_nav_next(nv), -1);
            assert_eq!(app7_nav_previous(nv), -1);
            assert_eq!(app7_nav_restore_position(nv, 0), -1);
            // can_go_forward checks size != 0.
            assert_eq!(app7_nav_can_go_forward(nv), 0);
            app7_nav_free(nv);
        }
    }

    /// initialize clamps negative total_size and chapter_count to 0, and
    /// adopts the count when the starts pointer is NULL but count > 0.
    #[test]
    fn initialize_clamps_and_null_starts() {
        unsafe {
            let nv = app7_nav_new();
            app7_nav_initialize(nv, -7, ptr::null(), -3);
            assert_eq!(app7_nav_size(nv), 0);
            assert_eq!(app7_nav_chapter_count(nv), 0);
            assert_eq!(app7_nav_position(nv), -1);

            // NULL starts with positive count: C keeps the count (buffer
            // contents indeterminate there; zero-filled here).
            app7_nav_initialize(nv, 10, ptr::null(), 2);
            assert_eq!(app7_nav_chapter_count(nv), 2);
            assert_eq!(app7_nav_size(nv), 10);
            assert_eq!(app7_nav_position(nv), -1);

            app7_nav_free(nv);
        }
    }

    /// restore_position bounds: 0 and size-1 accepted, size rejected.
    #[test]
    fn restore_position_bounds() {
        let mut nav = Navigator::new();
        nav.initialize(10, &[]);
        assert_eq!(nav.restore_position(0), Ok(0));
        assert_eq!(nav.restore_position(9), Ok(9));
        assert_eq!(
            nav.restore_position(10),
            Err(DomainError::IndexOutOfRange)
        );
        assert_eq!(nav.position(), 9); // rejected move leaves pos alone
    }

    /// next walks to the end then rejects; previous walks back to 0 then
    /// rejects (never to -1).
    #[test]
    fn walk_full_sequence() {
        let mut nav = Navigator::new();
        nav.initialize(3, &[]);
        assert_eq!(nav.next(), Ok(0));
        assert_eq!(nav.next(), Ok(1));
        assert_eq!(nav.next(), Ok(2));
        assert_eq!(nav.next(), Err(DomainError::IndexOutOfRange));
        assert_eq!(nav.position(), 2);
        assert_eq!(nav.previous(), Ok(1));
        assert_eq!(nav.previous(), Ok(0));
        assert_eq!(nav.previous(), Err(DomainError::IndexOutOfRange));
        assert_eq!(nav.position(), 0);
    }
}
