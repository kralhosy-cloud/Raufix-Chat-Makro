#!/usr/bin/env bash
#
# Fetches the toolchain `scripts/build-jar.sh` compiles with.
#
# Why this exists: the usual Java build needs Gradle + javac + Loom. On a machine
# where those are missing but npm and a JRE are present, this script assembles an
# equivalent: a real OpenJDK 23 javac compiled to WebAssembly (TraceJVM), plus the
# JDK 23 image it compiles against. The compiler is the genuine javac frontend and
# really emits .class files.
#
# Nothing here is committed: the payload is ~60 MB of third-party binaries and its
# versions are pinned below.
#
#   ./tools/fetch-javac-toolchain.sh && JAVA=/path/to/java ./scripts/build-jar.sh
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENDOR="$ROOT/tools/real-javac"
TRACEJVM_VERSION="0.4.1"
MCDATA_VERSION="3.119.0"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing required tool: $1" >&2; exit 2; }; }
need node
need npm
need tar
need python3

echo "== 1/4 TraceJVM @tracecode/tracejvm@$TRACEJVM_VERSION"
mkdir -p "$VENDOR/tracejvm"
cd "$WORK"
npm pack "@tracecode/tracejvm@$TRACEJVM_VERSION" --silent >/dev/null
tar xzf tracecode-tracejvm-*.tgz
cp -r package/dist package/package.json package/LICENSE package/THIRD_PARTY_NOTICES.md "$VENDOR/tracejvm/"
cp -r package/runtime-release "$VENDOR/tracejvm/"

echo "== 2/4 its runtime dependencies (effect, tar-stream)"
cd "$VENDOR/tracejvm"
npm install --silent --no-audit --no-fund --no-package-lock --omit=dev effect@3.22.0 tar-stream@3.2.0

echo "== 3/4 unpack the compiler + JDK 23 platform image"
python3 - "$VENDOR" <<'PY'
import sys, tarfile, zstandard, pathlib
vendor = pathlib.Path(sys.argv[1])
archives = list((vendor / "tracejvm/runtime-release").glob("*.tar.zst"))
if not archives:
    raise SystemExit("no runtime archive in tracejvm/runtime-release")
out = vendor / "runtime-release"
out.mkdir(parents=True, exist_ok=True)
with open(archives[0], "rb") as fh:
    with zstandard.ZstdDecompressor().stream_reader(fh) as reader:
        with tarfile.open(fileobj=reader, mode="r|") as tf:
            tf.extractall(out)
compiler_src = out / "compiler"
compiler_dst = vendor / "compiler"
compiler_dst.mkdir(parents=True, exist_ok=True)
for name in ("compiler.wasm", "compiler.wasm-runtime.js",
             "compiler.wasm-deobfuscator.wasm", "compiler.wasm.teadbg",
             "compile-classlib-teavm.bin"):
    (compiler_dst / name).write_bytes((compiler_src / name).read_bytes())
runtime_dst = vendor / "runtime"
runtime_dst.mkdir(parents=True, exist_ok=True)
(runtime_dst / "jdk23.jar").write_bytes((out / "profiles/core/jdk23.jar").read_bytes())
print("compiler + jdk23.jar staged")
PY

echo "== 4/4 minecraft-data $MCDATA_VERSION (for scripts/verify-minecraft-data.py)"
cd "$WORK"
npm pack "minecraft-data@$MCDATA_VERSION" --silent >/dev/null
tar xzf minecraft-data-*.tgz
mkdir -p "$ROOT/.cache"
rm -rf "$ROOT/.cache/minecraft-data-1.21.11"
cp -r package/minecraft-data/data/pc/1.21.11 "$ROOT/.cache/minecraft-data-1.21.11"

echo
echo "done. now run:"
echo "  JAVA=/path/to/bin/java ./scripts/build-jar.sh"
echo "  python3 scripts/verify-minecraft-data.py --data .cache/minecraft-data-1.21.11"
