package dev.chatmacro.studio.testing;

import dev.chatmacro.studio.theme.Accent;
import dev.chatmacro.studio.theme.Colors;
import dev.chatmacro.studio.theme.ThemeManager;
import dev.chatmacro.studio.theme.ThemeMode;
import dev.chatmacro.studio.theme.ThemeTokens;
import dev.chatmacro.studio.ui.Animation;

import java.util.List;

/** Palette correctness: contrast is asserted, not eyeballed. */
public final class ThemeTests {

    private ThemeTests() {
    }

    public static void register(TestRunner r) {
        ThemeManager manager = new ThemeManager();

        r.test("theme", "every mode and accent combination stays readable", () -> {
            for (ThemeMode mode : ThemeMode.values()) {
                for (Accent accent : Accent.values()) {
                    ThemeTokens tokens = manager.tokens(mode, accent);
                    List<String> problems = ThemeManager.validate(tokens);
                    Assert.isTrue(problems.isEmpty(),
                            mode + "/" + accent + " contrast problems: " + problems);
                }
            }
        });

        r.test("theme", "dark and light surfaces really differ", () -> {
            ThemeTokens dark = manager.tokens(ThemeMode.DARK, Accent.BLUE);
            ThemeTokens light = manager.tokens(ThemeMode.LIGHT, Accent.BLUE);
            Assert.ne(dark.background(), light.background(), "background differs");
            Assert.ne(dark.normalText(), light.normalText(), "text differs");
            Assert.ne(dark.panel(), light.panel(), "panel differs");
        });

        r.test("theme", "the accent colour follows the selection", () -> {
            ThemeTokens blue = manager.tokens(ThemeMode.DARK, Accent.BLUE);
            ThemeTokens amber = manager.tokens(ThemeMode.DARK, Accent.AMBER);
            Assert.ne(blue.accent(), amber.accent(), "accents differ");
            Assert.ne(blue.accentHover(), blue.accent(), "hover differs from the base");
            Assert.ne(blue.accentPressed(), blue.accentHover(), "pressed differs from hover");
        });

        r.test("theme", "danger and success are not the same colour", () -> {
            for (ThemeMode mode : ThemeMode.values()) {
                ThemeTokens tokens = manager.tokens(mode, Accent.BLUE);
                Assert.ne(tokens.danger(), tokens.success(), "danger != success in " + mode);
                Assert.ne(tokens.danger(), tokens.warning(), "danger != warning in " + mode);
            }
        });

        r.test("theme", "textOn corrects a colour that would not be readable", () -> {
            ThemeTokens tokens = manager.tokens(ThemeMode.DARK, Accent.BLUE);
            int unreadable = tokens.background();               // same as the surface
            int corrected = tokens.textOn(unreadable);
            Assert.ne(unreadable, corrected, "the colour was adjusted");
            Assert.isTrue(Colors.contrastRatio(corrected, unreadable) >= ThemeManager.MIN_BODY_CONTRAST,
                    "corrected colour meets the body-text ratio");
        });

        r.test("theme", "token sets are cached, not rebuilt per frame", () -> {
            ThemeTokens first = manager.tokens(ThemeMode.DARK, Accent.TEAL);
            ThemeTokens second = manager.tokens(ThemeMode.DARK, Accent.TEAL);
            Assert.isTrue(first == second, "the same instance is reused");
        });

        r.test("theme", "tint composites stay opaque", () -> {
            // Translucent tokens (accentSoft, selection, dangerSoft) must never be
            // handed to a fill call directly; composite() resolves them, and the
            // result must stay readable in every mode/accent pair.
            for (ThemeMode mode : ThemeMode.values()) {
                for (Accent accent : Accent.values()) {
                    ThemeTokens t = manager.tokens(mode, accent);
                    int[] tints = { t.accentSoft(), t.selection(), t.dangerSoft() };
                    for (int tint : tints) {
                        Assert.isTrue(Colors.composite(tint, t.panel()) >>> 24 == 0xFF,
                                "composite must be opaque for " + mode + "/" + accent);
                        Assert.isTrue(Colors.composite(tint, t.background()) >>> 24 == 0xFF,
                                "composite on background must be opaque");
                    }
                    int selectedCard = Colors.composite(t.accentSoft(), t.panel());
                    Assert.isTrue(Colors.contrastRatio(t.textOn(selectedCard), selectedCard) >= 4.5,
                            "selected card text stays readable for " + mode + "/" + accent);
                    int selectedRow = Colors.composite(t.selection(), t.panel());
                    Assert.isTrue(Colors.contrastRatio(t.textOn(selectedRow), selectedRow) >= 4.5,
                            "selected row text stays readable for " + mode + "/" + accent);
                }
            }
        });

        r.test("theme", "blend lands exactly on both endpoints", () -> {
            int a = Colors.rgb(0x10, 0x20, 0x30);
            int b = Colors.rgb(0xF0, 0xE0, 0xD0);
            Assert.eq(a, Colors.blend(a, b, 0f), "t=0");
            Assert.eq(b, Colors.blend(a, b, 1f), "t=1");
            Assert.eq(b, Colors.blend(a, b, 5f), "t is clamped above 1");
            Assert.eq(a, Colors.blend(a, b, -1f), "t is clamped below 0");
        });

        r.test("theme", "contrast maths matches the known WCAG example", () -> {
            double blackOnWhite = Colors.contrastRatio(0xFF000000, 0xFFFFFFFF);
            Assert.eq(21.0, blackOnWhite, 0.05, "black on white is 21:1");
            Assert.eq(1.0, Colors.contrastRatio(0xFF123456, 0xFF123456), 0.001, "same colour is 1:1");
        });

        r.test("animation", "progress is time based, not frame based", () -> {
            Animation a = new Animation(200, Animation.Easing.SMOOTH, false);
            a.snapTo(0f);
            a.setTo(1f, 1_000L);
            Assert.eq(0.0, a.valueAt(1_000L), 0.001, "starts at the old value");
            Assert.eq(0.5, a.valueAt(1_100L), 0.02, "halfway");
            Assert.eq(1.0, a.valueAt(1_200L), 0.001, "completes exactly on time");
            Assert.isTrue(a.isSettled(), "settled after the duration");
        });

        r.test("animation", "reduced motion snaps to the target", () -> {
            Animation a = new Animation(200, Animation.Easing.SMOOTH, true);
            a.setTo(1f, 1_000L);
            Assert.eq(1.0, a.valueAt(1_000L), 0.001, "no transition at all");
            Assert.isFalse(a.isRunning(), "nothing left running");
        });

        r.test("animation", "a new target retargets from the current value", () -> {
            Animation a = new Animation(100, Animation.Easing.LINEAR, false);
            a.snapTo(0f);
            a.setTo(1f, 0L);
            a.setTo(0f, 50L);                 // halfway through, user moved away
            Assert.eq(0.5, a.valueAt(50L), 0.01, "starts from where it was");
            Assert.eq(0.0, a.valueAt(150L), 0.01, "and reaches the new target");
        });

        r.test("animation", "a zero duration never animates", () -> {
            Animation a = new Animation(0, Animation.Easing.SMOOTH, false);
            a.setTo(1f, 0L);
            Assert.eq(1.0, a.valueAt(0L), 0.001, "instant");
        });
    }
}
