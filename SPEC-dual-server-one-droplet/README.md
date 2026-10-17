# SPEC — Two content servers, one DigitalOcean droplet (app7 comics ⊥ app11 papers)

**Status:** draft for approval · **Author:** app7hs-reviewer · **Date:** 2026-07-18
**Sibling input:** app11-reviewer (app11-fence rig) — folded in below.

## 0. Why this spec exists (the incident, corrected)

A screenshot of emulator `app7_e2e_api34:5554` showed a download list of
**research papers** ("App11 Placeholder", "Mauve 2021", "Pythia 2023",
"Spanbert 2020", "T5 …", "Trainable Spaced Repetition 2016") and was read as
"app7's comic content was deleted and replaced by app11 content."

**That diagnosis was wrong, and verified wrong from artifacts:**

| Check | Command | Result |
|---|---|---|
| Foreground app on 5554 | `adb -s emulator-5554 shell dumpsys window \| grep mCurrentFocus` | `me.bstearns.app11/.android.MainActivity` — **app11 was in front, not app7** |
| Both apps installed on 5554 | `adb -s emulator-5554 shell pm list packages \| grep bstearns` | `me.bstearns.app7` **and** `me.bstearns.app11` |
| app7 comics on droplet | `curl 129.212.166.125:8080/catalog.json` | full comic catalog (agniputra, asterix, chainsawman, one piece…) — **intact** |
| app11 papers on droplet | `curl 129.212.166.125:8812/catalog.json` | app11 papers only (app11-placeholder, mauve-2021, pythia-2023…) |
| app11 leak onto :8080 | `curl 129.212.166.125:8080/catalog.json?app=app11` | `{"comics":[]}` — **no app11 content on app7's server** |

**Conclusion: no server data loss.** The servers are already correctly split.
The failure was (a) **cross-rig emulator sharing** — app11's rig installed and
foregrounded `me.bstearns.app11` on app7's e2e emulator — and (b) a **reviewer
protocol gap** — screenshotting/curl-probing without first confirming *which app*
and *which live host* were in play. This spec locks in the isolation so the
confusion cannot recur, on the single droplet available.

## 1. Verified current architecture (the baseline to formalize)

One droplet, **`129.212.166.125`** (the only DigitalOcean instance;
historically `comic-server-sfo3-01` / droplet id `583224019`). Two independent
Flask services, distinct ports, distinct content roots:

| App | Service | Port | CONTENT_DIR | Serves | Client APK points at |
|---|---|---|---|---|---|
| **app7** | comic-server (`minimal-ftp-flask`) | **:8080** | app7 comic root | comics (`.zip`) | `129.212.166.125:8080` |
| **app11** | content-server (`app11-content.service`, fork of comic-server) | **:8812** | `/opt/app11-content` | papers (`.zip`) | `129.212.166.125:8812` |

- Same catalog.json *shape* (client-compatible), **different data roots** — a
  catalog rebuild on one can never absorb the other's zips.
- app7 comic-server has **no** `/health`; app11 content-server adds `/health`.
- **Known soft spot:** app11's content-server accepts `?app=` for client
  compatibility but **ignores it** — it always serves `/opt/app11-content`. So
  correctness depends entirely on each client pointing at the right *port*.

## 2. Design principles (parsimonious, single-box)

1. **One service per app, one port per app, one content dir per app.** Never a
   shared `CONTENT_DIR`. (Already true — this spec makes it a written invariant,
   not an accident.)
2. **Client pins its own `host:port`** in the live config
   (`androidApp/server-config.yaml` `backend_url` for app7; `BACKEND_URL` for
   app11) and a reviewer reads it **from the live file at check time**, never
   from prose or a prior record.
3. **Content-scope is enforced or explicitly documented.** Because `:8812`
   ignores `?app=`, port is the *only* isolation boundary — so port ownership is
   load-bearing and must be pinned + monitored per app.
4. **Rig/emulator isolation:** each app's e2e loop owns its **own AVD**. No app
   installs or foregrounds another app's package on a shared emulator.
   - app7 → `app7_e2e_api34` (`emulator-5554`).
   - app11 → its own AVD (`Pixel_API_34:5556` is free) — **not** 5554.
5. **Reviewer verifies the running artifact, not the story:** confirm the
   foreground package (`dumpsys window | grep mCurrentFocus`) and the served
   *catalog contents* (expected ids present, foreign ids absent) before making
   any "content is wrong/right" claim.

## 3. Requirements (what "done" means)

- **R1** Droplet runs exactly two enabled services: comic-server:8080 (app7) and
  app11-content.service:8812 (app11), each bound to its own CONTENT_DIR.
- **R2** `:8080/catalog.json` contains app7 comic ids and **zero** app11 paper
  ids; `:8812/catalog.json` contains app11 paper ids and **zero** comic ids.
- **R3** app7's `server-config.yaml backend_url` = the droplet **:8080**; app11's
  `BACKEND_URL` = the droplet **:8812**. Both pinned in-repo.
- **R4** `emulator-5554` (app7 rig) has **only** `me.bstearns.app7` installed and
  foregrounded during app7 e2e. app11 test app removed from 5554.
- **R5** A documented reviewer check (foreground pkg + catalog content) is part
  of every "app shows X" claim in both rigs.
- **R6** (hardening, optional) app11 content-server either honors `?app=` or the
  client contract states port-only isolation, so a mispointed client fails
  loudly (empty/typed error) rather than silently serving the other corpus.

## 4. Non-goals

- No second droplet (only one DO instance exists — by user constraint).
- No change to app7 domain/Haskell code or the F5 feature itself.
- No migration of app7 comic data (it is intact; moving it would add risk).

## 5. Cross-rig ownership & sign-off

- **app7hs-reviewer** owns R3(app7), R4, R5(app7), and resuming F5.
- **app11-reviewer** owns R1/R2 verification on the droplet, R3(app11), R6, and
  cleaning up its cross-rig side effect (app11 on 5554). It has offered to either
  `adb uninstall me.bstearns.app11` from 5554 or relaunch app7 to the foreground.

See `EXECUTION-PLAN.md` for the phased steps and verification gates.
