package dev.chatmacro.studio.theme;

/**
 * ARGB colour helpers. Pure integer maths so the palette is testable without a
 * renderer, and so "does this text have enough contrast on this surface" is an
 * assertion rather than a screenshot.
 */
public final class Colors {

    private Colors() {
    }

    public static int argb(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public static int rgb(int r, int g, int b) {
        return argb(0xFF, r, g, b);
    }

    public static int alpha(int color) { return (color >>> 24) & 0xFF; }
    public static int red(int color) { return (color >> 16) & 0xFF; }
    public static int green(int color) { return (color >> 8) & 0xFF; }
    public static int blue(int color) { return color & 0xFF; }

    public static int withAlpha(int color, int a) {
        return argb(a, red(color), green(color), blue(color));
    }

    /** Linear blend in 0..1; used for hover and selection transitions. */
    /**
     * Flattens {@code over} onto the opaque colour {@code under}, returning an
     * opaque colour. Widgets must use this (or {@link #blend}) before handing a
     * colour to a fill call: a translucent fill replaces pixels instead of
     * mixing with them, so passing token alphas straight through would punch
     * see-through holes in panels.
     */
    public static int composite(int over, int under) {
        int a = alpha(over);
        if (a >= 0xFF) return over;
        float k = a / 255f;
        return argb(0xFF,
                Math.round(red(over) * k + red(under) * (1f - k)),
                Math.round(green(over) * k + green(under) * (1f - k)),
                Math.round(blue(over) * k + blue(under) * (1f - k)));
    }

    public static int blend(int from, int to, float t) {
        float k = t < 0 ? 0 : (t > 1 ? 1 : t);
        int a = Math.round(alpha(from) + (alpha(to) - alpha(from)) * k);
        int r = Math.round(red(from) + (red(to) - red(from)) * k);
        int g = Math.round(green(from) + (green(to) - green(from)) * k);
        int b = Math.round(blue(from) + (blue(to) - blue(from)) * k);
        return argb(a, r, g, b);
    }

    public static int lighten(int color, float amount) {
        return blend(color, 0xFFFFFFFF, amount);
    }

    public static int darken(int color, float amount) {
        return blend(color, 0xFF000000, amount);
    }

    /** Relative luminance per WCAG, on the 0..1 scale. */
    public static double luminance(int color) {
        return 0.2126 * channel(red(color)) + 0.7152 * channel(green(color)) + 0.0722 * channel(blue(color));
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** WCAG contrast ratio between two colours, 1..21. */
    public static double contrastRatio(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        double lighter = Math.max(la, lb);
        double darker = Math.min(la, lb);
        return (lighter + 0.05) / (darker + 0.05);
    }

    /** Black or white, whichever reads better on the given surface. */
    public static int readableOn(int surface) {
        return contrastRatio(surface, 0xFFFFFFFF) >= contrastRatio(surface, 0xFF000000)
                ? 0xFFFFFFFF : 0xFF000000;
    }

    /**
     * Ensures {@code text} reaches {@code minRatio} against {@code surface} by
     * nudging it towards white or black. Returns the (possibly adjusted) colour.
     */
    public static int enforceContrast(int text, int surface, double minRatio) {
        if (contrastRatio(text, surface) >= minRatio) return text;
        int towardWhite = text;
        int towardBlack = text;
        for (int i = 1; i <= 10; i++) {
            towardWhite = lighten(text, i / 10f);
            towardBlack = darken(text, i / 10f);
            if (contrastRatio(towardWhite, surface) >= minRatio) return towardWhite;
            if (contrastRatio(towardBlack, surface) >= minRatio) return towardBlack;
        }
        return readableOn(surface);
    }
}
