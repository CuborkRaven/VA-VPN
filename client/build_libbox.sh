#!/usr/bin/env bash
set -euo pipefail

# Builds libbox.aar from SagerNet/sing-box and places it into android/app/libs/libbox.aar
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIBS_DIR="$SCRIPT_DIR/android/app/libs"
BUILD_TMP="$(mktemp -d)"

cleanup() {
  rm -rf "$BUILD_TMP"
}
trap cleanup EXIT

echo "=== [1/5] Checking toolchain (go, git, ANDROID_HOME, ANDROID_NDK_HOME) ==="
command -v go >/dev/null 2>&1 || { echo "Error: Go is not installed (Go 1.22+ required)."; exit 1; }
command -v git >/dev/null 2>&1 || { echo "Error: git is not installed."; exit 1; }

if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
  elif [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  else
    echo "Error: ANDROID_HOME is not set."
    exit 1
  fi
fi

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  LATEST_NDK="$(ls -1d "$ANDROID_HOME/ndk/"* 2>/dev/null | sort -V | tail -n1 || true)"
  if [ -n "$LATEST_NDK" ]; then
    export ANDROID_NDK_HOME="$LATEST_NDK"
  else
    echo "Error: ANDROID_NDK_HOME is not set and no NDK found in $ANDROID_HOME/ndk/."
    exit 1
  fi
fi

export PATH="$PATH:$(go env GOPATH)/bin"
echo "Using ANDROID_HOME=$ANDROID_HOME"
echo "Using ANDROID_NDK_HOME=$ANDROID_NDK_HOME"

echo "=== [2/5] Cloning SagerNet/sing-box ==="
git clone --depth 1 https://github.com/SagerNet/sing-box.git "$BUILD_TMP/sing-box"
cd "$BUILD_TMP/sing-box"

echo "=== [3/5] Installing SagerNet gomobile & gobind ==="
make lib_install

echo "=== [4/5] Compiling libbox.aar (Android + gVisor) ==="
go run ./cmd/internal/build_libbox -target android

if [ ! -f "$BUILD_TMP/sing-box/libbox.aar" ]; then
  echo "Error: libbox.aar was not produced."
  exit 1
fi

echo "=== [5/5] Copying libbox.aar to $LIBS_DIR ==="
mkdir -p "$LIBS_DIR"
cp -f "$BUILD_TMP/sing-box/libbox.aar" "$LIBS_DIR/libbox.aar"

echo "SUCCESS: $LIBS_DIR/libbox.aar is ready."
