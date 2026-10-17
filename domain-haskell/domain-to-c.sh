#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────
#  domain-to-c.sh — STEP 1 of the host-Linux Haskell build.
#
#  Historical note (cf. sla-ddd-to-c/user-to-c.sh): GHC's old "via-C"
#  backend is gone.  Modern GHC (9.6.6) compiles Haskell straight to
#  native object files — no .c artefact.  The C projection of this
#  algebra lives in domain-core/*.c (Phase 2), maintained by hand and
#  audited via ParityTest against the Haskell here.
#
#  This script compiles App7Domain.hs + Test.hs into ./build/*.o + *.hi.
#
#  Next: ./compile.sh
# ─────────────────────────────────────────────────────────────────────────
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p build

# -dynamic: Arch Linux ships GHC with dynamic libs only by default.
# Drop on distros that ship ghc-static.
ghc -dynamic -c App7Domain.hs -odir build -hidir build
ghc -dynamic -c Test.hs       -odir build -hidir build

echo "✓ compiled App7Domain.hs + Test.hs"
echo "  → build/App7Domain.{o,hi}"
echo "  → build/Main.{o,hi}     (named after Test.hs's 'module Main')"
echo "Next: ./compile.sh"
