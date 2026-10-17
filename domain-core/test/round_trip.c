/* ═══════════════════════════════════════════════════════════════════════
 *  round_trip.c — Phase 2a verification: same 31 assertions as Test.hs.
 *
 *  Run:    ./round_trip
 *  Pass:   exit 0, "── N/N passed ──" at the end.
 *
 *  This file is the **C-side oracle** that the Haskell algebra and the
 *  C projection agree case-by-case.  Failures here mean either:
 *    (a) the C projection drifted from App7Domain.hs (most likely), or
 *    (b) IEEE 754 / strict-< boundary semantics differ between GHC and
 *        the host C compiler (rare; investigate before "fixing" either
 *        implementation).
 *
 *  Mirror of domain-haskell/Test.hs — every assertion here corresponds
 *  to the same-labeled assertion there.
 * ═══════════════════════════════════════════════════════════════════════ */
#include "app7_domain.h"
#include <stdio.h>
#include <stdlib.h>
#include <math.h>      /* fabsf */
#include <string.h>    /* strcmp */

static int g_total  = 0;
static int g_failed = 0;

static void assert_label(const char *label, int cond) {
    g_total++;
    if (cond) {
        printf("  ok  %s\n", label);
    } else {
        g_failed++;
        printf("  FAIL %s\n", label);
    }
}

static int approx(float a, float b) {
    return fabsf(a - b) < 1e-5f;
}

int main(void) {
    printf("── App7Domain Phase 2a (C) round-trip assertions ──\n");

    /* ───────────────────────────────────────────────────────────── */
    printf("[CropSelection — normalizedX/Y/Width/Height]\n");
    /* c1 = CropSelection 0.5 0.2 0.1 0.4 */
    assert_label("normalizedX swaps when start>end",
        approx(app7_crop_normalized_x(0.5f, 0.2f, 0.1f, 0.4f), 0.1f));
    assert_label("normalizedY keeps when start<end",
        approx(app7_crop_normalized_y(0.5f, 0.2f, 0.1f, 0.4f), 0.2f));
    assert_label("normalizedWidth = abs(dx) = 0.4",
        approx(app7_crop_normalized_width(0.5f, 0.2f, 0.1f, 0.4f), 0.4f));
    assert_label("normalizedHeight = abs(dy) = 0.2",
        approx(app7_crop_normalized_height(0.5f, 0.2f, 0.1f, 0.4f), 0.2f));

    /* ───────────────────────────────────────────────────────────── */
    printf("[CropSelection — centerX/Y]\n");
    /* c2 = CropSelection 0.2 0.2 0.6 0.8 */
    assert_label("centerX = 0.4",
        approx(app7_crop_center_x(0.2f, 0.2f, 0.6f, 0.8f), 0.4f));
    assert_label("centerY = 0.5",
        approx(app7_crop_center_y(0.2f, 0.2f, 0.6f, 0.8f), 0.5f));

    /* ───────────────────────────────────────────────────────────── */
    printf("[CropSelection — isDegenerate at min_size=0.02]\n");
    /* tiny  = 0.10 .. 0.105   → width/height = 0.005 < 0.02 → degenerate */
    /* big   = 0.00 .. 0.50    → 0.5 not < 0.02 → not degenerate */
    /* edge  = 0.00 .. 0.02    → exactly 0.02, strict< → not degenerate */
    /* just  = (0.019, 0.50)   → x-axis 0.019 < 0.02 → degenerate */
    assert_label("tiny is degenerate",
        app7_crop_is_degenerate(APP7_MIN_NORMALIZED_SIZE,
                                0.10f, 0.10f, 0.105f, 0.105f));
    assert_label("big is NOT degenerate",
        !app7_crop_is_degenerate(APP7_MIN_NORMALIZED_SIZE,
                                 0.00f, 0.00f, 0.50f, 0.50f));
    assert_label("edge (==0.02) is NOT degenerate (strict <)",
        !app7_crop_is_degenerate(APP7_MIN_NORMALIZED_SIZE,
                                 0.00f, 0.00f, 0.02f, 0.02f));
    assert_label("one-axis-under IS degenerate",
        app7_crop_is_degenerate(APP7_MIN_NORMALIZED_SIZE,
                                0.00f, 0.00f, 0.019f, 0.50f));

    /* ───────────────────────────────────────────────────────────── */
    printf("[CropSelection — clamped]\n");
    {
        float ox, oy, oex, oey;
        app7_crop_clamped(-0.5f, 1.5f, 1.2f, -0.2f, &ox, &oy, &oex, &oey);
        assert_label("clamped startX -0.5 -> 0", approx(ox, 0.0f));
        assert_label("clamped startY  1.5 -> 1", approx(oy, 1.0f));
        assert_label("clamped endX    1.2 -> 1", approx(oex, 1.0f));
        assert_label("clamped endY   -0.2 -> 0", approx(oey, 0.0f));

        /* Idempotence: in-range stays put, and clamped(clamped(x)) == clamped(x). */
        float a1, b1, c1, d1, a2, b2, c2, d2;
        app7_crop_clamped(0.1f, 0.2f, 0.3f, 0.4f, &a1, &b1, &c1, &d1);
        app7_crop_clamped(a1, b1, c1, d1, &a2, &b2, &c2, &d2);
        assert_label("clamped is idempotent on in-range",
            approx(a1, a2) && approx(b1, b2) && approx(c1, c2) && approx(d1, d2));
    }

    /* ───────────────────────────────────────────────────────────── */
    printf("[ConversationBox — contains]\n");
    /* box at (0.1, 0.1) with width=0.4, height=0.4 → right=0.5, bottom=0.5 */
    assert_label("tap inside box (0.3,0.3)",
        app7_box_contains(0.3f, 0.3f, 0.1f, 0.1f, 0.4f, 0.4f));
    assert_label("tap outside box left (0.05,0.3)",
        !app7_box_contains(0.05f, 0.3f, 0.1f, 0.1f, 0.4f, 0.4f));
    assert_label("tap outside box below (0.3,0.6)",
        !app7_box_contains(0.3f, 0.6f, 0.1f, 0.1f, 0.4f, 0.4f));
    assert_label("tap on left edge (0.1,0.3) inclusive",
        app7_box_contains(0.1f, 0.3f, 0.1f, 0.1f, 0.4f, 0.4f));
    assert_label("tap on bottom-right corner inclusive",
        app7_box_contains(0.5f, 0.5f, 0.1f, 0.1f, 0.4f, 0.4f));

    /* ───────────────────────────────────────────────────────────── */
    printf("[PageBubbleOrdering — isValidPermutation]\n");
    {
        const int p_012[] = {0, 1, 2};
        const int p_201[] = {2, 0, 1};
        const int p_01[]  = {0, 1};
        const int p_013[] = {0, 1, 3};
        const int p_001[] = {0, 0, 1};
        const int p_0123[]= {0, 1, 2, 3};

        assert_label("[0,1,2] / 3 valid",
            app7_is_valid_permutation(p_012, 3, 3));
        assert_label("[2,0,1] / 3 valid (shuffled)",
            app7_is_valid_permutation(p_201, 3, 3));
        assert_label("[] / 0 valid (empty)",
            app7_is_valid_permutation(NULL, 0, 0));
        assert_label("[0,1] / 3 invalid (count mismatch)",
            !app7_is_valid_permutation(p_01, 2, 3));
        assert_label("[0,1,3] / 3 invalid (gap at 2)",
            !app7_is_valid_permutation(p_013, 3, 3));
        assert_label("[0,0,1] / 3 invalid (duplicate)",
            !app7_is_valid_permutation(p_001, 3, 3));
        assert_label("[0,1,2,3] / 3 invalid (overflow)",
            !app7_is_valid_permutation(p_0123, 4, 3));
    }

    /* ───────────────────────────────────────────────────────────── */
    printf("[DomainError — code projection (lock-step with Haskell)]\n");
    assert_label("NotPermutation = 1",
        APP7_ERR_NOT_PERMUTATION == 1);
    assert_label("DegenerateCrop = 2",
        APP7_ERR_DEGENERATE_CROP == 2);
    assert_label("ChapterOutOfRange = 12",
        APP7_ERR_CHAPTER_OUT_OF_RANGE == 12);

    /* Distinctness — all 12 codes pairwise different. */
    {
        const int codes[12] = {
            APP7_ERR_NOT_PERMUTATION,
            APP7_ERR_DEGENERATE_CROP,
            APP7_ERR_INVALID_NORMALIZED_COORD,
            APP7_ERR_EMPTY_ANNOTATION_POOL,
            APP7_ERR_INVALID_SAMPLE_SIZE,
            APP7_ERR_SESSION_NOT_STARTED,
            APP7_ERR_SESSION_ALREADY_STARTED,
            APP7_ERR_NAVIGATOR_NOT_INITIALIZED,
            APP7_ERR_EMPTY_IMAGE_SEQUENCE,
            APP7_ERR_INDEX_OUT_OF_RANGE,
            APP7_ERR_IMAGE_ID_NOT_FOUND,
            APP7_ERR_CHAPTER_OUT_OF_RANGE,
        };
        int distinct = 1;
        for (int i = 0; i < 12 && distinct; ++i)
            for (int j = i + 1; j < 12 && distinct; ++j)
                if (codes[i] == codes[j]) distinct = 0;
        assert_label("all 12 codes distinct", distinct);
    }

    /* Bonus C-only assertion: app7_strerror round-trips. */
    {
        const int n_distinct =
            (app7_strerror(APP7_OK) != app7_strerror(APP7_ERR_NOT_PERMUTATION))
         && (app7_strerror(999) != NULL);
        assert_label("app7_strerror covers OK + error + unknown", n_distinct);
    }

    /* ───────────────────────────────────────────────────────────── */
    printf("[AnnotationPool + sampler (Phase 2b)]\n");
    assert_label("pool size = 17", app7_annotation_pool_size() == 17);
    {
        const char *l0 = app7_annotation_pool_label(0);
        const char *d0 = app7_annotation_pool_display(0);
        assert_label("pool[0].label  = understood", l0 && strcmp(l0, "understood") == 0);
        assert_label("pool[0].display = Got it",    d0 && strcmp(d0, "Got it") == 0);
        const char *lLast = app7_annotation_pool_label(16);
        const char *dLast = app7_annotation_pool_display(16);
        assert_label("pool[16].label  = callback", lLast && strcmp(lLast, "callback") == 0);
        assert_label("pool[16].display = Callback", dLast && strcmp(dLast, "Callback") == 0);
        assert_label("pool[-1] is NULL",  app7_annotation_pool_label(-1) == NULL);
        assert_label("pool[17] is NULL",  app7_annotation_pool_label(17) == NULL);
    }

    {
        int out_a[3], out_b[3], out_c[3];
        int n_a = app7_sample_annotation_indices(42ULL, 3, out_a);
        (void)app7_sample_annotation_indices(42ULL, 3, out_b);
        (void)app7_sample_annotation_indices(43ULL, 3, out_c);
        assert_label("sample(42,3) returns 3", n_a == 3);
        assert_label("sample is deterministic for same seed",
            out_a[0] == out_b[0] && out_a[1] == out_b[1] && out_a[2] == out_b[2]);
        assert_label("sample differs across seeds (42 vs 43)",
            !(out_a[0] == out_c[0] && out_a[1] == out_c[1] && out_a[2] == out_c[2]));
        assert_label("sample indices all in [0, 17)",
            out_a[0] >= 0 && out_a[0] < 17 &&
            out_a[1] >= 0 && out_a[1] < 17 &&
            out_a[2] >= 0 && out_a[2] < 17);
        assert_label("sample indices all distinct",
            out_a[0] != out_a[1] && out_a[1] != out_a[2] && out_a[0] != out_a[2]);
    }

    {
        /* k > pool_size is capped at pool_size; result is a full permutation. */
        int out[20];
        int n = app7_sample_annotation_indices(7ULL, 20, out);
        assert_label("sample(seed, 20) caps at 17", n == 17);
        int seen[17] = {0};
        int all_in_range = 1, no_dup = 1;
        for (int i = 0; i < n; ++i) {
            if (out[i] < 0 || out[i] >= 17) all_in_range = 0;
            else if (seen[out[i]]) no_dup = 0;
            else seen[out[i]] = 1;
        }
        assert_label("sample(seed, 17) is full permutation — all in range", all_in_range);
        assert_label("sample(seed, 17) is full permutation — no duplicates", no_dup);
    }

    {
        int out[5];
        int n = app7_sample_annotation_indices(99ULL, 0, out);
        assert_label("sample(seed, 0) returns 0", n == 0);
        int n2 = app7_sample_annotation_indices(99ULL, -1, out);
        assert_label("sample(seed, -1) returns 0 (defensive)", n2 == 0);
    }

    /* ───────────────────────────────────────────────────────────── */
    printf("[SessionTimeTracker (Phase 2b-B)]\n");
    {
        App7SessionTracker *t = app7_session_new();
        assert_label("session_new returns non-NULL", t != NULL);

        int64_t out[9];
        app7_session_start_app(t, 1000);
        int n0 = app7_session_end_app(t, 1500, out);
        assert_label("end_app after start_app emits 1 (APP)", n0 == 1);
        assert_label("APP session type code = 2", out[0] == 2);
        assert_label("APP started_at = 1000", out[1] == 1000);
        assert_label("APP ended_at = 1500", out[2] == 1500);

        app7_session_start_app(t, 2000);
        int prev = app7_session_start_chapter(t, 2100, out);
        assert_label("start_chapter w/ no prior chapter emits 0", prev == 0);

        prev = app7_session_start_page(t, 2200, out);
        assert_label("start_page w/ no prior page emits 0", prev == 0);

        /* Now start a new page; expect the prior PAGE to close. */
        prev = app7_session_start_page(t, 2300, out);
        assert_label("start_page w/ prior page emits 1 PAGE", prev == 1);
        assert_label("emitted PAGE started_at = 2200", out[1] == 2200);

        /* Start a new chapter; expect the prior PAGE + CHAPTER to close. */
        prev = app7_session_start_chapter(t, 2400, out);
        assert_label("start_chapter emits 2 (PAGE+CHAPTER)", prev == 2);
        assert_label("first emitted = PAGE (type 0)", out[0] == 0);
        assert_label("second emitted = CHAPTER (type 1)", out[3] == 1);

        /* End app; expect remaining CHAPTER+APP to close (PAGE already closed). */
        prev = app7_session_end_app(t, 2500, out);
        assert_label("end_app closes remaining CHAPTER+APP (count=2)", prev == 2);
        assert_label("first = CHAPTER", out[0] == 1);
        assert_label("second = APP", out[3] == 2);

        /* Elapsed after end_app: all -1. */
        int64_t el[3];
        app7_session_elapsed(t, 2600, el);
        assert_label("elapsed app = -1 after end", el[0] == -1);
        assert_label("elapsed chapter = -1 after end", el[1] == -1);
        assert_label("elapsed page = -1 after end", el[2] == -1);

        /* Elapsed during open session. */
        app7_session_start_app(t, 3000);
        app7_session_start_page(t, 3100, out);  /* discard */
        app7_session_elapsed(t, 3200, el);
        assert_label("elapsed app = 200", el[0] == 200);
        assert_label("elapsed chapter = -1 (no chapter open)", el[1] == -1);
        assert_label("elapsed page = 100", el[2] == 100);

        app7_session_free(t);
        assert_label("session_free(NULL) is a no-op",
                     (app7_session_free(NULL), 1));
    }

    /* ───────────────────────────────────────────────────────────── */
    printf("[ImageSequenceNavigator (Phase 2b-C)]\n");
    {
        App7Navigator *nv = app7_nav_new();
        assert_label("nav_new returns non-NULL", nv != NULL);
        /* 10 images split into 3 chapters: [0..2], [3..5], [6..9] */
        int starts[] = { 0, 3, 6 };
        app7_nav_initialize(nv, 10, starts, 3);
        assert_label("size = 10", app7_nav_size(nv) == 10);
        assert_label("chapter_count = 3", app7_nav_chapter_count(nv) == 3);
        assert_label("position = -1 initially", app7_nav_position(nv) == -1);
        assert_label("can go forward initially", app7_nav_can_go_forward(nv) == 1);
        assert_label("cannot go back initially", app7_nav_can_go_back(nv) == 0);

        int i = app7_nav_next(nv);
        assert_label("next from -1 -> 0", i == 0);
        i = app7_nav_next(nv);
        assert_label("next -> 1", i == 1);
        assert_label("current_chapter_index at 1 = 0", app7_nav_current_chapter_index(nv) == 0);

        i = app7_nav_next_chapter(nv);
        assert_label("next_chapter from ch0 -> idx 3 (start of ch1)", i == 3);
        assert_label("current_chapter_index = 1", app7_nav_current_chapter_index(nv) == 1);

        i = app7_nav_next_chapter(nv);
        assert_label("next_chapter -> idx 6 (start of ch2)", i == 6);
        i = app7_nav_next_chapter(nv);
        assert_label("next_chapter at last chapter returns -1", i == -1);

        i = app7_nav_previous_chapter(nv);
        assert_label("previous_chapter -> idx 3", i == 3);

        i = app7_nav_go_to_chapter(nv, 0);
        assert_label("go_to_chapter(0) -> idx 0", i == 0);
        i = app7_nav_go_to_chapter(nv, 99);
        assert_label("go_to_chapter(99) returns -1 (OOB)", i == -1);

        i = app7_nav_restore_position(nv, 5);
        assert_label("restore_position(5) -> 5", i == 5);
        i = app7_nav_restore_position(nv, -1);
        assert_label("restore_position(-1) returns -1", i == -1);
        i = app7_nav_restore_position(nv, 100);
        assert_label("restore_position(100) returns -1", i == -1);

        /* After restore to 5, we're in chapter 1 (idx range 3..5). */
        app7_nav_restore_position(nv, 5);
        assert_label("at idx 5: current_chapter_index = 1",
                     app7_nav_current_chapter_index(nv) == 1);
        assert_label("at idx 5: can_go_forward = 1",
                     app7_nav_can_go_forward(nv) == 1);
        assert_label("at idx 5: can_go_next_chapter = 1",
                     app7_nav_can_go_next_chapter(nv) == 1);

        app7_nav_restore_position(nv, 9);
        assert_label("at idx 9: can_go_forward = 0",
                     app7_nav_can_go_forward(nv) == 0);
        assert_label("at idx 9: can_go_back = 1",
                     app7_nav_can_go_back(nv) == 1);
        assert_label("at idx 9: can_go_next_chapter = 0",
                     app7_nav_can_go_next_chapter(nv) == 0);

        /* Re-initialize with different chapter layout — should reset cleanly. */
        int starts2[] = { 0 };
        app7_nav_initialize(nv, 5, starts2, 1);
        assert_label("re-init: size = 5", app7_nav_size(nv) == 5);
        assert_label("re-init: chapter_count = 1", app7_nav_chapter_count(nv) == 1);
        assert_label("re-init: position back to -1", app7_nav_position(nv) == -1);

        app7_nav_free(nv);
        assert_label("nav_free(NULL) is a no-op",
                     (app7_nav_free(NULL), 1));
    }

    printf("── %d/%d passed ──\n", g_total - g_failed, g_total);
    return g_failed == 0 ? 0 : 1;
}
