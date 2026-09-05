#!/usr/bin/env bash
set -euo pipefail

TAG="152.0.7977.75"
ROOT="${CHROMIUM_WORKDIR:-$PWD/.chromium152-work}"
DEPOT_TOOLS="$ROOT/depot_tools"
SRC="$ROOT/src"
OUT="${CHROMIUM_OUT:-out/ChromeXDesktopAndroid152}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() { printf '\n==> %s\n' "$*"; }
fail() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

mkdir -p "$ROOT"

if [[ ! -d "$DEPOT_TOOLS/.git" ]]; then
  log "Cloning depot_tools"
  git clone --depth=1 https://chromium.googlesource.com/chromium/tools/depot_tools.git "$DEPOT_TOOLS"
fi
export PATH="$DEPOT_TOOLS:$PATH"

if [[ ! -d "$SRC/.git" ]]; then
  log "Fetching Chromium Android checkout"
  cd "$ROOT"
  fetch --nohooks android
fi

cd "$SRC"
log "Checking out Chromium $TAG"
git fetch --tags origin "refs/tags/$TAG:refs/tags/$TAG"
git checkout -f "$TAG"

log "Synchronising DEPS for $TAG"
gclient sync -D --force --reset --with_branch_heads --with_tags

if [[ "${SKIP_BUILD_DEPS:-0}" != "1" ]]; then
  log "Installing Chromium build dependencies"
  sudo ./build/install-build-deps.sh --android --no-prompt
fi

log "Running Chromium hooks"
gclient runhooks

log "Generating GN output: $OUT"
mkdir -p "$OUT"
cp "$SCRIPT_DIR/args.gn" "$OUT/args.gn"
gn gen "$OUT"

log "Verifying Desktop Android extension build flags"
python3 "$SCRIPT_DIR/verify_gn.py" "$SRC" "$OUT"

log "Checking required Trichrome targets"
TARGETS=(
  "//chrome/android:trichrome_chrome_64_bundle"
  "//chrome/android:trichrome_library_64_apk"
)
for target in "${TARGETS[@]}"; do
  if ! gn desc "$OUT" "$target" >/dev/null 2>&1; then
    fail "Required Chromium 152 target not found: $target"
  fi
done

JOBS="${NINJA_JOBS:-$(nproc)}"
log "Building Chromium 152 Desktop Android Extensions with $JOBS jobs"
autoninja -C "$OUT" -j "$JOBS" \
  chrome/android:trichrome_chrome_64_bundle \
  chrome/android:trichrome_library_64_apk

log "Collecting artifacts"
ARTIFACT_DIR="$ROOT/artifacts"
rm -rf "$ARTIFACT_DIR"
mkdir -p "$ARTIFACT_DIR"

find "$OUT/apks" -maxdepth 1 -type f \
  \( -name 'TrichromeChrome64*.aab' -o -name 'TrichromeChrome64*.apks' \
     -o -name 'TrichromeLibrary64*.apk' -o -name 'TrichromeChrome*.aab' \
     -o -name 'TrichromeLibrary*.apk' \) \
  -print -exec cp -f {} "$ARTIFACT_DIR/" \;

if [[ -f "$OUT/lib.unstripped/libmonochrome_64.so" ]]; then
  cp -f "$OUT/lib.unstripped/libmonochrome_64.so" "$ARTIFACT_DIR/"
fi

cp "$OUT/args.gn" "$ARTIFACT_DIR/args.gn"
git rev-parse HEAD > "$ARTIFACT_DIR/chromium_commit.txt"
printf '%s\n' "$TAG" > "$ARTIFACT_DIR/chromium_tag.txt"

log "Validating extension bridge output"
python3 "$SCRIPT_DIR/verify_artifacts.py" "$SRC" "$OUT" "$ARTIFACT_DIR"

log "Done. Artifacts: $ARTIFACT_DIR"
find "$ARTIFACT_DIR" -maxdepth 1 -type f -printf '%f\n' | sort
