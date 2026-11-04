#!/usr/bin/env bash
# Prepares the Gradle build tree: pinned ics-openvpn checkout + this repo's overlay.
# Used by CI and for local builds:  tools/prepare.sh && (cd build-tree && ./gradlew :main:assembleSkeletonOvpn2Release)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REF="$(tr -d '[:space:]' < "$ROOT/tools/ICS_OPENVPN_REF")"
BUILD="${BUILD_DIR:-$ROOT/build-tree}"

if [ ! -d "$BUILD/.git" ]; then
  git clone --filter=blob:none -q https://github.com/schwabe/ics-openvpn "$BUILD"
fi
git -C "$BUILD" fetch -q origin "$REF" 2>/dev/null || true
git -C "$BUILD" checkout -q -f "$REF"
git -C "$BUILD" submodule sync -q
git -C "$BUILD" submodule update --init --recursive --depth 1 --jobs 8 -q

# 1. Overlay app files (sources, resources, manifests) onto the upstream :main module.
[ -d "$ROOT/app/overlay" ] && cp -a "$ROOT/app/overlay/." "$BUILD/main/" || true

# 2. Engine sources (pure Kotlin, also unit-tested standalone in ./engine) are compiled into the app.
if [ -d "$ROOT/engine/src/main/kotlin" ]; then
  mkdir -p "$BUILD/main/src/main/java"
  cp -a "$ROOT/engine/src/main/kotlin/." "$BUILD/main/src/main/java/"
fi

# 3. Extra Gradle configuration appended to upstream's build file (idempotent).
if ! grep -q "VPNAPP_EXTRA_BEGIN" "$BUILD/main/build.gradle.kts"; then
  { echo; cat "$ROOT/tools/extra.gradle.kts"; } >> "$BUILD/main/build.gradle.kts"
fi
# Plugin declarations needed by the extra config go in the root build file.
if ! grep -q "VPNAPP_EXTRA_PLUGINS" "$BUILD/build.gradle.kts"; then
  cat "$ROOT/tools/extra-root.gradle.kts" >> "$BUILD/build.gradle.kts"
fi
echo "build tree ready: $BUILD @ $REF"
