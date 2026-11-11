#!/usr/bin/env bash
#
# Runs the project's test suites WITHOUT Gradle, using the offline javac
# toolchain described in tools/offline-javac/README.md.
#
# This is not the normal path - `./gradlew offlineTests` is. It exists because
# this repository was authored in a sandbox with no access to Maven Central or
# the Fabric repositories, and the suites still had to be executed for real.
#
# Usage: scripts/run-offline-tests.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${JW_ASSETS:?set JW_ASSETS to the directory holding the java-teavm assets}"
# The Minecraft client layer is excluded: Minecraft and Fabric are not on
# this harness's classpath. `./gradlew offlineTests` compiles everything.
exec node "${ROOT}/tools/offline-javac/jw.mjs" run \
  --exclude "/client/" \
  --entry "${ROOT}/src/test/java/dev/chatmacro/studio/testing/TestMain.java" \
  "${ROOT}/src/main/java" \
  "${ROOT}/src/test/java"
