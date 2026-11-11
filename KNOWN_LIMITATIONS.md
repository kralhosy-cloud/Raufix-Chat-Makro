# Known limitations

Everything below is a real, measured limitation, not a disclaimer. Where a
number is given, it came from a command in this repository.

---

## 1. The mod has never been compiled against Minecraft

**This is the one that matters.** The 15 files under
`src/main/java/dev/chatmacro/studio/client/` (the screen, widgets, key
bindings, commands, session bridge, dispatch adapter and entry point) have never
been through a compiler that could see `net.minecraft.*` or `net.fabricmc.*`.

Measured three ways, not estimated:

```
$ JW_ASSETS=... node tools/offline-javac/jw.mjs check src/main/java
# sources=57 diagnostics=100 errors=100 compileOk=false

$ node tools/real-javac/compile.mjs --out /tmp/x --platform tools/real-javac/runtime/jdk23.jar src/main/java
src13/.../MacroEditorPanel.java:55: error: cannot find symbol
  symbol:   class ButtonWidget
# sources=57 errors=100 status=compile-error

$ node tools/real-javac/compile.mjs --out build/classes --platform tools/real-javac/runtime/jdk23.jar \
      --exclude "/client/" src/main/java
# sources=42 excluded=15 errors=0 status=completed
```

The second command is the real OpenJDK 23 `javac` (TraceJVM), not the older
TeaVM harness, and it agrees: the core compiles clean, the client layer cannot
see its platform. All diagnostics are missing platform types — `package net.minecraft.client
does not exist`, `package net.fabricmc.fabric.api.* does not exist`,
`cannot find symbol: class DrawContext`, and so on. None of them is a logic
error the harness could have found. The same command with `--exclude "/client/"`
compiles the other 42 sources plus the 13 test sources with **0 diagnostics**.

Two weaker checks were run against the client layer instead
(see `TEST_REPORT.md` §2): every call into the core resolves to a declared
member with a matching arity, and client classes are mutually consistent by name
and arity. Neither sees types. Expect to fix compile errors on the first real
build. The most likely places, in order:

1. **Yarn mapping names for 1.21.11.** Every class here uses Yarn names
   (`MinecraftClient`, `DrawContext`, `KeyBinding`, `Text`, `Screen`,
   `TextFieldWidget`, `ButtonWidget`, `ClickableWidget`, `TextRenderer`,
   `NarrationMessageBuilder`, `ClientPlayNetworkHandler`, `IntegratedServer`,
   `ChatScreen`). No Mojang official name (`Minecraft`, `Component`,
   `KeyMapping`, `GuiGraphics`) appears anywhere. If 1.21.11 renamed one of
   these, the error will name it.
2. **`DrawContext.enableScissor` argument order and count.** Used for list
   clipping in three places.
3. **`TextFieldWidget` constructor and `setChangedListener`.**
4. **`KeyBinding` constructor** — the five-argument form with
   `InputUtil.Type.KEYSYM` and a category string.
5. **Client command registration** —
   `ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> ...)`
   with `CommandDispatcher<FabricClientCommandSource>` and
   `net.minecraft.command.CommandBuildContext`.

## 2. `./gradlew build` was never run; the jar that exists is a core-only jar

No Gradle, no Loom, no reachable Minecraft/Fabric artifacts, so the normal build
never ran. What *was* run (see `JAR.md`):

* a real `javac` (OpenJDK 23.0.2+7 via TraceJVM, WebAssembly build) compiled the
  42 platform-free sources to **71 class files**;
* the 114-test suite ran **on a stock JVM against those class files** (not against
  an interpreter) and passed;
* those classes were packed into `dist/core/chat-macro-studio-1.0.0-core.jar`
  (129 KB, sha256 in the adjacent `.build.json`), and a stock JVM loads classes
  out of it.

That jar has **no Fabric entrypoint and cannot be installed as a mod**, because
the 15 `client.**` sources need the Fabric API and Minecraft classes that this
environment cannot obtain. It is labelled as such in its own `fabric.mod.json`.
A remapped, installable production jar still requires `./gradlew build` =
**not produced**.

## 3. Loader 0.19.3 is not a stable release

Fabric's metadata for Minecraft 1.21.11 lists loader 0.19.3 with
`"stable": false`. That is reported here exactly as it is: 0.19.3 is **not**
stable and is **not** "the last recommended build". It is used because it is a
hard requirement of this deliverable, and nothing in this repository presents it
as a safe default. This could not be re-verified from inside the authoring
environment (`meta.fabricmc.net` returned HTTP 000); the value is taken as given
and pinned exactly.

If you want a supported combination instead, that is a separate, deliberate
decision: change `loader_version` in `gradle.properties` and rebuild. Nothing
here does that silently.

## 4. No GUI, input or network behaviour was ever executed

No display, no LWJGL, no Minecraft, no reachable server. Specifically unverified:

* that the screen draws at all, at any GUI scale or window size;
* narrow-vs-wide layout switching, long Turkish strings, scrolling, focus
  traversal, popups;
* that pressing Right Shift opens anything, in game;
* that a macro actually reaches a server's chat;
* the 120–220 ms animation timings on real frames.

The layout *maths* (`VirtualList.compute`, `clampScroll`, hit testing) and the
animation *curve* are unit-tested headlessly. That is not the same as a working
screen. `runClient` starting is not acceptance either.

### What `ui-preview/` is and is not

`ui-preview/*.png` are **renders, not screenshots**. They are drawn by
`tools/ui-preview/render_ui_preview.py` from three genuine inputs — the palette
returned by the shipping `ThemeManager` (dumped through the offline javac
harness), the geometry constants read out of the client sources, and the real
translation files — and the script aborts if a constant drifts from the source.

They are useful for catching contrast, clipping and overflow mistakes. They did:
the selected-card alpha bug, the unreadable amber pill, the header panel that
ended above its own label rows and the toolbar that ran off a 300 px window were
all found by looking at these images, and fixed in the product code (see
`ui-preview/README.md`).

They prove **nothing** about the game: no hitbox, focus order, narration,
animation timing or input behaviour was exercised. The interface has still never
run inside Minecraft. Treat the images as design review material.

`ui-preview/studio-walkthrough.gif` is not a screen recording either: it is five
hand-authored states drawn by the same script and stitched into a GIF. There is no
capture, no input playback and no game frame in it.

## 5. `FileByteStore` was never executed

The persistence suites run against `InMemoryByteStore`. The harness's WASI file
I/O is unreliable (`fd_seek: 76`, `fd_open: 44`, "access to directory not
granted by WASI runtime"), so the real file path — temp write, atomic replace,
non-atomic fallback, 8 MiB cap, path-traversal guard — is compile-verified only.

Two honest consequences:

* **There is no `fsync`.** A power cut between the temp write and the rename can
  still lose the last debounced save. The atomic replace means the previous
  config stays intact; it does not mean the newest one survives.
* The atomic-move fallback is recorded
  (`FileByteStore.lastMoveWasNotAtomic()`), but nothing surfaces it in the UI
  yet.

## 6. Detection is limited to what the client can see

The mod stops or holds when: there is no world or player, the player is not
ready, the game is paused, the chat screen is open (configurable), the window
lost focus (configurable), the profile's scope does not match, the session
generation changed, or the client refused a send.

It does **not**:

* read chat to guess whether a plugin muted or rate-limited you;
* recognise any particular anti-cheat or anti-spam plugin;
* know what a server command does. The high-impact command list
  (`/pay`, `/money`, `/withdraw`, `/deposit`, `/buy`, `/sell`, `/trade`,
  `/tpaccept`, `/tpyes`, `/ah`, `/warp`, `/give`, `/op`, `/deop`, `/ban`,
  `/kick`) is a local heuristic that asks you to confirm, not a guarantee.

When the client refuses a send, the mod stops the session rather than retrying,
and it never reconnects, switches accounts, rotates proxies or rewrites message
content to slip past a filter.

## 7. The send budget is a local safety limit

One dispatch per 3 seconds across all macros, on the client. It is **not** a
server limit, it does not make rapid sending safe, and it does not hide anything
from a server: every message goes out through the normal chat path and is fully
visible.

## 8. No undo, no un-send

A stop cancels pending work. Messages already handed to the client are gone; the
mod cannot retract them and never claims to.

## 9. Small honest gaps

* No profile description field in the UI yet — `updateProfileDescription` exists
  and is tested, but the screen has no editor for it.
* No per-macro reorder or drag; macros are sorted by name or last used.
* The bundle picker only lists files in `config/chatmacro_studio/exports/`.
  There is no free-form file picker, by design.
* Diagnostics are shown, not copied: there is no clipboard button.
* The offline test runner reports counts, not durations. The harness's
  `System.nanoTime()` is unreliable (it reported 28009611 ms for a ~10 s run),
  so timing output was removed rather than printed wrong.

## 10. What is genuinely solid

The 42 platform-independent sources — domain model, validation, scheduler,
persistence codec and store, repositories, bundle import/export, masking, theme
tokens, virtual list, animation — compile with zero diagnostics under a real
OpenJDK 21 javac and are covered by 112 passing tests, including the
cross-profile isolation regressions and the wall-clock regression. Those tests
really ran; the command and full output are in `TEST_REPORT.md`.

## 11. Tools that were requested and could not be installed

Every entry below was probed from this environment, not assumed:

| Tool | What happened |
| --- | --- |
| JetBrains Runtime (DCEVM, `-XX:+AllowEnhancedClassRedefinition`) + HotswapAgent | `cache-redirector.jetbrains.com` and `download.jetbrains.com` both return no connection (`000`). Even with the binary, hot-swapping needs a **running** game JVM with real `.class` files, Mixin applied and a display — none of which exists here. |
| Mill | no reachable distribution (its Maven/Releases hosts are blocked). |
| `javac` / Gradle | not present; `services.gradle.org`, `maven.fabricmc.net`, `repo1.maven.org`, `libraries.minecraft.net` are all unreachable, so the Fabric Loom build cannot resolve anything. `apt-get` is permission-denied. |
| ECJ (`ecj.jar`) | not on PyPI (`ecj`, `eclipse-jdt` → no distribution); GitHub release assets are blocked (`objects.githubusercontent.com` → `000`). |
| PMD | `repo1.maven.org` download → `000`; no PyPI package. |
| Qodana CLI | `download.jetbrains.com` → `000`. |
| SpotBugs | needs compiled `.class`/`.jar` files, which no available compiler can produce. |

**What did run instead:** Semgrep 1.185.0 on the `.java` sources (18 project rules,
0 findings after the fixes), `javalang` 0.13.0 as an independent Java parser, the
TeaVM javac harness for the Minecraft-free core (55 sources, 114 tests), and the
off-line UI renderer described in section 4. Those are real executions, listed
with their real limits — they are not substitutes for PMD/Qodana/SpotBugs, and no
report here claims otherwise.
