// ═══════════════════════════════════════════════════════════════════════
//  session — SessionTimeTracker (app7_domain.h §7, Phase 2b-B).
//
//  Algebraic port of domain-core/src/session_tracker.c.  The C state is
//  three int64_t timestamps where 0 is the "not open" sentinel; here each
//  slot is Option<i64> with that quirk PRESERVED bit-exactly: starting a
//  session at now_ms == 0 stores the sentinel and therefore records
//  nothing open (C truthiness of `t->field`).  Negative timestamps are
//  truthy in C, so they are Some(_) here.
//
//  Closure precedence (LAW, matches the C and the header comment):
//    end_app        closes PAGE → CHAPTER → APP  (up to 3 triplets)
//    start_chapter  closes PAGE → CHAPTER        (up to 2)
//    start_page     closes PAGE                  (up to 1)
//
//  This module's algebra is TOTAL — session_tracker.c has no error path
//  (its NULL-pointer guards are an FFI-boundary concern, handled in
//  `ffi` below), so no core function returns Result<_, DomainError>.
//  SessionNotStarted / SessionAlreadyStarted exist in the shared error
//  algebra for other layers; the C projection never emits them and
//  neither does this port.
// ═══════════════════════════════════════════════════════════════════════

// ─────────────────────────── ALGEBRAIC CORE ────────────────────────────

/// Session kind codes — LOCK-STEP with SESSION_TYPE_* in
/// session_tracker.c and the "Session type codes" block in
/// app7_domain.h §7.  The discriminant IS the wire code.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i64)]
pub enum SessionType {
    Page = 0,
    Chapter = 1,
    App = 2,
}

/// One closed session — the (type, started_at, ended_at) triplet the
/// C side emits as 3 × int64_t.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ClosedSession {
    pub kind: SessionType,
    pub started_at: i64,
    pub ended_at: i64,
}

/// Placeholder for unused slots (never observable through `as_slice`).
const EMPTY_SLOT: ClosedSession = ClosedSession {
    kind: SessionType::Page,
    started_at: 0,
    ended_at: 0,
};

/// The sessions closed by ONE transition, in C emission order.
/// Fixed capacity 3 on purpose: the C projection emits into a
/// caller-provided int64_t[9] with no allocation; the algebra matches.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Closures {
    len: usize,
    slots: [ClosedSession; 3],
}

impl Closures {
    pub const fn new() -> Self {
        Closures {
            len: 0,
            slots: [EMPTY_SLOT; 3],
        }
    }

    fn push(&mut self, c: ClosedSession) {
        // Invariant: at most 3 pushes per transition (end_app's maximum),
        // so this index is always in bounds.
        self.slots[self.len] = c;
        self.len += 1;
    }

    pub fn as_slice(&self) -> &[ClosedSession] {
        &self.slots[..self.len]
    }

    pub fn len(&self) -> usize {
        self.len
    }

    pub fn is_empty(&self) -> bool {
        self.len == 0
    }
}

impl Default for Closures {
    fn default() -> Self {
        Closures::new()
    }
}

/// Elapsed milliseconds per level; None == not open (projected to -1
/// on the wire).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Elapsed {
    pub app_ms: Option<i64>,
    pub chapter_ms: Option<i64>,
    pub page_ms: Option<i64>,
}

/// C sentinel projection: the C code stores `now_ms` directly into a
/// field whose 0 value means "not open".  Storing 0 therefore IS the
/// not-open state — this helper encodes exactly that quirk.
#[inline]
const fn open_at(now_ms: i64) -> Option<i64> {
    if now_ms == 0 {
        None
    } else {
        Some(now_ms)
    }
}

/// Mutable state machine mirroring `struct App7SessionTracker`
/// (invariant by construction in the Kotlin original: page nested in
/// chapter nested in app — the C, and hence this port, does NOT enforce
/// nesting; each level is independent, exactly as the C fields are).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SessionTracker {
    app_start: Option<i64>,
    chapter_start: Option<i64>,
    page_start: Option<i64>,
}

impl SessionTracker {
    /// C: malloc + zero all three fields (0 == not open).
    pub const fn new() -> Self {
        SessionTracker {
            app_start: None,
            chapter_start: None,
            page_start: None,
        }
    }

    /// Overwrites the app timestamp and emits NOTHING — re-entrant
    /// start_app silently resets the app clock, leaving any open
    /// chapter/page untouched (C line-for-line: `t->app_start = now_ms`).
    pub fn start_app(&mut self, now_ms: i64) {
        self.app_start = open_at(now_ms);
    }

    /// Closes PAGE then CHAPTER then APP (each only if open), clearing
    /// all three.  Ends without starts emit nothing.
    pub fn end_app(&mut self, now_ms: i64) -> Closures {
        let mut out = Closures::new();
        if let Some(started) = self.page_start.take() {
            out.push(ClosedSession {
                kind: SessionType::Page,
                started_at: started,
                ended_at: now_ms,
            });
        }
        if let Some(started) = self.chapter_start.take() {
            out.push(ClosedSession {
                kind: SessionType::Chapter,
                started_at: started,
                ended_at: now_ms,
            });
        }
        if let Some(started) = self.app_start.take() {
            out.push(ClosedSession {
                kind: SessionType::App,
                started_at: started,
                ended_at: now_ms,
            });
        }
        out
    }

    /// Closes prior PAGE (clearing it), then prior CHAPTER; the chapter
    /// slot is then overwritten with now_ms (sentinel rule applies).
    /// app_start is NOT touched.
    pub fn start_chapter(&mut self, now_ms: i64) -> Closures {
        let mut out = Closures::new();
        if let Some(started) = self.page_start.take() {
            out.push(ClosedSession {
                kind: SessionType::Page,
                started_at: started,
                ended_at: now_ms,
            });
        }
        if let Some(started) = self.chapter_start {
            // C emits without clearing — the field is overwritten below.
            out.push(ClosedSession {
                kind: SessionType::Chapter,
                started_at: started,
                ended_at: now_ms,
            });
        }
        self.chapter_start = open_at(now_ms);
        out
    }

    /// Closes prior PAGE only; the page slot is then overwritten with
    /// now_ms (sentinel rule applies).  chapter/app are NOT touched.
    pub fn start_page(&mut self, now_ms: i64) -> Closures {
        let mut out = Closures::new();
        if let Some(started) = self.page_start {
            // C emits without clearing — the field is overwritten below.
            out.push(ClosedSession {
                kind: SessionType::Page,
                started_at: started,
                ended_at: now_ms,
            });
        }
        self.page_start = open_at(now_ms);
        out
    }

    /// (app, chapter, page) elapsed; None == not open (C's -1).
    /// wrapping_sub reproduces the two's-complement bit pattern of the
    /// C `now_ms - start` (whose signed overflow is UB in C; wrapping
    /// is the panic-free total choice matching hardware behavior).
    pub fn elapsed(&self, now_ms: i64) -> Elapsed {
        Elapsed {
            app_ms: self.app_start.map(|s| now_ms.wrapping_sub(s)),
            chapter_ms: self.chapter_start.map(|s| now_ms.wrapping_sub(s)),
            page_ms: self.page_start.map(|s| now_ms.wrapping_sub(s)),
        }
    }
}

impl Default for SessionTracker {
    fn default() -> Self {
        SessionTracker::new()
    }
}

// ─────────────────────────── FFI PROJECTION ────────────────────────────

pub mod ffi {
    //! C ABI projection — app7_domain.h §7 signatures, byte-for-byte.
    //! All pointer handling and all unsafe live here, nowhere else.

    use super::{Closures, SessionTracker};
    use alloc::boxed::Box;

    /// The opaque type behind `App7SessionTracker *` in the header.
    pub type App7SessionTracker = SessionTracker;

    /// Write `closures` as (type, started_at, ended_at) triplets and
    /// return the slot count, mirroring the C `emit` helper.
    ///
    /// SAFETY: caller guarantees `out` is non-NULL and has room for
    /// `closures.len() * 3` int64_t (header contract: int64_t[9] for
    /// end_app, [6] for start_chapter, [3] for start_page).
    unsafe fn write_triplets(out: *mut i64, closures: &Closures) -> i32 {
        for (slot, c) in closures.as_slice().iter().enumerate() {
            *out.add(slot * 3) = c.kind as i64;
            *out.add(slot * 3 + 1) = c.started_at;
            *out.add(slot * 3 + 2) = c.ended_at;
        }
        closures.len() as i32
    }

    #[no_mangle]
    pub extern "C" fn app7_session_new() -> *mut App7SessionTracker {
        // C returns NULL on malloc failure; Rust's global allocator
        // aborts on OOM instead.  No caller branches on NULL-from-new,
        // so the observable happy path is identical.
        Box::into_raw(Box::new(SessionTracker::new()))
    }

    #[no_mangle]
    pub extern "C" fn app7_session_free(t: *mut App7SessionTracker) {
        // C: free(NULL) is a well-defined no-op — tolerate NULL here too.
        if !t.is_null() {
            // SAFETY: non-NULL handles come from app7_session_new's
            // Box::into_raw; ownership returns here exactly once.
            drop(unsafe { Box::from_raw(t) });
        }
    }

    #[no_mangle]
    pub extern "C" fn app7_session_start_app(t: *mut App7SessionTracker, now_ms: i64) {
        if t.is_null() {
            return;
        }
        // SAFETY: non-NULL handle from app7_session_new, not yet freed.
        unsafe { &mut *t }.start_app(now_ms);
    }

    #[no_mangle]
    pub extern "C" fn app7_session_end_app(
        t: *mut App7SessionTracker,
        now_ms: i64,
        out: *mut i64,
    ) -> i32 {
        if t.is_null() || out.is_null() {
            return 0;
        }
        // SAFETY: t non-NULL (checked) from app7_session_new; out
        // non-NULL (checked) with room for 3 triplets per the header.
        let closures = unsafe { &mut *t }.end_app(now_ms);
        unsafe { write_triplets(out, &closures) }
    }

    #[no_mangle]
    pub extern "C" fn app7_session_start_chapter(
        t: *mut App7SessionTracker,
        now_ms: i64,
        out: *mut i64,
    ) -> i32 {
        if t.is_null() || out.is_null() {
            return 0;
        }
        // SAFETY: as in end_app; out has room for 2 triplets.
        let closures = unsafe { &mut *t }.start_chapter(now_ms);
        unsafe { write_triplets(out, &closures) }
    }

    #[no_mangle]
    pub extern "C" fn app7_session_start_page(
        t: *mut App7SessionTracker,
        now_ms: i64,
        out: *mut i64,
    ) -> i32 {
        if t.is_null() || out.is_null() {
            return 0;
        }
        // SAFETY: as in end_app; out has room for 1 triplet.
        let closures = unsafe { &mut *t }.start_page(now_ms);
        unsafe { write_triplets(out, &closures) }
    }

    #[no_mangle]
    pub extern "C" fn app7_session_elapsed(
        t: *mut App7SessionTracker,
        now_ms: i64,
        out: *mut i64,
    ) {
        // C checks `out` FIRST: NULL out is a silent no-op even when
        // t is also NULL.  Preserve that order.
        if out.is_null() {
            return;
        }
        if t.is_null() {
            // SAFETY: out non-NULL (checked) with room for 3 int64_t.
            unsafe {
                *out.add(0) = -1;
                *out.add(1) = -1;
                *out.add(2) = -1;
            }
            return;
        }
        // SAFETY: t non-NULL (checked) from app7_session_new; out
        // non-NULL (checked) with room for 3 int64_t.
        let e = unsafe { &*t }.elapsed(now_ms);
        unsafe {
            *out.add(0) = e.app_ms.unwrap_or(-1);
            *out.add(1) = e.chapter_ms.unwrap_or(-1);
            *out.add(2) = e.page_ms.unwrap_or(-1);
        }
    }
}

// ────────────────────────────── TESTS ──────────────────────────────────

#[cfg(test)]
mod tests {
    use super::ffi::*;
    use super::*;
    use core::ptr;

    /// Verbatim port of round_trip.c "[SessionTimeTracker (Phase 2b-B)]"
    /// block — oracle values preserved exactly.
    #[test]
    fn round_trip_session_block() {
        let t = app7_session_new();
        assert!(!t.is_null(), "session_new returns non-NULL");

        let mut out = [0i64; 9];
        app7_session_start_app(t, 1000);
        let n0 = app7_session_end_app(t, 1500, out.as_mut_ptr());
        assert_eq!(n0, 1, "end_app after start_app emits 1 (APP)");
        assert_eq!(out[0], 2, "APP session type code = 2");
        assert_eq!(out[1], 1000, "APP started_at = 1000");
        assert_eq!(out[2], 1500, "APP ended_at = 1500");

        app7_session_start_app(t, 2000);
        let prev = app7_session_start_chapter(t, 2100, out.as_mut_ptr());
        assert_eq!(prev, 0, "start_chapter w/ no prior chapter emits 0");

        let prev = app7_session_start_page(t, 2200, out.as_mut_ptr());
        assert_eq!(prev, 0, "start_page w/ no prior page emits 0");

        // Now start a new page; expect the prior PAGE to close.
        let prev = app7_session_start_page(t, 2300, out.as_mut_ptr());
        assert_eq!(prev, 1, "start_page w/ prior page emits 1 PAGE");
        assert_eq!(out[1], 2200, "emitted PAGE started_at = 2200");

        // Start a new chapter; expect the prior PAGE + CHAPTER to close.
        let prev = app7_session_start_chapter(t, 2400, out.as_mut_ptr());
        assert_eq!(prev, 2, "start_chapter emits 2 (PAGE+CHAPTER)");
        assert_eq!(out[0], 0, "first emitted = PAGE (type 0)");
        assert_eq!(out[3], 1, "second emitted = CHAPTER (type 1)");

        // End app; expect remaining CHAPTER+APP to close (PAGE already closed).
        let prev = app7_session_end_app(t, 2500, out.as_mut_ptr());
        assert_eq!(prev, 2, "end_app closes remaining CHAPTER+APP (count=2)");
        assert_eq!(out[0], 1, "first = CHAPTER");
        assert_eq!(out[3], 2, "second = APP");

        // Elapsed after end_app: all -1.
        let mut el = [0i64; 3];
        app7_session_elapsed(t, 2600, el.as_mut_ptr());
        assert_eq!(el[0], -1, "elapsed app = -1 after end");
        assert_eq!(el[1], -1, "elapsed chapter = -1 after end");
        assert_eq!(el[2], -1, "elapsed page = -1 after end");

        // Elapsed during open session.
        app7_session_start_app(t, 3000);
        let _ = app7_session_start_page(t, 3100, out.as_mut_ptr()); // discard
        app7_session_elapsed(t, 3200, el.as_mut_ptr());
        assert_eq!(el[0], 200, "elapsed app = 200");
        assert_eq!(el[1], -1, "elapsed chapter = -1 (no chapter open)");
        assert_eq!(el[2], 100, "elapsed page = 100");

        app7_session_free(t);
        app7_session_free(ptr::null_mut()); // "session_free(NULL) is a no-op"
    }

    // ── Additional parity tests for C behaviors NOT covered by the
    //    round-trip oracle but observable through the ABI. ─────────────

    /// C quirk: a field value of 0 means "not open", so starting at
    /// now_ms == 0 records nothing.
    #[test]
    fn zero_timestamp_is_the_not_open_sentinel() {
        let t = app7_session_new();
        let mut out = [0i64; 9];

        app7_session_start_app(t, 0);
        assert_eq!(
            app7_session_end_app(t, 500, out.as_mut_ptr()),
            0,
            "start_app(0) leaves app not-open (C truthiness)"
        );

        let n = app7_session_start_page(t, 0, out.as_mut_ptr());
        assert_eq!(n, 0);
        let n = app7_session_start_page(t, 5, out.as_mut_ptr());
        assert_eq!(n, 0, "page started at 0 is invisible to the next start_page");

        let n = app7_session_start_chapter(t, 0, out.as_mut_ptr());
        assert_eq!(n, 1, "closes the page opened at 5");
        assert_eq!(out[0], 0);
        assert_eq!(out[1], 5);
        assert_eq!(out[2], 0);
        let n = app7_session_start_chapter(t, 7, out.as_mut_ptr());
        assert_eq!(n, 0, "chapter started at 0 is invisible to the next start_chapter");

        app7_session_free(t);
    }

    /// Negative timestamps are truthy in C — the session counts as open.
    #[test]
    fn negative_timestamps_are_open() {
        let t = app7_session_new();
        let mut el = [0i64; 3];
        app7_session_start_app(t, -5);
        app7_session_elapsed(t, 5, el.as_mut_ptr());
        assert_eq!(el[0], 10);
        assert_eq!(el[1], -1);
        assert_eq!(el[2], -1);
        app7_session_free(t);
    }

    /// Re-entrant start_app overwrites the app clock with no emission
    /// and without touching open chapter/page sessions.
    #[test]
    fn reentrant_start_app_resets_clock_silently() {
        let t = app7_session_new();
        let mut out = [0i64; 9];

        app7_session_start_app(t, 100);
        let _ = app7_session_start_page(t, 150, out.as_mut_ptr());
        app7_session_start_app(t, 200); // no emission possible (void return)

        let n = app7_session_end_app(t, 300, out.as_mut_ptr());
        assert_eq!(n, 2, "page survives re-entrant start_app; app restarts");
        assert_eq!(out[0], 0, "PAGE first");
        assert_eq!(out[1], 150);
        assert_eq!(out[2], 300);
        assert_eq!(out[3], 2, "APP second");
        assert_eq!(out[4], 200, "app started_at is the LATEST start_app");
        assert_eq!(out[5], 300);

        app7_session_free(t);
    }

    /// Ends/starts without prior state emit nothing; NULL guards match
    /// the C exactly (t or out NULL → 0 / no-op, state untouched).
    #[test]
    fn null_guards_and_ends_without_starts() {
        let mut out = [0i64; 9];
        let mut el = [7i64; 3];

        // NULL tracker.
        assert_eq!(app7_session_end_app(ptr::null_mut(), 1, out.as_mut_ptr()), 0);
        assert_eq!(app7_session_start_chapter(ptr::null_mut(), 1, out.as_mut_ptr()), 0);
        assert_eq!(app7_session_start_page(ptr::null_mut(), 1, out.as_mut_ptr()), 0);
        app7_session_start_app(ptr::null_mut(), 1); // no crash
        app7_session_elapsed(ptr::null_mut(), 1, el.as_mut_ptr());
        assert_eq!(el, [-1, -1, -1], "elapsed(NULL tracker) writes all -1");

        // NULL out: return 0 and do NOT mutate the tracker.
        let t = app7_session_new();
        app7_session_start_app(t, 10);
        let _ = app7_session_start_page(t, 20, out.as_mut_ptr());
        assert_eq!(app7_session_end_app(t, 30, ptr::null_mut()), 0);
        assert_eq!(app7_session_start_chapter(t, 30, ptr::null_mut()), 0);
        assert_eq!(app7_session_start_page(t, 30, ptr::null_mut()), 0);
        app7_session_elapsed(t, 30, ptr::null_mut()); // silent no-op
        let mut el2 = [0i64; 3];
        app7_session_elapsed(t, 30, el2.as_mut_ptr());
        assert_eq!(el2, [20, -1, 10], "state untouched by NULL-out calls");

        // Ends without starts.
        let n = app7_session_end_app(t, 40, out.as_mut_ptr());
        assert_eq!(n, 2, "open page + app close");
        assert_eq!(app7_session_end_app(t, 50, out.as_mut_ptr()), 0, "second end emits 0");

        app7_session_free(t);
    }

    /// Full-stack end_app: PAGE → CHAPTER → APP order with all three open.
    #[test]
    fn end_app_emits_three_in_page_chapter_app_order() {
        let t = app7_session_new();
        let mut out = [0i64; 9];
        app7_session_start_app(t, 1);
        let _ = app7_session_start_chapter(t, 2, out.as_mut_ptr());
        let _ = app7_session_start_page(t, 3, out.as_mut_ptr());
        let n = app7_session_end_app(t, 9, out.as_mut_ptr());
        assert_eq!(n, 3);
        assert_eq!(&out[0..3], &[0, 3, 9], "PAGE triplet first");
        assert_eq!(&out[3..6], &[1, 2, 9], "CHAPTER triplet second");
        assert_eq!(&out[6..9], &[2, 1, 9], "APP triplet third");
        app7_session_free(t);
    }

    /// Core-level algebra check (no FFI): the same transitions through
    /// the ADT API, proving the projection adds nothing semantic.
    #[test]
    fn algebraic_core_matches_projection() {
        let mut tr = SessionTracker::new();
        tr.start_app(1000);
        let c = tr.end_app(1500);
        assert_eq!(
            c.as_slice(),
            &[ClosedSession {
                kind: SessionType::App,
                started_at: 1000,
                ended_at: 1500,
            }]
        );
        assert_eq!(
            tr.elapsed(2000),
            Elapsed {
                app_ms: None,
                chapter_ms: None,
                page_ms: None,
            }
        );

        tr.start_app(2000);
        assert!(tr.start_chapter(2100).is_empty());
        assert!(tr.start_page(2200).is_empty());
        let c = tr.start_page(2300);
        assert_eq!(c.len(), 1);
        assert_eq!(c.as_slice()[0].started_at, 2200);
        let c = tr.start_chapter(2400);
        assert_eq!(c.len(), 2);
        assert_eq!(c.as_slice()[0].kind, SessionType::Page);
        assert_eq!(c.as_slice()[1].kind, SessionType::Chapter);
        let c = tr.end_app(2500);
        assert_eq!(c.len(), 2);
        assert_eq!(c.as_slice()[0].kind, SessionType::Chapter);
        assert_eq!(c.as_slice()[1].kind, SessionType::App);
    }
}
