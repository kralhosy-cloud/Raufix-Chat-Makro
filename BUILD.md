# Building Chat Macro Studio

## What you need

| Requirement | Version |
| --- | --- |
| JDK | 21 |
| Minecraft | 1.21.11 (exact) |
| Fabric Loader | 0.19.3 (exact) |
| Gradle | 8.14.2 (via the wrapper) |
| Network | Maven Central + `maven.fabricmc.net` + `libraries.minecraft.net` |

The Gradle wrapper jar (`gradle/wrapper/gradle-wrapper.jar`) is **not** in this
repository. It is a binary, it could not be downloaded in the environment where
this project was written, and inventing one would be worse than leaving it out.
Get the official one on a connected machine:

```bash
gradle wrapper --gradle-version 8.14.2 --distribution-type bin
scripts/verify-gradle-wrapper.sh        # fetches + pins the official sha256
```

`scripts/verify-gradle-wrapper.sh` downloads
`gradle-8.14.2-bin.zip.sha256` from `services.gradle.org` and appends the
`distributionSha256Sum` line to `gradle/wrapper/gradle-wrapper.properties`, so
every later download is checksum-verified.

## Build

```bash
scripts/sync-versions.sh      # optional, see "Version pins" below
./gradlew clean build
./gradlew offlineTests        # the test task (Gradle's own `test` has no JUnit)
```

`./gradlew build` produces, in `build/libs/`:

| File | What it is | Install it? |
| --- | --- | --- |
| `chat-macro-studio-1.0.0.jar` | the **remapped** production jar | **yes** |
| `chat-macro-studio-1.0.0-dev.jar` | development jar, intermediary names | no |
| `chat-macro-studio-1.0.0-sources.jar` | sources only | no |

Copy only the first one into `.minecraft/mods/`.

### Inspecting the artefact

```bash
unzip -l build/libs/chat-macro-studio-1.0.0.jar | grep -E 'fabric.mod.json|assets/|dev/chatmacro'
unzip -p build/libs/chat-macro-studio-1.0.0.jar fabric.mod.json | grep -E 'environment|entrypoints|client'
sha256sum build/libs/chat-macro-studio-1.0.0.jar
```

You should see `fabric.mod.json`, `assets/chatmacro_studio/lang/en_us.json`,
`assets/chatmacro_studio/lang/tr_tr.json`, `assets/chatmacro_studio/icon.png`
and the compiled classes under `dev/chatmacro/studio/`. `environment` must be
`client` and the single entrypoint must be under `client`.

## Version pins

`gradle.properties` holds every version. Two of them are hard requirements and
are never substituted:

```properties
minecraft_version=1.21.11
loader_version=0.19.3
```

**About `"stable": false`.** In the Fabric metadata for Minecraft 1.21.11 the
loader entry for 0.19.3 carries `"stable": false`. That field is reported here
as-is: 0.19.3 is **not** a stable release, it is not the "last recommended"
build, and nothing in this project or its documentation presents it as such. It
is used because it is the required version. If you need a supported combination
instead, that is a separate decision and would mean changing these pins
deliberately, not silently.

Three values could **not** be verified while this project was written, because
`meta.fabricmc.net` was unreachable:

```properties
yarn_mappings=1.21.11+build.1     # UNVERIFIED
loom_version=1.11-SNAPSHOT        # UNVERIFIED
fabric_version=0.0.0+1.21.11      # UNVERIFIED
```

Run `scripts/sync-versions.sh` on a connected machine; it queries
`meta.fabricmc.net` for the exact Yarn build, the Fabric API build for 1.21.11
and a Loom line that supports the pair, and rewrites those three lines. Do not
build for release before running it — `fabric_version=0.0.0+1.21.11` is a
placeholder that will not resolve.

## Tests

`./gradlew offlineTests` runs `dev.chatmacro.studio.testing.TestMain`, a
dependency-free runner with 114 tests over the domain, scheduler, persistence,
theme, UI-model and privacy layers. It exits non-zero on failure. There is no
JUnit dependency, because the suites had to be runnable in an environment where
Maven Central was unreachable; the runner is real code, not a stub.

`./gradlew check` runs `offlineTests`. Gradle's own `test` task is disabled
because there is no JUnit on the classpath.

## Clean-checkout rebuild

A clean-checkout rebuild was **not** possible in the authoring environment and
has not been attempted. Reason: no JDK with `javac`, no Gradle distribution and
no reachable Maven Central, Fabric or Mojang hosts. See `KNOWN_LIMITATIONS.md`
for exactly what was and was not executed.
