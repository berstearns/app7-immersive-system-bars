# app7-haskell (latest) — deploy topologies

> Build: `app7-haskell-token-annotation-suggestions_20260615_204826`
> (`app7-haskell-latest` → this folder)
> `applicationId = me.bstearns.app7` · `namespace = pl.czak.learnlauncher.android`
> Haskell domain via JNI: `libapp7_domain.so`
> Feature of this build: **token-annotation suggestions** (tap a bubble → OCR words
> offered as annotation suggestions, ✎ free / ⋯ others).

Three ways this exact APK/AppImage can be wired at runtime. The **same binary**
switches topology purely through `.env` (`BACKEND_PROVIDER`, `TURSO_URL`,
`BACKEND_URL`, `QUEUE_HOST/PORT`) — no rebuild.

| Doc | Topology | Sync plane | Content plane | When |
|-----|----------|-----------|---------------|------|
| [`local-deploy.md`](local-deploy.md)   | **Local** (emulator/laptop) | local TCP `:9999` → sqlite | local Flask `:8080` | throwaway dev loop, no cloud |
| [`cloud-deploy.md`](cloud-deploy.md)   | **Cloud / managed** | **Turso** libsql (`app7-sync`) | any HTTP host | sideloaded RELEASE, cross-device |
| [`droplet-deploy.md`](droplet-deploy.md) | **Single DO droplet** | self-hosted TCP `:9999` | Flask `:8080` on same box | one host you fully control |

## What THIS build defaults to

The `.env` in this folder has:

```
BACKEND_PROVIDER=        # blank → auto: Turso → TCP → Supabase
TURSO_URL=https://…      # populated  → Turso wins
BACKEND_URL=http://137.184.225.153:8080   # droplet (TCP fallback)
QUEUE_HOST=137.184.225.153 · QUEUE_PORT=9999
SUPABASE_URL=            # empty → tertiary fallback unused
```

Because `BACKEND_PROVIDER` is blank and `TURSO_URL` is populated, `selectUploader`
(`shared/.../data/sync/BackendModule.kt`) picks **Turso** → this build ships on the
**[cloud topology](cloud-deploy.md)** by default. Point it at the droplet by setting
`BACKEND_PROVIDER=tcp` (falls back to [droplet](droplet-deploy.md)); point it at the
laptop for the [local](local-deploy.md) dev loop.

## Rendering the PNGs

These are the ASCII-source `.md` files. The top-level `diagrams/` PNGs
(`local-deploy.png`, etc.) were rendered from equivalent markdown; regenerate this
folder's PNGs the same way when you want image versions.
