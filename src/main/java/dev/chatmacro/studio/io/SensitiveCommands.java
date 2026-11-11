package dev.chatmacro.studio.io;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Commands whose arguments are private or whose repetition has real
 * consequences.
 *
 * <p>Two honest limits:</p>
 * <ul>
 *   <li>This is a <em>local heuristic</em>, not a guarantee. A server plugin can
 *       name anything, and no mod can know what a command does on every
 *       server.</li>
 *   <li>The real safety control is explicit user confirmation plus a stop button
 *       that is always reachable - not this list.</li>
 * </ul>
 */
public final class SensitiveCommands {

    /** Arguments are secrets and must never be exported or logged in clear. */
    private static final Set<String> SECRET = new LinkedHashSet<>();

    /** Repeating these can move money or items; they need a first-run confirm. */
    private static final Set<String> HIGH_IMPACT = new LinkedHashSet<>();

    static {
        SECRET.add("login");
        SECRET.add("register");
        SECRET.add("reg");
        SECRET.add("changepassword");
        SECRET.add("password");
        SECRET.add("2fa");
        SECRET.add("otp");

        HIGH_IMPACT.add("pay");
        HIGH_IMPACT.add("money");
        HIGH_IMPACT.add("withdraw");
        HIGH_IMPACT.add("deposit");
        HIGH_IMPACT.add("buy");
        HIGH_IMPACT.add("sell");
        HIGH_IMPACT.add("trade");
        HIGH_IMPACT.add("tpaccept");
        HIGH_IMPACT.add("tpyes");
        HIGH_IMPACT.add("ah");
        HIGH_IMPACT.add("warp");
        HIGH_IMPACT.add("give");
        HIGH_IMPACT.add("op");
        HIGH_IMPACT.add("deop");
        HIGH_IMPACT.add("ban");
        HIGH_IMPACT.add("kick");
    }

    private SensitiveCommands() {
    }

    /** First token of a command body, lower-cased, without the slash. */
    public static String root(String commandBody) {
        if (commandBody == null) return "";
        String body = commandBody.strip();
        if (body.startsWith("/")) body = body.substring(1);
        int space = body.indexOf(' ');
        String head = space < 0 ? body : body.substring(0, space);
        return head.toLowerCase(Locale.ROOT);
    }

    public static boolean hasSecretArguments(String commandBody) {
        return SECRET.contains(root(commandBody));
    }

    public static boolean isHighImpact(String commandBody) {
        return HIGH_IMPACT.contains(root(commandBody));
    }

    public static boolean isSensitive(String commandBody) {
        return hasSecretArguments(commandBody) || isHighImpact(commandBody);
    }

    /** Replaces every argument with asterisks, keeping the command name. */
    public static String mask(String commandBody) {
        if (commandBody == null) return "";
        String body = commandBody.strip();
        String slash = body.startsWith("/") ? "/" : "";
        String rest = body.startsWith("/") ? body.substring(1) : body;
        int space = rest.indexOf(' ');
        if (space < 0) return slash + rest;
        return slash + rest.substring(0, space) + " \u2022\u2022\u2022";
    }

    public static Set<String> secretCommandNames() {
        return Set.copyOf(SECRET);
    }

    public static Set<String> highImpactCommandNames() {
        return Set.copyOf(HIGH_IMPACT);
    }
}
