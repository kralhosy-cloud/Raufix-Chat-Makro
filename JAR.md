# The jar in `dist/core/`

```
dist/core/chat-macro-studio-1.0.0-core.jar
dist/core/chat-macro-studio-1.0.0-core.jar.build.json
```

## What is inside

| | |
| --- | --- |
| Class files | **71**, compiled from the 42 platform-free sources (`dev.chatmacro.studio.**` minus `client.**`) |
| Bytecode version | major **67** = Java 23 (the compiler is OpenJDK 23.0.2+7 javac) |
| Resources | `fabric.mod.json`, `assets/chatmacro_studio/lang/{en_us,tr_tr}.json` (194 keys each), `icon.png` |
| Entrypoints | **none** — see below |
| Minecraft / Fabric classes | **none**, and no third-party jar is bundled |

The jar was produced by `scripts/build-jar.sh`, which compiles with a real
`javac` (TraceJVM 0.4.1, the OpenJDK 23 javac frontend compiled to WebAssembly),
runs the test suite on a real JVM, then packs with `scripts/pack-jar.py`.

## What it is not

**It is not installable as a Minecraft mod.** Fabric will refuse it, and that is
deliberate rather than an oversight:

* the client layer (`dev.chatmacro.studio.client.**`, 15 sources) needs the
  Fabric API and Minecraft classes; `maven.fabricmc.net`,
  `libraries.minecraft.net`, `launchermeta.mojang.com` and
  `piston-data.mojang.com` are all unreachable from the machine this was built
  on, and no PyPI/npm package ships them (probed: `minecraft-data`,
  `minecraft-launcher-lib`, `minecraft-wrap`, `@minecraft/*`);
* without that layer there is no `ClientModInitializer`, so the jar advertises
  **no entrypoint** instead of advertising one whose class is missing;
* there is no Loom remap step, so the bytecode is whatever javac emitted.

Its `fabric.mod.json` therefore says so in its own description, and carries
`"custom": { "chatMacroStudioCore": true }`. Shipping a jar named like a mod
that silently cannot load would be worse than shipping a jar that says what it is.

## What was actually verified about it

```
$ unzip -l dist/core/chat-macro-studio-1.0.0-core.jar     # 76 entries
$ python3 -c "import struct,zipfile; ..."                  # magic 0xCAFEBABE, major 67
$ java -cp dist/core/...-core.jar Probe
loaded dev.chatmacro.studio.Studio from file:.../chat-macro-studio-1.0.0-core.jar
```

So: a stock JVM loads classes **out of this jar**, and the same class files ran
the 114-test suite. That is real. What is not real is a working in-game mod.

## How to get the real thing

The supported path is unchanged and is documented in `BUILD.md`: run
`./gradlew build` on a machine with network access to Fabric's Maven and
Mojang's libraries. Loom will compile `client.**` against the real 1.21.11
artifacts, remap, and emit `build/libs/chat-macro-studio-1.0.0.jar`. Only that
jar can be dropped into `mods/`.
