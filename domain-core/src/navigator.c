/* ═══════════════════════════════════════════════════════════════════════
 *  navigator.c — C projection of ImageSequenceNavigator.
 *
 *  Cascade source of truth:
 *    shared/src/commonMain/.../domain/navigation/ImageSequenceNavigator.kt
 *
 *  Design notes:
 *  - State held: size, current index (-1 = before first), sorted
 *    chapter_starts[].  ImageItem list and chapter NAMES stay in Kotlin.
 *  - Functions return the resulting index, or -1 if the move was
 *    rejected (out of range / no chapter / etc.) — Kotlin then looks
 *    up images[idx] in its own list.
 *  - The struct owns its `chapter_starts` heap buffer; `initialize`
 *    reallocates as needed.
 * ═══════════════════════════════════════════════════════════════════════ */
#include "app7_domain.h"
#include <stdlib.h>
#include <string.h>

struct App7Navigator {
    int  size;
    int  index;             /* -1 = before first */
    int *chapter_starts;
    int  chapter_count;
    int  chapter_capacity;
};

App7Navigator *app7_nav_new(void) {
    App7Navigator *n = (App7Navigator *)malloc(sizeof(*n));
    if (n == NULL) return NULL;
    n->size = 0;
    n->index = -1;
    n->chapter_starts = NULL;
    n->chapter_count = 0;
    n->chapter_capacity = 0;
    return n;
}

void app7_nav_free(App7Navigator *n) {
    if (n == NULL) return;
    free(n->chapter_starts);
    free(n);
}

void app7_nav_initialize(App7Navigator *n, int total_size,
                         const int *chapter_starts, int chapter_count) {
    if (n == NULL) return;
    if (total_size < 0) total_size = 0;
    if (chapter_count < 0) chapter_count = 0;

    n->size = total_size;
    n->index = -1;

    if (chapter_count > n->chapter_capacity) {
        int *grown = (int *)realloc(n->chapter_starts,
                                    (size_t)chapter_count * sizeof(int));
        if (grown == NULL) {
            /* Allocation failed — degrade gracefully: no chapter index. */
            free(n->chapter_starts);
            n->chapter_starts = NULL;
            n->chapter_capacity = 0;
            n->chapter_count = 0;
            return;
        }
        n->chapter_starts = grown;
        n->chapter_capacity = chapter_count;
    }
    if (chapter_count > 0 && chapter_starts != NULL) {
        memcpy(n->chapter_starts, chapter_starts,
               (size_t)chapter_count * sizeof(int));
    }
    n->chapter_count = chapter_count;
}

int app7_nav_size                  (App7Navigator *n) { return n ? n->size : 0; }
int app7_nav_position              (App7Navigator *n) { return n ? n->index : -1; }
int app7_nav_chapter_count         (App7Navigator *n) { return n ? n->chapter_count : 0; }

/* Binary search: largest chapter_starts index s.t. value <= n->index. */
int app7_nav_current_chapter_index(App7Navigator *n) {
    if (n == NULL || n->chapter_count == 0 || n->index < 0) return 0;
    int lo = 0, hi = n->chapter_count - 1;
    while (lo < hi) {
        int mid = (lo + hi + 1) / 2;
        if (n->chapter_starts[mid] <= n->index) lo = mid;
        else hi = mid - 1;
    }
    return lo;
}

int app7_nav_next(App7Navigator *n) {
    if (n == NULL || n->size == 0) return -1;
    if (n->index < n->size - 1) {
        n->index++;
        return n->index;
    }
    return -1;
}

int app7_nav_previous(App7Navigator *n) {
    if (n == NULL || n->size == 0) return -1;
    if (n->index > 0) {
        n->index--;
        return n->index;
    }
    return -1;
}

int app7_nav_next_chapter(App7Navigator *n) {
    if (n == NULL || n->size == 0 || n->chapter_count == 0) return -1;
    int ci = app7_nav_current_chapter_index(n);
    if (ci < n->chapter_count - 1) {
        n->index = n->chapter_starts[ci + 1];
        return n->index;
    }
    return -1;
}

int app7_nav_previous_chapter(App7Navigator *n) {
    if (n == NULL || n->size == 0 || n->chapter_count == 0) return -1;
    int ci = app7_nav_current_chapter_index(n);
    if (ci > 0) {
        n->index = n->chapter_starts[ci - 1];
        return n->index;
    }
    return -1;
}

int app7_nav_go_to_chapter(App7Navigator *n, int chapter_idx) {
    if (n == NULL || chapter_idx < 0 || chapter_idx >= n->chapter_count) return -1;
    n->index = n->chapter_starts[chapter_idx];
    return n->index;
}

int app7_nav_restore_position(App7Navigator *n, int saved_idx) {
    if (n == NULL || n->size == 0 || saved_idx < 0 || saved_idx >= n->size) return -1;
    n->index = saved_idx;
    return n->index;
}

int app7_nav_can_go_forward         (App7Navigator *n) {
    return (n != NULL) && (n->size != 0) && (n->index < n->size - 1) ? 1 : 0;
}
int app7_nav_can_go_back            (App7Navigator *n) {
    return (n != NULL) && (n->index > 0) ? 1 : 0;
}
int app7_nav_can_go_next_chapter    (App7Navigator *n) {
    if (n == NULL || n->chapter_count == 0) return 0;
    return app7_nav_current_chapter_index(n) < n->chapter_count - 1 ? 1 : 0;
}
int app7_nav_can_go_previous_chapter(App7Navigator *n) {
    if (n == NULL || n->chapter_count == 0) return 0;
    return app7_nav_current_chapter_index(n) > 0 ? 1 : 0;
}
