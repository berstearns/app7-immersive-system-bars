# haskell-rust-algebraic-domain

## What this feature is

Moves the compiled DDD domain from Haskell→C→JNI to **Rust as the single
algebraic source of truth**, staying as close to algebraic domain-driven
modelling as possible: ADTs (enums/newtypes), total functions,
`Result<_, DomainError>` cores, with FFI as a thin unsafe projection layer.
The public C ABI (`domain-core/include/app7_domain.h`, **39 functions**,
12 `APP7_ERR_*` codes) and the JNI shim are UNCHANGED — the Rust staticlib
replaces the seven C objects inside `libapp7_domain.so`, so
`App7Native.kt` and everything above it is untouched.

> Doc trap: older docs said "36 functions". 36 is the number of `expect`
> funs in `App7Native.kt` (new/free pairs and out-param projections don't
> map 1:1); the ABI itself is 39 `app7_*` symbols. The header is the truth.

Bootstrapped 2026-07-20 from `app7-haskell-backend-domain-pointer_20260710_173833`;
Android cross-build finished on the WSL2 box (the only machine with rustup)
2026-07-20, cloned via the dev-repo branch `rust-algebraic-domain`.

## Success criterion

- `domain-rust/` builds dependency-free (no_std core+alloc); `cargo test`
  green (58 unit + 8 oracle tests porting the Haskell round-trip assertions).
- `domain-core/test/round_trip.c` (100 assertions) passes UNMODIFIED,
  linked against the Rust staticlib instead of the C objects — the ABI
  parity oracle, incl. bit-exact xoshiro256** sampling
  (`sample(42,3) == [1,15,16]`).
- Symbol conformance: exactly the header's 39 `app7_*` symbols exported.
- Android staticlibs for all 4 ABIs via `domain-rust/build-android.sh`,
  each 39 symbols + correct arch, linkable against the JNI shim.
- `:shared:desktopTest` `App7NativeParityTest` 25/25 with the Rust-backed .so.
- (Device gates — user-side) APK build + smoke + Maestro: run on the
  user's machine; the emulator is broken on this box by design of the split.

## How to verify

```bash
cd <this feature folder>
export PATH=~/.cargo/bin:$PATH
cargo test --manifest-path domain-rust/Cargo.toml
cargo build --release --manifest-path domain-rust/Cargo.toml
cc -Idomain-core/include domain-core/test/round_trip.c \
   domain-rust/target/release/libapp7_domain.a -o /tmp/rt && /tmp/rt
nm -g domain-rust/target/release/libapp7_domain.a \
  | grep -oE ' T app7_[a-z_]+' | sort -u | wc -l    # expect 39
# Android (needs rustup targets + NDK; see script header):
ANDROID_NDK_HOME=~/android-sdk/ndk/26.1.10909125 \
  domain-rust/build-android.sh x86_64 arm64-v8a armeabi-v7a x86
# Desktop parity (needs a JDK):
JAVA_HOME=<jdk17> ./gradlew :shared:desktopTest --tests '*App7NativeParityTest*'
```

## Current status

- [x] Verified (non-device gates) — see per-gate table below.
- [x] Device gate, build/install/smoke — **Verified 2026-07-21** on
  emulator-5554 (`app7_e2e_api34`): installed-APK sha == built sha
  (current build `6032708d…c62f`, incl. the F9 BackHandler fix commit
  `3608510`; pre-fix gate build was `77a9cfcc…fa50`), 4-ABI Rust `.so`
  (39 symbols), 0 fatal exceptions. See `EXECUTION-20260721T071847Z.md`.
- [x] Device gate, Maestro flows — **CLOSED 2026-07-21 on COMPOSED evidence
  (Reviewer waiver, OQ-3)**: run4 single-clean-run 8/11 on the pre-fix APK
  (`77a9cfcc…`, OQ-2 measurement window) + flow09 GREEN post-fix in the
  standard task on APK `6032708d…` (only delta = 1-file MainActivity
  BackHandler fix, commit `3608510`) ⇒ inferred 9/11-equivalent on the 9
  achievable flows; F11/F12 structurally absent. **NOT a single-run 9/11 —
  composed**; optional clean run7 pending user ruling on OQ-3. Enforcement
  =1 throughout (bare download 401 verified). No run implicates the Rust
  domain. `EXECUTION-20260721T071847Z.md` addenda 1–8.

| Gate | Status | Where |
|---|---|---|
| cargo test (58 + 8) | **Verified 2026-07-20** on WSL2 box | EXECUTION-20260720T121500Z.md |
| round_trip.c oracle 100/100 (unmodified) | **Verified 2026-07-20** | same |
| 39-symbol conformance (diff vs header = empty) | **Verified 2026-07-20** | same |
| Android staticlibs ×4 ABIs (39 syms + arch + shim link) | **Verified 2026-07-20** | same |
| Desktop `App7NativeParityTest` | **Verified 2026-07-20, 25/25** on BOTH machines (this box used JDK 17 from `~/toolchains`) | EXECUTION-20260720T121500Z.md |
| APK build / install / smoke (emulator-5554) | **Verified 2026-07-21** — sha match (`6032708d…`, F9 fix incl.), 39-sym .so, no crash | EXECUTION-20260721T071847Z.md |
| Maestro flows (9/11 baseline) | **CLOSED 2026-07-21, composed (OQ-3 waiver)** — run4 8/11 + post-fix flow09 GREEN ⇒ inferred 9/11-equivalent; F9 fixed via `3608510`; F11/F12 structurally absent | EXECUTION-20260721T071847Z.md addenda |
| F5 ship gate (token-suggestions, flow 05) | **Verified on Rust build 2026-07-21** — mechanical gate `F5_token_suggest ✅ PASS` on APK `6032708d…` (dex marker `annotation-suggestion-`, runtime `-$idx` @ MainScreen.kt:912); flow 05 GREEN in run4 incl. tag-level asserts; flow YAML unmodified since 2026-06-21 (untracked in git — integrity gap flagged) | EXECUTION-20260721T071847Z.md addendum 9 |
| F6 ship gate (pin-app, flow 06) | **Verified on Rust build 2026-07-21 under AMENDED spec** (old `pin-app` tag never satisfiable in any build — baseline `4f859bc1…` 0 hits; Reviewer-approved amendment) — markers `togglePinned`/`isPinned`/`pinned_apps` in dex (7 hits; source LauncherViewModel.kt:308/394/553); run4 flow 06 GREEN (long-press → 📌 visible); flow tracked at `569e298` | EXECUTION-20260721T071847Z.md addenda 10–11 |
| F7 ship gate (free-annotation-enter, flow 07) | **Verified on Rust build 2026-07-21** — dex hit `annotation-free` (static testTag @ MainScreen.kt:921); mechanical gate `F7_free_annot ✅ PASS`; run4 flow 07 GREEN end-to-end (tap by id → dialog → input+Enter → dialog dismissed); flow tracked at `569e298`, unweakened (mtime 2026-06-21) | EXECUTION-20260721T071847Z.md addendum 12 |

## Evidence

- `EXECUTION-20260720T065810Z.md` — Rust port + host gates + desktop parity
  25/25 (other machine).
- `EXECUTION-20260720T121500Z.md` — this box: gate re-runs + the
  first-ever Android cross-build (4 ABIs), real pasted output.
- `EXECUTION-20260721T071847Z.md` — device gate: build/install/smoke
  verified on emulator-5554; Maestro 2/11, blocked on dead comic-server
  (backend cascade — raw per-flow evidence + curl probe inside).

## Size note (honest)

The Rust-backed `.so` is much larger than the ~12 KB C one. Measured here
(NDK r26 clang -Os, `--gc-sections`, stripped, JNI shim + Rust archive):

| ABI | stripped .so | .text |
|---|---|---|
| x86_64 | 356,240 B | 346,419 B |
| arm64-v8a | 356,656 B | 346,873 B |
| armeabi-v7a | 272,924 B | 266,813 B |
| x86 | 363,204 B | 357,076 B |
| host linux-x86_64 (gcc -Os, unstripped) | — | 323,605 B |

That is ~22–30× the C `.text` (12,189 B) — core::fmt and panic plumbing
dominate. Known and accepted for this feature; do not "fix" by changing
the design.

## Related

- Supersedes the C projection in `domain-core/src/` (kept on disk as the
  reference oracle — no longer compiled into the app); `domain-haskell/`
  retained as historical oracle.
- Inherits the backend-domain-pointer work (reserved-IP backend
  `129.212.166.125:8080`, testTagsAsResourceIds shim) from its base folder.
- `RESUME.md` §cascade — discipline now reads RUST TYPE → RULE → ffi →
  header (frozen) → jni shim → App7Native.kt → tests.
