#!/usr/bin/env bash
# WHAT: Capture an app7 screencap and assert that the status bar is hidden and the
#       navigation bar is hidden or absent on displays that do not provide one.
# WHY: Android 15+ draws targetSdk 35 apps behind system bars; a successful build or
#      install does not prove that the reader and controls are unobscured.
# INPUTS: exactly one device selector, --avd NAME or --serial SERIAL, plus --output PNG.
# OUTPUTS: the requested PNG, a sibling .insets.txt with the visibility evidence, and
#          IMMERSIVE_PROVE_OK on stdout; named E_* errors on failure.
# USAGE: scripts/AUTO-immersive-system-bars-prove.sh --avd app7_e2e_api34 --output evidence/emulator-reader.png
#        scripts/AUTO-immersive-system-bars-prove.sh --serial RZGL623LAGK --output evidence/phone-settings.png
# RE-RUN SAFETY: read-only on the app and device; atomically replaces only the named
#                screenshot and its sibling evidence file.
set -euo pipefail

AVD=""
SERIAL=""
OUT=""
PKG="me.bstearns.app7"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --avd) AVD="${2:?--avd needs a name}"; shift 2 ;;
    --serial) SERIAL="${2:?--serial needs a serial}"; shift 2 ;;
    --output) OUT="${2:?--output needs a path}"; shift 2 ;;
    *) echo "ERROR: unknown flag '$1'" >&2; exit 64 ;;
  esac
done
[[ -n "$AVD" && -z "$SERIAL" || -z "$AVD" && -n "$SERIAL" ]] || {
  echo "ERROR: pass exactly one of --avd / --serial" >&2
  exit 64
}
[[ -n "$OUT" ]] || { echo "ERROR: --output is required" >&2; exit 64; }
for c in adb file; do
  command -v "$c" >/dev/null || { echo "ERROR: missing '$c'" >&2; exit 64; }
done

if [[ -n "$AVD" ]]; then
  for candidate in $(adb devices | awk '$1 ~ /^emulator-/ && $2 == "device" {print $1}'); do
    if [[ "$(adb -s "$candidate" emu avd name 2>/dev/null | head -1 | tr -d '\r')" == "$AVD" ]]; then
      SERIAL="$candidate"
      break
    fi
  done
  [[ -n "$SERIAL" ]] || { echo "E_AVD_NOT_RUNNING $AVD" >&2; exit 1; }
fi
[[ "$(adb -s "$SERIAL" get-state 2>/dev/null | tr -d '\r')" == "device" ]] || {
  echo "E_DEVICE_NOT_READY $SERIAL" >&2
  exit 1
}
adb -s "$SERIAL" shell dumpsys activity activities 2>/dev/null \
  | awk -v pkg="$PKG" '/ResumedActivity/ && index($0, pkg) {found=1} END {exit !found}' || {
  echo "E_APP_NOT_RESUMED $PKG on $SERIAL" >&2
  exit 1
}

started_ns="$(date +%s%N)"
attempt=0
while true; do
  attempt=$((attempt + 1))
  window_dump="$(adb -s "$SERIAL" shell dumpsys window 2>/dev/null)"
  insets="$(awk '
    /^[[:space:]]*InsetsSource id=/ {
      type = ""
      for (i = 1; i <= NF; i++) {
        if ($i ~ /^type=/) type = substr($i, 6)
      }
      if (!seen[type]++) print
    }
  ' <<<"$window_dump")"
  status_line="$(awk 'index($0, "type=statusBars") {print; exit}' <<<"$insets")"
  navigation_line="$(awk 'index($0, "type=navigationBars") {print; exit}' <<<"$insets")"
  if [[ "$status_line" == *"visible=false"* ]] \
    && [[ -z "$navigation_line" || "$navigation_line" == *"visible=false"* ]]; then
    break
  fi
  if (( attempt > 20 )); then
    [[ -n "$status_line" ]] || { echo "E_NO_STATUS_BAR_INSETS $SERIAL" >&2; exit 1; }
    [[ "$status_line" == *"visible=false"* ]] || {
      echo "E_STATUS_BAR_VISIBLE $status_line" >&2
      exit 1
    }
    [[ -z "$navigation_line" || "$navigation_line" == *"visible=false"* ]] || {
      echo "E_NAVIGATION_BAR_VISIBLE $navigation_line" >&2
      exit 1
    }
  fi
  sleep 0.5
done
finished_ns="$(date +%s%N)"
time_to_hidden_ms=$(( (finished_ns - started_ns) / 1000000 ))
navigation_state="hidden"
[[ -n "$navigation_line" ]] || navigation_state="absent"

mkdir -p "$(dirname "$OUT")"
tmp="$OUT.tmp"
trap 'rm -f "$tmp"' EXIT
adb -s "$SERIAL" exec-out screencap -p >"$tmp"
[[ "$(file -b "$tmp")" == PNG\ image\ data* ]] || {
  echo "E_BAD_SCREENCAP $OUT" >&2
  exit 1
}
mv "$tmp" "$OUT"
{
  printf 'serial=%s\ntimeToHiddenMs=%s\nstatusBars=hidden\nnavigationBars=%s\n' \
    "$SERIAL" "$time_to_hidden_ms" "$navigation_state"
  printf '%s\n' "$status_line"
  [[ -z "$navigation_line" ]] || printf '%s\n' "$navigation_line"
} >"${OUT%.png}.insets.txt"
printf 'IMMERSIVE_PROVE_OK serial=%s statusBars=hidden navigationBars=%s timeToHiddenMs=%s screenshot=%s\n' \
  "$SERIAL" "$navigation_state" "$time_to_hidden_ms" "$OUT"
