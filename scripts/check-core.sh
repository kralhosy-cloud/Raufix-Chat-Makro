#!/usr/bin/env bash
#
# Compiles the platform-independent core (domain, scheduler, persistence,
# theme, io) and the test sources, WITHOUT the Minecraft client layer.
#
# The client layer cannot be compiled by this harness because Minecraft and
# Fabric are not on its classpath. `./gradlew compileJava` compiles everything.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${JW_ASSETS:?set JW_ASSETS to the directory holding the java-teavm assets}"
exec node "${ROOT}/tools/offline-javac/jw.mjs" check \
  --exclude "/client/" \
  "${ROOT}/src/main/java" "${ROOT}/src/test/java"
