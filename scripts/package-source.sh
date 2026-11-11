#!/usr/bin/env bash
# Packages the repository as a source ZIP under dist/, excluding Git metadata,
# build outputs, run directories and anything else that must not ship.
# Writes <name>.zip and <name>.zip.sha256.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAME="${1:-chat-macro-studio-source}"
OUT="$ROOT/dist"
mkdir -p "$OUT"
rm -f "$OUT/$NAME.zip" "$OUT/$NAME.zip.sha256"

cd "$ROOT"
# -x patterns are matched against the stored path, so exclude the whole dist tree
# before writing into it (writing the zip into a directory being zipped is a trap).
zip -qr "$OUT/$NAME.zip" . -x "dist/*" \
  -x ".git/*" -x "build/*" -x "*/build/*" -x "*/run/*" -x "*/.gradle/*" \
  -x "*/dist/*" -x "*/logs/*" -x "*/.idea/*" -x "*.DS_Store" \
  -x "tools/real-javac/compiler/*" -x "tools/real-javac/runtime/*" \
  -x "tools/real-javac/tracejvm/*" -x "tools/real-javac/runtime-release/*" \
  -x ".cache/*"

# Fail loudly if a Minecraft/Fabric binary ever slips in.
if unzip -l "$OUT/$NAME.zip" | grep -qiE '(minecraft|fabric|loader|mappings)[^/]*\.jar'; then
  echo "refusing to package: the archive contains a game or loader jar" >&2
  rm -f "$OUT/$NAME.zip"
  exit 1
fi

( cd "$OUT" && sha256sum "$NAME.zip" | tee "$NAME.zip.sha256" )
ls -l "$OUT/$NAME.zip"
