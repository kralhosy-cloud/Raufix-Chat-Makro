#!/usr/bin/env bash
#
# Regenerates a genuine Gradle wrapper for this project and pins the official
# distribution checksum.
#
# This repository intentionally ships NO gradle-wrapper.jar: it could not be
# downloaded (or its checksum confirmed) in the offline authoring environment,
# and a fabricated/empty wrapper jar would be worse than none.  Run this script
# once on a machine with network access.
#
# Usage: scripts/verify-gradle-wrapper.sh [gradle-version]
set -euo pipefail

GRADLE_VERSION="${1:-8.14.2}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROPS="${ROOT}/gradle/wrapper/gradle-wrapper.properties"
DIST_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"

echo "==> fetching official checksum for ${DIST_URL}"
SHA256=$(curl -fsSL "${DIST_URL}.sha256")
echo "    sha256=${SHA256}"

echo "==> updating ${PROPS}"
python3 - "$PROPS" "$GRADLE_VERSION" "$SHA256" <<'PY'
import re, sys
path, version, sha = sys.argv[1], sys.argv[2], sys.argv[3]
lines = [l for l in open(path, encoding='utf-8').read().splitlines()]
out = []
seen_sha = False
for line in lines:
    if line.startswith('distributionUrl='):
        out.append(f'distributionUrl=https\\://services.gradle.org/distributions/gradle-{version}-bin.zip')
    elif line.startswith('distributionSha256Sum='):
        out.append(f'distributionSha256Sum={sha}')
        seen_sha = True
    elif line.startswith('# distributionSha256Sum'):
        continue  # drop the "intentionally absent" note once pinned
    else:
        out.append(line)
if not seen_sha:
    out.insert(3, f'distributionSha256Sum={sha}')
open(path, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
print('    pinned')
PY

echo "==> generating gradlew / gradlew.bat / gradle-wrapper.jar with Gradle ${GRADLE_VERSION}"
echo "    (uses a system Gradle; install one with 'sdk install gradle' or your package manager)"
if ! command -v gradle >/dev/null 2>&1; then
  echo "    gradle not found on PATH." >&2
  echo "    Install Gradle ${GRADLE_VERSION} and re-run this script, or run:" >&2
  echo "      gradle wrapper --gradle-version ${GRADLE_VERSION} --gradle-distribution-sha256-sum ${SHA256}" >&2
  exit 1
fi
(cd "$ROOT" && gradle wrapper --gradle-version "$GRADLE_VERSION" \
  --gradle-distribution-sha256-sum "$SHA256")

echo "==> verifying the downloaded distribution checksum"
(cd "$ROOT" && ./gradlew --version)
echo "done"
