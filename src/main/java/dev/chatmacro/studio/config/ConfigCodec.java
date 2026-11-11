package dev.chatmacro.studio.config;

import dev.chatmacro.studio.model.Macro;
import dev.chatmacro.studio.model.MacroContentType;
import dev.chatmacro.studio.model.Profile;
import dev.chatmacro.studio.model.ServerScope;
import dev.chatmacro.studio.model.ValidationService;
import dev.chatmacro.studio.theme.Accent;
import dev.chatmacro.studio.theme.ThemeMode;
import dev.chatmacro.studio.util.Json;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Versioned JSON codec for {@link ConfigDocument}.
 *
 * <p>Rules this codec enforces, all covered by tests:</p>
 * <ul>
 *   <li>A schema version newer than the one this build knows is refused, not
 *       guessed at - parsing it would lose fields on the next save.</li>
 *   <li>An older schema is migrated by real code
 *       ({@link SchemaMigrations}), not by hoping defaults are close.</li>
 *   <li>Recoverable damage (a bad enum, a duplicated id, an oversized field, a
 *       macro pointing at an unknown profile) is skipped and reported; the rest
 *       of the document survives.</li>
 *   <li>Hard limits exist for memory and file size. They are safety limits, not
 *       an artificial product quota.</li>
 * </ul>
 */
public final class ConfigCodec {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static final int MAX_PROFILES = 5_000;
    public static final int MAX_MACROS = 20_000;
    public static final int MAX_MACROS_PER_PROFILE = 1_000;

    private static final String K_SCHEMA = "schemaVersion";
    private static final String K_SAVED_AT = "savedAt";
    private static final String K_SELECTED = "selectedProfileId";
    private static final String K_SETTINGS = "settings";
    private static final String K_PROFILES = "profiles";
    private static final String K_MACROS = "macros";

    /** A decoded document plus everything that had to be skipped or defaulted. */
    public record DecodeResult(ConfigDocument document, List<String> problems) {
        public boolean hadProblems() { return !problems.isEmpty(); }
    }

    // ---------------------------------------------------------------- encode

    public String encode(ConfigDocument document) {
        Json.Obj root = Json.Obj.of();
        root.with(K_SCHEMA, Json.num(CURRENT_SCHEMA_VERSION));
        root.with(K_SAVED_AT, Json.num(document.savedAtEpochMillis()));
        root.with(K_SELECTED, Json.str(document.selectedProfileId()));
        root.with(K_SETTINGS, encodeSettings(document.settings()));
        root.with(K_PROFILES, encodeProfiles(document.profiles()));
        root.with(K_MACROS, encodeMacros(document.macros()));
        return Json.write(root);
    }

    private Json.Obj encodeSettings(GlobalSettings s) {
        Json.Obj o = Json.Obj.of();
        o.with("themeMode", Json.str(s.themeMode().storageName()));
        o.with("accent", Json.str(s.accent().storageName()));
        o.with("reducedMotion", Json.bool(s.reducedMotion()));
        o.with("globalMinDispatchGapMillis", Json.num(s.globalMinDispatchGapMillis()));
        o.with("holdDispatchWhileChatOpen", Json.bool(s.holdDispatchWhileChatOpen()));
        o.with("pauseOnGamePaused", Json.bool(s.pauseOnGamePaused()));
        o.with("pauseOnFocusLoss", Json.bool(s.pauseOnFocusLoss()));
        o.with("showRunStatusInChat", Json.bool(s.showRunStatusInChat()));
        o.with("confirmDangerousCommands", Json.bool(s.confirmDangerousCommands()));
        o.with("maskSensitivePreview", Json.bool(s.maskSensitivePreview()));
        o.with("verboseDiagnostics", Json.bool(s.verboseDiagnostics()));
        o.with("maxChatChars", Json.num(s.maxChatChars()));
        o.with("maxCommandChars", Json.num(s.maxCommandChars()));
        return o;
    }

    private Json.Arr encodeProfiles(List<Profile> profiles) {
        Json.Arr arr = Json.Arr.of();
        for (Profile p : profiles) {
            Json.Obj o = Json.Obj.of();
            o.with("id", Json.str(p.id().toString()));
            o.with("name", Json.str(p.name()));
            o.with("description", Json.str(p.description()));
            Json.Obj scope = Json.Obj.of();
            scope.with("kind", Json.str(p.scope().kind().name()));
            scope.with("serverAddress", Json.str(p.scope().serverAddress()));
            scope.with("worldName", Json.str(p.scope().worldName()));
            o.with("scope", scope);
            Json.Arr ids = Json.Arr.of();
            for (UUID id : p.macroIds()) ids.add(Json.str(id.toString()));
            o.with("macroIds", ids);
            o.with("createdAt", Json.num(p.createdAtEpochMillis()));
            o.with("updatedAt", Json.num(p.updatedAtEpochMillis()));
            o.with("lastUsedAt", Json.num(p.lastUsedAtEpochMillis()));
            o.with("autoStartOnJoin", Json.bool(p.autoStartOnJoin()));
            arr.add(o);
        }
        return arr;
    }

    private Json.Arr encodeMacros(List<Macro> macros) {
        Json.Arr arr = Json.Arr.of();
        for (Macro m : macros) {
            Json.Obj o = Json.Obj.of();
            o.with("id", Json.str(m.id().toString()));
            o.with("profileId", Json.str(m.profileId().toString()));
            o.with("name", Json.str(m.name()));
            o.with("contentType", Json.str(m.contentType().storageName()));
            o.with("content", Json.str(m.content()));
            o.with("intervalSeconds", Json.num(m.intervalSeconds()));
            o.with("initialDelaySeconds", Json.num(m.initialDelaySeconds()));
            o.with("enabled", Json.bool(m.enabled()));
            o.with("createdAt", Json.num(m.createdAtEpochMillis()));
            o.with("updatedAt", Json.num(m.updatedAtEpochMillis()));
            arr.add(o);
        }
        return arr;
    }

    // ---------------------------------------------------------------- decode

    public DecodeResult decode(String json) throws ConfigException {
        List<String> problems = new ArrayList<>();
        Json.Value root;
        try {
            root = Json.parse(json);
        } catch (Json.JsonException e) {
            throw new ConfigException(ConfigException.Kind.PARSE_ERROR, "invalid JSON: " + e.toString());
        }
        if (!(root instanceof Json.Obj obj)) {
            throw new ConfigException(ConfigException.Kind.PARSE_ERROR, "document root is not an object");
        }

        long rawSchema = obj.getLong(K_SCHEMA, 0);
        if (rawSchema > CURRENT_SCHEMA_VERSION) {
            // Refuse: a newer file may carry fields this build does not know,
            // and re-saving would delete them.
            throw new ConfigException(ConfigException.Kind.SCHEMA_TOO_NEW,
                    "document schema " + rawSchema + " is newer than supported " + CURRENT_SCHEMA_VERSION,
                    (int) rawSchema);
        }
        if (rawSchema < 0) {
            throw new ConfigException(ConfigException.Kind.UNSUPPORTED_SCHEMA,
                    "unknown schema version " + rawSchema, (int) rawSchema);
        }

        Json.Obj v1 = rawSchema < CURRENT_SCHEMA_VERSION
                ? SchemaMigrations.migrate(obj, (int) rawSchema, problems)
                : obj;

        GlobalSettings settings = decodeSettings(v1.getObj(K_SETTINGS), problems);
        List<Profile> profiles = decodeProfiles(v1.getArr(K_PROFILES), problems);
        List<Macro> macros = decodeMacros(v1.getArr(K_MACROS), problems);

        // Cross-check both directions of the profile <-> macro relationship.
        Set<UUID> profileIds = new HashSet<>();
        for (Profile p : profiles) profileIds.add(p.id());

        List<Macro> acceptedMacros = new ArrayList<>();
        Set<UUID> macroIds = new HashSet<>();
        for (Macro m : macros) {
            if (!macroIds.add(m.id())) {
                problems.add("duplicate macro id skipped: " + m.id());
                continue;
            }
            if (!profileIds.contains(m.profileId())) {
                problems.add("macro '" + m.name() + "' points at unknown profile; skipped");
                continue;
            }
            acceptedMacros.add(m);
        }

        // Drop macro ids a profile lists but that do not exist (or belong
        // elsewhere); the surviving order is preserved.
        Map<UUID, Macro> byId = new LinkedHashMap<>();
        for (Macro m : acceptedMacros) byId.put(m.id(), m);
        List<Profile> fixedProfiles = new ArrayList<>();
        for (Profile p : profiles) {
            List<UUID> kept = new ArrayList<>();
            for (UUID id : p.macroIds()) {
                Macro m = byId.get(id);
                if (m == null) {
                    problems.add("profile '" + p.name() + "' lists missing macro " + id + "; removed from list");
                } else if (!m.profileId().equals(p.id())) {
                    problems.add("macro " + id + " belongs to another profile; removed from '" + p.name() + "'");
                } else {
                    kept.add(id);
                }
            }
            if (kept.size() != p.macroIds().size()) {
                fixedProfiles.add(p.withMacroIdsNoTouch(kept));
            } else {
                fixedProfiles.add(p);
            }
        }

        // Keep macros that a profile never listed: they are real records and
        // deleting them would lose data, so they are appended to their parent.
        Set<UUID> listed = new HashSet<>();
        for (Profile p : fixedProfiles) listed.addAll(p.macroIds());
        List<Profile> finalProfiles = new ArrayList<>();
        for (Profile p : fixedProfiles) {
            List<UUID> ids = new ArrayList<>(p.macroIds());
            boolean changed = false;
            for (Macro m : acceptedMacros) {
                if (m.profileId().equals(p.id()) && !listed.contains(m.id())) {
                    ids.add(m.id());
                    listed.add(m.id());
                    changed = true;
                    problems.add("macro '" + m.name() + "' was not listed by its profile; re-attached");
                }
            }
            finalProfiles.add(changed ? p.withMacroIdsNoTouch(ids) : p);
        }

        String selected = v1.getString(K_SELECTED, "");
        UUID selectedId = parseUuid(selected, null);
        if (!selected.isEmpty() && (selectedId == null || !profileIds.contains(selectedId))) {
            problems.add("selected profile no longer exists; selection cleared");
            selected = "";
        }

        long savedAt = v1.getLong(K_SAVED_AT, 0L);
        ConfigDocument document = new ConfigDocument(CURRENT_SCHEMA_VERSION, settings,
                finalProfiles, acceptedMacros, selected, savedAt);
        return new DecodeResult(document, List.copyOf(problems));
    }

    private GlobalSettings decodeSettings(Json.Obj obj, List<String> problems) {
        if (obj == null) {
            problems.add("settings block missing; defaults used");
            return GlobalSettings.defaults();
        }
        GlobalSettings d = GlobalSettings.defaults();
        ThemeMode mode = ThemeMode.parse(obj.getString("themeMode", null), d.themeMode());
        Accent accent = Accent.parse(obj.getString("accent", null), d.accent());
        return new GlobalSettings(
                mode,
                accent,
                obj.getBoolean("reducedMotion", d.reducedMotion()),
                clamp(obj.getLong("globalMinDispatchGapMillis", d.globalMinDispatchGapMillis()), 0, 600_000),
                obj.getBoolean("holdDispatchWhileChatOpen", d.holdDispatchWhileChatOpen()),
                obj.getBoolean("pauseOnGamePaused", d.pauseOnGamePaused()),
                obj.getBoolean("pauseOnFocusLoss", d.pauseOnFocusLoss()),
                obj.getBoolean("showRunStatusInChat", d.showRunStatusInChat()),
                obj.getBoolean("confirmDangerousCommands", d.confirmDangerousCommands()),
                obj.getBoolean("maskSensitivePreview", d.maskSensitivePreview()),
                obj.getBoolean("verboseDiagnostics", d.verboseDiagnostics()),
                (int) clamp(obj.getLong("maxChatChars", d.maxChatChars()), 16, ValidationService.ABSOLUTE_MAX_CHARS),
                (int) clamp(obj.getLong("maxCommandChars", d.maxCommandChars()), 16, ValidationService.ABSOLUTE_MAX_CHARS),
                -1
        );
    }

    private List<Profile> decodeProfiles(Json.Arr arr, List<String> problems) {
        List<Profile> out = new ArrayList<>();
        if (arr == null) {
            problems.add("profile list missing; treated as empty");
            return out;
        }
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < arr.size(); i++) {
            if (out.size() >= MAX_PROFILES) {
                problems.add("profile limit " + MAX_PROFILES + " reached; further profiles skipped");
                break;
            }
            if (!(arr.get(i) instanceof Json.Obj o)) {
                problems.add("profile entry " + i + " is not an object; skipped");
                continue;
            }
            UUID id = parseUuid(o.getString("id", null), null);
            if (id == null) {
                problems.add("profile entry " + i + " has no usable id; skipped");
                continue;
            }
            if (!seen.add(id)) {
                problems.add("duplicate profile id skipped: " + id);
                continue;
            }
            String name = o.getString("name", "");
            if (name.strip().isEmpty()) {
                name = "Unnamed";
                problems.add("profile " + id + " had no name; renamed to 'Unnamed'");
            }
            ServerScope scope = decodeScope(o.getObj("scope"), problems, id);
            List<UUID> macroIds = new ArrayList<>();
            for (Json.Value v : o.getArr("macroIds").elements()) {
                if (!(v instanceof Json.Str s)) continue;
                UUID mid = parseUuid(s.value(), null);
                if (mid == null) {
                    problems.add("profile '" + name + "' lists an unreadable macro id; skipped");
                    continue;
                }
                if (macroIds.size() >= MAX_MACROS_PER_PROFILE) {
                    problems.add("macro limit per profile reached for '" + name + "'");
                    break;
                }
                macroIds.add(mid);
            }
            out.add(new Profile(id, name, o.getString("description", ""), macroIds, scope,
                    o.getLong("createdAt", 0L), o.getLong("updatedAt", 0L), o.getLong("lastUsedAt", 0L),
                    o.getBoolean("autoStartOnJoin", false)));
        }
        return out;
    }

    private ServerScope decodeScope(Json.Obj obj, List<String> problems, UUID profileId) {
        if (obj == null) return ServerScope.everywhere();
        String kindRaw = obj.getString("kind", "EVERYWHERE");
        ServerScope.ScopeKind kind;
        try {
            kind = ServerScope.ScopeKind.valueOf(kindRaw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problems.add("profile " + profileId + " has unknown scope '" + kindRaw + "'; using EVERYWHERE");
            return ServerScope.everywhere();
        }
        String address = ServerScope.normalizeAddress(obj.getString("serverAddress", ""));
        String world = obj.getString("worldName", "");
        if (kind == ServerScope.ScopeKind.SPECIFIC_SERVER && address.isEmpty()) {
            problems.add("profile " + profileId + " scope needs an address; using EVERYWHERE");
            return ServerScope.everywhere();
        }
        return new ServerScope(kind, address, world);
    }

    private List<Macro> decodeMacros(Json.Arr arr, List<String> problems) {
        List<Macro> out = new ArrayList<>();
        if (arr == null) {
            problems.add("macro list missing; treated as empty");
            return out;
        }
        for (int i = 0; i < arr.size(); i++) {
            if (out.size() >= MAX_MACROS) {
                problems.add("macro limit " + MAX_MACROS + " reached; further macros skipped");
                break;
            }
            if (!(arr.get(i) instanceof Json.Obj o)) {
                problems.add("macro entry " + i + " is not an object; skipped");
                continue;
            }
            UUID id = parseUuid(o.getString("id", null), null);
            UUID profileId = parseUuid(o.getString("profileId", null), null);
            if (id == null || profileId == null) {
                problems.add("macro entry " + i + " has no usable id/profileId; skipped");
                continue;
            }
            String name = o.getString("name", "");
            if (name.strip().isEmpty()) name = "Unnamed macro";

            String typeRaw = o.getString("contentType", null);
            MacroContentType type;
            try {
                type = MacroContentType.parse(typeRaw);
            } catch (IllegalArgumentException | NullPointerException e) {
                problems.add("macro '" + name + "' has unknown content type '" + typeRaw + "'; skipped");
                continue;
            }

            String content = o.getString("content", "");
            if (ValidationService.containsNewline(content)) {
                problems.add("macro '" + name + "' contained a newline; skipped");
                continue;
            }
            if (ValidationService.containsUnpairedSurrogate(content)) {
                problems.add("macro '" + name + "' contained an unpaired surrogate; skipped");
                continue;
            }
            int limit = ValidationService.ABSOLUTE_MAX_CHARS;
            if (content.codePointCount(0, content.length()) > limit) {
                problems.add("macro '" + name + "' content exceeds the safety limit; skipped");
                continue;
            }
            if (type == MacroContentType.COMMAND) {
                content = ValidationService.stripLeadingSlash(content).strip();
                if (content.isEmpty()) {
                    problems.add("macro '" + name + "' was an empty command; skipped");
                    continue;
                }
            }

            long interval = clamp(o.getLong("intervalSeconds", Macro.DEFAULT_INTERVAL_SECONDS),
                    ValidationService.MIN_INTERVAL_SECONDS, ValidationService.MAX_INTERVAL_SECONDS);
            long initialDelay = clamp(o.getLong("initialDelaySeconds", 0L),
                    0, ValidationService.MAX_INITIAL_DELAY_SECONDS);

            out.add(new Macro(id, profileId, name, type, content, interval, initialDelay,
                    o.getBoolean("enabled", false), o.getLong("createdAt", 0L), o.getLong("updatedAt", 0L)));
        }
        return out;
    }

    private static long clamp(long value, long min, long max) {
        return value < min ? min : Math.min(value, max);
    }

    static UUID parseUuid(String raw, List<String> problems) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            if (problems != null) problems.add("unreadable id: " + raw);
            return null;
        }
    }
}
