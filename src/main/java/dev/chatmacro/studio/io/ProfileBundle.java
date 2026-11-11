package dev.chatmacro.studio.io;

import dev.chatmacro.studio.config.ConfigException;
import dev.chatmacro.studio.model.Macro;
import dev.chatmacro.studio.model.MacroContentType;
import dev.chatmacro.studio.model.Profile;
import dev.chatmacro.studio.model.ServerScope;
import dev.chatmacro.studio.model.ValidationService;
import dev.chatmacro.studio.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Declarative export/import of one profile.
 *
 * <p>The bundle is data only. Importing it never executes anything: no scripts,
 * no classpath entries, no network calls, no reading of other applications'
 * files. Imported macros always arrive <b>disabled</b>, always receive fresh
 * UUIDs, and their content plus server scope is shown in a preview before
 * anything is written.</p>
 */
public final class ProfileBundle {

    public static final String FORMAT = "chatmacro_studio.export";
    public static final int VERSION = 1;

    public record BundleMacro(String name, MacroContentType type, String content,
                              long intervalSeconds, long initialDelaySeconds) { }

    public record BundleProfile(String name, String description, ServerScope scope,
                                List<BundleMacro> macros) {
        public BundleProfile {
            macros = List.copyOf(macros);
        }
    }

    public record Preview(BundleProfile profile, boolean containsSensitive, boolean containsSecrets,
                          List<String> problems) { }

    // ---------------------------------------------------------------- export

    /**
     * Serialises a profile. When {@code includeSensitive} is false, secret
     * command arguments are replaced before they ever leave the process.
     */
    public String export(Profile profile, List<Macro> macros, boolean includeSensitive) {
        Json.Obj root = Json.Obj.of();
        root.with("format", Json.str(FORMAT));
        root.with("version", Json.num(VERSION));
        root.with("profileName", Json.str(profile.name()));
        root.with("description", Json.str(profile.description()));

        Json.Obj scope = Json.Obj.of();
        scope.with("kind", Json.str(profile.scope().kind().name()));
        scope.with("serverAddress", Json.str(profile.scope().serverAddress()));
        scope.with("worldName", Json.str(profile.scope().worldName()));
        root.with("scope", scope);

        Json.Arr arr = Json.Arr.of();
        for (Macro m : macros) {
            String content = m.content();
            if (!includeSensitive && m.isCommand() && SensitiveCommands.isSensitive(content)) {
                content = SensitiveCommands.mask(content);
            }
            Json.Obj o = Json.Obj.of();
            o.with("name", Json.str(m.name()));
            o.with("contentType", Json.str(m.contentType().storageName()));
            o.with("content", Json.str(content));
            o.with("intervalSeconds", Json.num(m.intervalSeconds()));
            o.with("initialDelaySeconds", Json.num(m.initialDelaySeconds()));
            // Deliberately absent: id, profileId, enabled, timestamps. An import
            // must not be able to overwrite an unrelated record or arrive live.
            arr.add(o);
        }
        root.with("macros", arr);
        return Json.write(root);
    }

    // ---------------------------------------------------------------- import

    public Preview preview(String json) throws ConfigException {
        List<String> problems = new ArrayList<>();
        Json.Value root;
        try {
            root = Json.parse(json);
        } catch (Json.JsonException e) {
            throw new ConfigException(ConfigException.Kind.PARSE_ERROR, "invalid bundle: " + e);
        }
        if (!(root instanceof Json.Obj obj)) {
            throw new ConfigException(ConfigException.Kind.PARSE_ERROR, "bundle root is not an object");
        }
        if (!FORMAT.equals(obj.getString("format", ""))) {
            throw new ConfigException(ConfigException.Kind.PARSE_ERROR,
                    "not a Chat Macro Studio bundle");
        }
        long version = obj.getLong("version", 0);
        if (version > VERSION) {
            throw new ConfigException(ConfigException.Kind.SCHEMA_TOO_NEW,
                    "bundle version " + version + " is newer than supported " + VERSION, (int) version);
        }
        if (version < 1) {
            throw new ConfigException(ConfigException.Kind.UNSUPPORTED_SCHEMA,
                    "unknown bundle version " + version, (int) version);
        }

        String name = obj.getString("profileName", "");
        if (name.strip().isEmpty()) {
            name = "Imported profile";
            problems.add("bundle had no profile name; a placeholder is used");
        }
        ServerScope scope = readScope(obj.getObj("scope"), problems);

        List<BundleMacro> macros = new ArrayList<>();
        boolean containsSensitive = false;
        boolean containsSecrets = false;
        for (Json.Value entry : obj.getArr("macros").elements()) {
            if (!(entry instanceof Json.Obj o)) {
                problems.add("bundle macro entry is not an object; skipped");
                continue;
            }
            String typeRaw = o.getString("contentType", "");
            MacroContentType type;
            try {
                type = MacroContentType.parse(typeRaw);
            } catch (IllegalArgumentException | NullPointerException e) {
                problems.add("bundle macro has unknown type '" + typeRaw + "'; skipped");
                continue;
            }
            String content = o.getString("content", "");
            if (ValidationService.containsNewline(content)
                    || ValidationService.containsUnpairedSurrogate(content)) {
                problems.add("bundle macro content contains a newline or broken text; skipped");
                continue;
            }
            if (type == MacroContentType.COMMAND) {
                content = ValidationService.stripLeadingSlash(content).strip();
                if (content.isEmpty()) {
                    problems.add("bundle macro was an empty command; skipped");
                    continue;
                }
                if (SensitiveCommands.hasSecretArguments(content)) {
                    containsSecrets = true;
                    containsSensitive = true;
                } else if (SensitiveCommands.isHighImpact(content)) {
                    containsSensitive = true;
                }
            }
            long interval = o.getLong("intervalSeconds", Macro.DEFAULT_INTERVAL_SECONDS);
            if (interval < ValidationService.MIN_INTERVAL_SECONDS) {
                interval = ValidationService.MIN_INTERVAL_SECONDS;
                problems.add("bundle macro interval was below the minimum; raised to "
                        + ValidationService.MIN_INTERVAL_SECONDS + "s");
            }
            if (interval > ValidationService.MAX_INTERVAL_SECONDS) {
                interval = ValidationService.MAX_INTERVAL_SECONDS;
                problems.add("bundle macro interval was above the maximum; lowered to "
                        + ValidationService.MAX_INTERVAL_SECONDS + "s");
            }
            long delay = o.getLong("initialDelaySeconds", 0L);
            if (delay < 0) delay = 0;
            if (delay > ValidationService.MAX_INITIAL_DELAY_SECONDS) delay = ValidationService.MAX_INITIAL_DELAY_SECONDS;

            String macroName = o.getString("name", "");
            if (macroName.strip().isEmpty()) {
                macroName = "Imported macro " + (macros.size() + 1);
                problems.add("bundle macro had no name; a placeholder is used");
            }
            macros.add(new BundleMacro(macroName.strip(), type, content, interval, delay));
        }

        return new Preview(new BundleProfile(name.strip(), obj.getString("description", ""), scope, macros),
                containsSensitive, containsSecrets, List.copyOf(problems));
    }

    private ServerScope readScope(Json.Obj obj, List<String> problems) {
        if (obj == null) return ServerScope.everywhere();
        String kindRaw = obj.getString("kind", "EVERYWHERE").toUpperCase(Locale.ROOT);
        ServerScope.ScopeKind kind;
        try {
            kind = ServerScope.ScopeKind.valueOf(kindRaw);
        } catch (IllegalArgumentException e) {
            problems.add("bundle scope '" + kindRaw + "' is unknown; importing as EVERYWHERE");
            return ServerScope.everywhere();
        }
        String address = ServerScope.normalizeAddress(obj.getString("serverAddress", ""));
        if (kind == ServerScope.ScopeKind.SPECIFIC_SERVER && address.isEmpty()) {
            problems.add("bundle scope needs an address; importing as EVERYWHERE");
            return ServerScope.everywhere();
        }
        return new ServerScope(kind, address, obj.getString("worldName", ""));
    }
}
