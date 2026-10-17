# Maestro UI Automation — Playbook (app7-haskell)

A reusable, modular recipe for automating end-to-end UI use cases on this app with
**Maestro**, distilled from a full Quiva build-out. Copy this `docs/` file into any
new app and change the **App facts** + **Use cases** sections; the methodology is
identical.

---

## App facts (fill these in per app)

| Field | Value |
|-------|-------|
| App | app7 — "manga reader" (KMP: `androidApp` / `desktopApp` / `domain-haskell`) |
| `appId` (package) | `pl.czak.imageviewer.app7` *(verify per build: `adb shell pm list packages \| grep app7`)* |
| Launch activity | `pl.czak.learnlauncher.android.MainActivity` |
| UI framework | **Native Kotlin / Compose (KMP)** — *not* React Native |
| Emulator | `emulator-5554` (Pixel API 34) |

> **Why the framework matters:** this app is native Compose, so Maestro's `inputText`
> types directly (no ADBKeyBoard workaround needed — that was only for React-Native
> *New Architecture* apps). The patterns below otherwise apply unchanged.

---

## Why Maestro (not raw ADB + uiautomator)

We proved on a React-Native app that raw `adb`/`uiautomator` automation is brittle for
anything beyond a flat screen: injected text is ignored on RN-Fabric, multi-step
*scrolling* forms don't expose their inputs reliably, and coordinate taps drift.
**Maestro** fixed all of it: native text entry, `scrollUntilVisible`, auto-waits, and
flow modularity. Use raw ADB only for environment setup (install, push files, DB checks),
not for driving the UI.

---

## Install & prerequisites

```bash
# Maestro CLI (already on this machine at /home/b/t/maestro/bin/maestro)
export PATH="/home/b/t/maestro/bin:$PATH"
maestro --version          # 2.6.x
# Emulator running + app installed (build & install the debug APK first)
adb devices                # emulator-5554  device
adb shell pm list packages | grep app7
```

---

## Modular structure (create this in the app repo)

```
maestro/
├── flows/
│   ├── launch.yaml          # cold start → first usable screen (reused by every flow)
│   ├── login.yaml           # if the app has auth; otherwise fold into launch
│   ├── 01-<use-case>.yaml   # ONE flow per use case (self-contained)
│   └── _inspect.yaml        # throwaway: drive to a screen + stop, to dump its tree
├── data/
│   └── <use-case>.tsv       # test inputs (ASCII), one row per case — NEVER inline
└── run.sh                   # iterate data rows → maestro test -e ... ; tally PASS/FAIL
```

**One primitive idea per file.** Each `flows/NN-*.yaml` is a complete use case. Shared
preamble (launch/login) lives in its own flow and is invoked or copied at the top.

---

## Core patterns (the transferable lessons)

1. **Selectors — prefer stable ids.** Maestro matches `text`, `id` (resourceId), and
   accessibility labels. Compose elements without a `Modifier.testTag(...)` are matched
   only by visible text → fragile. **Add `testTag`/`contentDescription` to interactive
   elements**; until then, match visible `text` and keep strings unique.
2. **Scrolling screens → `scrollUntilVisible`.** `tapOn` only sees on-screen elements.
   For anything below the fold:
   ```yaml
   - scrollUntilVisible: { element: { text: "Save" }, direction: DOWN }
   - tapOn: "Save"
   ```
3. **Beware a field that echoes your input.** If you type "Tokyo" into a search box and
   then `tapOn: ".*Tokyo.*"`, the *search field itself* matches (index 0) — you tap the
   field, not the result. Match a pattern only the result row has (e.g. `".*Tokyo.* - .*"`)
   or skip index 0.
4. **Container vs row.** A list's parent node can match a broad regex and its center
   lands *between* rows → missed tap. Match a single row's text, or point-tap the row.
5. **External pickers (gallery / autocomplete / system dialogs)** are the brittlest
   parts. Use a `point:` tap (e.g. `{ point: "18%,30%" }`) for a grid cell, mark
   permission dialogs `optional: true`, and `waitForAnimationToEnd` after they close.
6. **`inputText` is ASCII-only** (Maestro #146). Keep fixtures ASCII; for accented
   on-screen results, match an ASCII *prefix* (`"Florian"` matches "Florianópolis").
7. **Wait, don't sleep.** Use `extendedWaitUntil: { visible: ..., timeout: N }` and
   `waitForAnimationToEnd`, never fixed sleeps.
8. **Verdict from two signals.** Assert the success UI (a confirmation/next screen) AND,
   when possible, verify ground truth out-of-band (DB row, file on disk, logcat).

---

## Data-driven runs (varied inputs, reproducible)

Inputs live in `data/*.tsv` (ASCII). The flow uses `${VARS}`; the runner injects them:

```bash
maestro test -e PAGE="3" -e TOKEN="...ktk..." flows/03-zoom-token-translation.yaml
```

⚠️ **Gotcha (verified on Maestro 2.6.1):** a flow's `env:` defaults block *overrode* the
CLI `-e` values — every run silently used the defaults. **Do not declare `env:` defaults
in a flow you parameterize with `-e`.** Let `-e` be the only source.

`run.sh` skeleton:
```bash
#!/usr/bin/env bash
set -uo pipefail
export PATH="/home/b/t/maestro/bin:$PATH"
pass=0; fail=0
while IFS=$'\t' read -r id a b; do
  [[ -z "$id" || "$id" == \#* || "$id" == id ]] && continue
  if maestro test -e A="$a" -e B="$b" flows/01-usecase.yaml >"/tmp/m-$id.log" 2>&1
  then pass=$((pass+1)); echo "$id PASS"; else fail=$((fail+1)); echo "$id FAIL ($id log)"; fi
done < data/usecase.tsv
echo "passed $pass / $((pass+fail))"
```

---

## Evidence collection

Maestro writes a per-run artifact dir to `~/.maestro/tests/<timestamp>/` with a
screenshot at the failing step and a full command log — inspect those first on failure.
For richer evidence, snapshot before/after externally:
`adb -s emulator-5554 exec-out screencap -p > step.png`. (On Quiva we also persisted
runs→cases→steps→screenshots into SQLite; replicate if you want a queryable history.)

---

## Operational gotchas (bit us; avoid them)

- **Kill orphaned Maestro JVMs between runs** — leftover `maestro.cli.AppKt` processes
  hold the adb channel and cause `IOException: ... closed` on the next run:
  `pkill -f 'maestro.cli.App[K]t'`.
- **Don't hammer adb concurrently** while Maestro runs (no parallel `adb`/screenshot
  loops) — it drops Maestro's channel. Monitor via the app's DB/files instead.
- If adb gets wedged after hours: `adb kill-server && adb start-server`, or cold-restart
  the emulator (`adb emu kill` → relaunch). App + IME settings persist in the AVD.
- After a `launchApp`, the app is **restarted** (not resumed) — flows can't "continue"
  an in-progress wizard across `launchApp`.

---

## Use cases to automate (app7 — skeletons)

This app already has a *raw-ADB* reference harness at
`/home/b/p/minimal-android-apps/app7-adb-automation/` (scenarios: login, zoom, token
translation, annotation). Port each scenario to a Maestro flow:

```yaml
# flows/01-login.yaml
appId: pl.czak.imageviewer.app7
---
- launchApp: { clearState: true }
- assertVisible: "Login"            # FEATURE_SPEC: Login screen on first start
- tapOn: { id: "user_id_input" }    # add a testTag; else match the hint text
- inputText: "user9395"
- tapOn: "Login"
- assertVisible: "..."              # a MainScreen element (page reading view)
```

```yaml
# flows/02-zoom-token-translation.yaml  (Main → tap a token → see its translation)
appId: pl.czak.imageviewer.app7
---
- launchApp
- assertVisible: "..."              # MainScreen marker
- tapOn: { point: "50%,45%" }       # tap a token region (or by testTag once added)
- assertVisible: "..."              # the translation/annotation bar text
```

Other use cases from the FEATURE_SPEC: open Settings (long-press empty area), Chat,
Annotation Log, annotation-bar position strategy. One flow each, `data/`-driven where
inputs vary.

---

## Quick start checklist

- [ ] `maestro/flows/`, `maestro/data/`, `maestro/run.sh` created
- [ ] `launch.yaml` reaches the first usable screen reliably
- [ ] testTags added to the elements each flow touches (or unique text confirmed)
- [ ] one `NN-<use-case>.yaml` per use case; inputs in `data/*.tsv` (ASCII)
- [ ] no `env:` defaults in `-e`-parameterized flows
- [ ] runner tallies PASS/FAIL; success asserted in-UI + verified out-of-band
- [ ] kill stray Maestro JVMs between runs; no concurrent adb hammering
