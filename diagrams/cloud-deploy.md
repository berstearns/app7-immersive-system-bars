# app7-haskell (latest) — CLOUD deploy (managed Turso, sideloaded RELEASE, NO Play Store)

> Build: `app7-haskell-token-annotation-suggestions_20260615_204826` · `me.bstearns.app7`
> **This is the DEFAULT topology for this build**: `.env` has `BACKEND_PROVIDER=` blank
> and `TURSO_URL` populated → `selectUploader` picks Turso.

PNG: `cloud-deploy.png` (render from this file)

Three distinct boundaries:
1. **Build host (your laptop)** — *build-time only*: Gradle assembles + you self-sign,
   then ship via leao-deploy / gdrive. **No Play Store, no EAS.**
2. **Managed cloud sync** — **Turso** (libsql) is the default sync backend and the
   server-side source of truth for cross-device learner-records. Supabase is a tertiary fallback.
3. **Content** — the comic-server (any HTTP host) serves the catalog + comic bundles.

```
   ┌──────────────────────────────────────────────────────────┐
   │ Build host  (your laptop — BUILD TIME, not a store)       │
   │   ./gradlew :androidApp:assembleRelease   (4 ABIs)        │
   │   self-sign: release-learnlauncher.keystore               │
   │   → leao-deploy / gdrive   (SIDELOAD, no Play Store)      │
   └───────────────────────────┬──────────────────────────────┘
              install signed APK / AppImage (dashed)
                               ▼
                 ┌────────────────────────────────────┐
                 │ Phone (RELEASE)  /  Desktop AppImage│
                 │ me.bstearns.app7 · token-annot build│
                 │ libapp7_domain.so · Room (local)    │
                 │ provider = turso  (default, blank→auto)│
                 └───┬───────────────┬─────────────────┘
        catalog +    │  learner-records sync          │  Google OAuth
        comic .zip   │  Hrana POST /v2/pipeline       │  ID token (PLANNED —
                     ▼  Authorization: Bearer <tok>   ▼  see google-auth spec)
       ┌──────────────────────────┐        ┌──────────────────────┐
       │ comic-server (HTTP host)  │        │ Google OAuth (3rd-party)│
       │ /catalog.json /download/  │        │ no Play Store needed   │
       └──────────────────────────┘        └──────────────────────┘
                     │  managed cloud sync
                     ▼
       ╔════════════ Turso  (managed libsql cloud) ═══════════════╗
       ║  app7-sync-bstearns.aws-eu-west-1.turso.io               ║
       ║  per-app DBs:  app7-sync · app11-sync · app9-…           ║
       ║  user_id-indexed learner-records                         ║
       ║  = SERVER SOURCE OF TRUTH for cross-device pull          ║
       ╚══════════════════════════╤══════════════════════════════╝
                                  ┆ tertiary fallback (provider=supabase)
                                  ▼
                       ┌──────────────────────────┐
                       │ Supabase (optional)       │
                       │ RLS by user_id            │
                       └──────────────────────────┘
```

## What runs WHERE

| Box | Where it lives | Lifetime |
|-----|----------------|----------|
| Gradle assemble + self-sign + leao-deploy | **your laptop** | build-time only |
| Phone (RELEASE) / Desktop AppImage | **end device** | runtime |
| `libapp7_domain.so` (Haskell domain) + Room | **on device** | runtime |
| comic-server (`/catalog.json`, `/download/`) | **HTTP host** (droplet today) | runtime |
| Turso libsql (learner-records, cross-device truth) | **managed cloud (Turso/AWS)** | runtime |
| Supabase (RLS fallback) | **managed cloud** | runtime |
| Google OAuth (identity, planned) | **3rd-party (Google)** | runtime |

Key idea: the **content plane** (comics) and the **sync plane** (learner-records) are
independent — the app can read comics from one host while syncing records to Turso.

## Confirmed vs inferred

**Confirmed from the repo / this build's `.env`:**
- `BACKEND_PROVIDER=` blank + `TURSO_URL=https://…` populated → auto-selects **Turso**.
- `TURSO_URL = https://app7-sync-bstearns.aws-eu-west-1.turso.io`, Bearer-token auth,
  provider priority Turso→TCP→Supabase (`shared/.../data/sync/BackendModule.kt`).
- Turso uploader speaks Hrana `/v2/pipeline` (`…/adapter/turso/`); per-app Turso DBs
  (`app7-sync`, `app11-sync`) per project memory.
- Release builds are **self-signed** (`release-learnlauncher.keystore`) and distributed by
  sideload — no Play Store anywhere in the build/deploy scripts.

**Inferred / planned (not proven by files here):**
- That the comic-server in cloud mode is a CDN / object store vs the droplet — today it is
  the droplet (see `droplet-deploy.md`); this diagram shows the generic "HTTP host".
- **Google OAuth** is a *planned* identity layer (`app7-ideas-for-later/google-auth-cross-app.md`),
  not yet wired into the sync token.
