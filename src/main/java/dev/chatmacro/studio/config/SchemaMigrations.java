package dev.chatmacro.studio.config;

import dev.chatmacro.studio.util.Json;

import java.util.List;
import java.util.Locale;

/**
 * Real migrations for older configuration files.
 *
 * <p>Schema 0 was the pre-release shape: macros nested inside their profile, the
 * interval stored as a string, a boolean {@code darkMode} instead of a theme
 * mode, and no scope or timestamps. Schema 1 flattens macros to a top-level list
 * with a {@code profileId}, types the interval as a number and adds scoping.</p>
 *
 * <p>The migration is ordinary code with its own tests - it is not a "read with
 * defaults and hope" path.</p>
 */
public final class SchemaMigrations {

    private SchemaMigrations() {
    }

    public static Json.Obj migrate(Json.Obj source, int fromVersion, List<String> problems) {
        Json.Obj current = source;
        for (int v = fromVersion; v < ConfigCodec.CURRENT_SCHEMA_VERSION; v++) {
            switch (v) {
                case 0 -> current = migrateV0ToV1(current, problems);
                default -> throw new ConfigException(ConfigException.Kind.UNSUPPORTED_SCHEMA,
                        "no migration path from schema " + v, v);
            }
        }
        return current;
    }

    private static Json.Obj migrateV0ToV1(Json.Obj v0, List<String> problems) {
        problems.add("migrated configuration from schema 0 to 1");

        Json.Obj root = Json.Obj.of();
        root.with("schemaVersion", Json.num(ConfigCodec.CURRENT_SCHEMA_VERSION));
        root.with("savedAt", Json.num(v0.getLong("savedAt", 0L)));
        root.with("selectedProfileId", Json.str(v0.getString("selectedProfileId", "")));

        // settings: darkMode -> themeMode, gapSeconds -> millis
        Json.Obj oldSettings = v0.getObj("settings");
        Json.Obj settings = Json.Obj.of();
        if (oldSettings != null) {
            boolean darkMode = oldSettings.getBoolean("darkMode", true);
            settings.with("themeMode", Json.str(darkMode ? "DARK" : "LIGHT"));
            settings.with("accent", Json.str(oldSettings.getString("accent", "BLUE")
                    .toUpperCase(Locale.ROOT)));
            long gapSeconds = oldSettings.getLong("gapSeconds", 3L);
            settings.with("globalMinDispatchGapMillis", Json.num(Math.max(0, gapSeconds) * 1000L));
        } else {
            settings.with("themeMode", Json.str("DARK"));
            settings.with("accent", Json.str("BLUE"));
            settings.with("globalMinDispatchGapMillis", Json.num(3000L));
            problems.add("schema 0 file had no settings block; defaults used");
        }
        root.with("settings", settings);

        Json.Arr profilesOut = Json.Arr.of();
        Json.Arr macrosOut = Json.Arr.of();

        for (Json.Value entry : v0.getArr("profiles").elements()) {
            if (!(entry instanceof Json.Obj oldProfile)) continue;
            String id = oldProfile.getString("id", "");
            if (id.isBlank()) {
                problems.add("schema 0 profile without an id was dropped");
                continue;
            }

            Json.Arr macroIds = Json.Arr.of();
            for (Json.Value macroEntry : oldProfile.getArr("macros").elements()) {
                if (!(macroEntry instanceof Json.Obj oldMacro)) continue;
                String macroId = oldMacro.getString("id", "");
                if (macroId.isBlank()) {
                    problems.add("schema 0 macro without an id was dropped");
                    continue;
                }
                Json.Obj migrated = Json.Obj.of();
                migrated.with("id", Json.str(macroId));
                migrated.with("profileId", Json.str(id));
                migrated.with("name", Json.str(oldMacro.getString("name", "Unnamed macro")));
                migrated.with("contentType", Json.str(mapLegacyType(oldMacro.getString("type", "message"),
                        oldMacro.getString("name", ""), problems)));
                migrated.with("content", Json.str(oldMacro.getString("text", "")));
                migrated.with("intervalSeconds", Json.num(parseLegacyInterval(
                        oldMacro.get("interval"), oldMacro.getString("name", ""), problems)));
                migrated.with("initialDelaySeconds", Json.num(0L));
                migrated.with("enabled", Json.bool(oldMacro.getBoolean("on", false)));
                migrated.with("createdAt", Json.num(0L));
                migrated.with("updatedAt", Json.num(0L));
                macrosOut.add(migrated);
                macroIds.add(Json.str(macroId));
            }

            Json.Obj profile = Json.Obj.of();
            profile.with("id", Json.str(id));
            profile.with("name", Json.str(oldProfile.getString("name", "Unnamed")));
            profile.with("description", Json.str(oldProfile.getString("description", "")));
            Json.Obj scope = Json.Obj.of();
            scope.with("kind", Json.str("EVERYWHERE"));
            scope.with("serverAddress", Json.str(""));
            scope.with("worldName", Json.str(""));
            profile.with("scope", scope);
            profile.with("macroIds", macroIds);
            profile.with("createdAt", Json.num(0L));
            profile.with("updatedAt", Json.num(0L));
            profile.with("lastUsedAt", Json.num(0L));
            profile.with("autoStartOnJoin", Json.bool(false));
            profilesOut.add(profile);
        }

        root.with("profiles", profilesOut);
        root.with("macros", macrosOut);
        return root;
    }

    private static String mapLegacyType(String legacy, String macroName, List<String> problems) {
        String v = legacy == null ? "" : legacy.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "command", "cmd", "slash" -> "COMMAND";
            case "message", "chat", "text", "" -> "CHAT_MESSAGE";
            default -> {
                problems.add("schema 0 macro '" + macroName + "' had unknown type '" + legacy
                        + "'; treated as CHAT_MESSAGE");
                yield "CHAT_MESSAGE";
            }
        };
    }

    /** Schema 0 stored the interval as a string of seconds. */
    private static long parseLegacyInterval(Json.Value raw, String macroName, List<String> problems) {
        long fallback = dev.chatmacro.studio.model.Macro.DEFAULT_INTERVAL_SECONDS;
        if (raw instanceof Json.Num n) return clampInterval(n.value());
        if (raw instanceof Json.Str s) {
            try {
                return clampInterval(Long.parseLong(s.value().trim()));
            } catch (NumberFormatException e) {
                problems.add("schema 0 macro '" + macroName + "' had unreadable interval '"
                        + s.value() + "'; using " + fallback + "s");
                return fallback;
            }
        }
        return fallback;
    }

    private static long clampInterval(long seconds) {
        if (seconds < dev.chatmacro.studio.model.ValidationService.MIN_INTERVAL_SECONDS) {
            return dev.chatmacro.studio.model.ValidationService.MIN_INTERVAL_SECONDS;
        }
        return Math.min(seconds, dev.chatmacro.studio.model.ValidationService.MAX_INTERVAL_SECONDS);
    }
}
