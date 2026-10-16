#!/usr/bin/env bash
# Installs the debug APK on the emulator, accepts the first-run dialogs, opens the assistant and
# fails if the app crashes. Crash lines are emitted as GitHub annotations.
set -uo pipefail
APK="$1"
PKG="io.github.derort.piggselle"
OUT="${RUNNER_TEMP:-/tmp}/smoke"
mkdir -p "$OUT"
echo "::notice title=Smoke test::started"
LAST_STEP="setup"
trap 'code=$?; if [ $code -ne 0 ]; then echo "::error title=Smoke script::exited with code $code during: $LAST_STEP"; fi' EXIT

LAST_STEP="waiting for device"
adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
LAST_STEP="install"
adb install -r -g "$APK" || { echo "::error title=Smoke test::APK install failed"; exit 1; }
adb logcat -c

tap_text() {
  LAST_STEP="tap $1"
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
  # Share text from "another app": it should land in a new chat's message box, unsent.
LAST_STEP="share text"
adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "Shared-hello-42" -n "$PKG/com.google.ai.edge.gallery.MainActivity" > /dev/null
sleep 4
crashed && report_and_fail "receiving a share"
expect_text "Shared-hello-42"

adb exec-out screencap -p > "$OUT/screen.png" 2>/dev/null || true
  exit 1
}

dump_screen() {
  adb exec-out screencap -p > "$OUT/screen.png" 2>/dev/null || true
  local focus
  focus=$(adb shell dumpsys window | grep -m1 -E "mCurrentFocus|mFocusedApp" | tr -s ' ')
  echo "::error title=Focused window::$focus"
  python3 - "$OUT/ui.xml" <<'PY' | head -40 | while IFS= read -r l; do echo "::error title=On screen::$l"; done
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]+)"', n)
    d = re.search(r' content-desc="([^"]+)"', n)
    b = re.search(r'bounds="([^"]+)"', n)
    if t or d:
        print(f"{html.unescape(t.group(1)) if t else ''} | desc={html.unescape(d.group(1)) if d else ''} | {b.group(1) if b else ''}")
PY
}

hide_keyboard() {
  if adb shell dumpsys input_method | grep -q "mInputShown=true"; then
    adb shell input keyevent KEYCODE_BACK
    sleep 1
  fi
}

has_text() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" > /dev/null 2>&1
  grep -qi "$1" "$OUT/ui.xml"
}

expect_text() {
  LAST_STEP="expect $1"
  for _ in $(seq 1 10); do has_text "$1" && return 0; sleep 2; done
  echo "::error title=Smoke test::Expected to see '$1' on screen"
  dump_screen
  exit 1
}

adb shell am start -W -n "$PKG/com.google.ai.edge.gallery.MainActivity" > /dev/null
sleep 8
crashed && report_and_fail "launch"

tap_text "Accept & continue"
sleep 5
crashed && report_and_fail "accepting the terms"

# The app opens straight into the chat; without a model it offers a download.
expect_text "Download a model to start"

tap_text "Browse models"
sleep 5
crashed && report_and_fail "opening the model manager"
# Scroll down to the community suggestions.
SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
for _ in 1 2 3 4 5 6 7 8; do adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 300; sleep 1; has_text "More models from Hugging Face" && break; done
expect_text "More models from Hugging Face"
adb shell input keyevent KEYCODE_BACK
sleep 3
crashed && report_and_fail "returning from the model manager"

# Drawer -> new folder dialog -> cancel.
tap_text "Chats"
sleep 2
expect_text "New chat"
tap_text "New folder"
sleep 2
crashed && report_and_fail "opening the new folder dialog"
tap_text "Cancel"
sleep 2
hide_keyboard

# Drawer -> settings -> knowledge base sheet.
tap_text "Settings"
sleep 3
crashed && report_and_fail "opening settings"
expect_text "Knowledge base"
tap_text "Knowledge base"
sleep 3
crashed && report_and_fail "opening the knowledge base sheet"
adb shell input keyevent KEYCODE_BACK
sleep 2
expect_text "Models folder"
tap_text "Models folder"
sleep 3
crashed && report_and_fail "opening the models folder sheet"
expect_text "Open access settings"
adb shell input keyevent KEYCODE_BACK
sleep 2
adb shell input keyevent KEYCODE_BACK
sleep 3
crashed && report_and_fail "going back to the chat"

adb exec-out screencap -p > "$OUT/screen.png" 2>/dev/null || true
if ! adb shell pidof "$PKG" > /dev/null; then
  echo "::error title=Smoke test::App process is not running at the end"
  exit 1
fi
echo "::notice title=Smoke test::Terms, chat, model manager with suggestions, drawer, folder dialog, settings, knowledge and models-folder sheets, and sharing text work without crashing"
