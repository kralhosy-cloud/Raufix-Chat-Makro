package dev.chatmacro.studio.client.screen;

import dev.chatmacro.studio.ModInfo;
import dev.chatmacro.studio.model.Macro;
import dev.chatmacro.studio.model.MacroContentType;
import dev.chatmacro.studio.model.Profile;
import dev.chatmacro.studio.theme.ThemeTokens;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The macro editor form.
 *
 * <p>The profile the macro belongs to is always visible in the header, so a macro
 * can never be edited "somewhere ambiguous". Saving validates first and shows the
 * reason inline; cancelling never touches the stored record.</p>
 */
public final class MacroEditorPanel {

    /** The screen performs the actual repository write. */
    public interface Listener {
        void onSave(MacroEditorPanel panel);
        void onCancel(MacroEditorPanel panel);
        void onDuplicate(MacroEditorPanel panel);
        void onTryOnce(MacroEditorPanel panel);
        void onDelete(MacroEditorPanel panel);
    }

    private final Screen screen;
    private final TextRenderer text;
    private ThemeTokens tokens;
    private final Listener listener;

    private final List<ClickableWidget> widgets = new ArrayList<>();
    private TextFieldWidget nameField;
    private TextFieldWidget contentField;
    private TextFieldWidget intervalField;
    private TextFieldWidget delayField;
    private ButtonWidget typeChatButton;
    private ButtonWidget typeCommandButton;
    private ButtonWidget enabledButton;
    private ButtonWidget saveButton;
    private ButtonWidget cancelButton;
    private ButtonWidget duplicateButton;
    private ButtonWidget tryOnceButton;
    private ButtonWidget deleteButton;

    private int x;
    private int y;
    private int width;
    private int height;

    private UUID editingId;
    private String profileName = "";
    private MacroContentType type = MacroContentType.CHAT_MESSAGE;
    private boolean enabled;
    private String errorKey = "";

    public MacroEditorPanel(Screen screen, TextRenderer text, ThemeTokens tokens, Listener listener) {
        this.screen = screen;
        this.text = text;
        this.tokens = tokens;
        this.listener = listener;
    }

    public List<ClickableWidget> widgets() {
        return List.copyOf(widgets);
    }

    public UUID editingId() {
        return editingId;
    }

    public MacroContentType type() {
        return type;
    }

    public boolean enabledChoice() {
        return enabled;
    }

    public String nameValue() {
        return nameField == null ? "" : nameField.getText();
    }

    public String contentValue() {
        return contentField == null ? "" : contentField.getText();
    }

    public long intervalValue() {
        return parseLong(intervalField == null ? "" : intervalField.getText(), -1);
    }

    public long initialDelayValue() {
        return parseLong(delayField == null ? "" : delayField.getText(), -1);
    }

    /** True when the user typed something that differs from what was loaded. */
    public boolean hasUnsavedChanges() {
        return dirty;
    }

    private boolean dirty;

    public void setError(String messageKey) {
        this.errorKey = messageKey == null ? "" : messageKey;
    }

    /** Builds the widgets for the current bounds. Called from {@code Screen.init}. */
    /**
     * Natural height of the editor form, measured from {@link #build}. The
     * screen clamps the panel to this so the action row is never cut off.
     */
    public static int contentHeight() {
        return CONTENT_HEIGHT;
    }

    /** Preferred height of the form; the screen may give it less and scrolls. */
    public static final int CONTENT_HEIGHT = 176;

    public void build(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.scroll = 0;
        widgets.clear();

        applyPositions();

        refreshActionButtons();
        refreshTypeButtons();
        refreshEnabledLabel();
    }

    /** Bottom edge of the last widget inside the panel, in screen pixels. */
    public int builtBottom() {
        return builtBottom;
    }

    private int builtBottom;

    /** Moves and resizes the existing widgets - used when the window narrows. */
    public void reposition(int newX, int newY, int newWidth, int newHeight) {
        this.x = newX;
        this.y = newY;
        this.width = newWidth;
        this.height = newHeight;
        this.scroll = Math.min(this.scroll, Math.max(0, builtBottom + 16 - (newY + newHeight)));
        applyPositions();
    }

    /** Places every widget for the current bounds and scroll offset. */
    private void applyPositions() {
        int pad = 10;
        int fieldX = this.x + pad;
        int fieldWidth = Math.max(60, this.width - pad * 2);
        int baseY = this.y - scroll;
        int cursor = baseY + 34;

        nameField = textField(fieldX, cursor, fieldWidth, "macro.editor.name");
        cursor += 28;

        int half = Math.max(40, (fieldWidth - 6) / 2);
        typeChatButton = ButtonWidget.builder(Text.translatable(ModInfo.tr("macro.type.chat")),
                        b -> setType(MacroContentType.CHAT_MESSAGE))
                .dimensions(fieldX, cursor, half, 18).build();
        typeCommandButton = ButtonWidget.builder(Text.translatable(ModInfo.tr("macro.type.command")),
                        b -> setType(MacroContentType.COMMAND))
                .dimensions(fieldX + half + 6, cursor, half, 18).build();
        widgets.add(typeChatButton);
        widgets.add(typeCommandButton);
        cursor += 24;

        contentField = textField(fieldX, cursor, fieldWidth, "macro.editor.content");
        contentField.setMaxLength(1024);
        cursor += 34;

        intervalField = textField(fieldX, cursor, half, "macro.editor.interval");
        intervalField.setMaxLength(6);
        delayField = textField(fieldX + half + 6, cursor, half, "macro.editor.initialDelay");
        delayField.setMaxLength(6);
        cursor += 34;

        enabledButton = ButtonWidget.builder(Text.translatable(ModInfo.tr("macro.editor.enabled")),
                        b -> { enabled = !enabled; refreshEnabledLabel(); markDirty(); })
                .dimensions(fieldX, cursor, fieldWidth, 18).build();
        widgets.add(enabledButton);
        cursor += 26;

        int buttonWidth = Math.max(48, (fieldWidth - 6) / 2);
        saveButton = ButtonWidget.builder(Text.translatable(ModInfo.tr("action.save")),
                        b -> listener.onSave(this))
                .dimensions(fieldX, cursor, buttonWidth, 20).build();
        cancelButton = ButtonWidget.builder(Text.translatable(ModInfo.tr("action.cancel")),
                        b -> listener.onCancel(this))
                .dimensions(fieldX + buttonWidth + 6, cursor, buttonWidth, 20).build();
        widgets.add(saveButton);
        widgets.add(cancelButton);
        cursor += 24;

        // Distinct, labelled actions - duplicate, try once and delete are never
        // the same click as selecting the card.
        int third = Math.max(36, (fieldWidth - 12) / 3);
        duplicateButton = ButtonWidget.builder(Text.translatable(ModInfo.tr("action.duplicate")),
                        b -> listener.onDuplicate(this))
                .dimensions(fieldX, cursor, third, 18).build();
        tryOnceButton = ButtonWidget.builder(Text.translatable(ModInfo.tr("action.tryOnce")),
                        b -> listener.onTryOnce(this))
                .dimensions(fieldX + third + 6, cursor, third, 18).build();
        deleteButton = ButtonWidget.builder(Text.translatable(ModInfo.tr("action.delete")),
                        b -> listener.onDelete(this))
                .dimensions(fieldX + (third + 6) * 2, cursor, third, 18).build();
        widgets.add(duplicateButton);
        widgets.add(tryOnceButton);
        widgets.add(deleteButton);

        // Record the real bottom edge so the caller can tell whether the form fits.
        builtBottom = cursor + 20 + 4;
    }

    private void refreshActionButtons() {
        if (duplicateButton == null) return;
        boolean existing = editingId != null;
        duplicateButton.visible = existing;
        tryOnceButton.visible = existing;
        deleteButton.visible = existing;
    }

    private TextFieldWidget textField(int fieldX, int fieldY, int fieldWidth, String key) {
        TextFieldWidget field = new TextFieldWidget(text, fieldX, fieldY, fieldWidth, 20,
                Text.translatable(ModInfo.tr(key)));
        field.setChangedListener(value -> markDirty());
        widgets.add(field);
        return field;
    }

    private void markDirty() {
        dirty = true;
    }

    private void setType(MacroContentType newType) {
        this.type = newType;
        refreshTypeButtons();
        markDirty();
    }

    private void refreshTypeButtons() {
        if (typeChatButton == null) return;
        typeChatButton.active = type != MacroContentType.CHAT_MESSAGE;
        typeCommandButton.active = type != MacroContentType.COMMAND;
    }

    private void refreshEnabledLabel() {
        if (enabledButton == null) return;
        enabledButton.setMessage(Text.translatable(ModInfo.tr(enabled
                ? "macro.editor.enabledOn" : "macro.editor.enabledOff")));
    }

    /** Loads an existing macro, or a blank draft when {@code macro} is null. */
    public void load(Profile profile, Macro macro) {
        this.profileName = profile == null ? "" : profile.name();
        this.editingId = macro == null ? null : macro.id();
        this.type = macro == null ? MacroContentType.CHAT_MESSAGE : macro.contentType();
        this.enabled = macro != null && macro.enabled();
        this.errorKey = "";
        if (nameField == null) return;
        nameField.setText(macro == null ? "" : macro.name());
        contentField.setText(macro == null ? "" : macro.content());
        intervalField.setText(macro == null ? "30" : Long.toString(macro.intervalSeconds()));
        delayField.setText(macro == null ? "0" : Long.toString(macro.initialDelaySeconds()));
        refreshActionButtons();
        refreshTypeButtons();
        refreshEnabledLabel();
        dirty = false;
    }

    public void render(DrawContext ctx, int mouseX, int mouseY) {
        StudioPalette.panel(ctx, x, y, width, height, tokens.panel(), tokens.border(), tokens.background());

        String header = Text.translatable(ModInfo.tr(editingId == null
                ? "macro.editor.titleNew" : "macro.editor.titleEdit"), profileName).getString();
        ctx.drawTextWithShadow(text, StudioPalette.ellipsize(text, header, width - 20),
                x + 10, y + 10, tokens.normalText());
        ctx.fill(x + 10, y + 24, x + width - 10, y + 25, tokens.divider());

        // The action row sits above the hint line, so the hint is only drawn when
        // there is no widget underneath it. Otherwise the hint would be painted
        // across Save/Cancel on short windows.
        boolean roomForHint = builtBottom + 12 <= y + height;
        if (!roomForHint) return;
        int hintY = Math.min(y + height - 14, builtBottom + 2);
        if (!errorKey.isEmpty()) {
            String message = Text.translatable(ModInfo.tr(errorKey)).getString();
            ctx.drawTextWithShadow(text, StudioPalette.ellipsize(text, message, width - 20),
                    x + 10, hintY, tokens.danger());
        } else {
            String hint = Text.translatable(ModInfo.tr(editingId == null
                    ? "macro.editor.hintNew" : "macro.editor.hintEdit")).getString();
            ctx.drawTextWithShadow(text, StudioPalette.ellipsize(text, hint, width - 20),
                    x + 10, hintY, tokens.mutedText());
        }
    }

    /** How many pixels the form overflows the panel (0 when it fits). */
    public int overflow() {
        return Math.max(0, builtBottom + 16 - (y + height));
    }

    /** Scrolls the form inside the panel, clamping so no row can scroll away. */
    public void scrollBy(int pixels) {
        if (pixels == 0) return;
        int max = Math.max(0, builtBottom + 16 - (y + height));
        this.scroll = Math.max(0, Math.min(max, this.scroll + pixels));
        applyPositions();
    }

    private int scroll;

    public boolean isInside(double mouseX, double mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private static long parseLong(String raw, long fallback) {
        try {
            return Long.parseLong(raw.strip());
        } catch (RuntimeException e) {
            return fallback;
        }
    }
/** Swaps the palette live, so a theme change does not rebuild the screen. */
    public void setTokens(ThemeTokens next) {
        this.tokens = next;
    }
}
