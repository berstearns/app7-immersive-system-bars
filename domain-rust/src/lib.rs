// ═══════════════════════════════════════════════════════════════════════
//  app7_domain — ALGEBRAIC SOURCE OF TRUTH for app7's pure domain (Rust).
//
//  Supersedes domain-haskell/App7Domain.hs (kept as historical reference)
//  and domain-core/src/*.c (the C projection this crate replaces).  The
//  public C ABI is UNCHANGED: domain-core/include/app7_domain.h remains
//  the contract, domain-core/jni/app7_domain_jni.c remains the JNI shim;
//  this staticlib is linked in place of the seven C objects.
//
//  ARCHITECTURE — algebra first, FFI as a projection:
//    * Each module has an ALGEBRAIC CORE: ADTs (enums/structs), total
//      functions, Result<_, DomainError> where the algebra can fail.
//      No raw pointers, no C types, no unsafe in the core.
//    * Each module has a `pub mod ffi` that PROJECTS the algebra onto the
//      exact app7_domain.h ABI (#[no_mangle] extern "C").  All unsafe
//      lives there, at the boundary, nowhere else.
//
//  CASCADE DISCIPLINE (INSTRUCTION.md §1) — now:
//    TYPE → RULE (this crate's core) → WIRE (ffi + app7_domain.h)
//         → CONSUMER (App7Native.kt) → STORAGE → DATA → TESTS
//  DomainError variants are LOCK-STEP with APP7_ERR_* macros in
//  app7_domain.h and App7Native.kt's error projection.  Adding a variant
//  here = adding a macro there.  No exceptions.
//
//  CONVENTIONS every module follows (porters: read this first):
//    * no_std (core + alloc).  Tests run with std (cfg_attr below).
//    * C ints are i32 / i64 exactly as in the header; floats are f32.
//    * Bit-exact semantics with the C projection: wrapping_* for uint64
//      arithmetic (C unsigned wraps), same comparison directions
//      (strict `<` vs `<=` matter — see is_degenerate boundary rule),
//      same iteration orders.
//    * FFI booleans are `i32` 0/1.  Navigator "rejected" is -1.
//    * Pool/strerror strings are NUL-terminated static bytes
//      (`b"understood\0"`), returned as *const c_char.  Never allocated.
//    * Opaque handles: Box::into_raw / Box::from_raw at the boundary,
//      NULL-checked on every entry.
// ═══════════════════════════════════════════════════════════════════════
#![cfg_attr(not(any(test, feature = "std")), no_std)]

extern crate alloc;

pub mod error;
pub mod region;
pub mod crop;
pub mod permutation;
pub mod pool;
pub mod session;
pub mod navigator;

// ── no_std runtime glue (release .so only; tests use std's) ─────────────
// Gated on the `std` feature, NOT cfg(test): integration tests build the
// library with cfg(test) FALSE, so a cfg(test) gate would ship a second
// #[panic_handler]/#[global_allocator] into the std test binary.  The
// dev-dependency on ourselves (Cargo.toml) turns `std` on for all tests.
#[cfg(not(any(test, feature = "std")))]
mod nostd_runtime {
    use core::alloc::{GlobalAlloc, Layout};

    extern "C" {
        fn malloc(size: usize) -> *mut u8;
        fn free(ptr: *mut u8);
        fn abort() -> !;
    }

    struct LibcAlloc;

    // SAFETY: libc malloc returns memory valid for any layout our domain
    // uses (max align 8 == malloc's guarantee on all shipped targets).
    unsafe impl GlobalAlloc for LibcAlloc {
        unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
            malloc(layout.size())
        }
        unsafe fn dealloc(&self, ptr: *mut u8, _layout: Layout) {
            free(ptr)
        }
    }

    #[global_allocator]
    static ALLOC: LibcAlloc = LibcAlloc;

    #[panic_handler]
    fn panic(_: &core::panic::PanicInfo) -> ! {
        // Domain code is total; a panic is a bug.  Hard-abort matches the
        // "never store unverified" guarantee: crash, don't corrupt.
        unsafe { abort() }
    }

    // The prebuilt sysroot `alloc` rlib carries DWARF unwind references to
    // the `rust_eh_personality` lang item even though this crate builds
    // with panic=abort (only -Zbuild-std would rebuild alloc without
    // them).  Aborting panics can never reach a personality routine, so a
    // no-op stub exists purely to satisfy the C linker (round_trip.c
    // links the staticlib with plain `cc`, no Rust runtime present).
    #[no_mangle]
    extern "C" fn rust_eh_personality() {}
}
