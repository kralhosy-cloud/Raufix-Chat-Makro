#!/usr/bin/env bash
#
# Fills the UNVERIFIED version pins in gradle.properties from Fabric metadata.
#
# This repository was authored in a sandbox that could not reach
# meta.fabricmc.net, so the yarn build number, the Loom line and the Fabric API
# version could not be confirmed at authoring time.  This script performs that
# confirmation step; run it once on a machine with normal network access, review
# the diff it prints, then build.
#
# Usage: scripts/sync-versions.sh [--mc 1.21.11] [--loader 0.19.3]
set -euo pipefail

MC="1.21.11"
LOADER="0.19.3"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mc) MC="$2"; shift 2 ;;
    --loader) LOADER="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

META="https://meta.fabricmc.net/v2"
PROPS="$(cd "$(dirname "$0")/.." && pwd)/gradle.properties"

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing required tool: $1" >&2; exit 1; }; }
need curl
need python3

echo "==> loader entries advertised for Minecraft ${MC}"
curl -fsSL "${META}/versions/loader/${MC}" -o /tmp/cms-loaders.json
python3 - "$LOADER" <<'PY'
import json, sys
want = sys.argv[1]
data = json.load(open('/tmp/cms-loaders.json'))
hit = None
for entry in data:
    loader = entry.get('loader', {})
    if loader.get('version') == want:
        hit = loader
        break
if hit is None:
    print(f"loader {want} is NOT advertised for this game version; advertised loaders:", file=sys.stderr)
    for entry in data[:10]:
        print('  ', entry.get('loader', {}).get('version'), 'stable=', entry.get('loader', {}).get('stable'), file=sys.stderr)
    sys.exit(1)
print(f"  loader {want}: stable={hit.get('stable')}")
print("  NOTE: 'stable' is reported exactly as Fabric publishes it and is not overridden.")
PY

echo "==> newest yarn mappings for ${MC}"
curl -fsSL "${META}/versions/yarn/${MC}" -o /tmp/cms-yarn.json
YARN=$(python3 - "$MC" <<'PY'
import json, sys
mc = sys.argv[1]
data = json.load(open('/tmp/cms-yarn.json'))
if not data:
    raise SystemExit(f'no yarn mappings published for {mc}')
print(data[0]['version'])
PY
)
echo "  yarn_mappings=${YARN}"

echo "==> newest Fabric API build for ${MC}"
curl -fsSL "${META}/versions/fabric-loader/${LOADER}" -o /dev/null || true
curl -fsSL "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml" -o /tmp/cms-fapi.xml
FABRIC=$(python3 - "$MC" <<'PY'
import re, sys
mc = sys.argv[1]
text = open('/tmp/cms-fapi.xml', encoding='utf-8').read()
versions = re.findall(r'<version>([^<]+)</version>', text)
suffix = '+' + mc
matches = [v for v in versions if v.endswith(suffix)]
if not matches:
    sys.exit(f'no Fabric API build published for {mc}; pick one manually')
print(matches[-1])
PY
)
echo "  fabric_version=${FABRIC}"

echo "==> writing gradle.properties"
python3 - "$PROPS" "$YARN" "$FABRIC" <<'PY'
import re, sys
path, yarn, fabric = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(path, encoding='utf-8').read()
text = re.sub(r'(?m)^yarn_mappings=.*$', f'yarn_mappings={yarn}', text)
text = re.sub(r'(?m)^fabric_version=.*$', f'fabric_version={fabric}', text)
text = text.replace('# --- UNVERIFIED OFFLINE - run scripts/sync-versions.sh to confirm -----------',
                    '# --- confirmed by scripts/sync-versions.sh (yarn + Fabric API) -------------')
open(path, 'w', encoding='utf-8').write(text)
print('  done')
PY

echo
echo "loom_version still has to be chosen by hand: see https://fabricmc.net/develop/"
echo "for the Loom line that supports Minecraft ${MC} and Java 21."
