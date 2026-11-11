# Chat Macro Studio

A Fabric **client** mod for Minecraft Java Edition **1.21.11** that manages chat
macros in profiles, with a GUI, a tick-driven scheduler and file storage.

> **Read `KNOWN_LIMITATIONS.md` first.** This repository was authored in a
> sandbox with no access to the Fabric or Mojang repositories, so
> `./gradlew build` could not be run here and **no compiled jar exists yet**.
> Everything under `src/main/java/dev/chatmacro/studio/client/` is unverified
> against the real Minecraft API. See also `BUILD.md` and `TEST_REPORT.md`.

---

## What it does

You create profiles. Each profile owns its own list of macros. A macro is either
a chat message or a command, repeated every *n* seconds, optionally after an
initial delay. You press **Start**; the mod sends through the client's own chat
path, exactly as if you typed it.

That is the whole feature set on purpose. There is no impersonation, no signed
chat manipulation, no packet injection, no anti-spam evasion, no remote
commands, no analytics and no network traffic of any kind.

### Intended use

Your own account, your own knowledge, in single player, on your own server, or
on a server whose rules allow automation. **Server rules outrank every feature
here.** If a server forbids automation, do not use this mod there. If a server
tells you to stop (kick, mute, cooldown, anti-spam message), the mod pauses or
backs off and never tries to work around it.

Economy and trade commands (`/pay`, `/buy`, `/sell`, `/trade`, `/withdraw`,
`/deposit`, `/ah`, `/tpaccept`, ...) are detected and require an explicit
confirmation the first time you send them, because this mod cannot know what a
server plugin does with them.

## Getting started

1. Build the mod — see `BUILD.md`. You need a JDK 21 and network access.
2. Install Fabric Loader **0.19.3** for Minecraft **1.21.11**, plus Fabric API.
3. Put the built `chat-macro-studio-<version>.jar` in `.minecraft/mods/`.
4. Launch the game.
5. Press **Right Shift** to open the studio.

### The keybind, and about "F30"

The real default key is **Right Shift**, and you can rebind it in
*Options → Controls → Chat Macro Studio*. Some documentation for this mod
mentions "F30". There is no F30 key: physical keyboards and GLFW define
function keys only up to F25, so binding F30 would silently bind nothing. "F30"
is kept only as a label for the action, and the command aliases below cover it:

| Action | Default |
| --- | --- |
| Open the studio | **Right Shift** (rebindable) |
| Emergency stop, all macros | **Right Ctrl** (rebindable) |

| Command | Effect |
| --- | --- |
| `/chatmacro ui` | opens the studio (same as the key) |
| `/chatmacro f30` | alias of `/chatmacro ui` |
| `/chatmacro start` / `pause` / `resume` / `stop` | run control |
| `/chatmacro list` | lists the macros of the active profile |
| `/chatmacro save` | forces a config write |

There is deliberately **no** `/chatmacro f30` key constant: GLFW defines
function keys only up to `F25`, so a binding to a nonexistent `GLFW_KEY_F30`
would silently bind nothing. Both bindings use real constants
(`GLFW_KEY_RIGHT_SHIFT`, `GLFW_KEY_RIGHT_CONTROL`).

The emergency stop is a separate binding and a separate code path from the UI
key: pressing the UI key never stops a running session, and stopping never
opens the UI.

## Safety behaviour

These are implemented, not aspirational:

* **Never starts by itself.** A run begins only from an explicit Start, in the
  UI or via `/chatmacro start`. Per-profile server scopes and the per-profile
  "allow auto start" switch are opt-in and default to off.
* **New macros are created disabled.** Saving a macro never sends anything.
* **No dispatch without a world and a player.** Title screen, disconnect, death,
  dimension change and identity change all stop or hold the run.
* **Session generation guards.** Every join increments a generation counter; a
  callback from an older session is discarded, so a reconnect cannot produce a
  burst of queued sends.
* **Catch-up never bursts.** If ticks are lost or the game was paused, missed
  repeats are dropped and only the next send is scheduled.
* **A local send budget**, by default one dispatch per 3 seconds across all
  macros. This is a safety limit for *your* client, not a bypass of server
  limits.
* **A run is never invisible.** Start, stop, pause, resume and
  scheduler-initiated stops are echoed into the chat log, so a session left
  running after you close the studio is still announced. This can be turned off
  in Settings.
* **Honest detection.** The mod detects what the client can see (disconnect,
  chat disabled, send rejected). It does **not** parse chat to guess whether a
  plugin muted you, and it does not claim to.
* **Never claims to un-send.** A stop cancels pending work; messages already
  sent are gone.

## Storage

Everything is local JSON under `config/chatmacro_studio/`:

```
config/chatmacro_studio/config.json          schema version 1
config/chatmacro_studio/config.json.corrupt-<millis>    (only after damage)
config/chatmacro_studio/config.json.newer-version       (only if written by a newer mod)
```

Writes go to a temporary file, are replaced atomically, and are debounced; the
file is flushed when the client shuts down. A damaged file is backed up and
reported, never silently overwritten, and the mod goes read-only rather than
destroying data it cannot understand. See `docs/config-schema.md`.

Exporting a profile writes a `.cmsprofile` bundle into
`config/chatmacro_studio/exports/`. Secrets such as `/login` passwords are
masked by default; a separate, confirmed action exports them in clear text.
Importing lists the bundles in that directory, shows a preview of the profile
and every macro it contains, and then always produces **disabled** macros with
**new** UUIDs. Overwriting an existing profile requires an explicit
confirmation. See `docs/config-schema.md`.

## Privacy

No tokens, no session data, no credentials, no analytics, no ads, no updater, no
remote commands, no telemetry of any kind. The only thing this mod writes is its
own config directory, its export folder and its own log lines. The log contains
ids and error classes, never message bodies. The **Diagnostics** screen shows
exactly what the mod reports about itself — counts, schema version, whether the
store is read-only, and a masked server address. It is display-only: there is no
send button and nothing leaves your machine.

## Appearance and accessibility

Blue/grey/red palette defined by semantic tokens in
`dev.chatmacro.studio.theme` — every widget reads `tokens.accent()`,
`tokens.danger()`, `tokens.focusRing()` and so on, so the light and dark themes
and the accent choices are one place, not scattered hex values. State is never
conveyed by colour alone: enabled/disabled/running/held each also has a text
label and a distinct shape.

* Tab / Shift+Tab move focus (Minecraft's own widget traversal), Enter
  activates, Escape closes safely and asks before discarding unsaved edits.
* Long lists are virtualised (bounded rendering), so 500 profiles do not cost
  500 widgets.
* Animations are 120–220 ms, time-based, and there is a reduced-motion setting
  that disables them. No timers or listeners outlive the screen.

## Layout of the repository

```
src/main/java/dev/chatmacro/studio/
  ModInfo.java, Studio.java         entry points and the facade
  model/                            Macro, Profile, scopes, validation
  sched/                            MacroScheduler, Clock, policy
  dispatch/                         ChatDispatchAdapter and results
  config/                           codec, store, migrations, settings
  store/                            ByteStore, file and in-memory
  repo/                             profile and macro repositories
  io/                               bundles, sensitive commands, masking
  theme/, ui/                       tokens, colours, virtual list, animation
  client/                           the Minecraft layer (see KNOWN_LIMITATIONS)
                                    15 files, written but never compiled here
src/main/resources/
  fabric.mod.json
  assets/chatmacro_studio/lang/{en_us,tr_tr}.json
  assets/chatmacro_studio/icon.png
src/test/java/dev/chatmacro/studio/  114 tests, dependency-free runner
scripts/                             sync-versions, wrapper check, test runner
tools/offline-javac/                 the harness used to run the tests offline
tools/ui-preview/, ui-preview/       off-line preview renders (not screenshots)
docs/config-schema.md
BUILD.md, TEST_REPORT.md, KNOWN_LIMITATIONS.md, THIRD_PARTY_NOTICES.md
```

`fabric.mod.json` declares `"environment": "client"` and a single `client`
entrypoint (`dev.chatmacro.studio.client.ChatMacroStudioClient`). The domain,
scheduler, persistence and theme layers have **no** Minecraft imports at all,
which is what makes them testable without the game.

## About the preview images

`ui-preview/*.png` are **not Minecraft screenshots**. They are drawn off-line
from the palette the shipping `ThemeManager` returns, the geometry constants in
the client sources and the real language files. They were used to catch contrast
and overflow defects (four real ones, all fixed) but they prove nothing about
in-game behaviour. `ui-preview/studio-walkthrough.gif` is the same renderer
drawing five states in sequence; it is not a screen recording. Details:
`ui-preview/README.md`, `KNOWN_LIMITATIONS.md`.

## Licence

MIT — see `LICENSE`. This project is not affiliated with, endorsed by or
sponsored by Mojang Studios or Microsoft. "Minecraft" is a trademark of Mojang
Studios.
