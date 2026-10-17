#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────
#  compile.sh — STEP 2: link object code → ./app7-domain-test.
#
#  Run:
#    ./domain-to-c.sh && ./compile.sh && ./app7-domain-test
#  Expect: exit 0, all assertions OK.
# ─────────────────────────────────────────────────────────────────────────
set -euo pipefail

cd "$(dirname "$0")"

if [[ ! -f build/Main.o ]]; then
  echo "build/Main.o not found — run ./domain-to-c.sh first." >&2
  exit 1
fi

# -dynamic must match the flag in domain-to-c.sh (Arch GHC quirk).
ghc -dynamic build/App7Domain.o build/Main.o -o app7-domain-test \
    -odir build -hidir build

echo "✓ linked → ./app7-domain-test"
echo "  size: $(du -h app7-domain-test | cut -f1)"
echo "Try:  ./app7-domain-test"
