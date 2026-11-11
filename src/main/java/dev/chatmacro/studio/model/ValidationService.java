package dev.chatmacro.studio.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Validation of persisted/user-entered values.
 *
 * <p>Everything here is pure so it can be unit-tested without Minecraft, and
 * every rejection carries a translation key so the UI can explain the problem
 * in the player's own language.</p>
 */
public final class ValidationService {

    /**
     * Upper bounds for outgoing text.
     *
     * <p>These are product safety limits, NOT server guarantees. The exact
     * vanilla limits for 1.21.11 could not be read from the target sources in
     * the offline authoring environment, so the defaults are deliberately
     * conservative and user-adjustable through {@link #withLimits(int, int)}.
     * A server is always free to reject something shorter.</p>
     */
    public static final int DEFAULT_MAX_CHAT_CHARS = 512;
    public static final int DEFAULT_MAX_COMMAND_CHARS = 512;

    /** Hard ceiling no configuration may exceed, to keep files and memory sane. */
    public static final int ABSOLUTE_MAX_CHARS = 4096;

    public static final int MIN_NAME_CHARS = 1;
    public static final int MAX_NAME_CHARS = 64;
    public static final int MAX_DESCRIPTION_CHARS = 280;

    public static final long MIN_INTERVAL_SECONDS = 1;
    public static final long MAX_INTERVAL_SECONDS = 24L * 60 * 60;
    public static final long MAX_INITIAL_DELAY_SECONDS = 60 * 60;

    private final int maxChatChars;
    private final int maxCommandChars;

    public ValidationService() {
        this(DEFAULT_MAX_CHAT_CHARS, DEFAULT_MAX_COMMAND_CHARS);
    }

    public ValidationService(int maxChatChars, int maxCommandChars) {
        this.maxChatChars = clampLimit(maxChatChars);
        this.maxCommandChars = clampLimit(maxCommandChars);
    }

    public ValidationService withLimits(int chat, int command) {
        return new ValidationService(chat, command);
    }

    public int maxChatChars() { return maxChatChars; }
    public int maxCommandChars() { return maxCommandChars; }

    private static int clampLimit(int value) {
        if (value < 16) return 16;
        return Math.min(value, ABSOLUTE_MAX_CHARS);
    }

    // ------------------------------------------------------------------ name

    public Result validateProfileName(String raw, boolean allowDuplicate) {
        Result r = new Result();
        if (raw == null) return r.error("validation.name.null");
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) return r.error("validation.name.empty");
        int chars = trimmed.codePointCount(0, trimmed.length());
        if (chars < MIN_NAME_CHARS) return r.error("validation.name.empty");
        if (chars > MAX_NAME_CHARS) return r.error("validation.name.tooLong");
        if (containsControlCharacters(trimmed)) return r.error("validation.name.controlChars");
        if (containsNewline(trimmed)) return r.error("validation.name.newline");
        // Duplicate names are a UI concern; the caller decides the policy and
        // tells us about it. Nothing is silently accepted or silently rejected.
        if (!allowDuplicate) r.warn("validation.name.duplicate");
        return r.ok(trimmed);
    }

    public Result validateMacroName(String raw) {
        Result r = new Result();
        if (raw == null) return r.error("validation.name.null");
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) return r.error("validation.macroName.empty");
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_NAME_CHARS) {
            return r.error("validation.name.tooLong");
        }
        if (containsControlCharacters(trimmed) || containsNewline(trimmed)) {
            return r.error("validation.name.controlChars");
        }
        return r.ok(trimmed);
    }

    public Result validateDescription(String raw) {
        if (raw == null || raw.isBlank()) return new Result().ok("");
        String trimmed = raw.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_DESCRIPTION_CHARS) {
            return new Result().error("validation.description.tooLong");
        }
        if (containsControlCharacters(trimmed)) {
            return new Result().error("validation.name.controlChars");
        }
        return new Result().ok(trimmed);
    }

    // --------------------------------------------------------------- content

    /**
     * Validates and normalises macro content.
     *
     * <p>Commands are normalised by removing exactly one leading slash - the
     * command API takes the body without it. Slashes inside the arguments are
     * never touched.</p>
     */
    public Result validateContent(MacroContentType type, String raw) {
        Result r = new Result();
        if (raw == null) return r.error("validation.content.null");
        String text = raw.strip();
        if (text.isEmpty()) return r.error("validation.content.empty");
        if (containsNewline(text)) return r.error("validation.content.newline");
        String bad = firstControlCharacter(text);
        if (bad != null) return r.error("validation.content.controlChar");
        if (containsUnpairedSurrogate(text)) return r.error("validation.content.unpairedSurrogate");

        int limit = type == MacroContentType.COMMAND ? maxCommandChars : maxChatChars;
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints > limit) {
            // Never silently truncated and never silently split into several
            // messages: the user is told the exact overflow.
            return r.error("validation.content.tooLong", codePoints, limit);
        }

        if (type == MacroContentType.COMMAND) {
            String body = text;
            if (body.startsWith("/")) body = body.substring(1);
            body = body.strip();
            if (body.isEmpty()) return r.error("validation.command.slashOnly");
            int bodyCodePoints = body.codePointCount(0, body.length());
            if (bodyCodePoints > limit) {
                return r.error("validation.content.tooLong", bodyCodePoints, limit);
            }
            return r.ok(body);
        }
        return r.ok(text);
    }

    /** Removes exactly one leading slash, for display and for the command API. */
    public static String stripLeadingSlash(String text) {
        Objects.requireNonNull(text);
        return text.startsWith("/") ? text.substring(1) : text;
    }

    /** Re-adds the slash for display in the UI, without doubling it. */
    public static String displayCommand(String body) {
        Objects.requireNonNull(body);
        return body.startsWith("/") ? body : "/" + body;
    }

    // -------------------------------------------------------------- interval

    /**
     * Bounded positive interval in seconds.
     *
     * <p>Zero, negative, NaN, infinities and out-of-range values are rejected
     * with a specific key rather than clamped, because a clamped value would be
     * a different macro than the one the player asked for.</p>
     */
    public Result validateIntervalSeconds(double rawSeconds) {
        Result r = new Result();
        if (Double.isNaN(rawSeconds)) return r.error("validation.interval.nan");
        if (Double.isInfinite(rawSeconds)) return r.error("validation.interval.infinite");
        if (rawSeconds <= 0) return r.error("validation.interval.notPositive");
        if (rawSeconds < MIN_INTERVAL_SECONDS) return r.error("validation.interval.tooSmall");
        if (rawSeconds > MAX_INTERVAL_SECONDS) return r.error("validation.interval.tooLarge");
        return r.ok((long) Math.floor(rawSeconds + 0.0));
    }

    public Result validateInitialDelaySeconds(double rawSeconds) {
        Result r = new Result();
        if (Double.isNaN(rawSeconds)) return r.error("validation.interval.nan");
        if (Double.isInfinite(rawSeconds)) return r.error("validation.interval.infinite");
        if (rawSeconds < 0) return r.error("validation.interval.notPositive");
        if (rawSeconds > MAX_INITIAL_DELAY_SECONDS) return r.error("validation.interval.tooLarge");
        return r.ok((long) Math.floor(rawSeconds));
    }

    // ------------------------------------------------------------- utilities

    public static boolean containsNewline(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == 0x0B || c == 0x0C || c == 0x85
                    || c == 0x2028 || c == 0x2029) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsControlCharacters(String text) {
        return firstControlCharacter(text) != null;
    }

    private static String firstControlCharacter(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Allow nothing below 0x20 except a literal tab, and reject the
            // C1 range plus the section-sign formatting introducer that
            // vanilla uses for colour codes. This mod will not craft invisible
            // text or filter-evading sequences.
            if (c < 0x20 || (c >= 0x7F && c <= 0x9F) || c == '\u00a7') {
                return String.valueOf((int) c);
            }
        }
        return null;
    }

    public static boolean containsUnpairedSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) return true;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /** Result of a validation call: either a normalised value or a message key. */
    public static final class Result {
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<Object[]> errorArgs = new ArrayList<>();
        private String value;

        Result ok(Object normalized) {
            this.value = normalized == null ? null : String.valueOf(normalized);
            return this;
        }

        Result error(String key, Object... args) {
            errors.add(key);
            errorArgs.add(args);
            return this;
        }

        Result warn(String key) {
            warnings.add(key);
            return this;
        }

        public boolean isValid() { return errors.isEmpty(); }
        public boolean hasWarnings() { return !warnings.isEmpty(); }
        public List<String> errors() { return List.copyOf(errors); }
        public List<String> warnings() { return List.copyOf(warnings); }
        public String value() { return value; }
        public long valueAsLong() { return Long.parseLong(value); }

        public String firstError() { return errors.isEmpty() ? null : errors.get(0); }

        public Object[] firstErrorArgs() { return errorArgs.isEmpty() ? new Object[0] : errorArgs.get(0); }
    }
}
