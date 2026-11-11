package dev.chatmacro.studio.theme;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and caches the token sets.
 *
 * <p>{@link #validate(ThemeTokens)} turns the palette into an assertion: every
 * theme must keep body text, muted text, accent and danger legible on the
 * surfaces they are drawn on. That check runs in the test suite, so a recolour
 * cannot silently ship an unreadable screen.</p>
 */
public final class ThemeManager {

    public static final double MIN_BODY_CONTRAST = 7.0;
    public static final double MIN_MUTED_CONTRAST = 4.5;
    public static final double MIN_ACCENT_CONTRAST = 3.0;

    private final Map<ThemeMode, Map<Accent, ThemeTokens>> cache = new EnumMap<>(ThemeMode.class);

    public ThemeTokens tokens(ThemeMode mode, Accent accent) {
        return cache
                .computeIfAbsent(mode, m -> new EnumMap<>(Accent.class))
                .computeIfAbsent(accent, a -> build(mode, a));
    }

    private static ThemeTokens build(ThemeMode mode, Accent accent) {
        boolean dark = mode == ThemeMode.DARK;

        int background = dark ? Colors.rgb(0x14, 0x17, 0x1C) : Colors.rgb(0xEE, 0xF1, 0xF5);
        int backgroundAlt = dark ? Colors.rgb(0x1A, 0x1E, 0x25) : Colors.rgb(0xE3, 0xE8, 0xEF);
        int panel = dark ? Colors.rgb(0x1F, 0x24, 0x2C) : Colors.rgb(0xFF, 0xFF, 0xFF);
        int raisedPanel = dark ? Colors.rgb(0x27, 0x2D, 0x37) : Colors.rgb(0xF7, 0xF9, 0xFC);
        int fieldBackground = dark ? Colors.rgb(0x11, 0x14, 0x19) : Colors.rgb(0xFF, 0xFF, 0xFF);
        int border = dark ? Colors.rgb(0x33, 0x3B, 0x47) : Colors.rgb(0xC9, 0xD1, 0xDB);
        int borderStrong = dark ? Colors.rgb(0x47, 0x52, 0x61) : Colors.rgb(0xA6, 0xB2, 0xC0);
        int divider = dark ? Colors.rgb(0x2A, 0x31, 0x3B) : Colors.rgb(0xDD, 0xE3, 0xEA);

        int normalText = dark ? Colors.rgb(0xE6, 0xEA, 0xF0) : Colors.rgb(0x16, 0x19, 0x1F);
        int mutedText = dark ? Colors.rgb(0x9A, 0xA5, 0xB4) : Colors.rgb(0x55, 0x5F, 0x6D);
        int disabledText = dark ? Colors.rgb(0x5C, 0x66, 0x75) : Colors.rgb(0x98, 0xA2, 0xAF);

        int accentColor = accent.color(mode);
        int accentHover = accent.hover(mode);
        int accentPressed = accent.pressed(mode);
        int accentSoft = dark
                ? Colors.withAlpha(accentColor, 0x33)
                : Colors.withAlpha(accentColor, 0x22);

        int danger = dark ? Colors.rgb(0xEF, 0x44, 0x44) : Colors.rgb(0xC8, 0x1E, 0x1E);
        int dangerHover = dark ? Colors.lighten(danger, 0.14f) : Colors.darken(danger, 0.12f);
        int dangerSoft = dark ? Colors.withAlpha(danger, 0x2E) : Colors.withAlpha(danger, 0x18);

        int success = dark ? Colors.rgb(0x34, 0xD3, 0x99) : Colors.rgb(0x15, 0x80, 0x3D);
        int warning = dark ? Colors.rgb(0xFB, 0xBF, 0x24) : Colors.rgb(0xB4, 0x53, 0x09);

        int onAccentText = Colors.readableOn(accentColor);
        int focusRing = dark ? Colors.rgb(0x7D, 0xB4, 0xFF) : Colors.rgb(0x1D, 0x4E, 0xD8);
        int shadow = dark ? Colors.argb(0x66, 0, 0, 0) : Colors.argb(0x33, 0, 0, 0);
        int selection = dark ? Colors.withAlpha(accentColor, 0x40) : Colors.withAlpha(accentColor, 0x2E);

        return new ThemeTokens(mode, accent, background, backgroundAlt, panel, raisedPanel, fieldBackground,
                border, borderStrong, divider, accentColor, accentHover, accentPressed, accentSoft,
                danger, dangerHover, dangerSoft, success, warning, normalText, mutedText, disabledText,
                onAccentText, focusRing, shadow, selection);
    }

    /**
     * Returns a list of human-readable contrast problems; empty means the theme
     * is readable. Called by the test suite for every mode/accent combination.
     */
    public static List<String> validate(ThemeTokens t) {
        List<String> problems = new ArrayList<>();
        check(problems, "normalText/background", t.normalText(), t.background(), MIN_BODY_CONTRAST);
        check(problems, "normalText/panel", t.normalText(), t.panel(), MIN_BODY_CONTRAST);
        check(problems, "normalText/raisedPanel", t.normalText(), t.raisedPanel(), MIN_BODY_CONTRAST);
        check(problems, "mutedText/panel", t.mutedText(), t.panel(), MIN_MUTED_CONTRAST);
        check(problems, "mutedText/background", t.mutedText(), t.background(), MIN_MUTED_CONTRAST);
        check(problems, "accent/panel", t.accent(), t.panel(), MIN_ACCENT_CONTRAST);
        check(problems, "danger/panel", t.danger(), t.panel(), MIN_ACCENT_CONTRAST);
        check(problems, "onAccentText/accent", t.onAccentText(), t.accent(), MIN_MUTED_CONTRAST);
        check(problems, "success/panel", t.success(), t.panel(), MIN_ACCENT_CONTRAST);
        check(problems, "warning/panel", t.warning(), t.panel(), MIN_ACCENT_CONTRAST);
        return problems;
    }

    private static void check(List<String> problems, String label, int fg, int bg, double min) {
        double ratio = Colors.contrastRatio(fg, bg);
        if (ratio < min) {
            problems.add(String.format(java.util.Locale.ROOT,
                    "%s contrast %.2f < %.2f", label, ratio, min));
        }
    }
}
