# Offline javac harness (development tool, not part of the mod)

`jw.mjs` compiles and runs the Java sources in this repository without Gradle,
Maven Central or the Fabric repositories.

## Why it exists

The environment this mod was authored in had network egress restricted to
`github.com`, `codeload.github.com`, `api.github.com`, `registry.npmjs.org`,
`pypi.org` and `files.pythonhosted.org`. In particular:

* `services.gradle.org` - unreachable, so no Gradle distribution and no wrapper jar
* `maven.fabricmc.net`, `repo.maven.apache.org`, `libraries.minecraft.net` -
  unreachable, so no Loom, no Fabric API, no Minecraft, no mappings
* no JDK on the machine (`javac` did not exist anywhere on the filesystem)

So `./gradlew build` could not run. Rather than ship unverified code, the
suites are executed through a real OpenJDK 21 `javac` compiled to WebAssembly.

## What it actually is

* compiler: OpenJDK 21 `javac`, built with TeaVM 0.13.1 to WasmGC
  (`konsoletyper/teavm-javac`), shipped as `java-teavm-0.13.1.compiler.wasm`
* class libraries: OpenJDK 21 (`java-teavm-0.13.1.compile-classlib.bin`,
  `java-teavm-0.13.1.runtime-classlib.bin`)
* runner: `node:wasi` in Node 22

All three come from the npm package `@wasm-oj/toolchain-java@0.2.0`. The asset
SHA-256 values in that package's manifest were verified against the downloaded
files:

```
738a82b7d7e7ff8d98b07bb076785da7035847a166afb8446fd150c4efce6103  java-teavm-0.13.1.compile-classlib.bin
0ed234b66cdde19cb29e6c2865a3636d792d3cf72f102e73a7b3fdede80b79e6  java-teavm-0.13.1.runtime-classlib.bin
8c37bce1c6fceeaffeffe93e10834309b67c8b6fb58c6f8a24547afe8be67f5d  java-teavm-0.13.1.compiler.wasm
```

That is a self-consistency check against the package's own manifest, not an
independent signature verification.

## Setup

```bash
npm pack @wasm-oj/toolchain-java@0.2.0
tar xzf wasm-oj-toolchain-java-0.2.0.tgz
export JW_ASSETS="$PWD/package/assets"
```

## Usage

```bash
scripts/run-offline-tests.sh                     # compile + run the suites
node tools/offline-javac/jw.mjs check src/main/java   # compile only
```

## Known limitations of this compiler build

These are properties of the Wasm `javac`, not of the mod, and each one was
reproduced with a minimal test case:

1. `@Override` crashes the compiler (`com.sun.tools.javac.comp.Flow` null
   dereference). `jw.mjs` blanks annotations of the same length before
   compiling so line numbers stay exact. The shipped sources keep `@Override`;
   real `javac` accepts them.
2. `Throwable.getMessage()` and `Object.getClass()` are missing from the compile
   class library, so the mod's core does not call them.
3. `Files.readString` / `java.nio.channels` are unavailable, so the persistence
   layer reads through `Files.newInputStream` and does not `fsync`.
4. `System.exit` is unavailable; `TestMain` throws instead, which also produces
   a non-zero exit status.
5. WASI file I/O in Node is unreliable, so the persistence suites run against
   `InMemoryByteStore`. `FileByteStore` is compile-verified only.
6. The Minecraft and Fabric classes are not available at all, so everything
   under `dev.chatmacro.studio.client` is compile-unverified. See
   KNOWN_LIMITATIONS.md.
