/* ═══════════════════════════════════════════════════════════════════════
 *  session_tracker.c — C projection of SessionTimeTracker.
 *
 *  Cascade source of truth (state shape + transition rules):
 *    shared/src/commonMain/.../domain/session/SessionTimeTracker.kt
 *
 *  Design choice — opaque handle, NOT pure function:
 *  The Kotlin original is a tiny mutable state machine with a clear
 *  invariant (page nested in chapter nested in app).  An opaque struct
 *  mirrors that 1:1 and keeps the JNI surface small (state-as-pointer,
 *  events-as-calls).  We pay one malloc per Activity/Window lifetime —
 *  cheap.  Strings (chapter name, page id/title) stay in Kotlin; C only
 *  holds the timestamps, which is the part with actual transition logic.
 *
 *  The Kotlin wrapper builds CompletedSession objects from the (type,
 *  started_at, ended_at) triplets the C side emits, attaching its own
 *  stored strings.  This keeps JNI marshalling primitive — no jstring
 *  arrays per call.
 * ═══════════════════════════════════════════════════════════════════════ */
#include "app7_domain.h"
#include <stdlib.h>     /* malloc, free */
#include <stdint.h>

struct App7SessionTracker {
    int64_t app_start;       /* 0 = not started */
    int64_t chapter_start;
    int64_t page_start;
};

#define SESSION_TYPE_PAGE    0
#define SESSION_TYPE_CHAPTER 1
#define SESSION_TYPE_APP     2

App7SessionTracker *app7_session_new(void) {
    App7SessionTracker *t = (App7SessionTracker *)malloc(sizeof(*t));
    if (t == NULL) return NULL;
    t->app_start     = 0;
    t->chapter_start = 0;
    t->page_start    = 0;
    return t;
}

void app7_session_free(App7SessionTracker *t) {
    free(t);   /* free(NULL) is well-defined as a no-op */
}

void app7_session_start_app(App7SessionTracker *t, int64_t now_ms) {
    if (t == NULL) return;
    t->app_start = now_ms;
}

/* Helper: write a triplet at position `slot` and return new slot count. */
static int emit(int64_t *out, int slot, int type, int64_t started, int64_t ended) {
    out[slot * 3 + 0] = type;
    out[slot * 3 + 1] = started;
    out[slot * 3 + 2] = ended;
    return slot + 1;
}

int app7_session_end_app(App7SessionTracker *t, int64_t now_ms, int64_t *out) {
    if (t == NULL || out == NULL) return 0;
    int n = 0;
    if (t->page_start) {
        n = emit(out, n, SESSION_TYPE_PAGE, t->page_start, now_ms);
        t->page_start = 0;
    }
    if (t->chapter_start) {
        n = emit(out, n, SESSION_TYPE_CHAPTER, t->chapter_start, now_ms);
        t->chapter_start = 0;
    }
    if (t->app_start) {
        n = emit(out, n, SESSION_TYPE_APP, t->app_start, now_ms);
        t->app_start = 0;
    }
    return n;
}

int app7_session_start_chapter(App7SessionTracker *t, int64_t now_ms, int64_t *out) {
    if (t == NULL || out == NULL) return 0;
    int n = 0;
    if (t->page_start) {
        n = emit(out, n, SESSION_TYPE_PAGE, t->page_start, now_ms);
        t->page_start = 0;
    }
    if (t->chapter_start) {
        n = emit(out, n, SESSION_TYPE_CHAPTER, t->chapter_start, now_ms);
    }
    t->chapter_start = now_ms;
    return n;
}

int app7_session_start_page(App7SessionTracker *t, int64_t now_ms, int64_t *out) {
    if (t == NULL || out == NULL) return 0;
    int n = 0;
    if (t->page_start) {
        n = emit(out, n, SESSION_TYPE_PAGE, t->page_start, now_ms);
    }
    t->page_start = now_ms;
    return n;
}

void app7_session_elapsed(App7SessionTracker *t, int64_t now_ms, int64_t *out) {
    if (out == NULL) return;
    if (t == NULL) {
        out[0] = out[1] = out[2] = -1;
        return;
    }
    out[0] = t->app_start     ? (now_ms - t->app_start)     : -1;
    out[1] = t->chapter_start ? (now_ms - t->chapter_start) : -1;
    out[2] = t->page_start    ? (now_ms - t->page_start)    : -1;
}
