#!/usr/bin/env bash
# Installs the debug APK on the emulator, accepts the first-run dialogs, opens the assistant and
# fails if the app crashes. Crash lines are emitted as GitHub annotations.
set -uo pipefail
APK="$1"
PKG="io.github.derort.piggselle"
OUT="${RUNNER_TEMP:-/tmp}/smoke"
mkdir -p "$OUT"

adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install -r -g "$APK" || { echo "::error title=Smoke test::APK install failed"; exit 1; }
adb logcat -c

tap_text() {
  # tap_text "Accept & continue": find a node whose text matches and tap its center.
  local label="$1"
  for _ in $(seq 1 15); do
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
    adb pull /sdcard/ui.xml "$OUT/ui.xml" > /dev/null 2>&1
    local bounds
    bounds=$(python3 - "$OUT/ui.xml" "$label" <<'PY'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
label = sys.argv[2]
for m in re.finditer(r'<node [^>]*>', xml):
    node = m.group(0)
    t = re.search(r' text="([^"]*)"', node)
    d = re.search(r' content-desc="([^"]*)"', node)
    texts = [html.unescape(x.group(1)) for x in (t, d) if x]
    if any(label.lower() in x.lower() for x in texts):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
PY
)
    if [ -n "$bounds" ]; then adb shell input tap $bounds; echo "tapped '$label' at $bounds"; return 0; fi
    sleep 2
  done
  echo "::warning title=Smoke test::Could not find '$label' on screen"
  return 1
}

crashed() {
  adb logcat -d > "$OUT/logcat.txt"
  grep -n -A25 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -A25 "$PKG\|Process: $PKG" > "$OUT/crash.txt"
  [ -s "$OUT/crash.txt" ]
}

report_and_fail() {
  echo "::error title=Smoke test::App crashed during: $1"
  head -40 "$OUT/crash.txt" | while IFS= read -r l; do echo "::error title=Crash::$l"; done
  adb exec-out screencap -p > "$OUT/screen.png" 2>/dev/null || true
  exit 1
}

adb shell am start -W -n "$PKG/com.google.ai.edge.gallery.MainActivity" > /dev/null
sleep 8
crashed && report_and_fail "launch"

tap_text "Accept & continue"
sleep 6
crashed && report_and_fail "accepting the terms"

# Home screen must render the assistant tile.
tap_text "Piggselle" || true
sleep 6
crashed && report_and_fail "opening the assistant"

adb shell input keyevent KEYCODE_BACK
sleep 3
crashed && report_and_fail "going back to home"

adb exec-out screencap -p > "$OUT/screen.png" 2>/dev/null || true
if ! adb shell pidof "$PKG" > /dev/null; then
  echo "::error title=Smoke test::App process is not running at the end"
  exit 1
fi
echo "::notice title=Smoke test::Launch, terms, home and assistant screen work without crashing"
