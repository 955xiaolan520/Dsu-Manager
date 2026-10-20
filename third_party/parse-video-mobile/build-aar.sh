#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the installed Android SDK directory}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}"
export PATH="${GOBIN:-$HOME/go/bin}:$PATH"
export GOTOOLCHAIN="${GOTOOLCHAIN:-go1.26.10+auto}"

cd "$HERE"
go mod tidy
gomobile init
gomobile bind \
  -target=android/arm,android/arm64 \
  -androidapi 29 \
  -javapkg=com.probiotics.xiaoni.parsevideo \
  -o "$ROOT/app/libs/parse-video-mobile.aar" \
  .
python3 "$ROOT/tools/rename-gomobile-library.py" "$ROOT/app/libs/parse-video-mobile.aar"
printf 'Generated %s\n' "$ROOT/app/libs/parse-video-mobile.aar"
