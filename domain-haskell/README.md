# domain-haskell — algebraic source of truth for app7

This directory holds the **Haskell algebra** that app7's pure domain is
defined in. It is **NOT shipped** in the APK / AppImage — those ship
`domain-core/*.c` (Phase 2), a hand-translation of this algebra.

## Layout

| File | Purpose |
|---|---|
| `App7Domain.hs` | All ADTs + pure predicates. The source of truth. |
| `Test.hs` | Hardcoded round-trip assertions. Exit 0 = green. |
| `domain-to-c.sh` | Step 1: `ghc -dynamic -c` → `build/*.o` |
| `compile.sh` | Step 2: link → `./app7-domain-test` |

## Why hand-translate instead of cross-compiling GHC

See `~/p/all-my-tiny-projects/sla-ddd-to-c/docs/auto-docs/ffi-export-for-android.md` §4. **TL;DR:** GHC cross-compilation to Android is fragile and the resulting `.so` bundles a ~2–3 MB Haskell runtime per ABI. Hand-translating to C gives us a ~5–20 KB `.so` that loads identically on Android (JNI) and Desktop (`System.load`), at the cost of maintaining two copies of the algebra. **`ParityTest.kt` (Phase 5) is what enforces the two stay in sync.**

## Build

```bash
./domain-to-c.sh && ./compile.sh && ./app7-domain-test
```

Expected output ends with:
```
── N/N passed ──
```
and `echo $?` returns `0`.

## Cascade discipline

Any change to a rule here must propagate per `INSTRUCTION.md §3`:

```
TYPE → RULE → WIRE → CONSUMER → STORAGE → DATA → TESTS
```

Concretely:
1. Edit `App7Domain.hs` (TYPE/RULE).
2. Add an assertion to `Test.hs` covering the new behavior (TESTS).
3. Mirror in `domain-core/include/app7_domain.h` enum + matching `.c` (Phase 2).
4. Update the JNI shim if the C signature changed (Phase 3 — WIRE).
5. Update Kotlin `App7Native.kt` `external fun` declarations (CONSUMER).
6. Audit Room CHECK constraints (STORAGE).
7. Add a Kotlin ParityTest case mirroring step 2's Haskell assertion (DATA + TESTS).

A single-layer commit is a smell.
