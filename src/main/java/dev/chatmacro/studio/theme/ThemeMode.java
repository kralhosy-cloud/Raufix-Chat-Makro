package dev.chatmacro.studio.theme;

/** Light or dark surfaces. A global preference, never profile data. */
public enum ThemeMode {
    DARK,
    LIGHT;

    public static ThemeMode parse(String raw, ThemeMode fallback) {
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
