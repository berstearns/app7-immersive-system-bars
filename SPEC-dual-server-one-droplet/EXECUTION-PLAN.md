# EXECUTION PLAN — Two servers, one droplet (app7 ⊥ app11)

Droplet: **`129.212.166.125`** (only DO instance). Each phase has a **gate** —
an artifact check that must pass before the next phase. Owners: **[A7]**
app7hs-reviewer, **[A11]** app11-reviewer.

Legend: ☐ todo · ✅ verified-done (with evidence) · ⚠ needs decision

---

## Phase 0 — Freeze & capture current truth  (mostly ✅ already)

- ✅ [A7] Foreground app on 5554 = `me.bstearns.app11` (`dumpsys window | grep
  mCurrentFocus`). Root cause of the "wiped comics" false alarm.
- ✅ [A7] `129.212.166.125:8080/catalog.json` → comics intact; `:8812` → app11
  papers; `:8080?app=app11` → empty. No data loss.
- ☐ [A7] Save these command outputs into a fresh `EXECUTION-*.md` in the app7
  folder as the incident record.

**Gate 0:** incident record written; both reviewers agree "no server data loss,
cross-rig emulator contamination only."

---

## Phase 1 — Lock server isolation on the droplet  (owner [A11], verify [A7])

- ☐ [A11] Confirm the two systemd units are enabled + bound to distinct dirs:
  `systemctl is-enabled comic-server app11-content` and the `CONTENT_DIR` of each
  (`/opt/app11-content` for app11; app7's comic root for :8080). Paste output.
- ☐ [A11] Confirm firewall exposes only :8080 and :8812 as intended.
- ☐ [A11] (R6) Decide `?app=` policy on :8812: either honor it (return typed
  404/empty for a wrong app) or document "port-only isolation" in the client
  contract. ⚠ decision.

**Gate 1 [A7 verifies, content not liveness]:**
`curl :8080/catalog.json` = only comic ids · `curl :8812/catalog.json` = only
paper ids · `curl :8080/catalog.json?app=app11` = `{"comics":[]}`.

---

## Phase 2 — Cross-rig emulator hygiene  (owner [A11] for its side, [A7] for rig)

- ☐ [A11] Remove its cross-rig side effect from app7's emulator: **either**
  `adb -s emulator-5554 uninstall me.bstearns.app11` **or** move app11 e2e to its
  own AVD (`Pixel_API_34:5556`). ⚠ user/[A7] preference: keep 5554 app7-only.
- ☐ [A7] After cleanup, confirm 5554 has only `me.bstearns.app7`:
  `adb -s emulator-5554 shell pm list packages | grep bstearns`.
- ☐ [A7] Relaunch app7 to foreground and **screenshot** — expect the **comic**
  catalog (chainsawman, one piece…), not papers.
- ☐ [A7] Standing rule: app7 rig only ever installs/launches `me.bstearns.app7`
  on 5554; app11 rig uses its own AVD.

**Gate 2:** `pm list packages` on 5554 = app7 only **and** a screenshot shows the
comic download list. This is the artifact that would have prevented the incident.

---

## Phase 3 — Client config pinning + reviewer protocol  (owner [A7] & [A11])

- ☐ [A7] Pin `androidApp/server-config.yaml backend_url: "http://129.212.166.125:8080"`
  and confirm the **installed** APK resolves to it (not a stale host from a prior
  build/record).
- ☐ [A11] Pin app11 `BACKEND_URL` → `129.212.166.125:8812`.
- ☐ [A7] Adopt the reviewer check as protocol (also saved as memory
  `reviewer-verify-server-content-not-liveness`): before any "app shows/serves X"
  claim — (1) read host from live config, (2) GET catalog & assert own ids
  present + foreign ids absent, (3) confirm foreground package.

**Gate 3:** both client configs pinned in-repo; reviewer protocol documented.

---

## Phase 4 — Resume app7 F5 ship-gate on the clean emulator  (owner [A7])

Only after Gates 0–2 pass:

- ☐ [A7] Prereq: flow 02 downloads `chainsawman-fr-hq-tokens-translated` from
  :8080 and makes it active (now that the emulator is app7-only and the catalog
  is comics).
- ☐ [A7] Run flow 05 `05-token-suggestions.yaml -e BUBBLE_IDX=0`; all assertions
  green (`annotation-suggestion-0`, `annotation-free`, `annotation-others`,
  `annotation-bar-expanded`, bar closes).
- ☐ [A7] Fresh `EXECUTION-*.md` with the real maestro pass tied to APK sha
  (rebuild if `server-config.yaml` changed → new sha, re-audit).
- ☐ [A7] Flip `FEATURE.md` → Verified with evidence pointer.

**Gate 4 (ship gate):** a real flow-05 pass, tied to the installed APK's sha,
recorded in a new EXECUTION file → FEATURE.md Verified.

---

## Decision points for the user (⚠)

1. **Phase 2 cleanup:** have app11-reviewer `uninstall me.bstearns.app11` from
   5554, or just relaunch app7 and leave app11 installed but backgrounded?
2. **Phase 1 R6:** make `:8812` reject wrong-app requests, or accept port-only
   isolation as the documented contract (cheaper, already true)?
3. **Rebuild question:** `server-config.yaml` currently already points at
   `:8080` on the right droplet — if the installed APK already matches, no
   rebuild is needed before resuming F5. [A7] to confirm the installed APK's
   baked host before Phase 4.
