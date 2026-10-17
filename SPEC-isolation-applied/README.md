# SPEC (APPLIED) — app7/app11 server + rig isolation on one droplet

**Status:** decisions resolved — ready to execute · **Author:** app7hs-reviewer
**Date:** 2026-07-18 · **Supersedes the open ⚠ items in:** `../SPEC-dual-server-one-droplet/`

This is the decision-resolved version of the dual-server spec. Every previously
open choice is now fixed to its recommended value and backed by an artifact
check. Nothing here is left to preference.

## Resolved decisions (were ⚠, now fixed)

| # | Decision | Resolution (applied) | Evidence / rationale |
|---|---|---|---|
| D1 | Shared emulator cleanup | **Uninstall `me.bstearns.app11` from `emulator-5554`; app7 rig is app7-only; app11 uses its own AVD (`Pixel_API_34:5556`).** | 5554 had both apps; app11 was foregrounded → the misdiagnosis. One app per rig removes the whole class of confusion. |
| D2 | `:8812` `?app=` handling | **Port-only isolation is the contract, AND add a loud-fail guard: a request to `:8812` with `?app=` ≠ `app11` returns an explicit empty/typed response, never silently the paper corpus.** | Cheap (isolation already true via distinct ports+dirs) + prevents a mispointed client from silently reading the wrong corpus. |
| D3 | Rebuild app7 APK? | **No rebuild needed.** Installed/built APK already bakes `http://129.212.166.125:8080`. | dex strings of `androidApp-debug.apk` → `http://129.212.166.125:8080`; no `.env`/`BACKEND_URL` override; source `server-config.yaml` matches. |

## Invariants this spec enforces

- **INV-1 (server):** one droplet `129.212.166.125`, two services, **never** a
  shared `CONTENT_DIR` — app7 comic-server `:8080`, app11 content-server `:8812`
  (`/opt/app11-content`).
- **INV-2 (content):** `:8080/catalog.json` = only comic ids; `:8812/catalog.json`
  = only paper ids; `:8080/catalog.json?app=app11` = `{"comics":[]}`.
- **INV-3 (client pin):** each client's effective backend host is read from the
  **live** build input (`BuildConfig.BACKEND_URL` ← `BACKEND_URL` env else
  `server-config.yaml`), pinned to its own port; verified from the built dex, not
  from prose.
- **INV-4 (rig):** each app's e2e loop owns exactly one AVD and installs/launches
  **only its own package** there. app7 = `app7_e2e_api34:5554`;
  app11 = `Pixel_API_34:5556`.
- **INV-5 (reviewer):** no "app shows/serves X" claim without (a) live-config
  host, (b) catalog content assertion (own ids present, foreign absent),
  (c) foreground-package check.

## Verified baseline (facts, 2026-07-18)

- Foreground on 5554 was `me.bstearns.app11/.android.MainActivity` (the screenshot
  source). Both `me.bstearns.app7` + `me.bstearns.app11` installed on 5554.
- `129.212.166.125:8080` → comics intact (agniputra, asterix, chainsawman, one
  piece…). `:8812` → app11 papers (app11-placeholder, mauve-2021, pythia-2023…).
  `:8080?app=app11` → empty. **No server data loss.**
- app7 APK baked host = `http://129.212.166.125:8080` (from dex).

## Requirements

- **R1** 5554 carries only `me.bstearns.app7`; app11 relocated to `Pixel_API_34:5556`.
- **R2** INV-1..INV-3 verified green on the droplet and the installed APK.
- **R3** `:8812` returns a typed empty/error for `?app=` ≠ `app11` (D2 guard).
- **R4** Reviewer protocol INV-5 documented in both rigs (app7 copy = memory
  `reviewer-verify-server-content-not-liveness`).
- **R5** F5 ship-gate resumes and completes on the clean, app7-only emulator
  **without** a rebuild (D3), unless a config change forces a new sha.

## Non-goals

- No second droplet (only one DO instance, by constraint).
- No app7 domain/Haskell/F5 feature-code changes.
- No app7 comic-data migration (intact; moving adds risk).

See `APPLY-CHECKLIST.md` for the ordered, gated steps + exact commands.
