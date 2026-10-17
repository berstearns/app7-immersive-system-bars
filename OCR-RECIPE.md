# OCR Recipe — process bubble text for a chapter (or a whole batch)

This is the reproducible recipe for taking a downloaded chapter and producing the `conversations_ocr.json` file that `BubbleTextOverlay` reads. After running this, the app will display real OCR'd text inside every bubble of that chapter (no app rebuild needed — the data file is what the app loads).

## TL;DR — one command per chapter

```bash
cd /home/b/p/all-my-tiny-projects/image-processing-tiny-projects/comic-bubble-text-replacer
./ocr-chapter.sh /home/b/p/minimal-android-apps/shared-assets/onepiece-de/batch-01-hq-tokens/Chapitre_0421
```

Drops `conversations_ocr.json` next to that chapter's `conversations.json` and `images/`. Re-running on the same chapter is a no-op (script detects existing output and skips).

The script lives **with the processing code**, alongside `batch.py` / `process_all.sh` / `deploy_tokens.sh`:

```
/home/b/p/all-my-tiny-projects/image-processing-tiny-projects/comic-bubble-text-replacer/
├── batch.py                # single-chapter OCR (PaddleOCR)
├── ocr-chapter.sh          # ← the one-command wrapper this doc points to
├── process_all.sh          # whole-batch loop (idempotent)
└── deploy_tokens.sh        # bundle + upload to comic-server
```

It wraps `batch.py` (PaddleOCR) and copies the result back into the chapter folder.

---

## Tooling

The pipeline already exists at `/home/b/p/all-my-tiny-projects/image-processing-tiny-projects/comic-bubble-text-replacer/`. It produces both formats in one shot:

| File | Purpose |
|---|---|
| `batch.py` | Single-chapter OCR. Reads `conversations.json` (bubble bboxes from YOLO), crops each bubble, runs PaddleOCR (CPU, no GPU). Writes `ocr_results.json` AND `conversations_ocr.json`. |
| `process_all.sh [batch-NN]` | Iterates over all chapters in a batch (skips already-processed). |
| `deploy_tokens.sh [batch-NN]` | After processing, zips the `-hq-tokens` asset dirs and uploads to the comic server. |

**Engine**: PaddleOCR. **CPU-only**. **Speed**: ~3–5 sec per page (~1–2 min per 18-page chapter). **Languages**: German (`de`) default, also English (`en`), French (`fr`), Japanese (`ja`).

**Python env**: pinned to `/home/b/.pyenv/versions/3.10.18/bin/python3` (per `process_all.sh:16`). Make sure that pyenv version + PaddleOCR are installed.

---

## Single-chapter workflow (deliberate, you pick the target)

```bash
cd /home/b/p/all-my-tiny-projects/image-processing-tiny-projects/comic-bubble-text-replacer

# Pick any target chapter — must contain images/ and conversations.json
TARGET=/home/b/p/minimal-android-apps/shared-assets/onepiece-de/batch-01-hq-tokens/Chapitre_0421

./ocr-chapter.sh "$TARGET"
```

What this does, expanded:

```bash
# Equivalent direct invocation (what ocr-chapter.sh runs under the hood):
PY=/home/b/.pyenv/versions/3.10.18/bin/python3
CBT_DIR=/home/b/p/all-my-tiny-projects/image-processing-tiny-projects/comic-bubble-text-replacer
WORK=/tmp/ocr-Chapitre_0421
mkdir -p "$WORK"
(cd "$CBT_DIR" && $PY batch.py "$TARGET" -o "$WORK")
# → $WORK/conversations_ocr.json
cp "$WORK/conversations_ocr.json" "$TARGET/conversations_ocr.json"
```

After completion the app picks up the file on the next chapter load (see `FileImageDataSource.kt` priority chain: `conversations_ocr.json` > `conversations_sorted.json` > `conversations.json`).

### Verifying the output

```bash
# Bubble count + first bubble's text
python3 -c "
import json
d = json.load(open('$TARGET/conversations_ocr.json'))
print('version:', d['version'])
for img, bubbles in list(d['conversations'].items())[:1]:
    print(f'{img}: {len(bubbles)} bubbles')
    for b in bubbles[:3]:
        print('  ', repr(b.get('text', '<no text>'))[:80])
"
```

Expect `version: 2`, per-image bubble lists with `text` (German strings for One Piece DE) and `ocrConfidence` (~90–100 on clean text, ~50–70 on SFX / stylized art).

---

## Whole-batch workflow (process a batch in one go)

```bash
cd /home/b/p/all-my-tiny-projects/image-processing-tiny-projects/comic-bubble-text-replacer
./process_all.sh batch-01      # all 25 chapters in batch-01, ~30–45 min total
./process_all.sh               # every batch (~hours)
```

`process_all.sh` is idempotent: skips chapters whose `outputs/<batch>/<chapter>/conversations_ocr.json` already exists. After it finishes, the OCR files live in the `outputs/` tree of the comic-bubble-text-replacer repo, NOT yet in the asset dirs — to ship them to assets:

```bash
./deploy_tokens.sh batch-01    # copies conversations_ocr.json into <batch>-hq-tokens, zips, uploads to comic-server
```

`deploy_tokens.sh` mirrors `<batch>-hq` → `<batch>-hq-tokens` with the OCR sidecars merged in, then uploads via `orch-comic.sh copy ...` to `/opt/comic-server/comics/`.

---

## Coverage tracking

Status snapshot — see [`OCR-coverage.md`](./OCR-coverage.md) for the up-to-date inventory and a priority queue. Quick refresh:

```bash
find /home/b/p/minimal-android-apps/shared-assets -maxdepth 4 -name 'conversations_ocr.json' | wc -l   # processed
find /home/b/p/minimal-android-apps/shared-assets -maxdepth 4 -type d -name 'Chapitre_*' | wc -l       # total chapter dirs
```

As of 2026-05-18: `3 / 6668` chapter dirs processed (= 3 copies of `Chapitre_0420` across 3 quality-tier batches). 

---

## Failure modes

| Symptom | Fix |
|---|---|
| `ModuleNotFoundError: paddleocr` | `/home/b/.pyenv/versions/3.10.18/bin/pip install paddleocr` |
| `pyenv: 3.10.18: not found` | `pyenv install 3.10.18` |
| `conversations.json` missing in a chapter | Chapter has no detected bubbles yet. Run YOLO step first (`batch_detect.py` in `comic-text-detector/` — separate doc). |
| OCR returns `"FAIL"` for ~all bubbles | Wrong `--lang` flag. Try `--lang de` (default), `--lang fr`, `--lang en`, or `--lang ja`. |
| Single chapter takes >5 minutes | Bubble count likely > 200 + slow CPU. Normal for OP DE chapters; let it finish. |
| App doesn't show OCR text after running this | Confirm `conversations_ocr.json` landed in the chapter dir the app is reading (typically `~/.cache/comics/<comic_id>/<chapter>/` or the dev `shared-assets/` path). The app uses the path it downloaded into; check `[FileImageDataSource]` log lines on startup. |

---

## Cost

PaddleOCR is local + free. No API costs.

(The original `Chapitre_0420` example was processed via Claude Vision instead — see `/home/b/p/minimal-android-apps/token-region-processor/process_token_regions_v4.py`. That path costs ~$0.05–0.15/chapter at Anthropic API pricing. We default to PaddleOCR for the incremental rollout: zero marginal cost, slightly lower text accuracy on stylized art, but consistent across thousands of chapters.)

---

## Reference

- Pipeline doc (original): `/home/b/p/minimal-android-apps/comic-text-detector/docs/INSTRUCTIONS-ocr-enrichment.md`
- Single-chapter script: `/home/b/p/all-my-tiny-projects/image-processing-tiny-projects/comic-bubble-text-replacer/batch.py`
- Batch wrapper: same dir, `process_all.sh`
- Deploy: same dir, `deploy_tokens.sh`
- Alternative (Claude Vision): `/home/b/p/minimal-android-apps/token-region-processor/process_token_regions_v4.py`
