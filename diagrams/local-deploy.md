# app7-haskell (latest) — LOCAL deploy (emulator + laptop, no cloud)

> Build: `app7-haskell-token-annotation-suggestions_20260615_204826` · `me.bstearns.app7`
> Set `BACKEND_PROVIDER=tcp` (or blank the Turso URL) + point `BACKEND_URL`/`QUEUE_HOST`
> at the laptop to select this topology.

PNG: `local-deploy.png` (render from this file)

Everything runs on your laptop + an emulator. The two backend planes the app needs
— **content** (comics) and **learner-records sync** — are both served locally; no
Turso, no droplet, nothing leaves the machine. This is the throwaway dev loop.

```
                ┌──────────────────────────────────────────────┐
                │  Android Emulator (Pixel_API_34)              │
                │  app7-haskell  (DEV, me.bstearns.app7)        │
                │  token-annotation-suggestions build           │
                │  libapp7_domain.so  (Haskell domain, JNI)     │
                │  Room DB = local learner-records              │
                │  BACKEND_URL = http://10.0.2.2:8080           │
                │  QUEUE_HOST  = 10.0.2.2 : 9999  (provider=tcp)│
                └───┬──────────────────────────────┬────────────┘
  catalog +         │                              │  learner-records
  comic .zip        │ GET /catalog.json            │  SyncService → TCP
  download          │ GET /download/<slug>-lq.zip  │  (Turso OFF, no cloud)
       │            ▼   (10.0.2.2 → host 127.0.0.1)│
       │   ╔═══════════════ Laptop host (127.0.0.1) ═══════════════╗
       │   ║ ┌───────────────────────────┐  ┌────────────────────┐ ║
       └──►║ │ comic-server (Flask) :8080 │  │ TCP queue worker    │║
           ║ │ /opt/comic-server/comics/  │  │ :9999  (sync sink)  │║
           ║ └───────────────────────────┘  └─────────┬──────────┘ ║
           ║                                           │ write      ║
           ║                                           ▼            ║
           ║                               ┌─────────────────────┐  ║
           ║                               │ local sqlite         │ ║
           ║                               │ (learner-records)    │ ║
           ║                               └─────────────────────┘  ║
           ╚════════════════════════════════════════════════════════╝

   Desktop variant: the JVM AppImage hits 127.0.0.1 directly (no 10.0.2.2 hop).
```

**Key enablers**

- `10.0.2.2` = QEMU alias routing to the host's `127.0.0.1` (emulator-only magic IP).
  The desktop/JVM build skips this and uses `127.0.0.1` directly.
- **Turso is OFF.** With `BACKEND_PROVIDER=tcp` (or a blank Turso URL) `selectUploader`
  routes learner-records to the local TCP queue on `:9999` — nothing reaches the cloud.
- **DEV build → no asset bundling** (slim-binary rule): comics are *downloaded* from the
  local comic-server, not packed into the APK; `libapp7_domain.so` runs on-device.
- **Reading works offline** once a `<slug>-lq.zip` bundle is downloaded + unpacked
  (`FileImageDataSource` caches it; `conversations_ocr.json` preferred over `conversations.json`).
  Token-annotation suggestions read the same OCR words from that unpacked bundle.
- `http://` is cleartext → the DEV manifest must allow cleartext traffic to `10.0.2.2`.

**Confirmed vs inferred**

- *Confirmed:* the app reads `BACKEND_URL`, `QUEUE_HOST/PORT`, provider selection
  Turso→TCP→Supabase (`shared/.../data/sync/BackendModule.kt`); comic-server endpoints
  `/catalog.json`, `/download/<file>.zip` (`comic-pipeline/docs/08-serve.md`).
- *Inferred:* running the comic-server **on the laptop** (it normally lives on the droplet);
  the TCP queue worker's local sink being plain sqlite — the TCP server is the external
  `simple-tcp-comm` process, not code in this repo.
