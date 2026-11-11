#!/usr/bin/env bash
# Renders off-line previews of the Studio screen (no Minecraft involved).
# See ui-preview/README.md for exactly what these images are and are not.
#
#   scripts/render-ui-preview.sh            # Turkish
#   scripts/render-ui-preview.sh en_us      # English
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LANG_TAG="${1:-tr_tr}"
PYTHON="${PYTHON:-python3}"
"$PYTHON" -c "import PIL" 2>/dev/null || {
  echo "Pillow is required:  $PYTHON -m pip install pillow" >&2
  exit 2
}

# 1. dump the palette the shipping ThemeManager produces
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cp "$ROOT/tools/ui-preview/ThemeDump.java" "$TMP/"
JW_ASSETS="${JW_ASSETS:?set JW_ASSETS to the @wasm-oj/toolchain-java assets dir}" \
  node "$ROOT/tools/offline-javac/jw.mjs" run --exclude "/client/" \
  --entry "$TMP/ThemeDump.java" "$ROOT/src/main/java" "$TMP" 2>/dev/null \
  | "$PYTHON" -c "import sys,json; raw=sys.stdin.read(); \
open('$ROOT/tools/ui-preview/theme-tokens.json','w').write(json.dumps(json.loads(raw[raw.index('{'):]), indent=2)+'\n')"

# 2. render
"$PYTHON" "$ROOT/tools/ui-preview/render_ui_preview.py" --lang "$LANG_TAG"
