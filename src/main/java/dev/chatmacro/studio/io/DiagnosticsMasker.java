package dev.chatmacro.studio.io;

import dev.chatmacro.studio.model.Macro;
import dev.chatmacro.studio.model.Profile;

/**
 * Redaction for anything that leaves the machine (logs, exports, bug reports).
 *
 * <p>Default policy: never write message bodies, never write the arguments of a
 * secret command, never write a server address in a diagnostic blob. Identifiers
 * and error classes are fine - they are what actually helps debugging.</p>
 */
public final class DiagnosticsMasker {

    private DiagnosticsMasker() {
    }

    /** Masks the content of one macro for display/export. */
    public static String maskContent(Macro macro, boolean maskSensitiveOnly) {
        if (macro == null) return "";
        if (macro.isCommand()) {
            if (SensitiveCommands.hasSecretArguments(macro.content())) {
                return SensitiveCommands.mask(macro.content());
            }
            if (maskSensitiveOnly) return macro.content();
            return SensitiveCommands.mask(macro.content());
        }
        return maskSensitiveOnly ? macro.content() : "\u2022\u2022\u2022";
    }

    /** A one-line, privacy-safe description of a macro for logs and exports. */
    public static String describe(Macro macro) {
        if (macro == null) return "<none>";
        return "macro id=" + shortId(macro.id().toString())
                + " type=" + macro.contentType()
                + " interval=" + macro.intervalSeconds() + "s"
                + " enabled=" + macro.enabled()
                + " chars=" + macro.content().codePointCount(0, macro.content().length());
    }

    public static String describe(Profile profile) {
        if (profile == null) return "<none>";
        return "profile id=" + shortId(profile.id().toString())
                + " macros=" + profile.macroCount()
                + " scope=" + profile.scope().kind();
    }

    /** Keeps the first 8 characters of a UUID; enough to correlate, not to track. */
    public static String shortId(String uuid) {
        if (uuid == null || uuid.length() < 8) return "????";
        return uuid.substring(0, 8);
    }

    /**
     * Masks a server address for diagnostics: keeps the top-level domain shape
     * so "it happens on one server" is still diagnosable.
     */
    public static String maskAddress(String address) {
        if (address == null || address.isBlank()) return "";
        String host = address;
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        host = host.toLowerCase(java.util.Locale.ROOT);
        int dot = host.indexOf('.');
        if (dot < 0 || dot == host.length() - 1) return "***";
        return "***" + host.substring(dot);
    }

    /**
     * Scrubs free text (for example an error message) of secret-command
     * arguments. Best effort, and documented as such: server messages arrive in
     * any language and cannot be parsed reliably.
     */
    public static String scrub(String text) {
        if (text == null || text.isEmpty()) return "";
        String out = text;
        for (String name : SensitiveCommands.secretCommandNames()) {
            out = scrubCommand(out, name);
        }
        return out;
    }

    private static String scrubCommand(String text, String command) {
        StringBuilder sb = new StringBuilder();
        String needle = "/" + command;
        int index = 0;
        while (index < text.length()) {
            int found = indexOfIgnoreCase(text, needle, index);
            if (found < 0) {
                sb.append(text, index, text.length());
                break;
            }
            int after = found + needle.length();
            boolean wordBoundary = after >= text.length() || text.charAt(after) == ' ';
            if (!wordBoundary) {
                // "/loginfoo" is a different command; leave it alone.
                sb.append(text, index, after);
                index = after;
                continue;
            }
            sb.append(text, index, after);
            int cursor = after;
            if (cursor < text.length() && text.charAt(cursor) == ' ') cursor++;
            int argumentEnd = cursor;
            while (argumentEnd < text.length() && text.charAt(argumentEnd) != ' ') argumentEnd++;
            if (argumentEnd > cursor) sb.append(" \u2022\u2022\u2022");
            index = argumentEnd;
        }
        return sb.toString();
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        int limit = haystack.length() - needle.length();
        for (int i = Math.max(0, from); i <= limit; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }
}
