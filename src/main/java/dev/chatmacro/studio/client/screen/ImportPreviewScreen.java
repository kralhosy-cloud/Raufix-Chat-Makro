package dev.chatmacro.studio.client.screen;

import dev.chatmacro.studio.ModInfo;
import dev.chatmacro.studio.Studio;
import dev.chatmacro.studio.io.ProfileBundle;
import dev.chatmacro.studio.model.Profile;
import dev.chatmacro.studio.repo.RepoResult;
import dev.chatmacro.studio.theme.ThemeTokens;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Shows what an import would do, before it does it.
 *
 * <p>The preview lists the profile name, description, scope and every macro that
 * would be created, and flags sensitive or secret content. Nothing is written
 * until the user presses import; imported macros are always disabled and always
 * get new UUIDs, and overwriting an existing profile needs a separate
 * confirmation inside {@code StudioScreen}.</p>
 */
public final class ImportPreviewScreen extends Screen {

    private final ProfileBundle.Preview preview;
    private final String json;
    private final ThemeTokens tokens;
    private final Studio studio;
    private final Runnable onImported;

    private int scroll;
    private String statusKey = "";
    private ButtonWidget importButton;

    public ImportPreviewScreen(ProfileBundle.Preview preview, String json, ThemeTokens tokens,
                               Studio studio, Runnable onImported) {
        super(Text.translatable(ModInfo.tr("import.previewTitle")));
        this.preview = preview;
        this.json = json;
        this.tokens = tokens;
        this.studio = studio;
        this.onImported = onImported;
    }

    @Override
    protected void init() {
        clearChildren();
        int buttonWidth = 90;
        int left = (this.width - buttonWidth * 2 - 8) / 2;
        int y = this.height - 32;
        importButton = addDrawableChild(ButtonWidget
                .builder(Text.translatable(ModInfo.tr("import.confirm")), b -> performImport())
                .dimensions(left, y, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget
                .builder(Text.translatable(ModInfo.tr("action.cancel")), b -> close())
                .dimensions(left + buttonWidth + 8, y, buttonWidth, 20).build());
    }

    private void performImport() {
        if (importButton != null) importButton.active = false;
        // Overwrite is false: a name collision is reported instead of replacing
        // anything. The caller decides what to do with that answer.
        try {
            ProfileBundle.Preview fresh = studio.bundles().preview(json);
            RepoResult<Profile> result = studio.importProfile(fresh, false);
            if (!result.success()) {
                setStatus(result.messageKeyOrEmpty());
                if (importButton != null) importButton.active = true;
                return;
            }
            onImported.run();
            close();
        } catch (dev.chatmacro.studio.config.ConfigException e) {
            setStatus("import.invalid");
            if (importButton != null) importButton.active = true;
        }
    }

    private void setStatus(String key) {
        this.statusKey = key == null ? "" : key;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fill(0, 0, this.width, this.height, tokens.background());
        int pad = 14;
        int lineH = 11;
        int y = pad;

        ctx.drawTextWithShadow(textRenderer,
                Text.translatable(ModInfo.tr("import.previewTitle")).getString(),
                pad, y, tokens.normalText());
        y += lineH + 4;

        List<String> lines = describe();
        ctx.enableScissor(pad - 4, y - 2, this.width - pad + 4, this.height - 44);
        for (int i = 0; i < lines.size(); i++) {
            int lineY = y + i * lineH - scroll;
            if (lineY < y - lineH || lineY > this.height - 44) continue;
            String line = lines.get(i);
            int color = line.startsWith("!") ? tokens.danger() : tokens.mutedText();
            String text = line.startsWith("!") ? line.substring(1) : line;
            ctx.drawTextWithShadow(textRenderer,
                    StudioPalette.ellipsize(textRenderer, text, this.width - pad * 2), pad, lineY, color);
        }
        ctx.disableScissor();

        if (!statusKey.isEmpty()) {
            ctx.drawTextWithShadow(textRenderer,
                    StudioPalette.ellipsize(textRenderer,
                            Text.translatable(ModInfo.tr(statusKey)).getString(), this.width - pad * 2),
                    pad, this.height - 46, tokens.danger());
        }

        super.render(ctx, mouseX, mouseY, delta);
    }

    private List<String> describe() {
        ProfileBundle.BundleProfile p = preview.profile();
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add(Text.translatable(ModInfo.tr("import.name"), p.name()).getString());
        if (!p.description().isEmpty()) {
            out.add(Text.translatable(ModInfo.tr("import.description"), p.description()).getString());
        }
        out.add(Text.translatable(ModInfo.tr(p.scope().describeKey())).getString());
        out.add(Text.translatable(ModInfo.tr("import.macroCount"), p.macros().size()).getString());
        for (ProfileBundle.BundleMacro macro : p.macros()) {
            out.add("  " + Text.translatable(ModInfo.tr("import.macroLine"), macro.name(),
                    Text.translatable(ModInfo.tr(macro.type() == dev.chatmacro.studio.model.MacroContentType.COMMAND
                            ? "macro.type.command" : "macro.type.chat")).getString(),
                    macro.intervalSeconds()).getString());
        }
        out.add("");
        out.add(Text.translatable(ModInfo.tr("import.alwaysDisabled")).getString());
        if (preview.containsSensitive()) {
            out.add("!" + Text.translatable(ModInfo.tr("import.containsSensitive")).getString());
        }
        if (preview.containsSecrets()) {
            out.add("!" + Text.translatable(ModInfo.tr("import.containsSecrets")).getString());
        }
        for (String problem : preview.problems()) {
            out.add("!" + Text.translatable(ModInfo.tr(problem)).getString());
        }
        return out;
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
}
