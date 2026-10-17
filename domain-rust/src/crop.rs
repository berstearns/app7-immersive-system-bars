//! CropSelection — algebraic port of domain-core/src/crop.c (itself the C
//! projection of App7Domain.hs §4 "PURE PREDICATES — CropSelection").
//!
//! PARITY NOTES (binding — the C CODE is law):
//!   * crop.c never calls libm `fminf`/`fmaxf`/`fabsf`.  It defines local
//!     ternary helpers (`a < b ? a : b`, etc.), whose NaN / signed-zero
//!     behavior DIFFERS from both libm and Rust's `f32::min`/`max`/`abs`:
//!       - `fmin_f(a, NaN)` → NaN, `fmin_f(NaN, b)` → b   (asymmetric!)
//!       - `fmax_f(a, NaN)` → NaN, `fmax_f(NaN, b)` → b
//!       - `fabs_f(NaN)`  → NaN (payload untouched)
//!       - `fabs_f(-0.0)` → -0.0 (`-0.0 < 0` is false; Rust `.abs()`
//!         would clear the sign bit — do NOT "clean this up")
//!       - `clamp01_f(NaN)` → NaN (passes through both ternaries)
//!     We replicate the ternaries verbatim below.
//!   * `is_degenerate` uses strict `<` on BOTH axes: boundary equality
//!     with `min_size` is NOT degenerate (header §3 + crop.c comment).
//!   * `app7_crop_clamped` computes all four results into locals BEFORE
//!     writing, so callers may pass aliased out-pointers.  The C does not
//!     NULL-check the out pointers (header: "must be non-NULL" is the
//!     caller's contract) — neither do we.
//!
//! The crop algebra is TOTAL — no function can fail, so no
//! `Result<_, DomainError>` appears here.  `DomainError::DegenerateCrop`
//! (code 2) is the reserved failure for future validated constructors;
//! Phase 2a's `is_degenerate` returns the predicate directly.

// ─── ALGEBRAIC CORE ──────────────────────────────────────────────────────
// No unsafe, no raw pointers, no C types.  Mirrors crop.c 1:1 in f32.

/// Mirror of `APP7_MIN_NORMALIZED_SIZE` (app7_domain.h §2) and Haskell
/// `minNormalizedSize` — CropSelection's companion constant in Kotlin.
pub const MIN_NORMALIZED_SIZE: f32 = 0.02;

/// C: `static inline float fmin_f(float a, float b) { return a < b ? a : b; }`
#[inline(always)]
fn fmin_f(a: f32, b: f32) -> f32 {
    if a < b {
        a
    } else {
        b
    }
}

/// C: `static inline float fmax_f(float a, float b) { return a > b ? a : b; }`
#[inline(always)]
fn fmax_f(a: f32, b: f32) -> f32 {
    if a > b {
        a
    } else {
        b
    }
}

/// C: `static inline float fabs_f(float a) { return a < 0 ? -a : a; }`
/// NOT `f32::abs` — preserves `-0.0` and NaN bit patterns exactly as the
/// ternary does (see module parity notes).
#[inline(always)]
fn fabs_f(a: f32) -> f32 {
    if a < 0.0 {
        -a
    } else {
        a
    }
}

/// C: `static inline float clamp01_f(float a) { return fmax_f(0.0f, fmin_f(1.0f, a)); }`
/// Haskell: `clamp01 = max 0 . min 1`.  NaN passes through as NaN.
#[inline(always)]
fn clamp01_f(a: f32) -> f32 {
    fmax_f(0.0, fmin_f(1.0, a))
}

/// Haskell: `data CropSelection = CropSelection
///             { csStartX, csStartY, csEndX, csEndY :: Float }`
///
/// A drag rectangle in normalized [0,1] image coordinates; start/end are
/// the two drag corners in gesture order (either may be the greater).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CropSelection {
    pub start_x: f32,
    pub start_y: f32,
    pub end_x: f32,
    pub end_y: f32,
}

impl CropSelection {
    #[inline]
    pub const fn new(start_x: f32, start_y: f32, end_x: f32, end_y: f32) -> CropSelection {
        CropSelection {
            start_x,
            start_y,
            end_x,
            end_y,
        }
    }

    /// Haskell: `normalizedX (CropSelection sx _ ex _) = min sx ex`
    #[inline]
    pub fn normalized_x(&self) -> f32 {
        fmin_f(self.start_x, self.end_x)
    }

    /// Haskell: `normalizedY (CropSelection _ sy _ ey) = min sy ey`
    #[inline]
    pub fn normalized_y(&self) -> f32 {
        fmin_f(self.start_y, self.end_y)
    }

    /// Haskell: `normalizedWidth (CropSelection sx _ ex _) = abs (ex - sx)`
    #[inline]
    pub fn normalized_width(&self) -> f32 {
        fabs_f(self.end_x - self.start_x)
    }

    /// Haskell: `normalizedHeight (CropSelection _ sy _ ey) = abs (ey - sy)`
    #[inline]
    pub fn normalized_height(&self) -> f32 {
        fabs_f(self.end_y - self.start_y)
    }

    /// Haskell: `centerX c = normalizedX c + normalizedWidth c / 2`
    #[inline]
    pub fn center_x(&self) -> f32 {
        self.normalized_x() + self.normalized_width() / 2.0
    }

    /// Haskell: `centerY c = normalizedY c + normalizedHeight c / 2`
    #[inline]
    pub fn center_y(&self) -> f32 {
        self.normalized_y() + self.normalized_height() / 2.0
    }

    /// Haskell: `isDegenerate ms c = normalizedWidth c < ms || normalizedHeight c < ms`
    ///
    /// Strict `<` matches Haskell and Kotlin
    /// (`normalizedWidth() < minSize || ...`).  Boundary equality with
    /// `min_size` is NOT degenerate.
    #[inline]
    pub fn is_degenerate(&self, min_size: f32) -> bool {
        self.normalized_width() < min_size || self.normalized_height() < min_size
    }

    /// Haskell: `clamped (CropSelection sx sy ex ey) =
    ///             CropSelection (clamp01 sx) (clamp01 sy) (clamp01 ex) (clamp01 ey)`
    #[inline]
    pub fn clamped(&self) -> CropSelection {
        CropSelection {
            start_x: clamp01_f(self.start_x),
            start_y: clamp01_f(self.start_y),
            end_x: clamp01_f(self.end_x),
            end_y: clamp01_f(self.end_y),
        }
    }
}

/// Mirror of `APP7_MAX_FOCUS_SCALE` (app7_domain.h §3b) and Haskell
/// `maxFocusScale` — FocusTransform's companion constant in Kotlin
/// (`FocusTransform.MAX_FOCUS_SCALE`).
pub const MAX_FOCUS_SCALE: f32 = 8.0;

/// Haskell: `data FocusTransform = FocusTransform
///             { ftRotationDeg, ftScale, ftTranslateX, ftTranslateY :: Float }`
///
/// Scene-focus fit transform: how to rotate + scale one scene rectangle
/// cropped out of a page so it fills the screen, maximized and letterboxed.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct FocusTransform {
    /// Auto-rotation in {0, 90} (f32 because the wire form is a float).
    pub rotation_deg: f32,
    /// Contain scale after rotation, capped at [MAX_FOCUS_SCALE].
    pub scale: f32,
    /// Centering translate on X, in screen pixels.
    pub translate_x: f32,
    /// Centering translate on Y, in screen pixels.
    pub translate_y: f32,
}

/// C: `app7_focus_transform` (crop.c) — scene-focus fit math.
///
/// Chooses rotation 0 or 90 (whichever contain-fits larger), the contain
/// scale (capped at [MAX_FOCUS_SCALE] — no upscaling a tiny scene into a
/// pixelated blowup), and the centering translate.  Any non-positive
/// dimension → identity `{0, 1, 0, 0}`.  `rx`/`ry` are part of the
/// sceneRect contract but the maximize-and-center transform does not
/// depend on the scene's page position, so they are unused — exactly as
/// the C's `(void)rx; (void)ry;`.
pub fn focus_transform(
    _rx: f32,
    _ry: f32,
    rw: f32,
    rh: f32,
    pw: f32,
    ph: f32,
    sw: f32,
    sh: f32,
) -> FocusTransform {
    let scene_w = rw * pw;
    let scene_h = rh * ph;
    if scene_w <= 0.0 || scene_h <= 0.0 || sw <= 0.0 || sh <= 0.0 {
        return FocusTransform {
            rotation_deg: 0.0,
            scale: 1.0,
            translate_x: 0.0,
            translate_y: 0.0,
        };
    }
    let scale0 = fmin_f(sw / scene_w, sh / scene_h);
    let scale90 = fmin_f(sw / scene_h, sh / scene_w);
    let (rot, s0) = if scale90 > scale0 {
        (90.0f32, scale90)
    } else {
        (0.0f32, scale0)
    };
    let s = if s0 > MAX_FOCUS_SCALE { MAX_FOCUS_SCALE } else { s0 };
    let disp_w = (if rot == 0.0 { scene_w } else { scene_h }) * s;
    let disp_h = (if rot == 0.0 { scene_h } else { scene_w }) * s;
    FocusTransform {
        rotation_deg: rot,
        scale: s,
        translate_x: (sw - disp_w) / 2.0,
        translate_y: (sh - disp_h) / 2.0,
    }
}

// ─── FFI PROJECTION — exact app7_domain.h §3 ABI ─────────────────────────
pub mod ffi {
    //! C ABI projection of the CropSelection algebra.  Flat scalar args
    //! per the header's ABI choice (no struct types in the public ABI).
    //! All pointer handling lives here; the core above is pointer-free.

    use super::CropSelection;

    /// Rebuild the algebraic value from the flat scalar wire form.
    #[inline(always)]
    fn crop(sx: f32, sy: f32, ex: f32, ey: f32) -> CropSelection {
        CropSelection::new(sx, sy, ex, ey)
    }

    /// C: `float app7_crop_normalized_x(float sx, float sy, float ex, float ey);`
    /// (sy/ey are part of the ABI but unused, as in crop.c's `(void)sy;`.)
    #[no_mangle]
    pub extern "C" fn app7_crop_normalized_x(sx: f32, sy: f32, ex: f32, ey: f32) -> f32 {
        crop(sx, sy, ex, ey).normalized_x()
    }

    /// C: `float app7_crop_normalized_y(float sx, float sy, float ex, float ey);`
    #[no_mangle]
    pub extern "C" fn app7_crop_normalized_y(sx: f32, sy: f32, ex: f32, ey: f32) -> f32 {
        crop(sx, sy, ex, ey).normalized_y()
    }

    /// C: `float app7_crop_normalized_width(float sx, float sy, float ex, float ey);`
    #[no_mangle]
    pub extern "C" fn app7_crop_normalized_width(sx: f32, sy: f32, ex: f32, ey: f32) -> f32 {
        crop(sx, sy, ex, ey).normalized_width()
    }

    /// C: `float app7_crop_normalized_height(float sx, float sy, float ex, float ey);`
    #[no_mangle]
    pub extern "C" fn app7_crop_normalized_height(sx: f32, sy: f32, ex: f32, ey: f32) -> f32 {
        crop(sx, sy, ex, ey).normalized_height()
    }

    /// C: `float app7_crop_center_x(float sx, float sy, float ex, float ey);`
    #[no_mangle]
    pub extern "C" fn app7_crop_center_x(sx: f32, sy: f32, ex: f32, ey: f32) -> f32 {
        crop(sx, sy, ex, ey).center_x()
    }

    /// C: `float app7_crop_center_y(float sx, float sy, float ex, float ey);`
    #[no_mangle]
    pub extern "C" fn app7_crop_center_y(sx: f32, sy: f32, ex: f32, ey: f32) -> f32 {
        crop(sx, sy, ex, ey).center_y()
    }

    /// C: `int app7_crop_is_degenerate(float min_size,
    ///                                 float sx, float sy, float ex, float ey);`
    /// Returns 1 if either normalized dimension is strictly less than
    /// `min_size`, 0 otherwise (FFI boolean, i32 0/1).
    #[no_mangle]
    pub extern "C" fn app7_crop_is_degenerate(
        min_size: f32,
        sx: f32,
        sy: f32,
        ex: f32,
        ey: f32,
    ) -> i32 {
        crop(sx, sy, ex, ey).is_degenerate(min_size) as i32
    }

    /// C: `void app7_crop_clamped(float sx, float sy, float ex, float ey,
    ///                            float *out_sx, float *out_sy,
    ///                            float *out_ex, float *out_ey);`
    ///
    /// Clamps each coordinate to [0, 1] and writes the four results.
    /// All four results are computed BEFORE any write (the `clamped()`
    /// value is the C code's block of locals), so out-pointers may alias
    /// each other; writes happen in the C order sx, sy, ex, ey.
    ///
    /// # Safety
    /// Per app7_domain.h: every out pointer must be non-NULL and valid
    /// for a float write.  The C projection does not NULL-check them and
    /// neither do we — the caller's contract is the guarantee.
    #[no_mangle]
    pub unsafe extern "C" fn app7_crop_clamped(
        sx: f32,
        sy: f32,
        ex: f32,
        ey: f32,
        out_sx: *mut f32,
        out_sy: *mut f32,
        out_ex: *mut f32,
        out_ey: *mut f32,
    ) {
        let c = crop(sx, sy, ex, ey).clamped();
        *out_sx = c.start_x;
        *out_sy = c.start_y;
        *out_ex = c.end_x;
        *out_ey = c.end_y;
    }

    /// C: `void app7_focus_transform(float rx, float ry, float rw, float rh,
    ///                               float pw, float ph, float sw, float sh,
    ///                               float *out_rot, float *out_scale,
    ///                               float *out_tx, float *out_ty);`
    ///
    /// Scene-focus fit transform (header §3b).  All four results are
    /// computed into the algebraic value BEFORE any write, so out-pointers
    /// may alias; writes happen in the C order rot, scale, tx, ty.
    ///
    /// # Safety
    /// Per app7_domain.h: every out pointer must be non-NULL and valid
    /// for a float write.  The C projection does not NULL-check them and
    /// neither do we — the caller's contract is the guarantee.
    #[no_mangle]
    pub unsafe extern "C" fn app7_focus_transform(
        rx: f32,
        ry: f32,
        rw: f32,
        rh: f32,
        pw: f32,
        ph: f32,
        sw: f32,
        sh: f32,
        out_rot: *mut f32,
        out_scale: *mut f32,
        out_tx: *mut f32,
        out_ty: *mut f32,
    ) {
        let t = super::focus_transform(rx, ry, rw, rh, pw, ph, sw, sh);
        *out_rot = t.rotation_deg;
        *out_scale = t.scale;
        *out_tx = t.translate_x;
        *out_ty = t.translate_y;
    }
}

// ─── TESTS — Test.hs + round_trip.c assertions, oracle values verbatim ───
#[cfg(test)]
mod tests {
    use super::ffi;
    use super::{CropSelection, MIN_NORMALIZED_SIZE};

    /// Shared oracle tolerance: C `fabsf(a - b) < 1e-5f`, Haskell
    /// `abs (a - b) < 1e-5`.
    fn approx(a: f32, b: f32) -> bool {
        (a - b).abs() < 1e-5
    }

    // ── [CropSelection — normalizedX/Y/Width/Height] ────────────────────
    // c1 = CropSelection 0.5 0.2 0.1 0.4  (start > end on x, < on y)

    #[test]
    fn normalized_x_swaps_when_start_gt_end() {
        let c1 = CropSelection::new(0.5, 0.2, 0.1, 0.4);
        assert!(approx(c1.normalized_x(), 0.1));
        assert!(approx(ffi::app7_crop_normalized_x(0.5, 0.2, 0.1, 0.4), 0.1));
    }

    #[test]
    fn normalized_y_keeps_when_start_lt_end() {
        let c1 = CropSelection::new(0.5, 0.2, 0.1, 0.4);
        assert!(approx(c1.normalized_y(), 0.2));
        assert!(approx(ffi::app7_crop_normalized_y(0.5, 0.2, 0.1, 0.4), 0.2));
    }

    #[test]
    fn normalized_width_is_abs_dx() {
        let c1 = CropSelection::new(0.5, 0.2, 0.1, 0.4);
        assert!(approx(c1.normalized_width(), 0.4));
        assert!(approx(
            ffi::app7_crop_normalized_width(0.5, 0.2, 0.1, 0.4),
            0.4
        ));
    }

    #[test]
    fn normalized_height_is_abs_dy() {
        let c1 = CropSelection::new(0.5, 0.2, 0.1, 0.4);
        assert!(approx(c1.normalized_height(), 0.2));
        assert!(approx(
            ffi::app7_crop_normalized_height(0.5, 0.2, 0.1, 0.4),
            0.2
        ));
    }

    // ── [CropSelection — centerX/Y] ─────────────────────────────────────
    // c2 = CropSelection 0.2 0.2 0.6 0.8

    #[test]
    fn center_x_is_0_4() {
        let c2 = CropSelection::new(0.2, 0.2, 0.6, 0.8);
        assert!(approx(c2.center_x(), 0.4));
        assert!(approx(ffi::app7_crop_center_x(0.2, 0.2, 0.6, 0.8), 0.4));
    }

    #[test]
    fn center_y_is_0_5() {
        let c2 = CropSelection::new(0.2, 0.2, 0.6, 0.8);
        assert!(approx(c2.center_y(), 0.5));
        assert!(approx(ffi::app7_crop_center_y(0.2, 0.2, 0.6, 0.8), 0.5));
    }

    // ── [CropSelection — isDegenerate at min_size=0.02] ─────────────────
    // tiny = 0.10 .. 0.105  → width/height = 0.005 < 0.02 → degenerate
    // big  = 0.00 .. 0.50   → 0.5 not < 0.02 → not degenerate
    // edge = 0.00 .. 0.02   → exactly 0.02, strict < → not degenerate
    // just = (0.019, 0.50)  → x-axis 0.019 < 0.02 → degenerate

    #[test]
    fn tiny_is_degenerate() {
        let tiny = CropSelection::new(0.10, 0.10, 0.105, 0.105);
        assert!(tiny.is_degenerate(MIN_NORMALIZED_SIZE));
        assert_eq!(
            ffi::app7_crop_is_degenerate(MIN_NORMALIZED_SIZE, 0.10, 0.10, 0.105, 0.105),
            1
        );
    }

    #[test]
    fn big_is_not_degenerate() {
        let big = CropSelection::new(0.00, 0.00, 0.50, 0.50);
        assert!(!big.is_degenerate(MIN_NORMALIZED_SIZE));
        assert_eq!(
            ffi::app7_crop_is_degenerate(MIN_NORMALIZED_SIZE, 0.00, 0.00, 0.50, 0.50),
            0
        );
    }

    #[test]
    fn edge_exactly_min_size_is_not_degenerate_strict_lt() {
        let edge = CropSelection::new(0.00, 0.00, 0.02, 0.02);
        assert!(!edge.is_degenerate(MIN_NORMALIZED_SIZE));
        assert_eq!(
            ffi::app7_crop_is_degenerate(MIN_NORMALIZED_SIZE, 0.00, 0.00, 0.02, 0.02),
            0
        );
    }

    #[test]
    fn one_axis_under_is_degenerate() {
        let just = CropSelection::new(0.00, 0.00, 0.019, 0.50);
        assert!(just.is_degenerate(MIN_NORMALIZED_SIZE));
        assert_eq!(
            ffi::app7_crop_is_degenerate(MIN_NORMALIZED_SIZE, 0.00, 0.00, 0.019, 0.50),
            1
        );
    }

    // ── [CropSelection — clamped] ───────────────────────────────────────
    // oob = CropSelection (-0.5) 1.5 1.2 (-0.2)

    #[test]
    fn clamped_out_of_bounds_core() {
        let clp = CropSelection::new(-0.5, 1.5, 1.2, -0.2).clamped();
        assert!(approx(clp.start_x, 0.0)); // clamped startX -0.5 -> 0
        assert!(approx(clp.start_y, 1.0)); // clamped startY  1.5 -> 1
        assert!(approx(clp.end_x, 1.0)); //   clamped endX    1.2 -> 1
        assert!(approx(clp.end_y, 0.0)); //   clamped endY   -0.2 -> 0
    }

    #[test]
    fn clamped_out_of_bounds_ffi() {
        let (mut ox, mut oy, mut oex, mut oey) = (9.0f32, 9.0f32, 9.0f32, 9.0f32);
        unsafe {
            ffi::app7_crop_clamped(-0.5, 1.5, 1.2, -0.2, &mut ox, &mut oy, &mut oex, &mut oey);
        }
        assert!(approx(ox, 0.0));
        assert!(approx(oy, 1.0));
        assert!(approx(oex, 1.0));
        assert!(approx(oey, 0.0));
    }

    #[test]
    fn clamped_is_idempotent_on_in_range() {
        // Haskell: clamped (clamped already) == clamped already  (exact Eq)
        let already = CropSelection::new(0.1, 0.2, 0.3, 0.4);
        assert_eq!(already.clamped().clamped(), already.clamped());

        // round_trip.c: two chained ffi calls, approx-compared.
        let (mut a1, mut b1, mut c1, mut d1) = (0.0f32, 0.0f32, 0.0f32, 0.0f32);
        let (mut a2, mut b2, mut c2, mut d2) = (0.0f32, 0.0f32, 0.0f32, 0.0f32);
        unsafe {
            ffi::app7_crop_clamped(0.1, 0.2, 0.3, 0.4, &mut a1, &mut b1, &mut c1, &mut d1);
            ffi::app7_crop_clamped(a1, b1, c1, d1, &mut a2, &mut b2, &mut c2, &mut d2);
        }
        assert!(approx(a1, a2) && approx(b1, b2) && approx(c1, c2) && approx(d1, d2));
    }

    // ── C-semantics guards (not in the oracles; they pin the parity
    //    subtleties documented in the module header so a future "cleanup"
    //    to f32::min/max/abs fails loudly) ─────────────────────────────

    #[test]
    fn clamped_out_pointers_may_alias() {
        // header §3: "Out pointers ... may alias".  crop.c computes into
        // locals first; with out_sx == out_ex the LAST write (ex) wins.
        let mut shared = 0.0f32;
        let mut oy = 0.0f32;
        let mut oey = 0.0f32;
        let shared_ptr: *mut f32 = &mut shared;
        unsafe {
            ffi::app7_crop_clamped(-0.5, 1.5, 1.2, -0.2, shared_ptr, &mut oy, shared_ptr, &mut oey);
        }
        assert!(approx(shared, 1.0)); // clamp01(1.2), not clamp01(-0.5)
    }

    #[test]
    fn ternary_min_max_nan_asymmetry() {
        // fmin_f(NaN, b) = b (comparison false → second arm); fmin_f(a, NaN) = NaN.
        assert!(approx(ffi::app7_crop_normalized_x(f32::NAN, 0.0, 0.3, 0.0), 0.3));
        assert!(ffi::app7_crop_normalized_x(0.3, 0.0, f32::NAN, 0.0).is_nan());
    }

    #[test]
    fn clamp01_passes_nan_through() {
        // C: fmax_f(0, fmin_f(1, NaN)) = fmax_f(0, NaN) = NaN.
        // (Rust f32::min/max would instead return the non-NaN operand.)
        let (mut ox, mut oy, mut oex, mut oey) = (0.0f32, 0.0f32, 0.0f32, 0.0f32);
        unsafe {
            ffi::app7_crop_clamped(f32::NAN, 0.5, 0.5, 0.5, &mut ox, &mut oy, &mut oex, &mut oey);
        }
        assert!(ox.is_nan());
        assert!(approx(oy, 0.5) && approx(oex, 0.5) && approx(oey, 0.5));
    }

    #[test]
    fn ternary_fabs_preserves_negative_zero() {
        // C: fabs_f(-0.0) → -0.0 (`-0.0 < 0` is false).  f32::abs would
        // clear the sign bit; the ternary must not.
        let w = ffi::app7_crop_normalized_width(0.0, 0.0, -0.0, 0.0); // ex - sx = -0.0
        assert_eq!(w.to_bits(), (-0.0f32).to_bits());
    }

    #[test]
    fn is_degenerate_nan_width_is_false() {
        // NaN < min_size is false on both sides of the `||`, exactly as C.
        assert_eq!(
            ffi::app7_crop_is_degenerate(MIN_NORMALIZED_SIZE, f32::NAN, 0.0, 0.5, 0.5),
            0
        );
    }

    // ── [FocusTransform — scene-focus fit math] ─────────────────────────
    // Hand-computed oracle values (NOT derived from the Kotlin fallback),
    // same cases as App7NativeParityTest.focusTransform_native_matches_
    // hand_computed_oracle — proving THIS crate computes the fit math.

    use super::{focus_transform, MAX_FOCUS_SCALE};

    #[test]
    fn focus_wide_scene_on_portrait_rotates_90() {
        // scene 800x375 px on a 1080x2160 screen:
        //   scale0  = min(1080/800, 2160/375) = 1.35
        //   scale90 = min(1080/375, 2160/800) = 2.7  → rotate
        let t = focus_transform(0.10, 0.05, 0.80, 0.25, 1000.0, 1500.0, 1080.0, 2160.0);
        assert!(approx(t.rotation_deg, 90.0));
        assert!(approx(t.scale, 2.7));
        // rotated: disp_w = scene_h*s = 1012.5 → tx = 33.75; disp_h = scene_w*s = 2160 → ty = 0
        assert!(approx(t.translate_x, 33.75));
        assert!(approx(t.translate_y, 0.0));
    }

    #[test]
    fn focus_tall_scene_keeps_rotation_0() {
        let t = focus_transform(0.05, 0.10, 0.40, 0.70, 1000.0, 1500.0, 1080.0, 2160.0);
        assert!(approx(t.rotation_deg, 0.0));
    }

    #[test]
    fn focus_degenerate_is_identity() {
        let t = focus_transform(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        assert!(approx(t.rotation_deg, 0.0));
        assert!(approx(t.scale, 1.0));
        assert!(approx(t.translate_x, 0.0));
        assert!(approx(t.translate_y, 0.0));
    }

    #[test]
    fn focus_scale_caps_at_max_focus_scale() {
        // 10x10 px scene on a 1000x1000 screen: contain scale 100 → capped.
        let t = focus_transform(0.0, 0.0, 0.01, 0.01, 1000.0, 1000.0, 1000.0, 1000.0);
        assert!(approx(t.scale, MAX_FOCUS_SCALE));
    }

    #[test]
    fn focus_ffi_writes_all_four_outs() {
        let (mut rot, mut s, mut tx, mut ty) = (9.0f32, 9.0f32, 9.0f32, 9.0f32);
        unsafe {
            ffi::app7_focus_transform(
                0.10, 0.05, 0.80, 0.25, 1000.0, 1500.0, 1080.0, 2160.0,
                &mut rot, &mut s, &mut tx, &mut ty,
            );
        }
        assert!(approx(rot, 90.0));
        assert!(approx(s, 2.7));
        assert!(approx(tx, 33.75));
        assert!(approx(ty, 0.0));
    }
}
