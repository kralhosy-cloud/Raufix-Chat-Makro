package dev.chatmacro.studio.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A single macro.
 *
 * <p>This record is <em>persisted preference</em> only: identity, content,
 * cadence and the user's enabled flag. It deliberately does <b>not</b> carry
 * runtime state such as "when was it last sent", "when is it next eligible" or
 * "what was the last error" - those live in
 * {@link dev.chatmacro.studio.sched.MacroRuntime} and are never written to
 * disk. Mixing the two is how mods end up resuming a half-finished timer after
 * a restart.</p>
 */
public record Macro(
        UUID id,
        UUID profileId,
        String name,
        MacroContentType contentType,
        String content,
        long intervalSeconds,
        long initialDelaySeconds,
        boolean enabled,
        long createdAtEpochMillis,
        long updatedAtEpochMillis
) {

    public static final long DEFAULT_INTERVAL_SECONDS = 30;

    public Macro {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(content, "content");
    }

    public static Macro create(UUID id, UUID profileId, String name, MacroContentType type, String content,
                               long intervalSeconds, long initialDelaySeconds, long nowEpochMillis) {
        return new Macro(id, profileId, name, type, content, intervalSeconds, initialDelaySeconds,
                /* new macros are always created disabled */ false, nowEpochMillis, nowEpochMillis);
    }

    /** Same macro, new profile parent and new identity - used by copy/clone/import. */
    public Macro asCopyWithNewIdentity(UUID newId, UUID newProfileId, long nowEpochMillis) {
        return new Macro(newId, newProfileId, name, contentType, content, intervalSeconds,
                initialDelaySeconds, false, nowEpochMillis, nowEpochMillis);
    }

    public Macro withName(String newName, long nowEpochMillis) {
        return new Macro(id, profileId, newName, contentType, content, intervalSeconds,
                initialDelaySeconds, enabled, createdAtEpochMillis, nowEpochMillis);
    }

    public Macro withContent(MacroContentType newType, String newContent, long nowEpochMillis) {
        return new Macro(id, profileId, name, newType, newContent, intervalSeconds,
                initialDelaySeconds, enabled, createdAtEpochMillis, nowEpochMillis);
    }

    public Macro withInterval(long newIntervalSeconds, long nowEpochMillis) {
        return new Macro(id, profileId, name, contentType, content, newIntervalSeconds,
                initialDelaySeconds, enabled, createdAtEpochMillis, nowEpochMillis);
    }

    public Macro withInitialDelay(long newDelaySeconds, long nowEpochMillis) {
        return new Macro(id, profileId, name, contentType, content, intervalSeconds,
                newDelaySeconds, enabled, createdAtEpochMillis, nowEpochMillis);
    }

    public Macro withEnabled(boolean newEnabled, long nowEpochMillis) {
        return new Macro(id, profileId, name, contentType, content, intervalSeconds,
                initialDelaySeconds, newEnabled, createdAtEpochMillis, nowEpochMillis);
    }

    public boolean isCommand() {
        return contentType == MacroContentType.COMMAND;
    }

    /** Safe single-line preview for cards and lists. Never logs the full body. */
    public String preview(int maxChars) {
        String body = isCommand() ? ValidationService.displayCommand(content) : content;
        int codePoints = body.codePointCount(0, body.length());
        if (codePoints <= maxChars) return body;
        int end = body.offsetByCodePoints(0, maxChars);
        return body.substring(0, end) + "\u2026";
    }
}
