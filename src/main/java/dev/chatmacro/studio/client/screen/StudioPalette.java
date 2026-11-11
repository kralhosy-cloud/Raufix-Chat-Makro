package dev.chatmacro.studio.client.screen;

import dev.chatmacro.studio.theme.Colors;
import dev.chatmacro.studio.theme.ThemeTokens;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * Drawing helpers for the studio UI.
 *
 * <p>Vanilla's GUI API has no rounded rectangles, so panels are built from three
 * filled bands plus chamfered corners. That gives a soft, consistent look with
 * no shaders, no blur and no texture atlas - which keeps it fast on weak GPUs
 * and keeps renderer state untouched (no blend or scissor changes leak out).</p>
 *
 * <p>Everything here is stateless: it fills pixels and returns.</p>
 */
public final class StudioPalette {

    /** Corner chamfer, in pixels, at GUI scale 1. */
    public static final int CORNER = 3;

    private StudioPalette() {
    }

    /** Filled panel with a chamfered corner and a 1px border. */
    public static void panel(DrawContext ctx, int x, int y, int w, int h,
                             int fill, int border, int outside) {
        panel(ctx, x, y, w, h, fill, border, outside, CORNER);
    }

    public static void panel(DrawContext ctx, int x, int y, int w, int h,
                             int fill, int border, int outside, int corner) {
        if (w <= 0 || h <= 0) return;
        int c = Math.max(0, Math.min(corner, Math.min(w, h) / 2));
        // Three bands make the body; the corners stay transparent.
        ctx.fill(x + c, y, x + w - c, y + h, fill);
        ctx.fill(x, y + c, x + w, y + h - c, fill);
        if (c > 1) {
            ctx.fill(x + 1, y + 1, x + c, y + c, fill);
            ctx.fill(x + w - c, y + 1, x + w - 1, y + c, fill);
            ctx.fill(x + 1, y + h - c, x + c, y + h - 1, fill);
            ctx.fill(x + w - c, y + h - c, x + w - 1, y + h - 1, fill);
        }
        if (border != 0) {
            ctx.drawBorder(x, y, w, h, border);
            // Trim the border out of the chamfered corners.
            ctx.fill(x, y, x + 1, y + 1, outside);
            ctx.fill(x + w - 1, y, x + w, y + 1, outside);
            ctx.fill(x, y + h - 1, x + 1, y + h, outside);
            ctx.fill(x + w - 1, y + h - 1, x + w, y + h, outside);
        }
    }

    /** Soft drop shadow: two offset bands under the panel, no blur. */
    public static void shadow(DrawContext ctx, int x, int y, int w, int h, int shadowColor) {
        ctx.fill(x + 2, y + h, x + w + 2, y + h + 2, shadowColor);
        ctx.fill(x + w, y + 2, x + w + 2, y + h, shadowColor);
    }

    /** Focus ring: a 1px accent outline drawn just outside the control. */
    public static void focusRing(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.drawBorder(x - 1, y - 1, w + 2, h + 2, color);
    }

    /** A status pill: filled capsule plus centred label. */
    public static void pill(DrawContext ctx, TextRenderer text, int x, int y, int h,
                            String label, int fill, int textColor) {
        int w = text.getWidth(label) + 12;
        panel(ctx, x, y, w, h, fill, 0, fill, h / 2);
        // The fill can be a saturated accent (amber "command" pill), where the
        // panel's normal text colour is unreadable. Pick a colour that works on
        // the pill itself, keeping the caller's choice when it already passes.
        int readable = Colors.enforceContrast(textColor, fill, 4.5);
        ctx.drawTextWithShadow(text, label, x + 6, y + (h - 8) / 2, readable);
        return;
    }

    public static int pillWidth(TextRenderer text, String label) {
        return text.getWidth(label) + 12;
    }

    /**
     * A track-and-knob switch. State is conveyed by the knob position <em>and</em>
     * by a text label drawn next to it, so it never depends on colour alone.
     */
    public static void toggle(DrawContext ctx, int x, int y, int w, int h,
                              boolean on, int trackOn, int trackOff, int knob, boolean focused,
                              int focusColor) {
        int radius = h / 2;
        panel(ctx, x, y, w, h, on ? trackOn : trackOff, 0, on ? trackOn : trackOff, radius);
        int knobSize = h - 4;
        int knobX = on ? x + w - knobSize - 2 : x + 2;
        panel(ctx, knobX, y + 2, knobSize, knobSize, knob, 0, knob, knobSize / 2);
        if (focused) focusRing(ctx, x, y, w, h, focusColor);
    }

    /** Text truncated to a pixel budget, with an ellipsis, never overflowing. */
    public static String ellipsize(TextRenderer text, String value, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (text.getWidth(value) <= maxWidth) return value;
        String ellipsis = "\u2026";
        int budget = maxWidth - text.getWidth(ellipsis);
        if (budget <= 0) return ellipsis;
        // Walk code points so a surrogate pair is never cut in half.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            String candidate = sb.toString() + new String(Character.toChars(codePoint));
            if (text.getWidth(candidate) > budget) break;
            sb.appendCodePoint(codePoint);
            i += charCount;
        }
        return sb + ellipsis;
    }

    /** Fades a colour towards transparent, used for the panel open animation. */
    public static int fade(int color, float progress) {
        int alpha = Math.round(Colors.alpha(color) * Math.max(0f, Math.min(1f, progress)));
        return Colors.withAlpha(color, alpha);
    }
}
