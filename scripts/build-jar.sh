#!/usr/bin/env bash
#
# Builds the jar without Gradle.
#
# `./gradlew build` is the supported path and the one BUILD.md documents, but it
# needs Gradle, Loom, Yarn mappings and the Minecraft/Fabric artifacts, none of
# which are reachable from every machine. This script is the fallback used here:
# it compiles the platform-free core with a real OpenJDK 23 javac (TraceJVM,
# WebAssembly build), runs the test suite on a real JVM, and packs a jar.
#
# What it cannot produce: the Minecraft client layer (needs the Fabric/Minecraft
# classes) and the Loom-remapped production jar. It fails loudly instead of
# pretending otherwise.
#
#   JAVA=/path/to/bin/java ./scripts/build-jar.sh
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

JAVA="${JAVA:-java}"
BUILD="$ROOT/build"
CLASSES="$BUILD/classes"
DIST="$ROOT/dist"
VENDOR="$ROOT/tools/real-javac"
PLATFORM="$VENDOR/runtime/jdk23.jar"

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing required tool: $1" >&2; exit 2; }; }
need node
need python3
"$JAVA" -version >/dev/null 2>&1 || { echo "JAVA=$JAVA does not run" >&2; exit 2; }

for asset in compiler/compiler.wasm compiler/compiler.wasm-runtime.js \
             compiler/compile-classlib-teavm.bin runtime/jdk23.jar \
             tracejvm/dist/index.js; do
  [ -f "$VENDOR/$asset" ] || { echo "missing vendored compiler asset: tools/real-javac/$asset" >&2; exit 2; }
done

echo "== java:    $("$JAVA" -version 2>&1 | head -1)"
echo "== node:    $(node --version)"

rm -rf "$CLASSES"
mkdir -p "$CLASSES" "$DIST"

TEST_CLASSES="$BUILD/test-classes"

echo
echo "== 1/4 compile core + tests in one javac run (real javac 23.0.2, WebAssembly build)"
rm -rf "$TEST_CLASSES"
mkdir -p "$TEST_CLASSES"
node "$VENDOR/compile.mjs" --out "$CLASSES" --test-classes "$TEST_CLASSES" \
  --test-root src/test/java \
  --platform "$PLATFORM" --exclude "/client/" src/main/java src/test/java

echo
echo "== 3/4 run the suite on the real JVM ($("$JAVA" -version 2>&1 | head -1))"
"$JAVA" -cp "$CLASSES:$TEST_CLASSES" dev.chatmacro.studio.testing.TestMain | tee "$BUILD/test-run.log"

echo
echo "== 4/4 pack the jar"
python3 scripts/pack-jar.py --core \
  --classes "$CLASSES" \
  --resources src/main/resources \
  --out "$DIST/core/chat-macro-studio-1.0.0-core.jar" \
  --props gradle.properties | tee "$BUILD/pack.log"

echo
echo "== done"
ls -l "$DIST/core/"
