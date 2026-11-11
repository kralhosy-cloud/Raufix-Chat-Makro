import dev.chatmacro.studio.theme.Accent;
import dev.chatmacro.studio.theme.ThemeManager;
import dev.chatmacro.studio.theme.ThemeMode;
import dev.chatmacro.studio.theme.ThemeTokens;

/**
 * Development tool: prints the exact palette the shipping ThemeManager produces,
 * so an off-line renderer can draw the UI with the real colours instead of a
 * guess. Not part of the mod, not a test.
 */
public class ThemeDump {

    private static String hex(int argb) {
        int a = (argb >>> 24) & 0xFF;
        return a == 0xFF
                ? String.format("#%06X", argb & 0xFFFFFF)
                : String.format("#%08X", argb);
    }

    public static void main(String[] args) {
        ThemeManager manager = new ThemeManager();
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        boolean firstCombo = true;
        for (ThemeMode mode : new ThemeMode[] { ThemeMode.DARK, ThemeMode.LIGHT }) {
            for (Accent accent : new Accent[] { Accent.BLUE, Accent.AMBER }) {
                ThemeTokens t = manager.tokens(mode, accent);
                if (!firstCombo) out.append(",\n");
                firstCombo = false;
                out.append("  \"").append(mode.name()).append('/').append(accent.name()).append("\": {");
                String[] names = { "background", "backgroundAlt", "panel", "raisedPanel",
                        "fieldBackground", "border", "borderStrong", "divider", "accent",
                        "accentHover", "accentPressed", "accentSoft", "danger", "dangerHover",
                        "dangerSoft", "success", "warning", "normalText", "mutedText",
                        "disabledText", "onAccentText", "focusRing", "shadow", "selection" };
                int[] values = { t.background(), t.backgroundAlt(), t.panel(), t.raisedPanel(),
                        t.fieldBackground(), t.border(), t.borderStrong(), t.divider(), t.accent(),
                        t.accentHover(), t.accentPressed(), t.accentSoft(), t.danger(), t.dangerHover(),
                        t.dangerSoft(), t.success(), t.warning(), t.normalText(), t.mutedText(),
                        t.disabledText(), t.onAccentText(), t.focusRing(), t.shadow(), t.selection() };
                for (int i = 0; i < names.length; i++) {
                    if (i > 0) out.append(", ");
                    out.append('"').append(names[i]).append("\": \"").append(hex(values[i])).append('"');
                }
                // textOn()/mutedOn() are what widgets actually draw with
                out.append(", \"textOnPanel\": \"").append(hex(t.textOn(t.panel()))).append('"');
                out.append(", \"mutedOnPanel\": \"").append(hex(t.mutedOn(t.panel()))).append('"');
                out.append(", \"textOnAccent\": \"").append(hex(t.textOn(t.accent()))).append('"');
                out.append(" }");
            }
        }
        out.append("\n}");
        System.out.println(out.toString());
    }
}
