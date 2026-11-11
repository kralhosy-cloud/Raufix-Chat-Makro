package dev.chatmacro.studio.client.screen;

import dev.chatmacro.studio.ModInfo;
import dev.chatmacro.studio.client.ProfileTransfer;
import dev.chatmacro.studio.theme.ThemeTokens;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Lists the ".cmsprofile" files in {@code config/chatmacro_studio/exports/} so
 * the user can pick one to import.
 *
 * <p>There is no free-form path entry on purpose: the only files this mod will
 * read are the ones in its own exports directory, and the resolved path is
 * checked against that directory before it is opened.</p>
 */
public final class BundlePickerScreen extends Screen {

    private static final int ROW_HEIGHT = 14;

    private final ProfileTransfer transfer;
    private final ThemeTokens tokens;
    private final java.util.function.Consumer<String> onChosen;
    private final Runnable onCancel;

    private final List<String> names;
    private int selected;
    private int scroll;

    public BundlePickerScreen(ProfileTransfer transfer, ThemeTokens tokens,
                              java.util.function.Consumer<String> onChosen, Runnable onCancel) {
        super(Text.translatable(ModInfo.tr("import.pickTitle")));
        this.transfer = transfer;
        this.tokens = tokens;
        this.onChosen = onChosen;
        this.onCancel = onCancel;
        this.names = transfer.availableBundles();
    }

    @Override
    protected void init() {
        clearChildren();
        int buttonWidth = 90;
        int left = (this.width - buttonWidth * 2 - 8) / 2;
        int y = this.height - 32;
        addDrawableChild(ButtonWidget
                .builder(Text.translatable(ModInfo.tr("import.preview")), b -> choose())
                .dimensions(left, y, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget
                .builder(Text.translatable(ModInfo.tr("action.cancel")), b -> close())
                .dimensions(left + buttonWidth + 8, y, buttonWidth, 20).build());
    }

    private void choose() {
        if (selected < 0 || selected >= names.size()) return;
        onChosen.accept(names.get(selected));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fill(0, 0, this.width, this.height, tokens.background());

        int pad = 14;
        int y = pad;
        ctx.drawTextWithShadow(textRenderer,
                Text.translatable(ModInfo.tr("import.pickTitle")).getString(), pad, y, tokens.normalText());
        y += 16;

        if (names.isEmpty()) {
            ctx.drawTextWithShadow(textRenderer,
                    Text.translatable(ModInfo.tr("import.noneFound")).getString(), pad, y, tokens.mutedText());
            super.render(ctx, mouseX, mouseY, delta);
            return;
        }

        int listTop = y;
        int listHeight = Math.max(ROW_HEIGHT, this.height - listTop - 44);
        StudioPalette.panel(ctx, pad - 4, listTop - 4, this.width - pad * 2 + 8, listHeight + 8,
                tokens.backgroundAlt(), tokens.border(), tokens.background());
        ctx.enableScissor(pad - 3, listTop - 3, this.width - pad + 3, listTop + listHeight + 3);
        for (int i = 0; i < names.size(); i++) {
            int rowY = listTop + i * ROW_HEIGHT - scroll;
            if (rowY < listTop - ROW_HEIGHT || rowY > listTop + listHeight) continue;
            if (i == selected) {
                ctx.fill(pad, rowY - 2, this.width - pad, rowY + ROW_HEIGHT - 3, tokens.raisedPanel());
                ctx.fill(pad, rowY - 2, pad + 2, rowY + ROW_HEIGHT - 3, tokens.accent());
            }
            ctx.drawTextWithShadow(textRenderer,
                    StudioPalette.ellipsize(textRenderer, names.get(i), this.width - pad * 2 - 8),
                    pad + 8, rowY, i == selected ? tokens.normalText() : tokens.mutedText());
        }
        ctx.disableScissor();

        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mouseX = click.x();
        double mouseY = click.y();
        int pad = 14;
        int listTop = pad + 16;
        int index = (int) ((mouseY - listTop + scroll) / ROW_HEIGHT);
        if (mouseX >= pad && mouseX <= this.width - pad && index >= 0 && index < names.size()) {
            selected = index;
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int previous = scroll;
        scroll = Math.max(0, scroll - (int) Math.signum(verticalAmount) * ROW_HEIGHT * 2);
        return scroll != previous || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int keyCode = input.key();
        if (keyCode == 264 && selected < names.size() - 1) { selected++; return true; }
        if (keyCode == 265 && selected > 0) { selected--; return true; }
        return super.keyPressed(input);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        super.close();
        onCancel.run();
    }
}
