package dev.chatmacro.studio.config;

/**
 * A configuration problem the user has to hear about.
 *
 * <p>The {@code kind} decides what the UI offers next: a parse error offers
 * "restore the backup", a too-new schema offers "this file was written by a
 * newer version, nothing was changed". Nothing is silently replaced with empty
 * defaults.</p>
 */
public final class ConfigException extends RuntimeException {

    public enum Kind {
        PARSE_ERROR,
        SCHEMA_TOO_NEW,
        DOCUMENT_TOO_LARGE,
        IO_ERROR,
        UNSUPPORTED_SCHEMA
    }

    private final Kind kind;
    private final int foundSchemaVersion;

    public ConfigException(Kind kind, String message) {
        this(kind, message, -1);
    }

    public ConfigException(Kind kind, String message, int foundSchemaVersion) {
        super(message);
        this.kind = kind;
        this.foundSchemaVersion = foundSchemaVersion;
    }

    public Kind kind() {
        return kind;
    }

    /** -1 when the problem is not about a schema version. */
    public int foundSchemaVersion() {
        return foundSchemaVersion;
    }
}
