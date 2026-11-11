# Configuration schema

Location: `config/chatmacro_studio/config.json` (relative to the game
directory). Nothing is stored anywhere else.

## Guarantees

* **Versioned.** `schemaVersion` is always written. The current version is `1`
  (`ConfigCodec.CURRENT_SCHEMA_VERSION`).
* **Atomic.** A save writes a temporary file in the same directory and then
  replaces the real file with `ATOMIC_MOVE`. If the filesystem refuses an atomic
  move, it falls back to a plain replace and records that
  (`FileByteStore.lastMoveWasNotAtomic()`).
* **Debounced.** Rapid edits collapse into one write; the file is also flushed
  when the client shuts down and by `/chatmacro save`.
* **Never silently destroyed.** A file that cannot be parsed is moved aside as
  `config.json.corrupt-<millis>` and reported. A file written by a *newer*
  version of the mod is moved aside as `config.json.newer-version`, reported, and
  the store goes **read-only** so this version cannot overwrite data it does not
  fully understand.
* **Bounded.** 8 MiB document cap (`FileByteStore.MAX_DOCUMENT_BYTES`).

There is **no `fsync`**: see `KNOWN_LIMITATIONS.md` §5.

## Document

```jsonc
{
  "schemaVersion": 1,
  "settings": { /* global preferences, see below */ },
  "selectedProfileId": "uuid-string-or-empty",
  "profiles": [ /* profile objects */ ],
  "macros":   [ /* macro objects   */ ]
}
```

`macros` is a flat list. **Isolation is enforced by `macro.profileId`**, not by
list position: a macro whose `profileId` does not exist is skipped with a
reported problem, and a macro that is listed under a profile but whose
`profileId` disagrees is skipped rather than re-parented. Repositories resolve
the live profile record on every access, so a stale handle cannot read or write
another profile's macros.

### settings

| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `themeMode` | `"DARK"` \| `"LIGHT"` | `"DARK"` | `ThemeMode.storageName()` |
| `accent` | string | `"BLUE"` | `Accent.storageName()`, the enum name |
| `reducedMotion` | bool | `false` | disables the 120–220 ms transitions |
| `globalMinDispatchGapMillis` | long | 3000 | local safety budget, all macros combined |
| `holdDispatchWhileChatOpen` | bool | `true` | |
| `pauseOnGamePaused` | bool | `true` | single-player pause menu |
| `pauseOnFocusLoss` | bool | `true` | multiplayer focus loss |
| `showRunStatusInChat` | bool | `true` | echoes run state into the chat log |
| `confirmDangerousCommands` | bool | `true` | first-run confirm for high-impact commands |
| `maskSensitivePreview` | bool | `true` | |
| `verboseDiagnostics` | bool | `false` | |
| `maxChatChars` | int | 512 | clamped to 16…4096 on load |
| `maxCommandChars` | int | 512 | clamped to 16…4096 on load |

These are user-level preferences. Changing them never marks a profile dirty and
never touches a macro.

### profiles[]

| Field | Type | Notes |
| --- | --- | --- |
| `id` | UUID string | duplicate ids are dropped, not merged |
| `name` | string | 1…64 chars |
| `description` | string | 0…280 chars |
| `scope.kind` | `EVERYWHERE` \| `SINGLEPLAYER_ONLY` \| `SPECIFIC_SERVER` | unknown values fall back to `EVERYWHERE` with a reported problem. `SINGLEPLAYER_ONLY` plus a non-empty `worldName` is shown as "this world only" (`ServerScope.describeKey()`) |
| `scope.serverAddress` | string | |
| `scope.worldName` | string | |
| `macroIds` | string[] | capped at 1000 per profile |
| `createdAt`, `updatedAt`, `lastUsedAt` | long (epoch millis) | |
| `autoStartOnJoin` | bool | **default false** — a profile never starts itself unless this is explicitly on |

### macros[]

| Field | Type | Notes |
| --- | --- | --- |
| `id` | UUID string | |
| `profileId` | UUID string | validated against the profile list |
| `name` | string | 1…64 chars |
| `contentType` | `"CHAT_MESSAGE"` \| `"COMMAND"` | the enum name; unknown → skipped with a problem |
| `content` | string | ≤ `maxChatChars` / `maxCommandChars` |
| `intervalSeconds` | long | clamped to 1…86400 on load |
| `initialDelaySeconds` | long | clamped to 0…3600 on load |
| `enabled` | bool | **new and imported macros are always false** |
| `createdAt`, `updatedAt` | long (epoch millis) | |

Runtime state — last send, next due, last error, consecutive failures, backoff —
lives in `MacroRuntime` and is **never persisted**. Reloading the config never
resumes a run.

## Limits

| Limit | Value | Constant |
| --- | --- | --- |
| Profiles | 5 000 | `ConfigCodec.MAX_PROFILES` |
| Macros total | 20 000 | `ConfigCodec.MAX_MACROS` |
| Macros per profile | 1 000 | `ConfigCodec.MAX_MACROS_PER_PROFILE` |
| Document size | 8 MiB | `FileByteStore.MAX_DOCUMENT_BYTES` |
| Name | 64 chars | `ValidationService.MAX_NAME_CHARS` |
| Description | 280 chars | `ValidationService.MAX_DESCRIPTION_CHARS` |
| Content (absolute) | 4096 chars | `ValidationService.ABSOLUTE_MAX_CHARS` |
| Interval | 1 s … 24 h | `MIN_/MAX_INTERVAL_SECONDS` |
| Initial delay | 0 … 1 h | `MAX_INITIAL_DELAY_SECONDS` |

Hitting a limit is reported, and the offending record is skipped. Nothing is
truncated silently and nothing is split into several sends.

## Migrations

`SchemaMigrations` upgrades older documents in memory before they are validated:

* **0 → 1** — adds the `settings` block with defaults, gives every macro an
  explicit `profileId`, and normalises `contentType` to the current storage
  names.

A document with `schemaVersion` greater than `CURRENT_SCHEMA_VERSION` is
**refused** (`SCHEMA_TOO_NEW`), moved aside and the store goes read-only.

Migration behaviour is covered by the persistence suite
(`schema 0 documents are migrated`, `a newer schema is refused and the store
goes read-only`).

## Profile bundles (`.cmsprofile`)

Written to `config/chatmacro_studio/exports/`:

```jsonc
{
  "format": "chatmacro_studio.export",
  "version": 1,
  "profile": { "name": "...", "description": "...", "scope": { ... } },
  "macros": [ { "name": "...", "contentType": "CHAT_MESSAGE", "content": "...",
                "intervalSeconds": 30, "initialDelaySeconds": 0 } ]
}
```

* Export masks secret command arguments (`/login`, `/register`, `/reg`,
  `/changepassword`, `/password`, `/2fa`, `/otp`) by default. A separate,
  confirmed action exports them in clear text.
* A bundle with a different `format` or a newer `version` is refused.
* Import **always** produces disabled macros with **new** UUIDs, shows a preview
  first, and refuses to overwrite a same-named profile without an explicit
  confirmation.
* Only files inside the exports directory can be imported; the resolved path is
  checked against that directory before it is opened.
