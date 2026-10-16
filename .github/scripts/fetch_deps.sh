#!/usr/bin/env bash
# Fetches native libraries that are not on Google Maven / Maven Central, verified by SHA-256.
set -euo pipefail
LIBS="Android/src/app/libs"
mkdir -p "$LIBS"

fetch() {
  local url="$1" out="$2" sha="$3"
  if [ -f "$out" ] && echo "$sha  $out" | sha256sum -c --status; then return 0; fi
  curl -fsSL --retry 3 -o "$out" "$url"
  echo "$sha  $out" | sha256sum -c
}

# sherpa-onnx (Apache-2.0): offline Whisper speech recognition.
fetch "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar" \
  "$LIBS/sherpa-onnx-1.13.8.aar" \
  "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
