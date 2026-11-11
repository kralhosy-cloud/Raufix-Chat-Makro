# `tools/real-javac` — compiling Java without a JDK

This directory makes `scripts/build-jar.sh` able to produce **real class files**
on a machine that has Node, Python and a JRE but no `javac`, no Gradle and no
access to Maven Central.

## What is used

* **TraceJVM 0.4.1** (`@tracecode/tracejvm`): OpenJDK 23.0.2+7 `javac` compiled
  to WebAssembly (TeaVM Wasm GC), plus a JDK 23 image for it to compile against.
  It is a real javac frontend: it emits ordinary `.class` files, not `.wasm`.
* Nothing else. No re-implementation of a Java parser or a class writer exists
  in this directory.

## What is here (all fetched, nothing committed)

```
tracejvm/dist/        the package's ESM bundles and the worker protocol
tracejvm/node_modules/ effect 3.22.0 and tar-stream 3.2.0 (its two dependencies)
compiler/             compiler.wasm, compiler.wasm-runtime.js, compile-classlib-teavm.bin
runtime-release/      the unpacked archive (also holds profiles/core/jdk23.jar)
runtime/jdk23.jar     the JDK 23 image handed to javac as its platform
```

`.gitignore` excludes the first four; `tools/fetch-javac-toolchain.sh` recreates
them. Total ~60 MB.

## What this directory adds

TraceJVM is written for a browser. Three small host files bridge it to Node, and
they are the only code here written for this project:

| File | Job |
| --- | --- |
| `node-host.mjs` | installs a disk-backed `fetch` (file:// and bare paths), `atob`/`btoa`, and a `node:worker_threads` wrapper that behaves like a browser `Worker` |
| `worker-boot.mjs` | maps `parentPort` onto the `self`/`onmessage` globals the worker bundle expects, then imports it untouched |
| `compile.mjs` | the CLI: walks sources, splits test output from main output, writes the class files |

## Usage

```bash
node tools/real-javac/compile.mjs \
  --out build/classes --test-classes build/test-classes \
  --test-root src/test/java \
  --platform tools/real-javac/runtime/jdk23.jar \
  --exclude "/client/" src/main/java src/test/java
```

`--exclude` is applied to the full path, so `"/client/"` drops the Minecraft
layer. `--exclude` is the only reason this can produce output at all here: the
client layer needs `net.minecraft.*`, which is not available in any reachable
package registry.

## Licence caution

TraceJVM is **AGPL-3.0-or-later**; the javac inside it is GPL-2.0-with-Classpath-
Exception. They are build tools here and never enter the mod jar, but read
`THIRD_PARTY_NOTICES.md` before redistributing a build service around them.
