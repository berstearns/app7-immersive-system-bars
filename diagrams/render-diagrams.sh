#!/usr/bin/env bash
# Render each topology .md's first ``` ASCII block to a matching .png.
# Reusable: re-run after editing any diagram's ASCII art.
#   ./render-diagrams.sh            # render local/cloud/droplet
#   ./render-diagrams.sh foo.md ... # render specific files
set -euo pipefail
cd "$(dirname "$0")"

FONT="${DIAGRAM_FONT:-DejaVu Sans Mono 13}"
MARGIN="${DIAGRAM_MARGIN:-24}"

files=("$@")
[ ${#files[@]} -eq 0 ] && files=(local-deploy.md cloud-deploy.md droplet-deploy.md)

for md in "${files[@]}"; do
  [ -f "$md" ] || { echo "skip (missing): $md"; continue; }
  png="${md%.md}.png"
  tmp="$(mktemp --suffix=.txt)"
  # Extract the FIRST fenced code block (the ASCII diagram), fences stripped.
  awk '/^```/{c++; next} c==1{print}' "$md" > "$tmp"
  if [ ! -s "$tmp" ]; then echo "skip (no code block): $md"; rm -f "$tmp"; continue; fi
  pango-view --no-display -q \
    --font="$FONT" --margin="$MARGIN" \
    --background=white --foreground=black \
    -o "$png" "$tmp"
  rm -f "$tmp"
  echo "rendered: $png  ($(identify -format '%wx%h' "$png" 2>/dev/null || echo '?'))"
done
