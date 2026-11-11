package dev.chatmacro.studio.theme;

/**
 * Semantic colour tokens.
 *
 * <p>Widgets ask for "panel", "border", "danger" - never for a literal hex
 * value - so a theme swap cannot leave half the screen in the old palette and so
 * the palette stays auditable in one place.</p>
 */
public record ThemeTokens(
        ThemeMode mode,
        Accent accentId,
        int background,
        int backgroundAlt,
        int panel,
        int raisedPanel,
        int fieldBackground,
        int border,
        int borderStrong,
        int divider,
        int accent,
        int accentHover,
        int accentPressed,
        int accentSoft,
        int danger,
        int dangerHover,
        int dangerSoft,
        int success,
        int warning,
        int normalText,
        int mutedText,
        int disabledText,
        int onAccentText,
        int focusRing,
        int shadow,
        int selection
) {

    /** Text that must stay readable on {@code surface}, corrected if necessary. */
    public int textOn(int surface) {
        return Colors.enforceContrast(normalText, surface, 7.0);
    }

    public int mutedOn(int surface) {
        return Colors.enforceContrast(mutedText, surface, 4.5);
    }
}
