//! Region algebra — ALGEBRAIC SOURCE for App7Domain.hs `RegionType`,
//! `ConversationBox`, and the pure predicate `contains` (§5 of the
//! Haskell source).  Replaces domain-core/src/region.c behind the
//! unchanged C ABI (`app7_box_contains`, header §4).
//!
//! The predicate is total (no failure algebra needed — a point/box query
//! always has an answer), so no `DomainError` appears here.

// ── ALGEBRAIC CORE ───────────────────────────────────────────────────────

/// Mirror of Haskell `data RegionType = Bubble | Token | Crop`.
///
/// Explicit discriminants are declared now so a future Phase that puts
/// region-type codes on the wire cannot renumber them by accident.  Not
/// part of the current C ABI (region.c exports no code for it).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum RegionType {
    Bubble = 0,
    Token = 1,
    Crop = 2,
}

/// Geometric part of Haskell `ConversationBox` (cbX, cbY, cbWidth,
/// cbHeight).  The metadata fields (region type, parent-bubble index,
/// token index, text) stay in Kotlin — the C ABI passes only the four
/// scalars, and `contains` ignores the rest (`ConversationBox x y w h
/// _ _ _ _` in the Haskell pattern).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BoxGeometry {
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
}

impl BoxGeometry {
    #[inline]
    pub const fn new(x: f32, y: f32, width: f32, height: f32) -> Self {
        BoxGeometry { x, y, width, height }
    }

    /// Haskell: `contains nx ny (ConversationBox x y w h _ _ _ _) =
    ///             nx >= x && nx <= x + w && ny >= y && ny <= y + h`
    ///
    /// INCLUSIVE on all four edges (>= / <=), matching Kotlin's
    /// `nx <= x + width`.  The right/bottom edges are computed as
    /// `x + w` / `y + h` in f32 — same rounding as the C expression
    /// `bx + bw`, so boundary equality behaves bit-identically.
    #[inline]
    pub fn contains(&self, nx: f32, ny: f32) -> bool {
        nx >= self.x
            && nx <= self.x + self.width
            && ny >= self.y
            && ny <= self.y + self.height
    }
}

// ── FFI PROJECTION ───────────────────────────────────────────────────────

pub mod ffi {
    //! C ABI projection of the region algebra — header §4.
    //!
    //! `int app7_box_contains(float nx, float ny,
    //!                        float bx, float by, float bw, float bh);`
    //!
    //! All-scalar signature: no pointers, so no NULL checks and no unsafe
    //! (matching region.c, which takes no pointers either).
    use super::BoxGeometry;

    /// Returns 1 if (nx, ny) lies inside the box (inclusive on all four
    /// edges), 0 otherwise.  FFI boolean per crate convention.
    #[no_mangle]
    pub extern "C" fn app7_box_contains(
        nx: f32,
        ny: f32,
        bx: f32,
        by: f32,
        bw: f32,
        bh: f32,
    ) -> i32 {
        BoxGeometry::new(bx, by, bw, bh).contains(nx, ny) as i32
    }
}

// ── TESTS — ported oracle values from round_trip.c / Test.hs ─────────────

#[cfg(test)]
mod tests {
    use super::ffi::app7_box_contains;
    use super::{BoxGeometry, RegionType};

    /// round_trip.c "[ConversationBox — contains]" block, verbatim:
    /// box at (0.1, 0.1) with width=0.4, height=0.4 → right=0.5, bottom=0.5
    #[test]
    fn tap_inside_box() {
        // "tap inside box (0.3,0.3)"
        assert_eq!(app7_box_contains(0.3f32, 0.3f32, 0.1f32, 0.1f32, 0.4f32, 0.4f32), 1);
    }

    #[test]
    fn tap_outside_box_left() {
        // "tap outside box left (0.05,0.3)"
        assert_eq!(app7_box_contains(0.05f32, 0.3f32, 0.1f32, 0.1f32, 0.4f32, 0.4f32), 0);
    }

    #[test]
    fn tap_outside_box_below() {
        // "tap outside box below (0.3,0.6)"
        assert_eq!(app7_box_contains(0.3f32, 0.6f32, 0.1f32, 0.1f32, 0.4f32, 0.4f32), 0);
    }

    #[test]
    fn tap_on_left_edge_inclusive() {
        // "tap on left edge (0.1,0.3) inclusive"
        assert_eq!(app7_box_contains(0.1f32, 0.3f32, 0.1f32, 0.1f32, 0.4f32, 0.4f32), 1);
    }

    #[test]
    fn tap_on_bottom_right_corner_inclusive() {
        // "tap on bottom-right corner inclusive" (0.5, 0.5)
        assert_eq!(app7_box_contains(0.5f32, 0.5f32, 0.1f32, 0.1f32, 0.4f32, 0.4f32), 1);
    }

    /// Same five oracles through the algebraic core (Test.hs §contains:
    /// `let box = ConversationBox 0.1 0.1 0.4 0.4 Bubble Nothing Nothing
    /// Nothing`).
    #[test]
    fn core_contains_matches_haskell_oracle() {
        let boxg = BoxGeometry::new(0.1, 0.1, 0.4, 0.4);
        assert!(boxg.contains(0.3, 0.3));
        assert!(!boxg.contains(0.05, 0.3));
        assert!(!boxg.contains(0.3, 0.6));
        assert!(boxg.contains(0.1, 0.3));
        assert!(boxg.contains(0.5, 0.5));
    }

    #[test]
    fn region_type_discriminants_are_stable() {
        assert_eq!(RegionType::Bubble as i32, 0);
        assert_eq!(RegionType::Token as i32, 1);
        assert_eq!(RegionType::Crop as i32, 2);
    }
}
