#!/usr/bin/env bash
# WHAT: Build the app7 debug APK and compile the desktop Kotlin target.
# WHY: These are the required pre-install development gates for the immersive-bar change.
# INPUTS: none; run from any working directory.
# OUTPUTS: androidApp/build/outputs/apk/debug/androidApp-debug.apk and desktop classes;
#          Gradle's normal task output and a nonzero exit on failure.
# USAGE: scripts/AUTO-app7-dev-gates.sh
# RE-RUN SAFETY: incremental Gradle build; writes only normal build outputs.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
./gradlew :androidApp:assembleDebug :desktopApp:compileKotlin
