package dev.chatmacro.studio.client.screen;

import dev.chatmacro.studio.ModInfo;
import dev.chatmacro.studio.theme.ThemeTokens;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

/**
 * A small confirmation dialog used for destructive actions (delete profile,
 * delete macro, discard unsaved edits) and for the first run of a command macro
 * that could move money or items.
 *
 * <p>Escape cancels. Enter confirms. The destructive button is red and the safe
 * button keeps the default styling, and both are reachable with Tab.</p>
 */
public final class ConfirmDialogScreen extends Screen {

    private final String titleKey;
    private final String bodyKey;
    private final Object[] bodyArgs;
    private final String confirmKey;
    private final boolean dangerous;
    private final Runnable onConfirm;
    private final Runnable onClose;
    private final ThemeTokens tokens;

    /**
     * @param onConfirm runs when the user confirms
     * @param onClose   runs on every exit path (confirm, cancel and Escape) so
     *                  the caller can put its own screen back
     */
    public ConfirmDialogScreen(String titleKey, String bodyKey, Object[] bodyArgs, String confirmKey,
                               boolean dangerous, ThemeTokens tokens, Runnable onConfirm, Runnable onClose) {
        super(Text.translatable(ModInfo.tr(titleKey)));
        this.titleKey = titleKey;
        this.bodyKey = bodyKey;
        this.bodyArgs = bodyArgs == null ? new Object[0] : bodyArgs;
        this.confirmKey = confirmKey;
        this.dangerous = dangerous;
        this.tokens = tokens;
        this.onConfirm = onConfirm;
        this.onClose = onClose == null ? () -> { } : onClose;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(260, this.width - 40);
        int panelX = (this.width - panelWidth) / 2;
        int buttonWidth = (panelWidth - 30) / 2;
        int buttonY = this.height / 2 + 24;

        addDrawableChild(ButtonWidget.builder(Text.translatable(ModInfo.tr(confirmKey)), b -> {
                    onConfirm.run();
                    close();
                })
                .dimensions(panelX + 10, buttonY, buttonWidth, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.translatable(ModInfo.tr("action.cancel")), b -> close())
                .dimensions(panelX + 20 + buttonWidth, buttonY, buttonWidth, 20)
                .build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int panelWidth = Math.min(260, this.width - 40);
        int panelHeight = 86;
        int panelX = (this.width - panelWidth) / 2;
        int panelY = this.height / 2 - panelHeight / 2;

        StudioPalette.shadow(ctx, panelX, panelY, panelWidth, panelHeight, tokens.shadow());
        StudioPalette.panel(ctx, panelX, panelY, panelWidth, panelHeight,
                tokens.raisedPanel(), tokens.borderStrong(), 0);

        String title = Text.translatable(ModInfo.tr(titleKey)).getString();
        ctx.drawTextWithShadow(textRenderer, title, panelX + 12, panelY + 10, tokens.normalText());

        String body = Text.translatable(ModInfo.tr(bodyKey), bodyArgs).getString();
        int lineY = panelY + 26;
        for (String line : wrap(body, panelWidth - 24)) {
            ctx.drawTextWithShadow(textRenderer, line, panelX + 12, lineY, tokens.mutedText());
            lineY += 11;
        }
    }

    @Override
    public boolean shouldPause() {
        // A dialog must not pause the game in multiplayer, and pausing in single
        // player would freeze the very scheduler the dialog is asking about.
        return false;
    }

    @Override
    public void close() {
        super.close();
        onClose.run();
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == 256) {                 // Escape
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    /** Tiny greedy wrapper; the dialog only ever shows a sentence or two. */
    private java.util.List<String> wrap(String value, int maxWidth) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : value.split(" ")) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (textRenderer.getWidth(candidate) > maxWidth && current.length() > 0) {
                lines.add(current.toString());
                current.setLength(0);
                current.append(word);
            } else {
                current.setLength(0);
                current.append(candidate);
            }
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    /** Exposed so the studio can style the dialog to match its own palette. */
    public boolean isDangerous() {
        return dangerous;
    }
}
