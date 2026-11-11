package dev.chatmacro.studio.theme;

/**
 * Selectable accent hues. Blue is the default and the product colour; the others
 * exist so the accent stays a user preference rather than a hard-coded value.
 * Every hue is darkened for the light theme so contrast is preserved.
 */
public enum Accent {
    BLUE(Colors.rgb(0x3B, 0x82, 0xF6), Colors.rgb(0x1D, 0x4E, 0xD8)),
    SKY(Colors.rgb(0x38, 0xBD, 0xF8), Colors.rgb(0x02, 0x84, 0xC7)),
    INDIGO(Colors.rgb(0x63, 0x66, 0xF1), Colors.rgb(0x43, 0x38, 0xCA)),
    TEAL(Colors.rgb(0x14, 0xB8, 0xA6), Colors.rgb(0x0D, 0x94, 0x88)),
    AMBER(Colors.rgb(0xF5, 0x9E, 0x0B), Colors.rgb(0xB4, 0x53, 0x09));

    private final int onDark;
    private final int onLight;

    Accent(int onDark, int onLight) {
        this.onDark = onDark;
        this.onLight = onLight;
    }

    public int color(ThemeMode mode) {
        return mode == ThemeMode.DARK ? onDark : onLight;
    }

    public int hover(ThemeMode mode) {
        return mode == ThemeMode.DARK ? Colors.lighten(onDark, 0.16f) : Colors.darken(onLight, 0.12f);
    }

    public int pressed(ThemeMode mode) {
        return mode == ThemeMode.DARK ? Colors.darken(onDark, 0.18f) : Colors.darken(onLight, 0.24f);
    }

    public static Accent parse(String raw, Accent fallback) {
        if (raw == null) return fallback;
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public String storageName() {
        return name();
    }
}
