package dev.chatmacro.studio.model;

import java.util.Locale;
import java.util.Objects;

/**
 * Where a profile is allowed to run.
 *
 * <p>Scoping exists so macros never fire on a server the player did not intend.
 * The check is deliberately explicit and conservative: when the scope names a
 * server, both the kind and a normalised address have to match. Single player
 * worlds are matched by world name, and a Realm is its own kind.</p>
 *
 * <p>The stored address is what the player typed (host and optional port),
 * lower-cased. It is shown to the player in the UI but is redacted from
 * diagnostic exports - see {@code DiagnosticsMasker}.</p>
 */
public record ServerScope(ScopeKind kind, String serverAddress, String worldName) {

    public enum ScopeKind {
        /** Runs in single player, multiplayer and realms alike. */
        EVERYWHERE,
        /** Only in single player, optionally only in the named world. */
        SINGLEPLAYER_ONLY,
        /** Only on a specific server address (or realm). */
        SPECIFIC_SERVER
    }

    public ServerScope {
        Objects.requireNonNull(kind, "kind");
        serverAddress = normalizeAddress(serverAddress);
        worldName = worldName == null ? "" : worldName.strip();
        if (kind == ScopeKind.SPECIFIC_SERVER && serverAddress.isEmpty()) {
            throw new IllegalArgumentException("SPECIFIC_SERVER scope requires an address");
        }
    }

    public static ServerScope everywhere() {
        return new ServerScope(ScopeKind.EVERYWHERE, "", "");
    }

    public static ServerScope singleplayerOnly(String worldName) {
        return new ServerScope(ScopeKind.SINGLEPLAYER_ONLY, "", worldName == null ? "" : worldName);
    }

    public static ServerScope specificServer(String address) {
        return new ServerScope(ScopeKind.SPECIFIC_SERVER, address, "");
    }

    /** Lower-cases and trims; strips a trailing dot from hostnames. */
    public static String normalizeAddress(String raw) {
        if (raw == null) return "";
        String v = raw.strip().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return "";
        // Strip a scheme if the player pasted one, and a trailing dot.
        int scheme = v.indexOf("://");
        if (scheme >= 0) v = v.substring(scheme + 3);
        int slash = v.indexOf('/');
        if (slash >= 0) v = v.substring(0, slash);
        while (v.endsWith(".")) v = v.substring(0, v.length() - 1);
        return v;
    }

    public boolean matches(SessionInfo session) {
        Objects.requireNonNull(session, "session");
        return switch (kind) {
            case EVERYWHERE -> session.inGame();
            case SINGLEPLAYER_ONLY -> session.kind() == SessionKind.SINGLEPLAYER
                    && (worldName.isEmpty() || worldName.equals(session.worldName()));
            case SPECIFIC_SERVER -> session.inGame()
                    && (session.kind() == SessionKind.MULTIPLAYER || session.kind() == SessionKind.REALM)
                    && serverAddress.equals(session.serverAddress());
        };
    }

    /** Human-readable, localisation-key based description for the UI. */
    public String describeKey() {
        return switch (kind) {
            case EVERYWHERE -> "scope.everywhere";
            case SINGLEPLAYER_ONLY -> worldName.isEmpty() ? "scope.singleplayer" : "scope.singleplayerWorld";
            case SPECIFIC_SERVER -> "scope.specificServer";
        };
    }
}
