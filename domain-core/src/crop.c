/* crop.c — C projection of App7Domain.hs CropSelection predicates.
 *
 * Each function mirrors a Haskell function 1:1.  Cascade rule: edit
 * App7Domain.hs first, then this file, then add a round_trip.c case.
 */
#include "app7_domain.h"

/* Local helper: same semantics as min/max but inline-friendly and explicit. */
static inline float fmin_f(float a, float b) { return a < b ? a : b; }
static inline float fmax_f(float a, float b) { return a > b ? a : b; }
static inline float fabs_f(float a)          { return a < 0 ? -a : a; }
static inline float clamp01_f(float a)       { return fmax_f(0.0f, fmin_f(1.0f, a)); }

float app7_crop_normalized_x(float sx, float sy, float ex, float ey) {
    (void)sy; (void)ey;
    return fmin_f(sx, ex);
}

float app7_crop_normalized_y(float sx, float sy, float ex, float ey) {
    (void)sx; (void)ex;
    return fmin_f(sy, ey);
}

float app7_crop_normalized_width(float sx, float sy, float ex, float ey) {
    (void)sy; (void)ey;
    return fabs_f(ex - sx);
}

float app7_crop_normalized_height(float sx, float sy, float ex, float ey) {
    (void)sx; (void)ex;
    return fabs_f(ey - sy);
}

float app7_crop_center_x(float sx, float sy, float ex, float ey) {
    return app7_crop_normalized_x(sx, sy, ex, ey)
         + app7_crop_normalized_width(sx, sy, ex, ey) / 2.0f;
}

float app7_crop_center_y(float sx, float sy, float ex, float ey) {
    return app7_crop_normalized_y(sx, sy, ex, ey)
         + app7_crop_normalized_height(sx, sy, ex, ey) / 2.0f;
}

int app7_crop_is_degenerate(float min_size,
                            float sx, float sy, float ex, float ey) {
    /* Strict `<` matches Haskell `normalizedWidth c < ms || ...` and
     * Kotlin `normalizedWidth() < minSize || ...`.  Boundary equality
     * is NOT degenerate. */
    return (app7_crop_normalized_width (sx, sy, ex, ey) < min_size)
        || (app7_crop_normalized_height(sx, sy, ex, ey) < min_size);
}

void app7_crop_clamped(float sx, float sy, float ex, float ey,
                       float *out_sx, float *out_sy,
                       float *out_ex, float *out_ey) {
    /* Compute into locals first so callers may safely pass aliased
     * out-pointers (e.g. all four pointing into one struct field). */
    const float csx = clamp01_f(sx);
    const float csy = clamp01_f(sy);
    const float cex = clamp01_f(ex);
    const float cey = clamp01_f(ey);
    *out_sx = csx;
    *out_sy = csy;
    *out_ex = cex;
    *out_ey = cey;
}

/* app7_focus_transform — C projection of App7Domain.hs focusTransform.
 *
 * Scene-focus fit math: choose rotation 0 or 90 (whichever contain-fits
 * larger), the contain scale (capped), and the centering translate.
 * Kept byte-for-byte with FocusTransform.pureFallback (Kotlin), the
 * Haskell oracle, and domain-rust/src/crop.rs focus_transform (the
 * algebraic source of truth — this C stays only for the standalone
 * round_trip oracle).  rx/ry are part of the sceneRect contract but the
 * maximize-and-center transform does not depend on the scene's page
 * position, so they are unused here. */
void app7_focus_transform(float rx, float ry, float rw, float rh,
                          float pw, float ph, float sw, float sh,
                          float *out_rot, float *out_scale,
                          float *out_tx, float *out_ty) {
    (void)rx; (void)ry;
    const float scene_w = rw * pw;
    const float scene_h = rh * ph;
    if (scene_w <= 0.0f || scene_h <= 0.0f || sw <= 0.0f || sh <= 0.0f) {
        *out_rot = 0.0f; *out_scale = 1.0f; *out_tx = 0.0f; *out_ty = 0.0f;
        return;
    }
    const float scale0  = fmin_f(sw / scene_w, sh / scene_h);
    const float scale90 = fmin_f(sw / scene_h, sh / scene_w);
    float rot, s;
    if (scale90 > scale0) { rot = 90.0f; s = scale90; }
    else                  { rot = 0.0f;  s = scale0;  }
    if (s > APP7_MAX_FOCUS_SCALE) s = APP7_MAX_FOCUS_SCALE;
    const float disp_w = (rot == 0.0f ? scene_w : scene_h) * s;
    const float disp_h = (rot == 0.0f ? scene_h : scene_w) * s;
    *out_rot   = rot;
    *out_scale = s;
    *out_tx    = (sw - disp_w) / 2.0f;
    *out_ty    = (sh - disp_h) / 2.0f;
}
