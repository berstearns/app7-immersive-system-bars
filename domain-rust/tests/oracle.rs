// ═══════════════════════════════════════════════════════════════════════
//  oracle.rs — cross-module ORACLE TESTS for the app7_domain Rust crate.
//
//  Re-asserts every oracle value from:
//    * domain-haskell/Test.hs          (36 round-trip assertions)
//    * domain-core/test/round_trip.c   (100 assertions, C-side oracle)
//    * the PHASE-6-SMOKE line (RESUME.md / MainActivity.kt / Main.kt):
//        perm=true clamped=[0.0,1.0,1.0,0.0] contains=true degenerate=true
//        poolSize=17 sample(42,3)=[1,15,16] firstLabel=partially
//        endApp(count=1,type=2) navNext=0 navNextChapter=3
//
//  STRATEGY: assertions about ABI behavior (strerror pointers, NULL
//  handling, out-params, handle lifecycles, -1 conventions, sampler
//  bit-exactness) go through the #[no_mangle] extern "C" symbols declared
//  below — declared by SYMBOL NAME exactly as in app7_domain.h, so this
//  file is independent of how the concurrently-written Rust modules are
//  organized internally.  The safe algebraic API is used where it is
//  already pinned (error::DomainError).
//
//  One #[test] per module + one cross-module smoke test.
// ═══════════════════════════════════════════════════════════════════════

use app7_domain::error::DomainError;

use core::ffi::{c_char, c_int, c_void};
use core::ptr;
use std::ffi::CStr;

// ── extern declarations — symbol names pinned by app7_domain.h ──────────
extern "C" {
    // 3. CropSelection
    fn app7_crop_normalized_x(sx: f32, sy: f32, ex: f32, ey: f32) -> f32;
    fn app7_crop_normalized_y(sx: f32, sy: f32, ex: f32, ey: f32) -> f32;
    fn app7_crop_normalized_width(sx: f32, sy: f32, ex: f32, ey: f32) -> f32;
    fn app7_crop_normalized_height(sx: f32, sy: f32, ex: f32, ey: f32) -> f32;
    fn app7_crop_center_x(sx: f32, sy: f32, ex: f32, ey: f32) -> f32;
    fn app7_crop_center_y(sx: f32, sy: f32, ex: f32, ey: f32) -> f32;
    fn app7_crop_is_degenerate(min_size: f32, sx: f32, sy: f32, ex: f32, ey: f32) -> c_int;
    fn app7_crop_clamped(
        sx: f32,
        sy: f32,
        ex: f32,
        ey: f32,
        out_sx: *mut f32,
        out_sy: *mut f32,
        out_ex: *mut f32,
        out_ey: *mut f32,
    );

    // 4. ConversationBox.contains
    fn app7_box_contains(nx: f32, ny: f32, bx: f32, by: f32, bw: f32, bh: f32) -> c_int;

    // 5. isValidPermutation
    fn app7_is_valid_permutation(indices: *const c_int, n: usize, domain_n: c_int) -> c_int;

    // 6. Annotation pool + sampler
    fn app7_annotation_pool_size() -> c_int;
    fn app7_annotation_pool_label(index: c_int) -> *const c_char;
    fn app7_annotation_pool_display(index: c_int) -> *const c_char;
    fn app7_sample_annotation_indices(seed: u64, k: c_int, out_indices: *mut c_int) -> c_int;

    // 7. SessionTimeTracker (opaque handle)
    fn app7_session_new() -> *mut c_void;
    fn app7_session_free(t: *mut c_void);
    fn app7_session_start_app(t: *mut c_void, now_ms: i64);
    fn app7_session_end_app(t: *mut c_void, now_ms: i64, out: *mut i64) -> c_int;
    fn app7_session_start_chapter(t: *mut c_void, now_ms: i64, out: *mut i64) -> c_int;
    fn app7_session_start_page(t: *mut c_void, now_ms: i64, out: *mut i64) -> c_int;
    fn app7_session_elapsed(t: *mut c_void, now_ms: i64, out: *mut i64);

    // 8. ImageSequenceNavigator (opaque handle)
    fn app7_nav_new() -> *mut c_void;
    fn app7_nav_free(n: *mut c_void);
    fn app7_nav_initialize(
        n: *mut c_void,
        total_size: c_int,
        chapter_starts: *const c_int,
        chapter_count: c_int,
    );
    fn app7_nav_size(n: *mut c_void) -> c_int;
    fn app7_nav_position(n: *mut c_void) -> c_int;
    fn app7_nav_chapter_count(n: *mut c_void) -> c_int;
    fn app7_nav_current_chapter_index(n: *mut c_void) -> c_int;
    fn app7_nav_next(n: *mut c_void) -> c_int;
    fn app7_nav_next_chapter(n: *mut c_void) -> c_int;
    fn app7_nav_previous_chapter(n: *mut c_void) -> c_int;
    fn app7_nav_go_to_chapter(n: *mut c_void, chapter_idx: c_int) -> c_int;
    fn app7_nav_restore_position(n: *mut c_void, saved_idx: c_int) -> c_int;
    fn app7_nav_can_go_forward(n: *mut c_void) -> c_int;
    fn app7_nav_can_go_back(n: *mut c_void) -> c_int;
    fn app7_nav_can_go_next_chapter(n: *mut c_void) -> c_int;
}

/// APP7_MIN_NORMALIZED_SIZE — mirror of the header macro.
const MIN_NORMALIZED_SIZE: f32 = 0.02;

/// Same epsilon as Test.hs `approx` and round_trip.c `approx`.
fn approx(a: f32, b: f32) -> bool {
    (a - b).abs() < 1e-5
}

/// Borrow a NUL-terminated static C string returned by the ABI.
fn cstr(p: *const c_char) -> &'static str {
    assert!(!p.is_null(), "expected a non-NULL static string");
    unsafe { CStr::from_ptr(p) }
        .to_str()
        .expect("pool/strerror strings are ASCII")
}

// ════════════════════════════════════════════════════════════════════════
// error — DomainError code projection (Test.hs) + strerror ABI (round_trip.c)
// ════════════════════════════════════════════════════════════════════════
#[test]
fn oracle_error_codes_and_strerror() {
    // [DomainError — code projection (Phase-2 lock-step)]
    assert_eq!(DomainError::NotPermutation.code(), 1, "NotPermutation = 1");
    assert_eq!(DomainError::DegenerateCrop.code(), 2, "DegenerateCrop = 2");
    assert_eq!(
        DomainError::ChapterOutOfRange.code(),
        12,
        "ChapterOutOfRange = 12"
    );

    // all 12 codes distinct
    let all_errors = [
        DomainError::NotPermutation,
        DomainError::DegenerateCrop,
        DomainError::InvalidNormalizedCoord,
        DomainError::EmptyAnnotationPool,
        DomainError::InvalidSampleSize,
        DomainError::SessionNotStarted,
        DomainError::SessionAlreadyStarted,
        DomainError::NavigatorNotInitialized,
        DomainError::EmptyImageSequence,
        DomainError::IndexOutOfRange,
        DomainError::ImageIdNotFound,
        DomainError::ChapterOutOfRange,
    ];
    let codes: Vec<i32> = all_errors.iter().map(|e| e.code()).collect();
    for i in 0..codes.len() {
        for j in (i + 1)..codes.len() {
            assert_ne!(codes[i], codes[j], "all 12 codes distinct");
        }
    }

    // Bonus C-only assertion: "app7_strerror covers OK + error + unknown".
    // (round_trip.c compares pointers; content inequality implies that and
    // is robust to string interning.)
    let ok = app7_domain::error::ffi::app7_strerror(0);
    let not_perm = app7_domain::error::ffi::app7_strerror(1);
    let unknown = app7_domain::error::ffi::app7_strerror(999);
    assert!(!ok.is_null(), "strerror(APP7_OK) non-NULL");
    assert!(!not_perm.is_null(), "strerror(NOT_PERMUTATION) non-NULL");
    assert!(!unknown.is_null(), "strerror(999) non-NULL (unknown code)");
    assert_ne!(
        cstr(ok),
        cstr(not_perm),
        "strerror(OK) differs from strerror(NOT_PERMUTATION)"
    );

    // Every known error code yields a non-NULL, non-empty string distinct
    // from the OK string.
    for code in 1..=12 {
        let s = app7_domain::error::ffi::app7_strerror(code);
        assert!(!s.is_null(), "strerror({code}) non-NULL");
        assert!(!cstr(s).is_empty(), "strerror({code}) non-empty");
        assert_ne!(cstr(s), cstr(ok), "strerror({code}) != strerror(OK)");
    }
}

// ════════════════════════════════════════════════════════════════════════
// crop — normalized / center / isDegenerate / clamped
// ════════════════════════════════════════════════════════════════════════
#[test]
fn oracle_crop() {
    unsafe {
        // [CropSelection — normalizedX/Y/Width/Height]   c1 = (0.5 0.2 0.1 0.4)
        assert!(
            approx(app7_crop_normalized_x(0.5, 0.2, 0.1, 0.4), 0.1),
            "normalizedX swaps when start>end"
        );
        assert!(
            approx(app7_crop_normalized_y(0.5, 0.2, 0.1, 0.4), 0.2),
            "normalizedY keeps when start<end"
        );
        assert!(
            approx(app7_crop_normalized_width(0.5, 0.2, 0.1, 0.4), 0.4),
            "normalizedWidth = abs(dx) = 0.4"
        );
        assert!(
            approx(app7_crop_normalized_height(0.5, 0.2, 0.1, 0.4), 0.2),
            "normalizedHeight = abs(dy) = 0.2"
        );

        // [CropSelection — centerX/Y]   c2 = (0.2 0.2 0.6 0.8)
        assert!(
            approx(app7_crop_center_x(0.2, 0.2, 0.6, 0.8), 0.4),
            "centerX = 0.4"
        );
        assert!(
            approx(app7_crop_center_y(0.2, 0.2, 0.6, 0.8), 0.5),
            "centerY = 0.5"
        );

        // [CropSelection — isDegenerate at minNormalizedSize=0.02]
        assert_eq!(
            app7_crop_is_degenerate(MIN_NORMALIZED_SIZE, 0.10, 0.10, 0.105, 0.105),
            1,
            "tiny is degenerate"
        );
        assert_eq!(
            app7_crop_is_degenerate(MIN_NORMALIZED_SIZE, 0.00, 0.00, 0.50, 0.50),
            0,
            "big is NOT degenerate"
        );
        assert_eq!(
            app7_crop_is_degenerate(MIN_NORMALIZED_SIZE, 0.00, 0.00, 0.02, 0.02),
            0,
            "edge (==0.02) is NOT degenerate (strict <)"
        );
        assert_eq!(
            app7_crop_is_degenerate(MIN_NORMALIZED_SIZE, 0.00, 0.00, 0.019, 0.50),
            1,
            "one-axis-under IS degenerate"
        );

        // [CropSelection — clamped]   oob = (-0.5 1.5 1.2 -0.2)
        let (mut ox, mut oy, mut oex, mut oey) = (f32::NAN, f32::NAN, f32::NAN, f32::NAN);
        app7_crop_clamped(-0.5, 1.5, 1.2, -0.2, &mut ox, &mut oy, &mut oex, &mut oey);
        assert!(approx(ox, 0.0), "clamped startX -0.5 -> 0");
        assert!(approx(oy, 1.0), "clamped startY  1.5 -> 1");
        assert!(approx(oex, 1.0), "clamped endX    1.2 -> 1");
        assert!(approx(oey, 0.0), "clamped endY   -0.2 -> 0");

        // Idempotence on in-range input: clamped(clamped(x)) == clamped(x).
        let (mut a1, mut b1, mut c1, mut d1) = (0f32, 0f32, 0f32, 0f32);
        let (mut a2, mut b2, mut c2, mut d2) = (0f32, 0f32, 0f32, 0f32);
        app7_crop_clamped(0.1, 0.2, 0.3, 0.4, &mut a1, &mut b1, &mut c1, &mut d1);
        app7_crop_clamped(a1, b1, c1, d1, &mut a2, &mut b2, &mut c2, &mut d2);
        assert!(
            approx(a1, a2) && approx(b1, b2) && approx(c1, c2) && approx(d1, d2),
            "clamped is idempotent on in-range"
        );
    }
}

// ════════════════════════════════════════════════════════════════════════
// region — ConversationBox.contains (inclusive on all four edges)
// ════════════════════════════════════════════════════════════════════════
#[test]
fn oracle_region_box_contains() {
    // box at (0.1, 0.1), width 0.4, height 0.4 → right=0.5, bottom=0.5
    unsafe {
        assert_eq!(
            app7_box_contains(0.3, 0.3, 0.1, 0.1, 0.4, 0.4),
            1,
            "tap inside box (0.3,0.3)"
        );
        assert_eq!(
            app7_box_contains(0.05, 0.3, 0.1, 0.1, 0.4, 0.4),
            0,
            "tap outside box left (0.05,0.3)"
        );
        assert_eq!(
            app7_box_contains(0.3, 0.6, 0.1, 0.1, 0.4, 0.4),
            0,
            "tap outside box below (0.3,0.6)"
        );
        assert_eq!(
            app7_box_contains(0.1, 0.3, 0.1, 0.1, 0.4, 0.4),
            1,
            "tap on left edge (0.1,0.3) inclusive"
        );
        assert_eq!(
            app7_box_contains(0.5, 0.5, 0.1, 0.1, 0.4, 0.4),
            1,
            "tap on bottom-right corner inclusive"
        );
    }
}

// ════════════════════════════════════════════════════════════════════════
// permutation — isValidPermutation (incl. NULL/0 ABI case)
// ════════════════════════════════════════════════════════════════════════
#[test]
fn oracle_permutation() {
    let p_012: [c_int; 3] = [0, 1, 2];
    let p_201: [c_int; 3] = [2, 0, 1];
    let p_01: [c_int; 2] = [0, 1];
    let p_013: [c_int; 3] = [0, 1, 3];
    let p_001: [c_int; 3] = [0, 0, 1];
    let p_0123: [c_int; 4] = [0, 1, 2, 3];

    unsafe {
        assert_eq!(
            app7_is_valid_permutation(p_012.as_ptr(), 3, 3),
            1,
            "[0,1,2] / 3 valid"
        );
        assert_eq!(
            app7_is_valid_permutation(p_201.as_ptr(), 3, 3),
            1,
            "[2,0,1] / 3 valid (shuffled)"
        );
        assert_eq!(
            app7_is_valid_permutation(ptr::null(), 0, 0),
            1,
            "[] / 0 valid (empty, NULL pointer accepted when n == 0)"
        );
        assert_eq!(
            app7_is_valid_permutation(p_01.as_ptr(), 2, 3),
            0,
            "[0,1] / 3 invalid (count mismatch)"
        );
        assert_eq!(
            app7_is_valid_permutation(p_013.as_ptr(), 3, 3),
            0,
            "[0,1,3] / 3 invalid (gap at 2)"
        );
        assert_eq!(
            app7_is_valid_permutation(p_001.as_ptr(), 3, 3),
            0,
            "[0,0,1] / 3 invalid (duplicate)"
        );
        assert_eq!(
            app7_is_valid_permutation(p_0123.as_ptr(), 4, 3),
            0,
            "[0,1,2,3] / 3 invalid (overflow)"
        );
    }
}

// ════════════════════════════════════════════════════════════════════════
// pool — DefaultAnnotationPool + xoshiro256** sampler
// ════════════════════════════════════════════════════════════════════════
#[test]
fn oracle_pool_and_sampler() {
    unsafe {
        // [DefaultAnnotationPool]
        assert_eq!(app7_annotation_pool_size(), 17, "pool size = 17");

        assert_eq!(
            cstr(app7_annotation_pool_label(0)),
            "understood",
            "pool[0].label = understood"
        );
        assert_eq!(
            cstr(app7_annotation_pool_display(0)),
            "Got it",
            "pool[0].display = Got it"
        );
        assert_eq!(
            cstr(app7_annotation_pool_label(16)),
            "callback",
            "pool[16].label = callback"
        );
        assert_eq!(
            cstr(app7_annotation_pool_display(16)),
            "Callback",
            "pool[16].display = Callback"
        );
        // Pinned by the PHASE-6-SMOKE firstLabel=partially oracle.
        assert_eq!(
            cstr(app7_annotation_pool_label(1)),
            "partially",
            "pool[1].label = partially (smoke firstLabel)"
        );

        assert!(
            app7_annotation_pool_label(-1).is_null(),
            "pool[-1] is NULL"
        );
        assert!(
            app7_annotation_pool_label(17).is_null(),
            "pool[17] is NULL"
        );
        assert!(
            app7_annotation_pool_display(-1).is_null(),
            "display[-1] is NULL"
        );
        assert!(
            app7_annotation_pool_display(17).is_null(),
            "display[17] is NULL"
        );

        // all labels distinct / all displays distinct (Test.hs)
        let labels: Vec<&str> = (0..17).map(|i| cstr(app7_annotation_pool_label(i))).collect();
        let displays: Vec<&str> = (0..17)
            .map(|i| cstr(app7_annotation_pool_display(i)))
            .collect();
        for i in 0..17 {
            for j in (i + 1)..17 {
                assert_ne!(labels[i], labels[j], "all labels distinct");
                assert_ne!(displays[i], displays[j], "all displays distinct");
            }
        }

        // [sampler]
        let mut out_a: [c_int; 3] = [-9; 3];
        let mut out_b: [c_int; 3] = [-9; 3];
        let mut out_c: [c_int; 3] = [-9; 3];
        let n_a = app7_sample_annotation_indices(42u64, 3, out_a.as_mut_ptr());
        let _ = app7_sample_annotation_indices(42u64, 3, out_b.as_mut_ptr());
        let _ = app7_sample_annotation_indices(43u64, 3, out_c.as_mut_ptr());

        assert_eq!(n_a, 3, "sample(42,3) returns 3");
        assert_eq!(out_a, out_b, "sample is deterministic for same seed");
        assert_ne!(out_a, out_c, "sample differs across seeds (42 vs 43)");
        assert!(
            out_a.iter().all(|&i| (0..17).contains(&i)),
            "sample indices all in [0, 17)"
        );
        assert!(
            out_a[0] != out_a[1] && out_a[1] != out_a[2] && out_a[0] != out_a[2],
            "sample indices all distinct"
        );
        // Bit-exact xoshiro256** oracle from PHASE-6-SMOKE: sample(42,3)=[1,15,16]
        assert_eq!(out_a, [1, 15, 16], "sample(42,3) == [1,15,16] (bit-exact)");

        // k > pool_size caps at pool_size and yields a full permutation.
        let mut out: [c_int; 20] = [-9; 20];
        let n = app7_sample_annotation_indices(7u64, 20, out.as_mut_ptr());
        assert_eq!(n, 17, "sample(seed, 20) caps at 17");
        let mut seen = [false; 17];
        for &v in &out[..n as usize] {
            assert!(
                (0..17).contains(&v),
                "sample(seed, 17) is full permutation — all in range"
            );
            assert!(
                !seen[v as usize],
                "sample(seed, 17) is full permutation — no duplicates"
            );
            seen[v as usize] = true;
        }

        // k <= 0 is defensive: returns 0.
        let mut out5: [c_int; 5] = [-9; 5];
        assert_eq!(
            app7_sample_annotation_indices(99u64, 0, out5.as_mut_ptr()),
            0,
            "sample(seed, 0) returns 0"
        );
        assert_eq!(
            app7_sample_annotation_indices(99u64, -1, out5.as_mut_ptr()),
            0,
            "sample(seed, -1) returns 0 (defensive)"
        );
    }
}

// ════════════════════════════════════════════════════════════════════════
// session — SessionTimeTracker triplet emission order + elapsed + lifecycle
//   type codes: 0 = PAGE, 1 = CHAPTER, 2 = APP
// ════════════════════════════════════════════════════════════════════════
#[test]
fn oracle_session_tracker() {
    const PAGE: i64 = 0;
    const CHAPTER: i64 = 1;
    const APP: i64 = 2;

    unsafe {
        let t = app7_session_new();
        assert!(!t.is_null(), "session_new returns non-NULL");

        let mut out = [0i64; 9];

        app7_session_start_app(t, 1000);
        let n0 = app7_session_end_app(t, 1500, out.as_mut_ptr());
        assert_eq!(n0, 1, "end_app after start_app emits 1 (APP)");
        assert_eq!(out[0], APP, "APP session type code = 2");
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

        // Start a new chapter; expect the prior PAGE + CHAPTER to close,
        // PAGE first (closure precedence PAGE -> CHAPTER).
        let prev = app7_session_start_chapter(t, 2400, out.as_mut_ptr());
        assert_eq!(prev, 2, "start_chapter emits 2 (PAGE+CHAPTER)");
        assert_eq!(out[0], PAGE, "first emitted = PAGE (type 0)");
        assert_eq!(out[3], CHAPTER, "second emitted = CHAPTER (type 1)");

        // End app; remaining CHAPTER+APP close (PAGE already closed).
        let prev = app7_session_end_app(t, 2500, out.as_mut_ptr());
        assert_eq!(prev, 2, "end_app closes remaining CHAPTER+APP (count=2)");
        assert_eq!(out[0], CHAPTER, "first = CHAPTER");
        assert_eq!(out[3], APP, "second = APP");

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
}

// ════════════════════════════════════════════════════════════════════════
// navigator — index moves, -1 rejection convention, re-initialize reset
// ════════════════════════════════════════════════════════════════════════
#[test]
fn oracle_navigator() {
    unsafe {
        let nv = app7_nav_new();
        assert!(!nv.is_null(), "nav_new returns non-NULL");

        // 10 images split into 3 chapters: [0..2], [3..5], [6..9]
        let starts: [c_int; 3] = [0, 3, 6];
        app7_nav_initialize(nv, 10, starts.as_ptr(), 3);
        assert_eq!(app7_nav_size(nv), 10, "size = 10");
        assert_eq!(app7_nav_chapter_count(nv), 3, "chapter_count = 3");
        assert_eq!(app7_nav_position(nv), -1, "position = -1 initially");
        assert_eq!(app7_nav_can_go_forward(nv), 1, "can go forward initially");
        assert_eq!(app7_nav_can_go_back(nv), 0, "cannot go back initially");

        let i = app7_nav_next(nv);
        assert_eq!(i, 0, "next from -1 -> 0");
        let i = app7_nav_next(nv);
        assert_eq!(i, 1, "next -> 1");
        assert_eq!(
            app7_nav_current_chapter_index(nv),
            0,
            "current_chapter_index at 1 = 0"
        );

        let i = app7_nav_next_chapter(nv);
        assert_eq!(i, 3, "next_chapter from ch0 -> idx 3 (start of ch1)");
        assert_eq!(
            app7_nav_current_chapter_index(nv),
            1,
            "current_chapter_index = 1"
        );

        let i = app7_nav_next_chapter(nv);
        assert_eq!(i, 6, "next_chapter -> idx 6 (start of ch2)");
        let i = app7_nav_next_chapter(nv);
        assert_eq!(i, -1, "next_chapter at last chapter returns -1");

        let i = app7_nav_previous_chapter(nv);
        assert_eq!(i, 3, "previous_chapter -> idx 3");

        let i = app7_nav_go_to_chapter(nv, 0);
        assert_eq!(i, 0, "go_to_chapter(0) -> idx 0");
        let i = app7_nav_go_to_chapter(nv, 99);
        assert_eq!(i, -1, "go_to_chapter(99) returns -1 (OOB)");

        let i = app7_nav_restore_position(nv, 5);
        assert_eq!(i, 5, "restore_position(5) -> 5");
        let i = app7_nav_restore_position(nv, -1);
        assert_eq!(i, -1, "restore_position(-1) returns -1");
        let i = app7_nav_restore_position(nv, 100);
        assert_eq!(i, -1, "restore_position(100) returns -1");

        // After restore to 5, we're in chapter 1 (idx range 3..5).
        app7_nav_restore_position(nv, 5);
        assert_eq!(
            app7_nav_current_chapter_index(nv),
            1,
            "at idx 5: current_chapter_index = 1"
        );
        assert_eq!(app7_nav_can_go_forward(nv), 1, "at idx 5: can_go_forward = 1");
        assert_eq!(
            app7_nav_can_go_next_chapter(nv),
            1,
            "at idx 5: can_go_next_chapter = 1"
        );

        app7_nav_restore_position(nv, 9);
        assert_eq!(app7_nav_can_go_forward(nv), 0, "at idx 9: can_go_forward = 0");
        assert_eq!(app7_nav_can_go_back(nv), 1, "at idx 9: can_go_back = 1");
        assert_eq!(
            app7_nav_can_go_next_chapter(nv),
            0,
            "at idx 9: can_go_next_chapter = 0"
        );

        // Re-initialize with a different chapter layout — resets cleanly.
        let starts2: [c_int; 1] = [0];
        app7_nav_initialize(nv, 5, starts2.as_ptr(), 1);
        assert_eq!(app7_nav_size(nv), 5, "re-init: size = 5");
        assert_eq!(app7_nav_chapter_count(nv), 1, "re-init: chapter_count = 1");
        assert_eq!(app7_nav_position(nv), -1, "re-init: position back to -1");

        app7_nav_free(nv);
        app7_nav_free(ptr::null_mut()); // "nav_free(NULL) is a no-op"
    }
}

// ════════════════════════════════════════════════════════════════════════
// cross-module smoke — replicates the PHASE-6-SMOKE line byte-for-byte:
//   perm=true clamped=[0.0,1.0,1.0,0.0] contains=true degenerate=true
//   poolSize=17 sample(42,3)=[1,15,16] firstLabel=partially
//   endApp(count=1,type=2) navNext=0 navNextChapter=3
// (Mirror of MainActivity.kt / Main.kt PHASE-6 smoke blocks.)
// ════════════════════════════════════════════════════════════════════════
#[test]
fn oracle_phase6_cross_module_smoke() {
    unsafe {
        // perm=true
        let p: [c_int; 3] = [0, 1, 2];
        assert_eq!(app7_is_valid_permutation(p.as_ptr(), 3, 3), 1, "perm=true");

        // clamped=[0.0, 1.0, 1.0, 0.0]
        let (mut sx, mut sy, mut ex, mut ey) = (f32::NAN, f32::NAN, f32::NAN, f32::NAN);
        app7_crop_clamped(-0.5, 1.5, 1.2, -0.2, &mut sx, &mut sy, &mut ex, &mut ey);
        assert_eq!(
            [sx, sy, ex, ey],
            [0.0, 1.0, 1.0, 0.0],
            "clamped=[0.0, 1.0, 1.0, 0.0]"
        );

        // contains=true
        assert_eq!(
            app7_box_contains(0.3, 0.3, 0.1, 0.1, 0.4, 0.4),
            1,
            "contains=true"
        );

        // degenerate=true
        assert_eq!(
            app7_crop_is_degenerate(0.02, 0.0, 0.0, 0.019, 0.5),
            1,
            "degenerate=true"
        );

        // poolSize=17
        assert_eq!(app7_annotation_pool_size(), 17, "poolSize=17");

        // sample(42,3)=[1, 15, 16]
        let mut s_idx: [c_int; 3] = [-9; 3];
        let n = app7_sample_annotation_indices(42u64, 3, s_idx.as_mut_ptr());
        assert_eq!(n, 3, "sample(42,3) count");
        assert_eq!(s_idx, [1, 15, 16], "sample(42,3)=[1, 15, 16]");

        // firstLabel=partially (label of the first sampled index)
        assert_eq!(
            cstr(app7_annotation_pool_label(s_idx[0])),
            "partially",
            "firstLabel=partially"
        );

        // endApp(count=1,type=2)
        let t = app7_session_new();
        assert!(!t.is_null());
        app7_session_start_app(t, 1000);
        let mut out = [0i64; 9];
        let end_count = app7_session_end_app(t, 1500, out.as_mut_ptr());
        app7_session_free(t);
        assert_eq!(end_count, 1, "endApp count=1");
        assert_eq!(out[0], 2, "endApp type=2 (APP)");

        // navNext=0 navNextChapter=3   (5 images, chapters at [0, 3])
        let nv = app7_nav_new();
        assert!(!nv.is_null());
        let starts: [c_int; 2] = [0, 3];
        app7_nav_initialize(nv, 5, starts.as_ptr(), 2);
        let next_idx = app7_nav_next(nv);
        let next_chapter_idx = app7_nav_next_chapter(nv);
        app7_nav_free(nv);
        assert_eq!(next_idx, 0, "navNext=0");
        assert_eq!(next_chapter_idx, 3, "navNextChapter=3");
    }
}
