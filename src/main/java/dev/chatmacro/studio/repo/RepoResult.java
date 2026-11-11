package dev.chatmacro.studio.repo;

import java.util.List;

/**
 * Outcome of a repository operation, optionally carrying the affected record.
 *
 * <p>Failures carry a translation key plus arguments, never a hard-coded
 * sentence, so both language files remain the single source of user-facing
 * text.</p>
 */
public record RepoResult<T>(T value, boolean success, String messageKey, List<Object> args) {

    public static <T> RepoResult<T> ok(T value) {
        return new RepoResult<>(value, true, "", List.of());
    }

    public static <T> RepoResult<T> ok(T value, String messageKey) {
        return new RepoResult<>(value, true, messageKey, List.of());
    }

    public static <T> RepoResult<T> error(String messageKey, Object... args) {
        return new RepoResult<>(null, false, messageKey, List.of(args));
    }

    public String messageKeyOrEmpty() {
        return messageKey == null ? "" : messageKey;
    }

    /** Convenience for operations with no meaningful return value. */
    public static RepoResult<Void> done() {
        return new RepoResult<>(null, true, "", List.of());
    }

    public static RepoResult<Void> done(String messageKey) {
        return new RepoResult<>(null, true, messageKey, List.of());
    }
}
