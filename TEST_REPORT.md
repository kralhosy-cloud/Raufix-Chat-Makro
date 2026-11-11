# Test report

Two separate things are true at the same time and this report keeps them apart:

* **The 114 tests below really ran**, in this repository, with the output shown.
* **`./gradlew build` never ran**, because the authoring environment had no JDK,
  no Gradle distribution and no reachable Maven Central, Fabric or Mojang host.
  **No jar exists.** The Minecraft client layer has never been compiled against
  the real Minecraft and Fabric APIs.

"Headless tests pass" is not UI acceptance and not network acceptance. Nothing
here proves the screen draws correctly or that a message reaches a server.

---

## 1. Actually executed

### 1.1 Command and environment

```
$ JW_ASSETS=<path to @wasm-oj/toolchain-java assets> ./scripts/run-offline-tests.sh
# sources=55 diagnostics=0 errors=0 compileOk=true
```

Compiler: a real OpenJDK 21 `javac` compiled to WebAssembly (TeaVM 0.13.1),
running under Node 22's `node:wasi`. Not a JVM, not a re-implementation of
javac — the diagnostics it reports are javac's. See
`tools/offline-javac/README.md` for provenance and for the compiler's own
limitations.

55 sources compiled with **0 diagnostics**: the 42 platform-independent core
sources plus the 13 test sources. The 16 sources under
`dev.chatmacro.studio.client` are excluded (`# excluded=16` in the log) because
Minecraft and Fabric are not on this harness's classpath.

### 1.2 Result

```
suites registered: 114 tests
  json: 13 tests, 0 failed
  validation: 18 tests, 0 failed
  scheduler: 27 tests, 0 failed
  persistence: 13 tests, 0 failed
  isolation: 11 tests, 0 failed
  theme: 9 tests, 0 failed
  animation: 4 tests, 0 failed
  ui: 7 tests, 0 failed
  privacy: 7 tests, 0 failed
  bundle: 5 tests, 0 failed
TOTAL 114 passed, 0 failed, 114 tests
RESULT: PASS
```

Exit status 0. The runner throws on failure, which makes the exit status
non-zero.

### 1.3 Coverage by requirement

**Scheduler (27 tests)** — injectable `Clock.Fake` plus a recording dispatch
adapter, no threads and no real time:

| Requirement | Test |
| --- | --- |
| never sends without an explicit start | `never sends before the user presses start` |
| interval and first dispatch | `sends immediately on start and then on the interval` |
| initial delay | `honours the initial delay` |
| disabled macros never run | `disabled macros never send` |
| global budget (1 per 3 s) | `the global budget caps the combined rate` |
| fair ordering | `fair ordering: the longest waiting macro goes first` |
| catch-up drops missed repeats | `catch-up drops missed repeats instead of bursting` |
| **wall-clock regression** | `a wall-clock jump does not release queued messages` |
| duplicate due instant | `ticking twice at the same instant does not double-send` |
| interval edit applies immediately | `an interval edit takes effect immediately` |
| disabling cancels pending work | `disabling a macro cancels its pending work` |
| pause / resume waits a full interval | `pause holds and resume waits a full interval` |
| chat open / game paused / focus lost / scope | 4 tests |
| session generation guard | `a stale session generation is a hard stop` |
| rebind after reconnect | `binding a session re-arms without replaying` |
| no world / no player | `not being in a game holds rather than sends` |
| hard stop on client rejection | `a client rejection stops the session instead of retrying` |
| backoff then stop | `transient failures back off and then stop that macro` |
| adapter exception cannot escape | `an adapter exception never escapes the tick` |
| profile switch deactivates old timers | `switching profiles deactivates the old timers` |
| try-once is a real send | `try-once is a real send and obeys the budget` |

**Persistence (13 tests)** — `InMemoryByteStore`: round-trip, corrupt file set
aside, newer schema refused with read-only store, duplicate ids dropped, unknown
parent skipped, macro re-attached, schema-0 migration, failed write reported,
debounce + flush on shutdown, imported macros disabled with fresh ids, secret
masking on export, oversized macro skipped with a report.

**Cross-profile isolation (11 tests)** — a same-named macro in two profiles,
re-parenting attempt, profile deletion, duplicate profile (new ids, all
disabled), rename keeps macros, only the active profile can send, auto-start
opt-in, leaving the world stops the run, scope gating, reload stays passive.

**Validation (18), JSON (13), theme (9), animation (4), UI model (7),
privacy (7), bundle (5)** — see the suite listing above; each name states the
behaviour.

### 1.4 What the suite caught, and fixed

These are real product bugs the run found, not test-expectation edits:

| Bug | Fix |
| --- | --- |
| `MacroRepository` accepted a stale `Profile` handle and reported an empty macro list; delete/duplicate then threw `IndexOutOfBoundsException` | every mutating entry point resolves the live record through `current(Profile)` first |
| `resumeByUser()` fired a send immediately after resume | resume re-arms a full interval ahead |
| `DiagnosticsMasker` kept the server host's original case (`***.Example.NET`) and scrubbed nothing when the argument run followed the command with no word boundary | host tail lower-cased; `scrubCommand` requires a boundary and scrubs the argument run |
| `Json.Value` accessors threw on matching types | per-record overrides |
| `Json` wrote control characters with `String.format("\\u%04x", ...)` and no locale, so a Turkish/Arabic default locale produced non-ASCII hex digits and corrupted the config file | `Locale.ROOT` pinned; regression test runs the writer under `tr_TR`, `ar_EG` and `fa_IR` |

An earlier run of the same suite reported **92 passed / 20 failed**; the fixes
above plus five corrected test expectations (which were wrong, not the product)
produced the 114/0 result.

### 1.5 Also executed

```
$ python3 scripts/check-languages.py
keys: en_us=194 tr_tr=194; referenced by code or runtime list=194
RESULT: PASS - both language files are consistent
```

This is a real check, not a description: it parses both files, verifies key-set
equality, compares `%s` placeholder counts, scans every string literal in
`src/main/java` for dotted key-shaped candidates, and fails on any key the code
can ask for that has no translation. It also asserts the Turkish file really
contains Turkish-specific characters. It found and fixed 43 real gaps on first
run (all `validation.*` keys) and 4 more after the UI grew
(`profile.autoStart`, `run.stoppedByUser`, `profile.copy`, `settings.f30Note`).

```
$ JW_ASSETS=... node tools/offline-javac/jw.mjs check --exclude "/client/" src/main/java src/test/java
# sources=55 diagnostics=0 errors=0 compileOk=true
```

### 1.6 Real javac and a real JVM (added later, same branch)

The suite above runs on a WebAssembly build of javac and a WASI runtime. A second
path was then built, and it removes the interpreter from the loop:

```
$ JAVA=/tmp/jdkprobe/x/jdk4py/java-runtime/bin/java ./scripts/build-jar.sh
== java:    openjdk version "25.0.2" 2026-01-20 LTS
== 1/4 compile core + tests in one javac run (real javac 23.0.2, WebAssembly build)
# sources=55 excluded=15 errors=0 status=completed ms=2110
# classfiles=90 tests=19 out=.../build/classes
== 3/4 run the suite on the real JVM (openjdk version "25.0.2")
TOTAL 114 passed, 0 failed, 114 tests
RESULT: PASS
== 4/4 pack the jar
  "bytes": 129219, "classFiles": 71
```

What that means, precisely:

* the compiler is the real OpenJDK 23 `javac` frontend, not a re-implementation,
  and it emitted `.class` files (major version 67) — verified by the magic number
  and by loading them;
* the tests ran on **Temurin 25**, a stock HotSpot JVM (`java -cp ... TestMain`),
  against those class files, and the same 114 assertions passed;
* a `Probe` class loaded `dev.chatmacro.studio.Studio` **from the packaged jar**
  (`getProtectionDomain().getCodeSource()` reported the jar path).

What it still does not mean: the `client.**` sources were not part of that
compile (no Minecraft/Fabric classes exist here), and the jar is therefore not
installable — see `JAR.md` and `KNOWN_LIMITATIONS.md` §1–2.

### 1.7 Checks against published Minecraft 1.21.11 data

```
$ python3 scripts/verify-minecraft-data.py
dataset          : Minecraft 1.21.11 (protocol 774, release)
exact version pin: 1.21.11 == 1.21.11 -> True
  key.keyboard.right.shift   -> 'Right Shift' OK
  key.keyboard.f30           -> None (absent, as required)
our keys vs vanilla: 194 namespaced keys, 0 collision(s)
RESULT: PASS

$ python3 scripts/verify-fabric-api-surface.py
Fabric types imported by the mod: 9   (all OK)
RESULT: PASS
```

The dataset is `minecraft-data` 3.119.0 (`data/pc/1.21.11`, 7 767 language
entries). The Fabric check compares the 9 imported types against an index in
`tools/fabric-api-surface.json`; that index is maintained by hand because
`maven.fabricmc.net` is unreachable, and it says so in its own `verified: false`.

## 2. Verified by inspection only (not compiled)

The 16 files under `src/main/java/dev/chatmacro/studio/client/`. Two checks were
run against them, and both are weaker than a compiler:

1. **Core-side call resolution.** Every `studio.*`, `tokens.*`, `result.*`,
   `macro.*` call made from the client layer resolves to a declared member of a
   core class (71 distinct call names checked against 461 core declarations),
   and the arity of the important ones was read by hand:
   `Studio.createMacro(Profile, String, MacroContentType, String, long, long)`,
   `ThemeManager.tokens(ThemeMode, Accent)`,
   `VirtualList.compute(int, int, int, int)`,
   `Animation(long, Easing, boolean)`.
2. **Intra-client consistency.** Constructors and methods called between client
   classes match by name and arity (16 classes, 73 declared public members, 0
   mismatches).

Neither check sees types. A wrong `TextRenderer` overload, a changed
`DrawContext.enableScissor` signature or a renamed Yarn mapping in 1.21.11 would
not be caught here. Treat the client layer as **written, reviewed, uncompiled**.

## 3. Designed but never executed

| Area | Why |
| --- | --- |
| `./gradlew build`, `./gradlew offlineTests`, `./gradlew runClient` | no JDK, no Gradle, no reachable Maven Central / Fabric / Mojang hosts |
| production remapped jar, SHA-256 and size of a real artefact | no jar was produced; fabricating one would be dishonest |
| any pixel of the GUI | no display, no Minecraft, no LWJGL |
| real chat dispatch against a server | no network to any Minecraft server |
| `FileByteStore` on a real filesystem | WASI file I/O in the harness is unreliable; only `InMemoryByteStore` was exercised. `FileByteStore` is compile-verified, not run. |
| GUI scale / narrow-window / long-Turkish-text layout | layout maths (`VirtualList`, `clamp`) is unit-tested; the widget layout itself is not |
| focus traversal, scroll, popup behaviour in-game | depends on Minecraft's own input handling |
| Right Shift toggle in-game | the binding is registered with `GLFW_KEY_RIGHT_SHIFT` and polled on the client tick; not executed |
| clean-checkout rebuild | not attempted; the blocker above |

## 4. Bugs found by review that the tests cannot see

Found while writing the client layer, fixed before this report:

* the key bindings were registered but **never polled**, so neither would have
  done anything — now handled once per client tick in
  `ChatMacroStudioClient.pollKeyBindings`, plus a direct `matchesKey` check in
  the screen so the emergency stop also works while a screen has focus;
* `openConfirm` passed a lambda where a two-method interface was declared, and
  returned to the studio by calling `setScreen` on a field that `super.close()`
  had just nulled — the dialog now takes a confirm `Runnable` and an explicit
  close hook;
* the theme switch rebuilt the screen with `init(client, ...)`, losing focus and
  typed text — it now swaps tokens on the existing widgets via `setTokens`;
* profile export, import and diagnostics existed in the domain layer and were
  unit-tested but were **unreachable from the UI** — now wired through the
  settings panel, a bundle picker and a preview screen.

None of these would have been caught by the offline suite. That is the honest
cost of the client layer not compiling here.
