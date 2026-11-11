package dev.chatmacro.studio.client.screen;

import dev.chatmacro.studio.ModInfo;
import dev.chatmacro.studio.model.Macro;
import dev.chatmacro.studio.sched.MacroRuntime;
import dev.chatmacro.studio.theme.Colors;
import dev.chatmacro.studio.theme.ThemeTokens;
import dev.chatmacro.studio.ui.Animation;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

/**
 * One macro card.
 *
 * <p>The card body selects the macro; the switch on the right toggles
 * {@code enabled}. They are separate hit boxes on purpose, so clicking a card to
 * edit it can never silently switch the macro on.</p>
 *
 * <p>State is shown three ways - a text label, the switch knob position and the
 * colour - so it stays readable without colour vision and at large GUI scales.</p>
 */
public final class MacroCardWidget extends ClickableWidget {

    /** Card height at GUI scale 1. */
    public static final int HEIGHT = 46;
    private static final int TOGGLE_WIDTH = 26;
    private static final int TOGGLE_HEIGHT = 12;

    /** Where a click on the card should lead. */
    public interface Actions {
        void onSelect(Macro macro);
        void onToggle(Macro macro, boolean enabled);
        void onEdit(Macro macro);
        void onDelete(Macro macro);
        void onDuplicate(Macro macro);
        void onTryOnce(Macro macro);
    }

    private final TextRenderer text;
    private ThemeTokens tokens;
    private final Actions actions;
    private final Animation hover = new Animation(140, Animation.Easing.SMOOTH, false);
    private final Animation state = new Animation(180, Animation.Easing.SMOOTH, false);

    private Macro macro;
    private MacroRuntime runtime;
    private boolean selected;
    private boolean reducedMotion;
    private long nowMillis;
    private boolean hoveredToggle;

    public MacroCardWidget(int x, int y, int width, TextRenderer text, ThemeTokens tokens, Actions actions) {
        super(x, y, width, HEIGHT, Text.translatable(ModInfo.tr("macro.card")));
        this.text = text;
        this.tokens = tokens;
        this.actions = actions;
        this.hover.snapTo(0f);
        this.state.snapTo(0f);
    }

    public void setReducedMotion(boolean value) {
        this.reducedMotion = value;
    }

    /** Binds the card to a record. Called when the list is (re)built, not per frame. */
    public void bind(Macro macro, MacroRuntime runtime, boolean selected, long nowMillis) {
        this.macro = macro;
        this.runtime = runtime;
        this.selected = selected;
        this.nowMillis = nowMillis;
        this.state.setTo(macro.enabled() ? 1f : 0f, nowMillis);
    }

    public Macro macro() {
        return macro;
    }

    @Override
    protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (macro == null) return;
        int x = getX();
        int y = getY();
        int w = getWidth();

        boolean hoveredCard = isHovered() || isMouseOver(mouseX, mouseY);
        hover.setTo(hoveredCard ? 1f : 0f, nowMillis);
        float hoverProgress = reducedMotion ? (hoveredCard ? 1f : 0f) : hover.valueAt(nowMillis);
        float stateProgress = reducedMotion ? (macro.enabled() ? 1f : 0f) : state.valueAt(nowMillis);

        int outside = tokens.background();
        int surface = Colors.blend(tokens.panel(), tokens.raisedPanel(), hoverProgress * 0.6f);
        // accentSoft carries alpha; flatten it so the card stays opaque.
        if (selected) surface = Colors.composite(tokens.accentSoft(), surface);
        int nameColor = tokens.textOn(surface);
        StudioPalette.panel(ctx, x, y, w, HEIGHT, surface,
                selected ? tokens.accent() : tokens.border(), outside);
        if (selected) {
            ctx.fill(x, y, x + 2, y + HEIGHT, tokens.accent());
        }

        int textX = x + 10;
        int maxNameWidth = w - TOGGLE_WIDTH - 96;
        ctx.drawTextWithShadow(text, StudioPalette.ellipsize(text, macro.name(), Math.max(20, maxNameWidth)),
                textX, y + 7, nameColor);

        String typeKey = macro.isCommand() ? "macro.type.command" : "macro.type.chat";
        String typeLabel = Text.translatable(ModInfo.tr(typeKey)).getString();
        int pillWidth = StudioPalette.pillWidth(text, typeLabel);
        // accentSoft carries alpha: flatten it onto the card before painting the pill,
        // otherwise the capsule would replace the card pixels instead of tinting them.
        int pillFill = macro.isCommand() ? tokens.warning() : Colors.composite(tokens.accentSoft(), surface);
        StudioPalette.pill(ctx, text, textX, y + 20, 12, typeLabel, pillFill, tokens.normalText());

        String preview = macro.preview(60);
        int previewX = textX + pillWidth + 6;
        ctx.drawTextWithShadow(text,
                StudioPalette.ellipsize(text, preview, Math.max(20, w - (previewX - x) - TOGGLE_WIDTH - 24)),
                previewX, y + 22, tokens.mutedText());

        String meta = Text.translatable(ModInfo.tr("macro.card.interval"), macro.intervalSeconds()).getString();
        if (runtime != null && runtime.nextEligibleNanos() != Long.MAX_VALUE) {
            long millis = runtime.millisUntilNext(System.nanoTime());
            if (millis >= 0) {
                meta = meta + "  \u00b7  " + Text.translatable(ModInfo.tr("macro.card.nextIn"),
                        Math.max(0, millis / 1000)).getString();
            }
        }
        ctx.drawTextWithShadow(text, StudioPalette.ellipsize(text, meta, Math.max(20, w - 120)),
                textX, y + 33, tokens.mutedText());

        // State label + switch, both on the right.
        String stateLabel = stateLabel();
        int stateColor = macro.enabled() ? tokens.success() : tokens.mutedText();
        ctx.drawTextWithShadow(text, stateLabel, x + w - TOGGLE_WIDTH - 14 - text.getWidth(stateLabel),
                y + 8, stateColor);

        int toggleX = x + w - TOGGLE_WIDTH - 8;
        int toggleY = y + (HEIGHT - TOGGLE_HEIGHT) / 2 + 6;
        hoveredToggle = mouseX >= toggleX && mouseX <= toggleX + TOGGLE_WIDTH
                && mouseY >= toggleY && mouseY <= toggleY + TOGGLE_HEIGHT;
        int track = Colors.blend(tokens.border(), tokens.accent(), stateProgress);
        StudioPalette.toggle(ctx, toggleX, toggleY, TOGGLE_WIDTH, TOGGLE_HEIGHT,
                macro.enabled(), tokens.accent(), tokens.border(),
                Colors.blend(tokens.mutedText(), 0xFFFFFFFF, stateProgress),
                isFocused(), tokens.focusRing());
    }

    private String stateLabel() {
        if (runtime == null) {
            return Text.translatable(ModInfo.tr(macro.enabled() ? "state.enabled" : "state.disabled")).getString();
        }
        return switch (runtime.state()) {
            case INACTIVE -> Text.translatable(ModInfo.tr("state.disabled")).getString();
            case WAITING -> Text.translatable(ModInfo.tr("state.running")).getString();
            case HELD -> Text.translatable(ModInfo.tr("state.held")).getString();
            case BACKOFF -> Text.translatable(ModInfo.tr("state.backoff")).getString();
            case STOPPED -> Text.translatable(ModInfo.tr("state.stopped")).getString();
        };
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (macro == null || !isVisible() || !isMouseOver(mouseX, mouseY)) return false;
        if (hoveredToggle) {
            actions.onToggle(macro, !macro.enabled());
            return true;
        }
        actions.onSelect(macro);
        if (button == 1) {
            actions.onEdit(macro);
        }
        return true;
    }

    /** Enter on a focused card edits it - the same action as a middle click. */
    @Override
    public void onPress() {
        if (macro != null) actions.onEdit(macro);
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        if (macro == null) return;
        builder.addDefault();
    }
/** Swaps the palette live, so a theme change does not rebuild the screen. */
    public void setTokens(ThemeTokens next) {
        this.tokens = next;
    }
}
