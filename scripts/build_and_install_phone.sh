#!/usr/bin/env bash
set -euo pipefail

die() {
  echo "error: $*" >&2
  exit 1
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

sync_tree() {
  local source="$1"
  local dest="$2"
  if command -v cpto >/dev/null 2>&1; then
    cpto "$source" "$dest"
    return
  fi
}

repo_state() {
  local repo="$1"
  local head dirty
  head="$(git -C "$repo" rev-parse HEAD)"
  dirty="$(
    {
      git -C "$repo" diff --binary --no-ext-diff HEAD -- .
      git -C "$repo" diff --binary --no-ext-diff --cached HEAD -- .
      git -C "$repo" ls-files --others --exclude-standard -z | while IFS= read -r -d '' path; do
        printf '%s\n' "---UNTRACKED:${path}---"
        cat "$repo/$path"
        printf '\n'
      done
    } | sha256sum | awk '{print $1}'
  )"
  printf '%s:%s' "$head" "$dirty"
}

read_state_value() {
  local key="$1"
  [[ -f "$STATE_FILE" ]] || return 0
  awk -F= -v key="$key" '$1 == key { print substr($0, length(key) + 2) }' "$STATE_FILE" | tail -n1
}

write_state() {
  local gallery_state="$1"
  local litert_state="$2"
  cat >"$STATE_FILE" <<EOF
gallery_state=$gallery_state
litert_state=$litert_state
EOF
}

GALLERY_SRC="${GALLERY_SRC:-/home/rebroad/src/gallery}"
LITERT_SRC="${LITERT_SRC:-/home/rebroad/src/LiteRT-LM}"
GALLERY_BUILD="${GALLERY_BUILD:-/home/rebroad/src/gallery.build}"
LITERT_BUILD="${LITERT_BUILD:-/home/rebroad/src/LiteRT-LM.build}"
STATE_FILE="${STATE_FILE:-/var/tmp/gallery-phone-build.state}"
ADB_BIN="${ADB_BIN:-adb}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}"
CC="${CC:-clang}"
CXX="${CXX:-clang++}"

need_cmd git
need_cmd "$ADB_BIN"
need_cmd rg

[[ -d "$GALLERY_SRC" ]] || die "missing Gallery source tree: $GALLERY_SRC"
[[ -d "$LITERT_SRC" ]] || die "missing LiteRT-LM source tree: $LITERT_SRC"

USE_BUILD_TREES=false
if command -v cpto >/dev/null 2>&1; then
  USE_BUILD_TREES=true
  [[ -d "$GALLERY_BUILD" ]] || die "missing Gallery build tree: $GALLERY_BUILD"
  [[ -d "$LITERT_BUILD" ]] || die "missing LiteRT-LM build tree: $LITERT_BUILD"
  GALLERY_WORKTREE="$GALLERY_BUILD"
  LITERT_WORKTREE="$LITERT_BUILD"
else
  GALLERY_WORKTREE="$GALLERY_SRC"
  LITERT_WORKTREE="$LITERT_SRC"
fi

GALLERY_APK="$GALLERY_WORKTREE/Android/src/app/build/outputs/apk/debug/app-debug.apk"
LITERT_JAR="$LITERT_WORKTREE/bazel-bin/kotlin/java/com/google/ai/edge/litertlm/litertlm-jvm.jar"
LITERT_ANDROID_JNI="$LITERT_WORKTREE/bazel-bin/kotlin/java/com/google/ai/edge/litertlm/jni/liblitertlm_jni.so"
LITERT_ANDROID_NATIVE_DIR="$LITERT_WORKTREE/prebuilt/android_arm64"

cur_gallery_state="$(repo_state "$GALLERY_SRC")"
cur_litert_state="$(repo_state "$LITERT_SRC")"
prev_gallery_state="$(read_state_value gallery_state || true)"
prev_litert_state="$(read_state_value litert_state || true)"

build_litert=false
build_gallery=false

if [[ "$cur_litert_state" != "$prev_litert_state" || ! -f "$LITERT_JAR" ]]; then
  build_litert=true
  build_gallery=true
fi

if [[ "$cur_gallery_state" != "$prev_gallery_state" || ! -f "$GALLERY_APK" ]]; then
  build_gallery=true
fi

if [[ "$build_litert" == false && "$build_gallery" == false ]]; then
  echo "No source changes detected; reinstalling the existing APK."
else
  if [[ "$build_litert" == true ]]; then
    if [[ "$USE_BUILD_TREES" == true ]]; then
      echo "[1/4] Syncing LiteRT-LM source into build tree"
      sync_tree "$LITERT_SRC" "$LITERT_BUILD"
    else
      echo "[1/4] Building LiteRT-LM directly in the source tree"
    fi
    echo "[2/4] Building LiteRT-LM Android artifact"
    (
      cd "$LITERT_WORKTREE"
      export ANDROID_HOME ANDROID_SDK_ROOT ANDROID_NDK_HOME CC CXX
      bazel build --config=android_arm64 \
        //kotlin/java/com/google/ai/edge/litertlm:litertlm-jvm \
        //kotlin/java/com/google/ai/edge/litertlm/jni:litertlm_jni
    )
    [[ -f "$LITERT_JAR" ]] || die "LiteRT-LM build finished but missing artifact: $LITERT_JAR"
  fi

  strip_bin=""
  if command -v llvm-strip >/dev/null 2>&1; then
    strip_bin="$(command -v llvm-strip)"
  elif [[ -x "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" ]]; then
    strip_bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
  fi
  if [[ -n "$strip_bin" ]]; then
    echo "[2/4] Stripping LiteRT-LM Android shared libraries"
    for so_file in "$LITERT_ANDROID_JNI" "$LITERT_ANDROID_NATIVE_DIR"/*.so; do
      [[ -f "$so_file" ]] || continue
      "$strip_bin" --strip-debug "$so_file" || die "failed to strip debug symbols from $so_file"
    done
  fi

  if [[ "$build_gallery" == true ]]; then
    if [[ "$USE_BUILD_TREES" == true ]]; then
      echo "[3/4] Syncing Gallery source into build tree"
      sync_tree "$GALLERY_SRC" "$GALLERY_BUILD"
    else
      echo "[3/4] Building Gallery directly in the source tree"
    fi
    echo "[4/4] Building Gallery APK"
    (
      cd "$GALLERY_WORKTREE/Android/src"
      export ANDROID_HOME ANDROID_SDK_ROOT ANDROID_NDK_HOME CC CXX
      ./gradlew :app:assembleDebug
    )
    [[ -f "$GALLERY_APK" ]] || die "Gallery build finished but missing APK: $GALLERY_APK"
  fi
fi

echo "Installing APK to phone"
"$ADB_BIN" install -r "$GALLERY_APK"

echo "Installed package version:"
"$ADB_BIN" shell dumpsys package com.google.aiedge.gallery | rg -n "versionName|versionCode"

write_state "$cur_gallery_state" "$cur_litert_state"
