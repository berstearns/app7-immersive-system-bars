# app7-haskell (latest) — SINGLE DROPLET (one DO host serves content + sync)

> Build: `app7-haskell-token-annotation-suggestions_20260615_204826` · `me.bstearns.app7`
> Select with `BACKEND_PROVIDER=tcp` — this build's `.env` already carries the droplet's
> `BACKEND_URL`/`QUEUE_HOST` as the TCP fallback (Turso is the blank-provider default).

PNG: `droplet-deploy.png` (render from this file)

This is the **current real self-hosted setup**: one DigitalOcean droplet `137.184.225.153`
hosts **both** backend planes — the Flask comic-server (content) **and** the self-hosted TCP
queue (sync sink) — so a physical phone over the internet has a single host to talk to.
No managed cloud DB; everything self-hosted on the one box.

```
        ┌────────────────────────────────────────────────────┐
        │  Physical phone (DEV or RELEASE)  /  Desktop        │
        │  me.bstearns.app7 · token-annotation-suggestions    │
        │  app7-haskell · libapp7_domain.so · Room (local)    │
        │  BACKEND_URL = http://137.184.225.153:8080          │
        │  QUEUE_HOST  = 137.184.225.153 : 9999 (provider=tcp)│
        └──────┬───────────────────────────┬─────────────────┘
   catalog +   │                           │  learner-records
   comic .zip  │ GET /catalog.json         │  SyncService → TCP :9999
  (HTTP :8080) │ GET /download/<slug>.zip  │  (self-hosted, NOT Turso)
              ▼                            ▼
        ╔════════ DigitalOcean droplet  137.184.225.153 ══════════╗
        ║   ── firewall: SSH/HTTP ports are IP-ALLOWLISTED ──     ║
        ║  ┌───────────────────────────┐  ┌────────────────────┐ ║
        ║  │ comic-server (Flask) :8080 │  │ TCP queue worker    │║
        ║  │ tmux session: comic-server │  │ :9999  (sync sink)  │║
        ║  │ /opt/comic-server/comics/  │  └─────────┬──────────┘ ║
        ║  └───────────────────────────┘            │ write      ║
        ║                                           ▼            ║
        ║                               ┌─────────────────────┐  ║
        ║                               │ sqlite / self-hosted │ ║
        ║                               │ libsql (sqld)        │ ║
        ║                               │ learner-records      │ ║
        ║                               └─────────────────────┘  ║
        ╚══════════════════════════════════════════════════════════╝
```

**Key enablers**

- **One box, two planes.** The droplet runs the Flask comic-server on `:8080`
  (`/catalog.json`, `/download/<file>.zip`) and the TCP queue on `:9999` that sinks
  learner-records — the same host satisfies both `BACKEND_URL` and `QUEUE_HOST`.
- **Firewall allowlist.** The droplet's SSH/HTTP ports are IP-allowlisted; pre-flight
  `do-firewall add-ip` (never `update`) before any SSH-based pipeline stage.
- **Self-hosted sync, not Turso.** `provider=tcp` keeps records on the droplet — useful
  when you want a single host you fully control instead of managed Turso.
- **Plain HTTP today** (`http://…:8080`) → release builds need cleartext allowed for this
  host. For real HTTPS (required by stricter release configs / off-network devices) add an
  **sslip.io** hostname + Let's Encrypt reverse proxy — e.g. `137-184-225-153.sslip.io`
  → TLS terminates → proxies to `:8080` / `:9999` (this is the **Quiva droplet pattern**).
- Operate via `do-automation/comic-server/orch-comic.sh` (`comic-status`, `comic-deploy`,
  `comic-restart`, `logs capture`).

**Confirmed vs inferred**

- *Confirmed:* `BACKEND_URL=http://137.184.225.153:8080`, `QUEUE_HOST=137.184.225.153`,
  `QUEUE_PORT=9999` (this build's `.env`); Flask comic-server on droplet `:8080`, tmux session
  `comic-server`, comics in `/opt/comic-server/comics/` (`comic-pipeline/docs/08-serve.md`);
  firewall IP-allowlist (project memory + `do-automation`).
- *Inferred:* the TCP queue's server-side sink being **sqlite / sqld on the droplet** — the
  TCP queue server is the external `simple-tcp-comm` process, not code in this repo. The
  **sslip.io + Let's Encrypt** TLS box (Quiva pattern) is a suggested upgrade, not currently
  deployed (today is plain `http`).
