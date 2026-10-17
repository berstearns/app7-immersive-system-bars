/* ═══════════════════════════════════════════════════════════════════════
 *  app7_domain_jni.c — JNI shim around domain-core/.
 *
 *  Symbol naming follows the JNI convention:
 *    Java_<package-with-underscores>_<class>_<method>
 *  matching `package pl.czak.learnlauncher.domain.native; object App7Native`.
 *
 *  Each shim:
 *    1) Marshals jXxx → C primitive.
 *    2) Calls the corresponding `app7_*` function in domain-core/.
 *    3) Marshals back to jXxx.
 *  No allocation except for the FloatArray return in `clamped`.
 *
 *  ABI assumption: jint == int32_t == int and jfloat == float on every
 *  ABI we target (x86_64, arm64-v8a).  Documented; verified by the
 *  Phase-3a emulator smoke test.
 * ═══════════════════════════════════════════════════════════════════════ */
#include <jni.h>
#include <stdint.h>
#include "app7_domain.h"

#define JNI_FN(name) \
    Java_pl_czak_learnlauncher_domain_native_App7Native_##name

/* JNI mangling note: `.` in the package becomes `_`, but the `_1` escape
 * (per JNI spec §11) only applies to underscores in the ORIGINAL Java
 * identifier — not to the separator characters the mangler itself
 * inserts.  Our package segment `native` has no underscore, so the
 * mangled prefix is plain `Java_pl_..._domain_native_App7Native_`.
 * Verified empirically: the JVM probes
 * `Java_pl_czak_learnlauncher_domain_native_App7Native_isValidPermutation`
 * (without `_1`) before falling back to the overloaded signature form. */

/* ── CropSelection — 6 scalar predicates ──────────────────────────────── */

JNIEXPORT jfloat JNICALL JNI_FN(normalizedX)(
    JNIEnv *env, jclass clazz,
    jfloat sx, jfloat sy, jfloat ex, jfloat ey) {
    (void)env; (void)clazz;
    return app7_crop_normalized_x(sx, sy, ex, ey);
}

JNIEXPORT jfloat JNICALL JNI_FN(normalizedY)(
    JNIEnv *env, jclass clazz,
    jfloat sx, jfloat sy, jfloat ex, jfloat ey) {
    (void)env; (void)clazz;
    return app7_crop_normalized_y(sx, sy, ex, ey);
}

JNIEXPORT jfloat JNICALL JNI_FN(normalizedWidth)(
    JNIEnv *env, jclass clazz,
    jfloat sx, jfloat sy, jfloat ex, jfloat ey) {
    (void)env; (void)clazz;
    return app7_crop_normalized_width(sx, sy, ex, ey);
}

JNIEXPORT jfloat JNICALL JNI_FN(normalizedHeight)(
    JNIEnv *env, jclass clazz,
    jfloat sx, jfloat sy, jfloat ex, jfloat ey) {
    (void)env; (void)clazz;
    return app7_crop_normalized_height(sx, sy, ex, ey);
}

JNIEXPORT jfloat JNICALL JNI_FN(centerX)(
    JNIEnv *env, jclass clazz,
    jfloat sx, jfloat sy, jfloat ex, jfloat ey) {
    (void)env; (void)clazz;
    return app7_crop_center_x(sx, sy, ex, ey);
}

JNIEXPORT jfloat JNICALL JNI_FN(centerY)(
    JNIEnv *env, jclass clazz,
    jfloat sx, jfloat sy, jfloat ex, jfloat ey) {
    (void)env; (void)clazz;
    return app7_crop_center_y(sx, sy, ex, ey);
}

JNIEXPORT jboolean JNICALL JNI_FN(isDegenerate)(
    JNIEnv *env, jclass clazz,
    jfloat min_size, jfloat sx, jfloat sy, jfloat ex, jfloat ey) {
    (void)env; (void)clazz;
    return app7_crop_is_degenerate(min_size, sx, sy, ex, ey) ? JNI_TRUE : JNI_FALSE;
}

/* ── CropSelection.clamped — returns FloatArray of 4 ──────────────────── */

JNIEXPORT jfloatArray JNICALL JNI_FN(clamped)(
    JNIEnv *env, jclass clazz,
    jfloat sx, jfloat sy, jfloat ex, jfloat ey) {
    (void)clazz;
    jfloat out[4];
    app7_crop_clamped(sx, sy, ex, ey, &out[0], &out[1], &out[2], &out[3]);

    jfloatArray result = (*env)->NewFloatArray(env, 4);
    if (result == NULL) return NULL;  /* OOM — JNI throws on return */
    (*env)->SetFloatArrayRegion(env, result, 0, 4, out);
    return result;
}

/* ── FocusTransform.compute — returns FloatArray of 4 ─────────────────── */

JNIEXPORT jfloatArray JNICALL JNI_FN(focusTransform)(
    JNIEnv *env, jclass clazz,
    jfloat rx, jfloat ry, jfloat rw, jfloat rh,
    jfloat pw, jfloat ph, jfloat sw, jfloat sh) {
    (void)clazz;
    jfloat out[4];
    app7_focus_transform(rx, ry, rw, rh, pw, ph, sw, sh,
                         &out[0], &out[1], &out[2], &out[3]);

    jfloatArray result = (*env)->NewFloatArray(env, 4);
    if (result == NULL) return NULL;  /* OOM — JNI throws on return */
    (*env)->SetFloatArrayRegion(env, result, 0, 4, out);
    return result;
}

/* ── ConversationBox.contains ─────────────────────────────────────────── */

JNIEXPORT jboolean JNICALL JNI_FN(boxContains)(
    JNIEnv *env, jclass clazz,
    jfloat nx, jfloat ny,
    jfloat bx, jfloat by, jfloat bw, jfloat bh) {
    (void)env; (void)clazz;
    return app7_box_contains(nx, ny, bx, by, bw, bh) ? JNI_TRUE : JNI_FALSE;
}

/* ── PageBubbleOrdering.isValidPermutation ────────────────────────────── */

JNIEXPORT jboolean JNICALL JNI_FN(isValidPermutation)(
    JNIEnv *env, jclass clazz,
    jintArray indices, jint domain_n) {
    (void)clazz;
    jsize n = (*env)->GetArrayLength(env, indices);

    /* GetIntArrayElements gives us a (possibly-pinned) pointer.  JNI_ABORT
     * on release skips the copy-back (we only read). */
    jint *elems = (*env)->GetIntArrayElements(env, indices, NULL);
    if (elems == NULL) return JNI_FALSE;   /* OOM */

    /* jint == int32_t == int on every ABI we target.  See file header. */
    int result = app7_is_valid_permutation((const int *)elems,
                                           (size_t)n, (int)domain_n);

    (*env)->ReleaseIntArrayElements(env, indices, elems, JNI_ABORT);
    return result ? JNI_TRUE : JNI_FALSE;
}

/* ── Annotation pool + sampler (Phase 3b) ──────────────────────────── */

JNIEXPORT jint JNICALL JNI_FN(annotationPoolSize)(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return app7_annotation_pool_size();
}

JNIEXPORT jstring JNICALL JNI_FN(annotationPoolLabel)(
    JNIEnv *env, jclass clazz, jint index) {
    (void)clazz;
    const char *s = app7_annotation_pool_label(index);
    return s ? (*env)->NewStringUTF(env, s) : NULL;
}

JNIEXPORT jstring JNICALL JNI_FN(annotationPoolDisplay)(
    JNIEnv *env, jclass clazz, jint index) {
    (void)clazz;
    const char *s = app7_annotation_pool_display(index);
    return s ? (*env)->NewStringUTF(env, s) : NULL;
}

JNIEXPORT jintArray JNICALL JNI_FN(sampleAnnotationIndices)(
    JNIEnv *env, jclass clazz, jlong seed, jint k) {
    (void)clazz;
    /* POOL_SIZE is 17 in domain-core; cap defensively here too. */
    int scratch[64];
    int wanted = (int)k;
    if (wanted < 0) wanted = 0;
    if (wanted > (int)(sizeof(scratch) / sizeof(scratch[0]))) {
        wanted = (int)(sizeof(scratch) / sizeof(scratch[0]));
    }
    int n = app7_sample_annotation_indices((uint64_t)seed, wanted, scratch);

    jintArray result = (*env)->NewIntArray(env, n);
    if (result == NULL) return NULL;
    if (n > 0) {
        (*env)->SetIntArrayRegion(env, result, 0, n, (const jint *)scratch);
    }
    return result;
}

/* ── SessionTimeTracker (Phase 3b-B) ──────────────────────────────── */
/* Opaque handle marshalled as jlong (= pointer-as-integer).  Kotlin
 * wrapper owns its lifetime — explicit close() invokes Free().
 * The CompletedSession's chapter_name/page_id/page_title strings live
 * in Kotlin; here we marshal just the (type, started_at, ended_at)
 * triplets via a Kotlin-allocated LongArray. */

JNIEXPORT jlong JNICALL JNI_FN(sessionTrackerNew)(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return (jlong)(intptr_t)app7_session_new();
}

JNIEXPORT void JNICALL JNI_FN(sessionTrackerFree)(
    JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    app7_session_free((App7SessionTracker *)(intptr_t)handle);
}

JNIEXPORT void JNICALL JNI_FN(sessionTrackerStartApp)(
    JNIEnv *env, jclass clazz, jlong handle, jlong now_ms) {
    (void)env; (void)clazz;
    app7_session_start_app((App7SessionTracker *)(intptr_t)handle, now_ms);
}

/* Returns count (0..3); fills out_longs[0 .. count*3-1] with triplets. */
JNIEXPORT jint JNICALL JNI_FN(sessionTrackerEndApp)(
    JNIEnv *env, jclass clazz, jlong handle, jlong now_ms, jlongArray out_longs) {
    (void)clazz;
    int64_t scratch[9];
    int n = app7_session_end_app((App7SessionTracker *)(intptr_t)handle, now_ms, scratch);
    if (n > 0) {
        (*env)->SetLongArrayRegion(env, out_longs, 0, n * 3, (const jlong *)scratch);
    }
    return n;
}

JNIEXPORT jint JNICALL JNI_FN(sessionTrackerStartChapter)(
    JNIEnv *env, jclass clazz, jlong handle, jlong now_ms, jlongArray out_longs) {
    (void)clazz;
    int64_t scratch[6];
    int n = app7_session_start_chapter((App7SessionTracker *)(intptr_t)handle, now_ms, scratch);
    if (n > 0) {
        (*env)->SetLongArrayRegion(env, out_longs, 0, n * 3, (const jlong *)scratch);
    }
    return n;
}

JNIEXPORT jint JNICALL JNI_FN(sessionTrackerStartPage)(
    JNIEnv *env, jclass clazz, jlong handle, jlong now_ms, jlongArray out_longs) {
    (void)clazz;
    int64_t scratch[3];
    int n = app7_session_start_page((App7SessionTracker *)(intptr_t)handle, now_ms, scratch);
    if (n > 0) {
        (*env)->SetLongArrayRegion(env, out_longs, 0, n * 3, (const jlong *)scratch);
    }
    return n;
}

JNIEXPORT void JNICALL JNI_FN(sessionTrackerElapsed)(
    JNIEnv *env, jclass clazz, jlong handle, jlong now_ms, jlongArray out_longs) {
    (void)clazz;
    int64_t scratch[3];
    app7_session_elapsed((App7SessionTracker *)(intptr_t)handle, now_ms, scratch);
    (*env)->SetLongArrayRegion(env, out_longs, 0, 3, (const jlong *)scratch);
}

/* ── ImageSequenceNavigator (Phase 3b-C) ─────────────────────────────── */
/* Opaque handle as jlong.  ImageItem data + chapter NAMES stay in Kotlin;
 * here we only marshal indices. */

#define NAV(h) ((App7Navigator *)(intptr_t)(h))

JNIEXPORT jlong JNICALL JNI_FN(navNew)(JNIEnv *env, jclass c) {
    (void)env; (void)c;
    return (jlong)(intptr_t)app7_nav_new();
}
JNIEXPORT void JNICALL JNI_FN(navFree)(JNIEnv *env, jclass c, jlong h) {
    (void)env; (void)c;
    app7_nav_free(NAV(h));
}

JNIEXPORT void JNICALL JNI_FN(navInitialize)(
    JNIEnv *env, jclass c, jlong h, jint total_size, jintArray chapter_starts) {
    (void)c;
    jsize n_chapters = (chapter_starts != NULL)
        ? (*env)->GetArrayLength(env, chapter_starts) : 0;
    jint *cs = NULL;
    if (n_chapters > 0) {
        cs = (*env)->GetIntArrayElements(env, chapter_starts, NULL);
        if (cs == NULL) return;
    }
    app7_nav_initialize(NAV(h), (int)total_size, (const int *)cs, (int)n_chapters);
    if (cs != NULL) {
        (*env)->ReleaseIntArrayElements(env, chapter_starts, cs, JNI_ABORT);
    }
}

JNIEXPORT jint JNICALL JNI_FN(navSize)(JNIEnv *env, jclass c, jlong h) {
    (void)env; (void)c; return app7_nav_size(NAV(h));
}
JNIEXPORT jint JNICALL JNI_FN(navPosition)(JNIEnv *env, jclass c, jlong h) {
    (void)env; (void)c; return app7_nav_position(NAV(h));
}
JNIEXPORT jint JNICALL JNI_FN(navChapterCount)(JNIEnv *env, jclass c, jlong h) {
    (void)env; (void)c; return app7_nav_chapter_count(NAV(h));
}
JNIEXPORT jint JNICALL JNI_FN(navCurrentChapterIndex)(JNIEnv *env, jclass c, jlong h) {
    (void)env; (void)c; return app7_nav_current_chapter_index(NAV(h));
}
JNIEXPORT jint JNICALL JNI_FN(navNext)(JNIEnv *env, jclass c, jlong h) {
    (void)env; (void)c; return app7_nav_next(NAV(h));
}
JNIEXPORT jint JNICALL JNI_FN(navPrevious)(JNIEnv *env, jclass c, jlong h) {
    (void)env; (void)c; return app7_nav_previous(NAV(h));
}
JNIEXPORT jint JNICALL JNI_FN(navNextChapter)(JNIEnv *env, jclass c, jlong h) {
    (void)env; (void)c; return app7_nav_next_chapter(NAV(h));
}
JNIEXPORT jint JNICALL JNI_FN(navPreviousChapter)(JNIEnv *env, jclass c, jlong h) {
    (void)env; (void)c; return app7_nav_previous_chapter(NAV(h));
}
JNIEXPORT jint JNICALL JNI_FN(navGoToChapter)(JNIEnv *env, jclass c, jlong h, jint idx) {
    (void)env; (void)c; return app7_nav_go_to_chapter(NAV(h), (int)idx);
}
JNIEXPORT jint JNICALL JNI_FN(navRestorePosition)(JNIEnv *env, jclass c, jlong h, jint idx) {
    (void)env; (void)c; return app7_nav_restore_position(NAV(h), (int)idx);
}
JNIEXPORT jboolean JNICALL JNI_FN(navCanGoForward)(JNIEnv *env, jclass c, jlong h) {
    (void)env; (void)c; return app7_nav_can_go_forward(NAV(h)) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL JNI_FN(navCanGoBack)(JNIEnv *env, jclass c, jlong h) {
    (void)env; (void)c; return app7_nav_can_go_back(NAV(h)) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL JNI_FN(navCanGoNextChapter)(JNIEnv *env, jclass c, jlong h) {
    (void)env; (void)c; return app7_nav_can_go_next_chapter(NAV(h)) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL JNI_FN(navCanGoPreviousChapter)(JNIEnv *env, jclass c, jlong h) {
    (void)env; (void)c; return app7_nav_can_go_previous_chapter(NAV(h)) ? JNI_TRUE : JNI_FALSE;
}
