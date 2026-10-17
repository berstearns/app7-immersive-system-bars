# APPLY CHECKLIST — isolation-applied spec

Owners: **[A7]** app7hs-reviewer · **[A11]** app11-reviewer. Each step has an
artifact gate. Droplet `129.212.166.125`. app7 AVD `app7_e2e_api34:5554`.

## Step 1 — De-contaminate app7's emulator (D1 / R1)  [A11 acts, A7 verifies]

- [ ] [A11] `adb -s emulator-5554 uninstall me.bstearns.app11`
- [ ] [A11] Relocate app11 e2e to its own AVD (`Pixel_API_34:5556`); never 5554.
- [ ] [A7] Verify: `adb -s emulator-5554 shell pm list packages | grep bstearns`
      → **only** `me.bstearns.app7`.

**Gate 1:** 5554 shows app7 only.

## Step 2 — Confirm app7 shows comics on the clean emulator (INV-5 / R2)  [A7]

- [ ] `adb -s emulator-5554 shell monkey -p me.bstearns.app7 -c android.intent.category.LAUNCHER 1`
- [ ] `adb -s emulator-5554 shell dumpsys window | grep mCurrentFocus`
      → `me.bstearns.app7/...MainActivity`.
- [ ] Screenshot → **comic** download list (chainsawman, one piece…), not papers.

**Gate 2:** foreground = app7 AND screenshot = comics.

## Step 3 — Verify server isolation, content not liveness (INV-1/2)  [A7]

- [ ] `curl -s 129.212.166.125:8080/catalog.json | jq '.comics[].id'` → comic ids only.
- [ ] `curl -s 129.212.166.125:8812/catalog.json | jq '.comics[].id'` → paper ids only.
- [ ] `curl -s '129.212.166.125:8080/catalog.json?app=app11'` → `{"comics":[]}`.

**Gate 3:** INV-2 holds.

## Step 4 — `:8812` wrong-app loud-fail guard (D2 / R3)  [A11 acts, A7 verifies]

- [ ] [A11] In app11 `content-server/app.py`: when `?app=` is present and ≠ `app11`,
      return an explicit empty catalog (or 409), never the paper corpus silently.
      Keep no-`?app=` behavior unchanged. Restart `app11-content.service`.
- [ ] [A7] Verify: `curl -s '129.212.166.125:8812/catalog.json?app=app7'`
      → empty/typed, not papers.

**Gate 4:** wrong-app request to :8812 fails loudly.

## Step 5 — Client pin verification (INV-3 / D3)  [A7 & A11]

- [ ] [A7] Confirm app7 baked host (no rebuild expected):
      `for d in $(unzip -Z1 androidApp/build/outputs/apk/debug/androidApp-debug.apk | grep '\.dex$'); do unzip -p androidApp/build/outputs/apk/debug/androidApp-debug.apk "$d" | strings | grep -oE 'https?://[0-9.]+:[0-9]+'; done | sort -u`
      → `http://129.212.166.125:8080` only.
- [ ] [A11] Confirm app11 baked host → `…:8812`.

**Gate 5:** each client pins its own port; no rebuild triggered for app7.

## Step 6 — Reviewer protocol documented (INV-5 / R4)  [A7 & A11]

- [ ] [A7] Memory `reviewer-verify-server-content-not-liveness` present (done).
- [ ] [A11] Mirror the same check in the app11 rig notes.

**Gate 6:** both rigs carry the content+foreground verification protocol.

## Step 7 — Resume F5 ship-gate (R5)  [A7]  — only after Gates 1–5

- [ ] Prereq flow 02: download `chainsawman-fr-hq-tokens-translated` from :8080,
      make active (emulator is app7-only, catalog is comics).
- [ ] Flow 05: `maestro --device emulator-5554 test
      ../app7-maestro-automation/flows/05-token-suggestions.yaml -e BUBBLE_IDX=0`
      → all assertions green.
- [ ] Fresh `EXECUTION-*.md` with the real maestro pass tied to APK sha
      `5be33ec8…` (rebuild+re-audit only if config changed the sha).
- [ ] Flip `FEATURE.md` → Verified with evidence pointer.

**Gate 7 (ship gate):** real flow-05 pass tied to the installed APK sha → FEATURE.md Verified.
