package dev.chatmacro.studio.model;

/**
 * What a macro sends. Explicit enum values so an unknown persisted value is a
 * loud, reportable error instead of a silently downgraded message.
 */
public enum MacroContentType {
    /** Plain chat text, sent through the normal chat path. */
    CHAT_MESSAGE,
    /**
     * A command. The user may type the leading slash or not; dispatch
     * normalises it for the command API (which expects no slash) without
     * touching slashes inside the arguments.
     */
    COMMAND;

    public static MacroContentType parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("null content type");
        return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    }

    public String storageName() {
        return name();
    }
}
