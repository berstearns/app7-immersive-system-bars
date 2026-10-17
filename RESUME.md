# RESUME — Rust algebraic domain for app7

> Top-level handoff record for the `app7-haskell-rust-algebraic-domain` dev folder.
> See [`EXECUTION-20260720T065810Z.md`](./EXECUTION-20260720T065810Z.md) (port + desktop parity)
> and [`EXECUTION-20260720T121500Z.md`](./EXECUTION-20260720T121500Z.md) (Android cross-build) for gate logs.
> Historical lineage: the Haskell→C era is recorded in `EXECUTION-2026051*.md` / `EXECUTION-20260523_190704.md`.

## What shipped

The pure domain of app7 — region geometry, crop predicates, permutation
validation, annotation sampling, session-time tracking, and image-sequence
navigation — now lives in **Rust as the single algebraic source of truth**
(`domain-rust/`, `no_std` core+alloc, zero third-party deps). The **C ABI
header and the JNI shim are FROZEN**; the Rust staticlib replaces the seven
C objects inside `libapp7_domain.so` on both Android and Compose Desktop.
`domain-core/src/*.c` stays on disk as the reference oracle but is no
longer compiled into the app; `domain-haskell/` is a historical oracle.

```
domain-rust/src/{error,region,crop,permutation,pool,session,navigator}.rs
  ADT cores: pure, total, Result<_, DomainError>          (source of truth)
        │
        ▼  pub mod ffi — ALL unsafe lives here
domain-rust →  libapp7_domain.a   (staticlib; cargo, no build.rs, no cbindgen)
        │
        ▼  must match EXACTLY (39 app7_* symbols; header wins on any dispute)
domain-core/include/app7_domain.h   (FROZEN — 39 fns, 12 APP7_ERR_* codes)
domain-core/jni/app7_domain_jni.c   (FROZEN — one shim, both targets)
domain-core/test/round_trip.c       (C oracle, 100 assertions, UNMODIFIED)
        │
        ├──→ Android: androidApp/src/main/cpp/CMakeLists.txt links the
        │    PREBUILT domain-rust/target/<triple>/release/libapp7_domain.a
        │    (built by domain-rust/build-android.sh; FATAL_ERROR if absent)
        └──→ Desktop: shared/build.gradle.kts `cargoBuildDomain` →
             `buildHostNativeLib` (gcc shim+archive → META-INF/native/...)
        │
        ▼
shared/.../domain/native/App7Native.kt   (36 expect funs — UNTOUCHED)
        └──→ delegating wrappers unchanged (see guarantee below)
```

Note the 39 vs 36: 39 is the ABI symbol count in the header; 36 is the
`expect`-fun count in `App7Native.kt`. Older docs saying "36 functions"
for the ABI are wrong.

## Cascade discipline (now load-bearing)

Future changes to any domain rule must walk:

```
RUST TYPE → RULE → ffi → header (frozen: renegotiate explicitly) → jni shim → App7Native.kt → tests
```

Concretely: **edit the algebraic core in `domain-rust/src/<module>.rs`
first**, then its `ffi` projection, and only if the ABI itself must change,
renegotiate `app7_domain.h` + `app7_domain_jni.c` + `App7Native.kt` in the
SAME commit (single-file domain commits are a smell). Gates, in order:
`cargo test` → `round_trip.c` linked against the Rust archive (100/100,
oracle stays unmodified) → 39-symbol `nm` conformance →
`build-android.sh` per ABI → `:shared:desktopTest` parity.
Do NOT edit `App7Domain.hs` first anymore — the Haskell cascade is retired.

## The "never store unverified" guarantee — unchanged

User rule (2026-05-18): *"never ever ever store long term anything that is
wrong/not verified"*. Enforcement is exactly as in the Haskell→C era —
nothing above the ABI moved:

1. **Type-level delegation**: `CropSelection.*`, `ConversationBox.contains`,
   `PageBubbleOrdering.isValidPermutation`, the trackers and the annotation
   decider all delegate to `App7Native.*`, which now lands in Rust.
2. **Hard-fail on `.so` load failure**: wrappers catch only
   `NotImplementedError` (iOS stub); `UnsatisfiedLinkError` etc. crash at
   first domain call, so unverified pure-Kotlin output can never leak to
   Room → Turso. iOS remains the only Kotlin-fallback target.

## Gate status

- cargo test: 58 unit + 8 oracle — green (both machines).
- round_trip.c (UNMODIFIED) vs Rust archive: **100/100** — incl. bit-exact
  xoshiro256** `sample(42,3) == [1,15,16]`.
- Symbol conformance: 39 exported = 39 in header, name diff empty.
- Android cross-build (rustup box, 2026-07-20): all 4 ABIs
  (arm64-v8a, armeabi-v7a, x86, x86_64) — 39 symbols each, arch verified,
  JNI shim links against each archive. First time this gate ever ran.
- Desktop `App7NativeParityTest`: **25/25 on both machines** (rustup box
  run used JDK 17.0.19 from `~/toolchains/jdk-17.0.19+10`).
- Device gates (APK install, smoke, Maestro): PENDING user-side.

## Sizes — honest regression note

The Rust `.so` is ~22–30× the C one in `.text` (core::fmt + panic
plumbing). Known and accepted. Per-ABI stripped numbers live in
`FEATURE.md` §Size note (e.g. arm64-v8a: 346,873 B `.text` vs 12,189 B
for the C build).

## Next steps (NOT in this dev folder)

1. **User-test the APK** on real devices — build with the prebuilt Rust
   archives (`build-android.sh` first, then AGP).
2. **Promote `app7-latest`** ONLY after user-tested green — the LLM never
   moves the symlink autonomously.
3. **Public-repo conversion + e2e deploy** per the standard playbook.
4. **iOS bridge** (later, separate folder): Swift FFI over the same frozen
   header, now backed by the Rust staticlib.
