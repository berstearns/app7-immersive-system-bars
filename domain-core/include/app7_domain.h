/* ═══════════════════════════════════════════════════════════════════════
 *  app7_domain.h — C projection of App7Domain.hs (the algebraic source).
 *
 *  PUBLIC ABI of domain-core/.  Shipped as part of libapp7_domain.so on
 *  both Android (via JNI) and Desktop JVM (via System.load).  Same .so
 *  byte-for-byte serves both targets.
 *
 *  CASCADE LOCK-STEP (from sla-ddd-to-c/docs/auto-docs/INSTRUCTION.md §1):
 *  The APP7_ERR_* macros below MUST stay in sync with
 *  domain-haskell/App7Domain.hs's `data DomainError` constructors AND
 *  with App7Native.kt's Kotlin sealed-class projection.  Any change in
 *  one of those files = change here.  A single-file domain commit is a
 *  smell.
 *
 *  ABI CHOICE: flat scalar args wherever possible.  No struct types in
 *  the public ABI for Phase-1 predicates — keeps the JNI marshalling
 *  trivial (Phase 3 will pass jfloat/jint primitives straight through).
 *  Sum-type returns (e.g. DoubleTapAction, Phase 2b) will use tagged
 *  unions and out-params, not value returns.
 * ═══════════════════════════════════════════════════════════════════════ */
#ifndef APP7_DOMAIN_H
#define APP7_DOMAIN_H

#include <stddef.h>   /* size_t */

#ifdef __cplusplus
extern "C" {
#endif

/* ── 1. Return codes — lock-step with App7Domain.DomainError ───────────── */
/*    APP7_OK == 0; every error >0.  Phase 2a functions return Bool/Float
 *    directly, so they don't use these yet, but the catalog is declared
 *    in full so Phase 2b additions don't perturb the macro numbering. */
#define APP7_OK                              0
#define APP7_ERR_NOT_PERMUTATION             1
#define APP7_ERR_DEGENERATE_CROP             2
#define APP7_ERR_INVALID_NORMALIZED_COORD    3
#define APP7_ERR_EMPTY_ANNOTATION_POOL       4
#define APP7_ERR_INVALID_SAMPLE_SIZE         5
#define APP7_ERR_SESSION_NOT_STARTED         6
#define APP7_ERR_SESSION_ALREADY_STARTED     7
#define APP7_ERR_NAVIGATOR_NOT_INITIALIZED   8
#define APP7_ERR_EMPTY_IMAGE_SEQUENCE        9
#define APP7_ERR_INDEX_OUT_OF_RANGE         10
#define APP7_ERR_IMAGE_ID_NOT_FOUND         11
#define APP7_ERR_CHAPTER_OUT_OF_RANGE       12

const char *app7_strerror(int code);

/* ── 2. Constants ──────────────────────────────────────────────────────── */
/*    Mirror of App7Domain.minNormalizedSize and CropSelection's
 *    MIN_NORMALIZED_SIZE companion constant in Kotlin. */
#define APP7_MIN_NORMALIZED_SIZE 0.02f

/* ── 3. CropSelection — flat scalar args ───────────────────────────────── */
/*    Haskell: data CropSelection = CropSelection
 *               { csStartX, csStartY, csEndX, csEndY :: Float } */

float app7_crop_normalized_x      (float sx, float sy, float ex, float ey);
float app7_crop_normalized_y      (float sx, float sy, float ex, float ey);
float app7_crop_normalized_width  (float sx, float sy, float ex, float ey);
float app7_crop_normalized_height (float sx, float sy, float ex, float ey);
float app7_crop_center_x          (float sx, float sy, float ex, float ey);
float app7_crop_center_y          (float sx, float sy, float ex, float ey);

/* Returns 1 if either normalized dimension is strictly less than min_size,
 * 0 otherwise.  Boundary equality (== min_size) is NOT degenerate. */
int   app7_crop_is_degenerate     (float min_size,
                                   float sx, float sy, float ex, float ey);

/* Clamps each of (sx, sy, ex, ey) to [0, 1].  Writes results to the four
 * out pointers.  Out pointers must be non-NULL and may alias inputs. */
void  app7_crop_clamped           (float sx, float sy, float ex, float ey,
                                   float *out_sx, float *out_sy,
                                   float *out_ex, float *out_ey);

/* ── 3b. Scene-focus fit transform ─────────────────────────────────────
 *    Haskell: focusTransform :: ... -> FocusTransform
 *    Given a scene rectangle in page-normalized fractions (rx,ry,rw,rh),
 *    the page pixel size (pw,ph) and the screen pixel size (sw,sh), writes
 *    the transform mapping the scene onto the screen maximized +
 *    letterboxed:
 *      out_rot   in {0,90}   — the auto-rotation whose contain-fit scale
 *                              is larger (wide scene on a portrait screen
 *                              -> 90, so its long axis maps to the screen's)
 *      out_scale             — contain scale after rotation, capped at
 *                              APP7_MAX_FOCUS_SCALE (no upscaling a tiny
 *                              scene into a pixelated blowup)
 *      out_tx, out_ty        — centering translate, in screen pixels
 *    Any non-positive dimension -> identity {0,1,0,0}.  All four out
 *    pointers must be non-NULL. */
#define APP7_MAX_FOCUS_SCALE 8.0f
void  app7_focus_transform        (float rx, float ry, float rw, float rh,
                                   float pw, float ph, float sw, float sh,
                                   float *out_rot, float *out_scale,
                                   float *out_tx, float *out_ty);

/* ── 4. ConversationBox.contains ───────────────────────────────────────── */
/*    Haskell: contains :: Float -> Float -> ConversationBox -> Bool
 *    Inclusive on all four edges, matching Kotlin's `nx <= x + width`. */
int app7_box_contains (float nx, float ny,
                       float bx, float by, float bw, float bh);

/* ── 5. PageBubbleOrdering.isValidPermutation ──────────────────────────── */
/*    Haskell: isValidPermutation :: [Int] -> Int -> Bool
 *    `indices` is read-only and caller-owned; binary does no malloc.
 *    Returns 1 if `indices[0..n-1]` is a permutation of {0, ..., domain_n-1}
 *    AND n == domain_n; 0 otherwise. */
int app7_is_valid_permutation (const int *indices, size_t n, int domain_n);

/* ── 6. Annotation pool + sampler (Phase 2b) ───────────────────────────── */
/*    Mirror of DefaultAnnotationPool and RandomSamplingDecisionFunction
 *    in shared/src/commonMain/.../annotation/RandomSamplingDecisionFunction.kt
 *
 *    The pool is 17 hardcoded (label, display) pairs returned by index.
 *    Strings are pointers to static program data — DO NOT free.
 *
 *    The sampler is a Fisher-Yates partial shuffle seeded by xoshiro256**.
 *    `seed` is a 64-bit value passed straight from Kotlin's Long; same
 *    seed always yields the same indices.  Determinism is the parity
 *    contract — we don't compare bit-for-bit against Kotlin's Random
 *    (different algorithm) but the C side IS deterministic per seed. */

#include <stdint.h>   /* uint64_t */

int          app7_annotation_pool_size   (void);                 /* == 17 */
const char * app7_annotation_pool_label  (int index);            /* NULL on OOB */
const char * app7_annotation_pool_display(int index);            /* NULL on OOB */

/* Sample up to `k` distinct pool indices into `out_indices`.
 * Returns the actual count written (min(k, pool_size), or 0 if k<=0).
 * `out_indices` MUST have room for at least min(k, pool_size) ints.
 * No malloc; uses an internal stack-resident scratch buffer. */
int app7_sample_annotation_indices(uint64_t seed, int k, int *out_indices);

/* ── 7. SessionTimeTracker (Phase 2b-B) ─────────────────────────────────
 *  Mirror of SessionTimeTracker in
 *    shared/src/commonMain/.../domain/session/SessionTimeTracker.kt
 *  Opaque-handle pattern: state lives in C, Kotlin holds a Long pointer.
 *
 *  Session type codes (out arrays):
 *    0 = PAGE     1 = CHAPTER     2 = APP
 *  Each closed-session entry is a tuple of 3 int64_t: (type, started_at, ended_at).
 *
 *  Closure precedence (matches the Kotlin original):
 *    endAppSession    closes PAGE → CHAPTER → APP (up to 3 emitted)
 *    startChapter     closes PAGE → CHAPTER       (up to 2)
 *    startPage        closes PAGE                  (up to 1) */

typedef struct App7SessionTracker App7SessionTracker;

App7SessionTracker *app7_session_new(void);
void                app7_session_free(App7SessionTracker *t);

void app7_session_start_app   (App7SessionTracker *t, int64_t now_ms);
/* Returns count of closed sessions (0..3) written to `out` as triplets
 * (type, started_at, ended_at).  `out` must be int64_t[9]. */
int  app7_session_end_app     (App7SessionTracker *t, int64_t now_ms, int64_t *out);

/* Returns count (0..2) — closes prior PAGE then prior CHAPTER. */
int  app7_session_start_chapter(App7SessionTracker *t, int64_t now_ms, int64_t *out);

/* Returns count (0..1) — closes prior PAGE only. */
int  app7_session_start_page  (App7SessionTracker *t, int64_t now_ms, int64_t *out);

/* Writes (app_ms, chapter_ms, page_ms) into out[3]; -1 = not open. */
void app7_session_elapsed     (App7SessionTracker *t, int64_t now_ms, int64_t *out);

/* ── 8. ImageSequenceNavigator (Phase 2b-C) ──────────────────────────────
 *  Mirror of ImageSequenceNavigator in
 *    shared/src/commonMain/.../domain/navigation/ImageSequenceNavigator.kt
 *  Opaque-handle pattern.  ImageItem data + chapter NAMES stay in Kotlin
 *  (they're plain data structures the C side has no reason to own); the
 *  C side tracks size, current index, and the sorted chapter-start
 *  indices.  Index of -1 = before any page selected.
 *
 *  Convention: every navigation function returns the resulting index
 *  (>= 0) or -1 if the move was rejected (out of range, empty, etc.).
 *  Kotlin then looks up images[newIndex] in its own list. */

typedef struct App7Navigator App7Navigator;

App7Navigator *app7_nav_new(void);
void           app7_nav_free(App7Navigator *n);

/* Resets navigator state.  `chapter_starts` must be sorted ascending and
 * each value in [0, total_size).  Pass NULL/0 for no chapter index.
 * Copies the chapter_starts array internally. */
void app7_nav_initialize(App7Navigator *n, int total_size,
                         const int *chapter_starts, int chapter_count);

int app7_nav_size                   (App7Navigator *n);
int app7_nav_position               (App7Navigator *n);
int app7_nav_chapter_count          (App7Navigator *n);
int app7_nav_current_chapter_index  (App7Navigator *n);

int app7_nav_next                   (App7Navigator *n);
int app7_nav_previous               (App7Navigator *n);
int app7_nav_next_chapter           (App7Navigator *n);
int app7_nav_previous_chapter       (App7Navigator *n);
int app7_nav_go_to_chapter          (App7Navigator *n, int chapter_idx);
int app7_nav_restore_position       (App7Navigator *n, int saved_idx);

int app7_nav_can_go_forward         (App7Navigator *n);   /* 0 or 1 */
int app7_nav_can_go_back            (App7Navigator *n);
int app7_nav_can_go_next_chapter    (App7Navigator *n);
int app7_nav_can_go_previous_chapter(App7Navigator *n);

#ifdef __cplusplus
}
#endif

#endif /* APP7_DOMAIN_H */
