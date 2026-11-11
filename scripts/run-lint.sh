#!/usr/bin/env bash
#
# Static analysis over the .java sources with Semgrep. No compiler, no
# bytecode and no Minecraft on the classpath needed, which is why this runs in
# environments where ./gradlew cannot.
#
# The rules are local (tools/semgrep/chatmacro-studio.yml) because the Semgrep
# registry at semgrep.dev is not reachable from every build machine.
#
# Usage: scripts/run-lint.sh [extra semgrep args]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SEMGREP="${SEMGREP:-semgrep}"
if ! command -v "$SEMGREP" >/dev/null 2>&1; then
  echo "semgrep not found. Install it with:  python3 -m venv .venv && .venv/bin/pip install semgrep" >&2
  echo "then:  SEMGREP=.venv/bin/semgrep scripts/run-lint.sh" >&2
  exit 2
fi
exec "$SEMGREP" scan \
  --config "${ROOT}/tools/semgrep/chatmacro-studio.yml" \
  --metrics=off --disable-version-check --no-git-ignore \
  --error \
  "$@" \
  "${ROOT}/src/main/java" "${ROOT}/src/test/java"
