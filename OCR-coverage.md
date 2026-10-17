# OCR Coverage — current state and priority queue

Snapshot as of 2026-05-18.

## Quick stats

| Metric | Value |
|---|---|
| Total chapter dirs (all comics, all quality tiers) | 6668 |
| Chapters with `conversations_ocr.json` | 3 |
| Coverage | ~0.05% |

The 3 processed chapters are all copies of `Chapitre_0420` across One Piece DE quality tiers (`batch-01`, `batch-01-lq`, `batch-01-hq-tokens`).

## Refresh stats

```bash
find /home/b/p/minimal-android-apps/shared-assets -maxdepth 4 -name 'conversations_ocr.json' | wc -l
find /home/b/p/minimal-android-apps/shared-assets -maxdepth 4 -type d -name 'Chapitre_*' | wc -l

# Per-batch breakdown
for B in /home/b/p/minimal-android-apps/shared-assets/onepiece-de/batch-*; do
  TOTAL=$(find "$B" -maxdepth 2 -type d -name 'Chapitre_*' | wc -l)
  DONE=$(find "$B" -maxdepth 3 -name 'conversations_ocr.json' | wc -l)
  printf "%-50s %d / %d\n" "$(basename "$B")" "$DONE" "$TOTAL"
done
```

## Priority queue — what to process first

Order by user value (chapters the user actually reads next > chapters that fill long-running gaps).

1. **One Piece DE — batch-01-hq-tokens** (Chapitre_0420–0444, 25 chapters) — already partially seeded (1/25). High-leverage: full batch unlocks the German learning experience the user is actively reading.
2. **One Piece DE — batch-02-hq through batch-05-hq** — continuation of the same comic. Adds another ~100 chapters.
3. **chainsawman-fr-hq** — second-most-read comic per the running desktop session (active comic was `chainsawman-fr-hq` during dev). Process `batch-01-hq` first.
4. **evangelion-es-hq**, **hi-supremo-hq** — less-frequently-read; lower priority.

The HQ (high-quality) variants are the canonical targets for OCR — the LQ/MQ tiers use the same coordinates so they automatically get bubble text for free at runtime (the app downloads the HQ-tokens variant when available; see `deploy_tokens.sh`).

## Suggested rollout schedule

Each chapter ≈ 1–2 minutes on CPU PaddleOCR (one-off, no incremental cost).

- **Day 1 (≈45 min)**: `./process_all.sh batch-01` → all 25 chapters of OP DE batch-01.
- **Day 2 (≈3–4 hours)**: `./process_all.sh` (everything else under `~/shared-assets/onepiece-de/batch-NN`, skipping already-done). Run overnight if preferred.
- **Day 3+**: `./process_all.sh` per `chainsawman-fr-hq`, `evangelion-es-hq`, etc. (these may need their own pipeline configs — different language flag, different bubble detection model. See `INSTRUCTIONS-ocr-enrichment.md`.)

After processing, run `./deploy_tokens.sh batch-NN` to mirror the OCR'd files into the `-hq-tokens` asset dirs and upload them to the comic server. The app downloads from there.

## How the user gets the new files into the running app

Two paths:

- **Production app users** download new chapters from the comic server. After `deploy_tokens.sh`, the new `<batch>-hq-tokens.zip` lives at `/opt/comic-server/comics/`. Users who pull that comic-id get the OCR text automatically.
- **Local dev** (the desktop pane): the app reads from `shared-assets/onepiece-de/batch-01-hq-tokens/Chapitre_XXXX/conversations_ocr.json` directly. After `ocr-chapter.sh`, restart the desktop app and the text appears.

## Tracking progress

Optional manual ledger. After each `ocr-chapter.sh` run, append a line to `OCR-PROGRESS.tsv` in this folder:

```
date            chapter                          bubbles    notes
2026-05-18      onepiece-de/batch-01-hq/Chap_0421   189
2026-05-18      onepiece-de/batch-01-hq/Chap_0422   162
```

(File doesn't exist yet — start it when you run the first non-`Chapitre_0420` chapter.)
