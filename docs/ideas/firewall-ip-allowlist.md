# Idea — firewall strategy: attaching new IPs to a signed-in user

Status: **idea only**, nothing implemented. Captured 2026-07-20 while running the
Maestro suite against the Rust-domain build.

## Why this came up

app7 talks to three different network endpoints, and they have very different
exposure profiles:

| Endpoint | Transport | Today's exposure |
|---|---|---|
| `BACKEND_URL` — comic server (`:8080`) | plain HTTP | open port on a DigitalOcean droplet |
| `QUEUE_HOST:QUEUE_PORT` (`:9999`) | raw TCP | open port on the same droplet |
| Turso (`*.turso.io`) | HTTPS + DB-scoped token | managed, token-authenticated |

Turso is already the well-defended one: it is TLS, and access is a per-database
token rather than a network position. The two droplet ports are the problem —
they are reachable by anyone who finds the IP, and they carry no authentication
of their own. The obvious hardening move is a host firewall that denies by
default, which immediately raises the question this note is about: **how does a
legitimate phone, on a mobile network with a rotating IP, get let in?**

(During today's run both droplet ports were `connection refused` from the dev
laptop — services down, not firewalled. Same lesson either way: reachability is
currently an all-or-nothing property of the network, not of the user.)

## The core idea

Make the allowlist a **property of an authenticated user**, not of a static list
maintained by hand. An IP is admitted because a signed-in user vouched for it
from a channel that is already authenticated, and it is admitted only for as
long as that remains true.

The enrollment path deliberately does **not** run over the firewalled ports —
that would be a chicken-and-egg lock-out. It runs over the channel that is
already safe and always reachable:

```
phone (any IP)
  → signs in
  → writes {user_id, observed_ip, device_id, ttl} over HTTPS to Turso
       (or to a small always-open enrollment endpoint)
  → droplet-side reconciler polls that table every N seconds
  → renders nftables/ufw rules allowing those IPs to :8080 and :9999
  → expired rows are dropped and their rules withdrawn
```

Turso is the natural rendezvous point precisely because it is *not* behind the
firewall being managed. The live database already has an **`ip_umbrella`** table,
which looks like it was meant for something in this space — worth reading before
designing anything new.

> Schema drift worth flagging: `ip_umbrella` exists in the live Turso DB but is
> **not** in `schemas/turso-schema.sql` (that file defines 12 tables; the live DB
> has 14). Reconcile before building on it.

## Design points that actually matter

- **TTL is the whole trick.** Mobile IPs rotate constantly; a permanent
  allowlist grows without bound and every stale entry is a hole left open for
  whoever inherits that address from the carrier. Short TTL (hours) plus silent
  re-enrollment on each app launch keeps the rule set roughly the size of the
  active user base.
- **Never let the client assert its own IP.** The server records the source
  address it actually observed on the enrollment connection. A client-supplied
  IP is an allowlist-anything primitive.
- **Cap IPs per user and rate-limit enrollment**, or one compromised account can
  walk the firewall open one address at a time. Per-user cap, per-hour rate
  limit, and log every add with user and timestamp.
- **Carrier-grade NAT is a real limit.** Admitting one phone's IP can admit
  thousands of unrelated subscribers behind the same CGNAT egress. This is why
  an IP allowlist is a *reduction* in attack surface, never an authentication
  mechanism.
- **Fail closed, but never lock out the reconciler.** Keep a permanent
  administrative allow rule for the maintenance IP / SSH, applied out-of-band, so
  a bad reconciler run cannot orphan the droplet.

## Honest assessment

An IP allowlist is a blunt instrument, and it is worth being clear that it is
**defense in depth, not a substitute for authenticating the ports themselves**.
The higher-value fix for `:8080` and `:9999` is to put real auth and TLS on them
— a bearer token the app already holds after sign-in would do — at which point
the firewall becomes a nice-to-have that cuts scanner noise rather than the only
thing standing between the internet and the queue.

Suggested ordering if this is ever picked up:

1. Reconcile `ip_umbrella` between the live DB and the repo schema.
2. Put a token on the comic server and the queue (biggest real win).
3. Then, and only then, add the TTL allowlist as a second layer.

## Open questions

- Is `ip_umbrella` already carrying a design intent someone else wrote? Check
  its columns and any writer in the Haskell/Rust domain before redesigning.
- Does the queue protocol on `:9999` have any framing where a token could be
  added without breaking the frozen C ABI's expectations?
- Desktop/emulator dev traffic: same enrollment path, or a separate static
  admin rule?
