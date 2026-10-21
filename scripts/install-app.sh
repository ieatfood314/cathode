#!/usr/bin/env bash
# Install the Android app over ADB (CMF Phone 2 Pro / any Android 8+).
# Usage: ./scripts/install-app.sh [path/to/app-debug.apk]
set -euo pipefail
APK="${1:-android/app/build/outputs/apk/debug/app-debug.apk}"
if [ ! -f "$APK" ]; then
  echo "APK not found: $APK"
  echo "Build it: cd android && ./gradlew assembleDebug"
  echo "Or grab it from GitHub Actions artifacts."
  exit 1
fi
adb install -r "$APK"
echo "Installed. Open Cathode, enter http://<your-nas>:8000, stay on Wi-Fi."
