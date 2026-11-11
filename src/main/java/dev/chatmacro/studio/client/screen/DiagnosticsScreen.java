package dev.chatmacro.studio.client.screen;

import dev.chatmacro.studio.ModInfo;
import dev.chatmacro.studio.Studio;
import dev.chatmacro.studio.theme.ThemeTokens;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Shows the local diagnostics the mod is willing to share, exactly as they will
 * appear if the user copies them.
 *
 * <p>The content comes from {@link Studio#diagnostics()}, which already masks
 * the server address and never contains message bodies. This screen previews
 * that text; it does not send it anywhere, and there is no "send" button.</p>
 */
public final class DiagnosticsScreen extends Screen {

    private final ThemeTokens tokens;
    private final List<String> lines;
    private final Runnable onClose;

    private int scroll;

    public DiagnosticsScreen(Studio studio, ThemeTokens tokens, Runnable onClose) {
        super(Text.translatable(ModInfo.tr("diagnostics.title")));
        this.tokens = tokens;
        this.lines = studio.diagnostics();
        this.onClose = onClose;
    }

    @Override
    protected void init() {
        clearChildren();
        addDrawableChild(ButtonWidget
                .builder(Text.translatable(ModInfo.tr("action.close")), b -> close())
                .dimensions((this.width - 90) / 2, this.height - 32, 90, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fill(0, 0, this.width, this.height, tokens.background());

        int pad = 14;
        ctx.drawTextWithShadow(textRenderer,
                Text.translatable(ModInfo.tr("diagnostics.title")).getString(),
                pad, pad, tokens.normalText());
        int y = pad + 14;
        ctx.drawTextWithShadow(textRenderer,
                Text.translatable(ModInfo.tr("diagnostics.explainer")).getString(),
                pad, y, tokens.mutedText());
        y += 18;

        ctx.enableScissor(pad - 4, y - 2, this.width - pad + 4, this.height - 44);
        for (int i = 0; i < lines.size(); i++) {
            int lineY = y + i * 11 - scroll;
            if (lineY < y - 11 || lineY > this.height - 44) continue;
            ctx.drawTextWithShadow(textRenderer,
                    StudioPalette.ellipsize(textRenderer, lines.get(i), this.width - pad * 2),
                    pad, lineY, tokens.normalText());
        }
        ctx.disableScissor();

        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int previous = scroll;
        scroll = Math.max(0, scroll - (int) Math.signum(verticalAmount) * 22);
        return scroll != previous || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        super.close();
        onClose.run();
    }
}
