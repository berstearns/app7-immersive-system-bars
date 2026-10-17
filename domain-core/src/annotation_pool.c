/* ═══════════════════════════════════════════════════════════════════════
 *  annotation_pool.c — C projection of DefaultAnnotationPool + sampler.
 *
 *  Cascade source of truth: domain-haskell/App7Domain.hs
 *  (defaultAnnotationPool).  Adding / reordering / renaming a pool entry
 *  here MUST be mirrored in App7Domain.hs in the same commit.
 *
 *  The RNG is xoshiro256** (Blackman & Vigna 2018 — small, fast,
 *  excellent statistical properties; trivially seedable from a single
 *  uint64_t via splitmix64).  We don't try to match Kotlin's Random
 *  (it's a Mersenne-Twister-shaped algorithm with different output for
 *  the same seed); parity is asserted at the SEMANTIC level (sample is
 *  k distinct indices in [0, pool_size), deterministic per seed) rather
 *  than byte-for-byte against Kotlin.
 * ═══════════════════════════════════════════════════════════════════════ */
#include "app7_domain.h"
#include <stddef.h>
#include <stdint.h>

/* ── Pool data — keep in lock-step with App7Domain.defaultAnnotationPool */
static const char * const POOL_LABELS[] = {
    "understood", "partially", "not_understood", "need_re_read",
    "key_idiom", "vocabulary", "grammar", "surprising",
    "funny", "sad", "agree", "disagree", "confused",
    "follow_up_question", "character_intro", "plot_beat", "callback",
};
static const char * const POOL_DISPLAYS[] = {
    "Got it", "Partial", "Didn't get it", "Re-read",
    "Idiom", "Vocab", "Grammar", "Surprising",
    "Funny", "Sad", "Agree", "Disagree", "Confused",
    "Question", "Character", "Plot beat", "Callback",
};
#define POOL_SIZE ((int)(sizeof(POOL_LABELS) / sizeof(POOL_LABELS[0])))

int app7_annotation_pool_size(void) { return POOL_SIZE; }

const char *app7_annotation_pool_label(int index) {
    if (index < 0 || index >= POOL_SIZE) return NULL;
    return POOL_LABELS[index];
}
const char *app7_annotation_pool_display(int index) {
    if (index < 0 || index >= POOL_SIZE) return NULL;
    return POOL_DISPLAYS[index];
}

/* ── xoshiro256** state + transitions ───────────────────────────────────
 * Spec: https://prng.di.unimi.it/xoshiro256starstar.c (public domain).
 * splitmix64 used only to spread a single seed into the 4-word state. */

static uint64_t rotl(uint64_t x, int k) { return (x << k) | (x >> (64 - k)); }

static uint64_t splitmix64(uint64_t *x) {
    uint64_t z = (*x += 0x9E3779B97F4A7C15ULL);
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
    return z ^ (z >> 31);
}

static void xoshiro_seed(uint64_t seed, uint64_t s[4]) {
    uint64_t state = seed;
    /* Avoid an all-zero state if seed=0: splitmix64(0+0x9E37...) is fine. */
    s[0] = splitmix64(&state);
    s[1] = splitmix64(&state);
    s[2] = splitmix64(&state);
    s[3] = splitmix64(&state);
}

static uint64_t xoshiro_next(uint64_t s[4]) {
    const uint64_t result = rotl(s[1] * 5, 7) * 9;
    const uint64_t t = s[1] << 17;
    s[2] ^= s[0];
    s[3] ^= s[1];
    s[1] ^= s[2];
    s[0] ^= s[3];
    s[2] ^= t;
    s[3] = rotl(s[3], 45);
    return result;
}

/* ── Sampler ───────────────────────────────────────────────────────────
 * Fisher-Yates partial shuffle: build [0, POOL_SIZE) in a scratch array,
 * then swap forwards for k positions, writing the front k to out_indices.
 *
 * No malloc — POOL_SIZE is a compile-time constant (17) so the scratch
 * lives on the stack. */

int app7_sample_annotation_indices(uint64_t seed, int k, int *out_indices) {
    if (k <= 0 || out_indices == NULL) return 0;
    if (k > POOL_SIZE) k = POOL_SIZE;

    int scratch[POOL_SIZE];
    for (int i = 0; i < POOL_SIZE; ++i) scratch[i] = i;

    uint64_t s[4];
    xoshiro_seed(seed, s);

    for (int i = 0; i < k; ++i) {
        /* Pick j uniformly in [i, POOL_SIZE).  Modulo is fine for a 17-
         * element range — bias from a 64-bit RNG is negligible. */
        int range = POOL_SIZE - i;
        int j = i + (int)(xoshiro_next(s) % (uint64_t)range);
        int tmp = scratch[i];
        scratch[i] = scratch[j];
        scratch[j] = tmp;
        out_indices[i] = scratch[i];
    }
    return k;
}
