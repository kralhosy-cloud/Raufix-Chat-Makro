package dev.chatmacro.studio.client.screen;

import dev.chatmacro.studio.ModInfo;
import dev.chatmacro.studio.Studio;
import dev.chatmacro.studio.client.ChatMacroStudioClient;
import dev.chatmacro.studio.client.ProfileTransfer;
import dev.chatmacro.studio.client.StudioKeyBindings;
import dev.chatmacro.studio.dispatch.DispatchResult;
import dev.chatmacro.studio.io.SensitiveCommands;
import dev.chatmacro.studio.model.Macro;
import dev.chatmacro.studio.model.MacroContentType;
import dev.chatmacro.studio.model.Profile;
import dev.chatmacro.studio.repo.RepoResult;
import dev.chatmacro.studio.repo.SortMode;
import dev.chatmacro.studio.sched.MacroRuntime;
import dev.chatmacro.studio.theme.Accent;
import dev.chatmacro.studio.theme.ThemeMode;
import dev.chatmacro.studio.theme.ThemeTokens;
import dev.chatmacro.studio.ui.Animation;
import dev.chatmacro.studio.ui.VirtualList;
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
 * The Chat Macro Studio screen.
 *
 * <p>Layout: header with the profile identity and run controls, a profile list on
 * the left, macro cards in the middle, the macro editor on the right. On narrow
 * windows the profile list and the editor become overlays instead of columns, so
 * nothing is squashed into unreadability.</p>
 *
 * <p>This class only wires layout and input to the domain layer. It performs no
 * file I/O, no JSON parsing and no network work during {@code render}, and every
 * mutation goes through {@link Studio}, which validates and persists.</p>
 */
public final class StudioScreen extends Screen {

    private static final int HEADER_HEIGHT = 44;
    private static final int FOOTER_HEIGHT = 20;
    private static final int PROFILE_COLUMN = 150;
    private static final int EDITOR_COLUMN = 210;
    private static final int NARROW_WIDTH = 420;
    private static final int TOOLBAR_Y = 8;
    private boolean compactHeader;
    private final List<ButtonWidget> toolbarButtons = new ArrayList<>();
    private final java.util.Map<ButtonWidget, String> toolbarKeys = new java.util.HashMap<>();
    private boolean compactMenuOpen;
    private static final int SETTINGS_ROWS_HEIGHT = 226;
    private int settingsScroll;
    private int hoveredMouseX;
    private int hoveredMouseY;
    /** Modal backdrop; the one place a translucent fill is intended. */
    private static final int SCRIM = 0xA0000000;
    private ButtonWidget compactMenuButton;
    private static final int CARD_GAP = 4;

    private final ChatMacroStudioClient host;
    private final Studio studio;
    private final ProfileTransfer transfer = new ProfileTransfer();
    private final Animation panelOpen = new Animation(180, Animation.Easing.OUT_CUBIC, false);

    private ThemeTokens tokens;
    private ProfileListPanel profileList;
    private MacroEditorPanel editor;

    private final List<MacroCardWidget> cards = new ArrayList<>();
    private List<Macro> visibleMacros = List.of();
    private List<Profile> visibleProfiles = List.of();

    private TextFieldWidget searchField;
    private TextFieldWidget profileNameField;
    private ButtonWidget startStopButton;
    private ButtonWidget pauseResumeButton;
    private ButtonWidget newProfileButton;
    private ButtonWidget deleteProfileButton;
    private ButtonWidget duplicateProfileButton;
    private ButtonWidget newMacroButton;
    private ButtonWidget sortButton;
    private ButtonWidget profilesToggleButton;
    private ButtonWidget themeButton;
    private ButtonWidget accentButton;
    private ButtonWidget motionButton;
    private ButtonWidget exportButton;
    private ButtonWidget exportSecretsButton;
    private ButtonWidget importButton;
    private ButtonWidget diagnosticsButton;
    private ButtonWidget f30NoteButton;
    private ButtonWidget statusButton;

    private UUID selectedMacroId;
    private int cardScroll;
    private SortMode sortMode = SortMode.NAME_ASC;
    private boolean profileListOpen = true;
    private boolean settingsOpen;
    private String statusMessageKey = "";
    private long nowMillis;
    private float renderDelta;

    public StudioScreen() {
        super(Text.translatable(ModInfo.tr("screen.title")));
        this.host = ChatMacroStudioClient.get();
        this.studio = host == null ? null : host.studio();
    }

    // ----------------------------------------------------------------- setup

    @Override
    protected void init() {
        if (studio == null) {
            close();
            return;
        }
        clearChildren();

        tokens = studio.themes().tokens(studio.settings().themeMode(), studio.settings().accent());
        panelOpen.setTo(1f, System.currentTimeMillis());

        profileList = new ProfileListPanel(textRenderer, tokens, this::chooseProfile);
        editor = new MacroEditorPanel(this, textRenderer, tokens, new MacroEditorPanel.Listener() {
            @Override
            public void onSave(MacroEditorPanel panel) {
                saveMacro(panel);
            }

            @Override
            public void onCancel(MacroEditorPanel panel) {
                editor.load(selectedProfile(), selectedMacro());
                panel.setError("");
            }

            @Override
            public void onDuplicate(MacroEditorPanel panel) {
                Macro macro = selectedMacro();
                if (macro != null) afterMutation(studio.duplicateMacro(selectedProfile(), macro.id()));
            }

            @Override
            public void onTryOnce(MacroEditorPanel panel) {
                Macro macro = selectedMacro();
                if (macro != null) tryOnce(macro);
            }

            @Override
            public void onDelete(MacroEditorPanel panel) {
                Macro macro = selectedMacro();
                if (macro != null) confirmDeleteMacro(macro);
            }
        });

        int headerButtonWidth = 62;
        int cursor = 10;
        toolbarButtons.clear();
        toolbarKeys.clear();

        // The name field shrinks on small windows instead of pushing buttons off-screen.
        int nameWidth = Math.min(120, Math.max(60, this.width / 3));
        profileNameField = new TextFieldWidget(textRenderer, cursor, 10, nameWidth, 18,
                Text.translatable(ModInfo.tr("profile.name")));
        profileNameField.setMaxLength(64);
        profileNameField.setChangedListener(value -> { /* committed on focus loss / Enter */ });
        addDrawableChild(profileNameField);
        cursor += nameWidth + 6;

        // Wide layout: three labelled primary buttons, then 32px icon buttons for
        // copy / delete / profiles / sort / settings. Narrow layout: primary
        // buttons shrink to icons and everything secondary moves into one menu.
        int secondary = 32;
        int primaryWidth = headerButtonWidth;
        int needed = primaryWidth * 3 + 12 + secondary * 5 + 12;
        compactHeader = cursor + needed > this.width - 12;
        if (compactHeader) primaryWidth = Math.max(28, Math.min(headerButtonWidth, (this.width - cursor - 64) / 3));

        startStopButton = addButton(cursor, 10, primaryWidth, 20, "run.start", b -> toggleRun());
        cursor += primaryWidth + 4;
        pauseResumeButton = addButton(cursor, 10, primaryWidth, 20, "run.pause", b -> togglePause());
        cursor += primaryWidth + 4;
        newMacroButton = addButton(cursor, 10, primaryWidth, 20, "macro.new", b -> newMacro());
        cursor += primaryWidth + 4;

        if (!compactHeader) {
            duplicateProfileButton = addButton(cursor, 10, secondary, 20, "profile.copy", b -> duplicateProfile());
            cursor += secondary + 4;
            deleteProfileButton = addButton(cursor, 10, secondary, 20, "action.delete", b -> confirmDeleteProfile());
            cursor += secondary + 4;
            profilesToggleButton = addButton(cursor, 10, secondary, 20, "profile.toggleList", b -> {
                profileListOpen = !profileListOpen;
                relayout();
            });
            cursor += secondary + 4;
            sortButton = addButton(cursor, 10, secondary, 20, "profile.sort", b -> cycleSort());
            cursor += secondary + 4;
            addButton(cursor, 10, secondary, 20, "action.settings", b -> settingsOpen = !settingsOpen);
        } else {
            compactMenuButton = addButton(this.width - 40, 10, 30, 20, "action.menu",
                    b -> compactMenuOpen = !compactMenuOpen);
        }

        searchField = new TextFieldWidget(textRenderer, 12, HEADER_HEIGHT + 6, PROFILE_COLUMN - 14, 18,
                Text.translatable(ModInfo.tr("profile.search")));
        searchField.setMaxLength(48);
        searchField.setChangedListener(value -> refreshProfiles());
        addDrawableChild(searchField);

        int editorX = Math.max(10, this.width - EDITOR_COLUMN - 10);
        int editorY = HEADER_HEIGHT + 6;
        int editorHeight = Math.max(120, this.height - HEADER_HEIGHT - FOOTER_HEIGHT - 16);
        editor.build(editorX, editorY, EDITOR_COLUMN, editorHeight);
        if (editor.builtBottom() > this.height - FOOTER_HEIGHT) {
            // Form taller than the window: grow the footer allowance instead of
            // drawing Save/Cancel over the footer text or outside the screen.
            editorHeight = Math.max(120, this.height - HEADER_HEIGHT - 12);
            editor.build(editorX, editorY, EDITOR_COLUMN, editorHeight);
        }
        for (ClickableWidget widget : editor.widgets()) addDrawableChild(widget);

        rebuildCards();
        refreshProfiles();
        refreshHeader();
        relayout();
    }

    private ButtonWidget addButton(int x, int y, int width, int height, String key,
                                   ButtonWidget.PressAction action) {
        String shortKey = (width < 44 || compactHeader) ? shortLabelKey(key) : null;
        String shortText = shortKey == null ? null
                : Text.translatable(ModInfo.tr(shortKey)).getString();
        String fullText = Text.translatable(ModInfo.tr(key)).getString();
        String shown = shortText != null
                ? shortText
                : textRenderer.getWidth(fullText) > width - 8
                        ? StudioPalette.ellipsize(textRenderer, fullText, width - 8)
                        : fullText;
        ButtonWidget button = ButtonWidget.builder(Text.literal(shown), action)
                .dimensions(x, y, width, height)
                .build();
        addDrawableChild(button);
        toolbarButtons.add(button);
        toolbarKeys.put(button, key);
        return button;
    }

    /**
     * Keeps a button's label inside the button. A label wider than its button is
     * ellipsized rather than drawn over the neighbouring control, which is what
     * made the compact toolbar unreadable at 300px.
     */
    private void fitLabel(ButtonWidget button, String key) {
        String shortKey = (button.getWidth() < 44 || compactHeader) ? shortLabelKey(key) : null;
        String label = Text.translatable(ModInfo.tr(shortKey != null ? shortKey : key)).getString();
        int inner = button.getWidth() - 8;
        if (textRenderer.getWidth(label) > inner) {
            label = StudioPalette.ellipsize(textRenderer, label, inner);
        }
        button.setMessage(Text.literal(label));
    }

    /**
     * Short label for a narrow toolbar button. These are real translation keys,
     * not pictographs: the vanilla font has no gear/copy glyphs and would draw
     * an empty box. Hovering the button shows the full label in the footer.
     */
    private static String shortLabelKey(String key) {
        switch (key) {
            case "profile.copy": return "action.copyShort";
            case "action.delete": return "action.deleteShort";
            case "profile.toggleList": return "action.profilesShort";
            case "profile.sort": return "action.sortShort";
            case "action.settings": return "action.settingsShort";
            case "action.menu": return "action.menu";
            case "run.start": return "run.startShort";
            case "run.stop": return "run.stopShort";
            case "run.pause": return "run.pauseShort";
            case "run.resume": return "run.resumeShort";
            case "macro.new": return "macro.newShort";
            default: return null;
        }
    }

    private void rebuildCards() {
        cards.clear();
        int columnX = profileColumnWidth() > 0 ? 10 + profileColumnWidth() + 6 : 10;
        int available = Math.max(120, this.width - columnX - (editorColumnWidth() + 16));
        int rows = Math.max(1, (this.height - HEADER_HEIGHT - FOOTER_HEIGHT - 12)
                / (MacroCardWidget.HEIGHT + CARD_GAP));
        for (int i = 0; i < rows + 2; i++) {
            MacroCardWidget card = new MacroCardWidget(columnX, 0, available, textRenderer, tokens,
                    new MacroCardWidget.Actions() {
                        @Override public void onSelect(Macro macro) { selectMacro(macro); }
                        @Override public void onToggle(Macro macro, boolean enabled) { toggleMacro(macro, enabled); }
                        @Override public void onEdit(Macro macro) { editMacro(macro); }
                        @Override public void onDelete(Macro macro) { confirmDeleteMacro(macro); }
                        @Override public void onDuplicate(Macro macro) { duplicateMacro(macro); }
                        @Override public void onTryOnce(Macro macro) { tryOnce(macro); }
                    });
            card.setReducedMotion(studio.settings().reducedMotion());
            cards.add(card);
        }
    }

    private int profileColumnWidth() {
        if (!profileListOpen) return 0;
        return this.width < NARROW_WIDTH ? this.width - 20 : PROFILE_COLUMN;
    }

    private int editorColumnWidth() {
        return this.width < NARROW_WIDTH + EDITOR_COLUMN ? 0 : EDITOR_COLUMN;
    }

    private void relayout() {
        int columnX = profileColumnWidth() > 0 ? 10 + profileColumnWidth() + 6 : 10;
        int available = Math.max(120, this.width - columnX - (editorColumnWidth() + 16));
        profileList.setBounds(10, HEADER_HEIGHT + 6, Math.max(0, profileColumnWidth()),
                Math.max(0, this.height - HEADER_HEIGHT - FOOTER_HEIGHT - 12));
        for (MacroCardWidget card : cards) {
            card.setX(columnX);
            card.setWidth(available);
            card.setReducedMotion(studio.settings().reducedMotion());
        }
        if (editorColumnWidth() == 0) {
            // Narrow window: overlay the editor on the card column. The widgets
            // are moved, not rebuilt, so focus and typed text survive a resize.
            editor.reposition(columnX, HEADER_HEIGHT + 6, Math.max(160, available),
                    Math.max(120, this.height - HEADER_HEIGHT - 12));
        } else {
            editor.reposition(Math.max(10, this.width - EDITOR_COLUMN - 10), HEADER_HEIGHT + 6,
                    EDITOR_COLUMN, Math.max(120, this.height - HEADER_HEIGHT - 12));
        }
    }

    // ---------------------------------------------------------------- render

    /**
     * Icon-width toolbar buttons carry only a glyph, so hovering one shows its
     * full label in the footer. Returns the key, or null when nothing is hovered.
     */
    private String hoveredToolbarLabel() {
        for (ButtonWidget button : toolbarButtons) {
            if (button.getWidth() >= 44) continue;
            String key = toolbarKeys.get(button);
            if (key != null && isOver(hoveredMouseX, hoveredMouseY,
                    button.getX(), button.getY(), button.getWidth(), button.getHeight())) {
                return key;
            }
        }
        return null;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        nowMillis = System.currentTimeMillis();
        hoveredMouseX = mouseX;
        hoveredMouseY = mouseY;
        renderDelta = delta;
        renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fill(0, 0, this.width, this.height, withAlpha(tokens.background(), panelOpen.valueAt(nowMillis)));

        drawHeader(ctx, mouseX, mouseY);

        if (profileColumnWidth() > 0) {
            profileList.render(ctx, mouseX, mouseY);
        }

        drawCards(ctx, mouseX, mouseY);

        if (editorColumnWidth() > 0 || selectedMacroId != null || editorDirty()) {
            editor.render(ctx, mouseX, mouseY);
        }

        drawFooter(ctx);

        super.render(ctx, mouseX, mouseY, delta);

        // Last: the popup is a modal. A scrim dims everything super.render()
        // already drew, so widgets behind the panel read as disabled instead of
        // peeking out from under its edge.
        if (settingsOpen || compactMenuOpen) {
            ctx.fill(0, 0, this.width, this.height, SCRIM);
            drawSettings(ctx, mouseX, mouseY);
        }
    }

    private void drawHeader(DrawContext ctx, int mouseX, int mouseY) {
        StudioPalette.panel(ctx, 4, 4, this.width - 8, HEADER_HEIGHT - 4,
                tokens.panel(), tokens.border(), tokens.background());
        String title = Text.translatable(ModInfo.tr("screen.title")).getString();
        // The header holds two rows: buttons at y=8 and labels at y=35, so the
        // panel must cover both (HEADER_HEIGHT-8 = 36 only reached y=40).
        int labelY = HEADER_HEIGHT - 11;
        int availableLabel = Math.max(40, this.width - 24);
        String clippedTitle = StudioPalette.ellipsize(textRenderer, title, availableLabel - 60);
        ctx.drawTextWithShadow(textRenderer, clippedTitle, 10, labelY, tokens.mutedText());

        String status = runStatusLabel();
        int statusColor = studio.scheduler().isRunning() ? tokens.success() : tokens.mutedText();
        int statusX = this.width - 12 - textRenderer.getWidth(status);
        ctx.drawTextWithShadow(textRenderer, status, Math.max(10, statusX), labelY, statusColor);
    }

    private void drawCards(DrawContext ctx, int mouseX, int mouseY) {
        int columnX = profileColumnWidth() > 0 ? 10 + profileColumnWidth() + 6 : 10;
        int top = HEADER_HEIGHT + 6;
        int height = Math.max(0, this.height - HEADER_HEIGHT - FOOTER_HEIGHT - 12);
        int width = Math.max(120, this.width - columnX - (editorColumnWidth() + 16));

        StudioPalette.panel(ctx, columnX - 4, top - 4, width + 8, height + 8,
                tokens.backgroundAlt(), tokens.border(), tokens.background());

        if (selectedProfile() == null) {
            drawCentredMessage(ctx, columnX, top, width, height, "profile.noneSelected");
            return;
        }
        if (visibleMacros.isEmpty()) {
            drawCentredMessage(ctx, columnX, top, width, height, "macro.empty");
            return;
        }

        ctx.enableScissor(columnX - 3, top - 3, columnX + width + 3, top + height + 3);
        VirtualList.Window window = VirtualList.compute(visibleMacros.size(),
                MacroCardWidget.HEIGHT + CARD_GAP, height, cardScroll);
        for (int i = 0; i < window.count() && i < cards.size(); i++) {
            int index = window.firstIndex() + i;
            if (index >= visibleMacros.size()) break;
            Macro macro = visibleMacros.get(index);
            MacroCardWidget card = cards.get(i);
            int cardY = top + index * (MacroCardWidget.HEIGHT + CARD_GAP) - window.scrollOffset();
            card.setY(cardY);
            card.bind(macro, runtimeFor(macro.id()), macro.id().equals(selectedMacroId), nowMillis);
            card.render(ctx, mouseX, mouseY, renderDelta);
        }
        ctx.disableScissor();
    }

    private void drawCentredMessage(DrawContext ctx, int x, int y, int width, int height, String key) {
        String message = Text.translatable(ModInfo.tr(key)).getString();
        ctx.drawTextWithShadow(textRenderer, StudioPalette.ellipsize(textRenderer, message, width - 16),
                x + 8, y + height / 2 - 4, tokens.mutedText());
    }

    private void drawSettings(DrawContext ctx, int mouseX, int mouseY) {
        int w = 210;
        int h = settingsPopupHeight();
        int x = Math.max(6, this.width - w - 12);
        int y = HEADER_HEIGHT + 2 + settingsScroll;
        StudioPalette.shadow(ctx, x, y, w, h, tokens.shadow());
        StudioPalette.panel(ctx, x, y, w, h, tokens.raisedPanel(), tokens.borderStrong(), tokens.background());
        ctx.drawTextWithShadow(textRenderer,
                Text.translatable(ModInfo.tr("settings.title")).getString(), x + 10, y + 8, tokens.normalText());

        themeButton = placeSetting(themeButton, x + 10, y + 24, w - 20, "settings.theme",
                b -> cycleTheme());
        accentButton = placeSetting(accentButton, x + 10, y + 48, w - 20, "settings.accent",
                b -> cycleAccent());
        motionButton = placeSetting(motionButton, x + 10, y + 72, w - 20, "settings.reducedMotion",
                b -> toggleReducedMotion());
        statusButton = placeSetting(statusButton, x + 10, y + 94, w - 20, "settings.runStatus",
                b -> toggleRunStatus());
        exportButton = placeSetting(exportButton, x + 10, y + 116, w - 20, "action.export",
                b -> exportProfile());
        exportSecretsButton = placeSetting(exportSecretsButton, x + 10, y + 138, w - 20,
                "action.exportSecrets", b -> exportProfileWithSecrets());
        importButton = placeSetting(importButton, x + 10, y + 160, w - 20, "action.import",
                b -> openImportPicker());
        diagnosticsButton = placeSetting(diagnosticsButton, x + 10, y + 182, w - 20, "action.diagnostics",
                b -> openDiagnostics());
        f30NoteButton = placeSetting(f30NoteButton, x + 10, y + 204, w - 20, "settings.f30Note",
                b -> openF30Note());
        for (ButtonWidget button : settingButtons()) {
            // Clip to the popup so nothing is drawn over the macro list, and skip
            // rows scrolled out of view instead of leaving invisible hitboxes.
            if (button.getY() < HEADER_HEIGHT + 2 || button.getY() + button.getHeight() > this.height - FOOTER_HEIGHT) {
                continue;
            }
            button.render(ctx, mouseX, mouseY, renderDelta);
        }
    }

    /** Height of the settings popup, clamped so it always fits on screen. */
    private int settingsPopupHeight() {
        return Math.min(SETTINGS_ROWS_HEIGHT, Math.max(60, this.height - HEADER_HEIGHT - FOOTER_HEIGHT - 6));
    }

    /** Scroll offset for the settings popup, in pixels, never showing empty space. */
    private int settingsScrollMax() {
        return Math.max(0, SETTINGS_ROWS_HEIGHT - settingsPopupHeight());
    }

    private List<ButtonWidget> settingButtons() {
        List<ButtonWidget> out = new ArrayList<>();
        for (ButtonWidget button : new ButtonWidget[] { themeButton, accentButton, motionButton,
                exportButton, exportSecretsButton, importButton, diagnosticsButton, f30NoteButton,
                statusButton }) {
            if (button != null) out.add(button);
        }
        return out;
    }

    private void clampSettings() {
        if (settingsScroll > settingsScrollMax()) settingsScroll = settingsScrollMax();
        if (settingsScroll < 0) settingsScroll = 0;
    }

    private ButtonWidget placeSetting(ButtonWidget existing, int x, int y, int width, String key,
                                      ButtonWidget.PressAction action) {
        ButtonWidget button = existing != null ? existing : ButtonWidget.builder(
                Text.translatable(ModInfo.tr(key)), action).build();
        button.setX(x);
        button.setY(y);
        button.setWidth(width);
        button.setHeight(20);
        return button;
    }

    private void drawFooter(DrawContext ctx) {
        int y = this.height - FOOTER_HEIGHT;
        ctx.fill(0, y, this.width, this.height, tokens.panel());
        ctx.fill(0, y, this.width, y + 1, tokens.border());

        String budget = Text.translatable(ModInfo.tr("footer.budget"),
                studio.settings().globalMinDispatchGapMillis() / 1000.0).getString();
        ctx.drawTextWithShadow(textRenderer, budget, 10, y + 6, tokens.mutedText());

        String hovered = hoveredToolbarLabel();
        String status = hovered != null
                ? Text.translatable(ModInfo.tr(hovered)).getString()
                : statusMessageKey.isEmpty()
                        ? Text.translatable(ModInfo.tr("footer.ready")).getString()
                        : Text.translatable(ModInfo.tr(statusMessageKey)).getString();
        ctx.drawTextWithShadow(textRenderer,
                StudioPalette.ellipsize(textRenderer, status, this.width - 20 - textRenderer.getWidth(budget)),
                14 + textRenderer.getWidth(budget), y + 6, tokens.normalText());
    }

    private static int withAlpha(int color, float progress) {
        int alpha = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, progress)));
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    // ----------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if ((settingsOpen || compactMenuOpen) && themeButton != null) {
            for (ButtonWidget widget : settingButtons()) {
                if (widget.mouseClicked(mouseX, mouseY, button)) return true;
            }
            boolean insidePopup = isOver(mouseX, mouseY, this.width - 222, HEADER_HEIGHT,
                    216, this.height - HEADER_HEIGHT - FOOTER_HEIGHT);
            if (!insidePopup) {
                settingsOpen = false;
                compactMenuOpen = false;
            }
        }
        if (profileColumnWidth() > 0 && profileList.mouseClicked(mouseX, mouseY, button)) return true;
        for (MacroCardWidget card : cards) {
            if (card.mouseClicked(mouseX, mouseY, button)) return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (settingsOpen && isOver(mouseX, mouseY, this.width - 222, HEADER_HEIGHT,
                216, this.height - HEADER_HEIGHT - FOOTER_HEIGHT)) {
            settingsScroll = Math.max(0, Math.min(settingsScrollMax(),
                    settingsScroll - (int) Math.signum(verticalAmount) * 22));
            return true;
        }
        if (profileColumnWidth() > 0 && isOver(mouseX, mouseY, 10, HEADER_HEIGHT,
                profileColumnWidth(), this.height - HEADER_HEIGHT - FOOTER_HEIGHT)) {
            return profileList.mouseScrolled(verticalAmount);
        }
        // The editor scrolls its own form when the window is too short for it.
        int editorX = editorColumnWidth() > 0 ? Math.max(10, this.width - EDITOR_COLUMN - 10)
                : (profileColumnWidth() > 0 ? 10 + profileColumnWidth() + 6 : 10);
        int editorW = editorColumnWidth() > 0 ? EDITOR_COLUMN
                : Math.max(160, this.width - editorX - 16);
        if (isOver(mouseX, mouseY, editorX, HEADER_HEIGHT, editorW,
                this.height - HEADER_HEIGHT - FOOTER_HEIGHT) && editor.overflow() > 0) {
            editor.scrollBy(-(int) Math.signum(verticalAmount) * 16);
            return true;
        }
        int columnX = profileColumnWidth() > 0 ? 10 + profileColumnWidth() + 6 : 10;
        int previous = cardScroll;
        cardScroll = VirtualList.clampScroll(cardScroll - (int) Math.signum(verticalAmount)
                        * (MacroCardWidget.HEIGHT + CARD_GAP) * 2,
                visibleMacros.size(), MacroCardWidget.HEIGHT + CARD_GAP,
                Math.max(1, this.height - HEADER_HEIGHT - FOOTER_HEIGHT - 12));
        return cardScroll != previous || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private boolean isOver(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // The game only queues key-bind presses while no screen has focus, so
        // the emergency stop is matched here as well: it must work everywhere.
        if (StudioKeyBindings.emergencyStop != null
                && StudioKeyBindings.emergencyStop.matchesKey(keyCode, scanCode)) {
            studio.stopRun("run.stoppedByUser");
            status("run.stopped");
            return true;
        }
        if (keyCode == 256) {                       // Escape
            if (settingsOpen) { settingsOpen = false; compactMenuOpen = false; return true; }
            if (editorDirty()) { confirmDiscardEdits(); return true; }
            close();
            return true;
        }
        if (keyCode == 264 && profileColumnWidth() > 0) {   // Down
            profileList.moveSelection(1);
            return true;
        }
        if (keyCode == 265 && profileColumnWidth() > 0) {   // Up
            profileList.moveSelection(-1);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Escape closes the screen; it never silently stops a running session. */
    @Override
    public void close() {
        if (studio != null) {
            commitProfileName();
            studio.configStore().flush();
        }
        super.close();
    }

    @Override
    public boolean shouldPause() {
        // Pausing here would freeze the scheduler the screen is controlling, and
        // it would pause the game in single player while the player edits.
        return false;
    }

    @Override
    public void tick() {
        if (studio == null) return;
        refreshHeader();
    }

    // -------------------------------------------------------------- actions

    private Profile selectedProfile() {
        return studio == null ? null : studio.profiles().selected().orElse(null);
    }

    private MacroRuntime runtimeFor(UUID macroId) {
        for (MacroRuntime runtime : studio.scheduler().runtimeSnapshot()) {
            if (runtime.macroId().equals(macroId)) return runtime;
        }
        return null;
    }

    private void refreshProfiles() {
        if (studio == null) return;
        String query = searchField == null ? "" : searchField.getText();
        visibleProfiles = studio.profiles().visible(query, sortMode);
        Profile selected = selectedProfile();
        UUID selectedId = selected == null ? null : selected.id();
        profileList.bind(visibleProfiles, selectedId, System.currentTimeMillis());
        refreshMacroList();
    }

    private void refreshMacroList() {
        Profile profile = selectedProfile();
        visibleMacros = profile == null ? List.of() : studio.macros().forProfile(profile);
        cardScroll = VirtualList.clampScroll(cardScroll, visibleMacros.size(),
                MacroCardWidget.HEIGHT + CARD_GAP,
                Math.max(1, this.height - HEADER_HEIGHT - FOOTER_HEIGHT - 12));
        if (profileNameField != null) {
            String current = profileNameField.getText();
            String wanted = profile == null ? "" : profile.name();
            if (!current.equals(wanted) && !profileNameField.isActive()) {
                profileNameField.setText(wanted);
            }
        }
    }

    private void refreshHeader() {
        if (startStopButton == null) return;
        boolean running = studio.scheduler().isRunning();
        fitLabel(startStopButton, running ? "run.stop" : "run.start");
        fitLabel(pauseResumeButton, studio.scheduler().isPausedByUser() ? "run.resume" : "run.pause");
        pauseResumeButton.active = running;
        boolean hasProfile = selectedProfile() != null;
        newMacroButton.active = hasProfile;
        deleteProfileButton.active = hasProfile;
        duplicateProfileButton.active = hasProfile;
    }

    private String runStatusLabel() {
        if (!studio.scheduler().isRunning()) {
            return Text.translatable(ModInfo.tr("state.stopped")).getString();
        }
        if (studio.scheduler().isPausedByUser()) {
            return Text.translatable(ModInfo.tr("state.paused")).getString();
        }
        String hold = studio.scheduler().holdReasonKey();
        if (!hold.isEmpty()) return Text.translatable(ModInfo.tr(hold)).getString();
        return Text.translatable(ModInfo.tr("state.running")).getString();
    }

    private void chooseProfile(Profile profile) {
        commitProfileName();
        studio.selectProfile(profile.id());
        selectedMacroId = null;
        editor.load(profile, null);
        refreshProfiles();
        status("profile.selected");
    }

    private void selectMacro(Macro macro) {
        selectedMacroId = macro.id();
        editor.load(selectedProfile(), macro);
    }

    private void editMacro(Macro macro) {
        selectMacro(macro);
    }

    private void newMacro() {
        Profile profile = selectedProfile();
        if (profile == null) return;
        selectedMacroId = null;
        editor.load(profile, null);
        status("macro.editor.titleNew");
    }

    private void toggleMacro(Macro macro, boolean enabled) {
        RepoResult<Macro> result = studio.setMacroEnabled(selectedProfile(), macro.id(), enabled);
        afterMutation(result);
    }

    private void duplicateMacro(Macro macro) {
        afterMutation(studio.duplicateMacro(selectedProfile(), macro.id()));
    }

    private void confirmDeleteMacro(Macro macro) {
        openConfirm("confirm.deleteMacro.title", "confirm.deleteMacro.body",
                new Object[] { macro.name() }, "action.delete", true, () -> {
                    afterMutation(studio.deleteMacro(selectedProfile(), macro.id()));
                    if (macro.id().equals(selectedMacroId)) {
                        selectedMacroId = null;
                        editor.load(selectedProfile(), null);
                    }
                });
    }

    private void tryOnce(Macro macro) {
        Runnable send = () -> {
            DispatchResult result = studio.dispatchOnce(macro);
            status(result.succeeded() ? "dispatch.handedToClient" : result.detailKey());
        };
        if (macro.isCommand() && SensitiveCommands.isHighImpact(macro.content())
                && studio.settings().confirmDangerousCommands()) {
            openConfirm("confirm.dangerous.title", "confirm.dangerous.body",
                    new Object[] { SensitiveCommands.root(macro.content()) },
                    "action.sendOnce", true, send);
        } else {
            send.run();
        }
    }

    private void saveMacro(MacroEditorPanel panel) {
        Profile profile = selectedProfile();
        if (profile == null) {
            panel.setError("error.profileNotFound");
            return;
        }
        RepoResult<Macro> result = panel.editingId() == null
                ? studio.createMacro(profile, panel.nameValue(), panel.type(), panel.contentValue(),
                        panel.intervalValue(), panel.initialDelayValue())
                : studio.updateMacro(profile, panel.editingId(), panel.nameValue(), panel.type(),
                        panel.contentValue(), panel.intervalValue(), panel.initialDelayValue());
        if (!result.success()) {
            panel.setError(result.messageKeyOrEmpty());
            return;
        }
        panel.setError("");
        selectedMacroId = result.value() == null ? selectedMacroId : result.value().id();
        afterMutation(result);
    }

    private boolean editorDirty() {
        return editor != null && editor.hasUnsavedChanges();
    }

    private void confirmDiscardEdits() {
        openConfirm("confirm.discard.title", "confirm.discard.body", null,
                "action.discard", true, () -> {
                    editor.load(selectedProfile(), selectedMacro());
                    editor.setError("");
                });
    }

    private Macro selectedMacro() {
        if (selectedMacroId == null) return null;
        return studio.macros().find(selectedMacroId).orElse(null);
    }

    private void toggleRun() {
        if (studio.scheduler().isRunning()) {
            studio.stopRun("run.stoppedByUser");
            status("run.stopped");
        } else {
            if (!studio.activeProfileHasEnabledMacros()) {
                status("run.noEnabledMacros");
                return;
            }
            if (profileRequiresConfirmation()) {
                openConfirm("confirm.start.title", "confirm.start.body",
                        new Object[] { studio.macros().enabledCountFor(studio.scheduler().activeProfileId()) },
                        "run.start", true, () -> {
                            studio.startRun();
                            status("run.started");
                        });
                return;
            }
            studio.startRun();
            status("run.started");
        }
    }

    /** A run is confirmed first when any enabled macro is a high-impact command. */
    private boolean profileRequiresConfirmation() {
        if (!studio.settings().confirmDangerousCommands()) return false;
        UUID profileId = studio.scheduler().activeProfileId();
        if (profileId == null) return false;
        for (Macro macro : studio.macros().forProfileId(profileId)) {
            if (macro.enabled() && macro.isCommand() && SensitiveCommands.isHighImpact(macro.content())) {
                return true;
            }
        }
        return false;
    }

    private void togglePause() {
        if (studio.scheduler().isPausedByUser()) {
            studio.resumeRun();
            status("run.resumed");
        } else {
            studio.pauseRun();
            status("run.paused");
        }
    }

    private void duplicateProfile() {
        Profile profile = selectedProfile();
        if (profile == null) return;
        RepoResult<Profile> result = studio.duplicateProfile(profile.id(), null);
        if (result.success() && result.value() != null) {
            studio.selectProfile(result.value().id());
        }
        afterMutation(result);
    }

    private void confirmDeleteProfile() {
        Profile profile = selectedProfile();
        if (profile == null) return;
        openConfirm("confirm.deleteProfile.title", "confirm.deleteProfile.body",
                new Object[] { profile.name(), profile.macroCount() }, "action.delete", true, () -> {
                    studio.stopRun("run.profileDeleted");
                    afterMutation(studio.deleteProfile(profile.id()));
                    selectedMacroId = null;
                    editor.load(selectedProfile(), null);
                });
    }

    private void cycleSort() {
        SortMode[] modes = SortMode.values();
        sortMode = modes[(sortMode.ordinal() + 1) % modes.length];
        refreshProfiles();
        status("profile.sortMode");
    }

    private void cycleTheme() {
        ThemeMode next = studio.settings().themeMode() == ThemeMode.DARK ? ThemeMode.LIGHT : ThemeMode.DARK;
        studio.updateSettings(studio.settings().withThemeMode(next));
        rebuildAfterThemeChange();
    }

    private void cycleAccent() {
        Accent[] accents = Accent.values();
        Accent next = accents[(studio.settings().accent().ordinal() + 1) % accents.length];
        studio.updateSettings(studio.settings().withAccent(next));
        rebuildAfterThemeChange();
    }

    /** Re-reads the tokens and re-lays the screen out after a theme change. */
    private void rebuildAfterThemeChange() {
        tokens = studio.themes().tokens(studio.settings().themeMode(), studio.settings().accent());
        editor.setTokens(tokens);
        profileList.setTokens(tokens);
        for (MacroCardWidget card : cards) card.setTokens(tokens);
        relayout();
    }

    private void toggleRunStatus() {
        studio.updateSettings(
                studio.settings().withShowRunStatusInChat(!studio.settings().showRunStatusInChat()));
    }

    private void toggleReducedMotion() {
        studio.updateSettings(studio.settings().withReducedMotion(!studio.settings().reducedMotion()));
        relayout();
    }

    private void commitProfileName() {
        Profile profile = selectedProfile();
        if (profile == null || profileNameField == null) return;
        String typed = profileNameField.getText();
        if (typed.strip().equals(profile.name())) return;
        RepoResult<Profile> result = studio.renameProfile(profile.id(), typed);
        afterMutation(result);
    }

    private <T> void afterMutation(RepoResult<T> result) {
        if (result.messageKeyOrEmpty().isEmpty()) {
            refreshProfiles();
            return;
        }
        status(result.messageKeyOrEmpty());
        refreshProfiles();
    }

    private void status(String messageKey) {
        this.statusMessageKey = messageKey == null ? "" : messageKey;
    }

    private void openConfirm(String titleKey, String bodyKey, Object[] args, String confirmKey,
                             boolean dangerous, Runnable onConfirm) {
        ChatMacroStudioClient.openScreen(new ConfirmDialogScreen(titleKey, bodyKey, args,
                confirmKey, dangerous, tokens, onConfirm, this::returnToStudio));
    }

    /** Puts this screen back after a popup closed. */
    private void returnToStudio() {
        ChatMacroStudioClient.openScreen(this);
    }

    // --------------------------------------------------------- data transfer

    /** Writes the selected profile to config/chatmacro_studio/exports/. */
    private void exportProfile() {
        Profile profile = selectedProfile();
        if (profile == null) { status("profile.noneSelected"); return; }
        ProfileTransfer.ExportResult result = transfer.export(studio, profile, false);
        status(result.messageKey());
    }

    /** Export with secret arguments left in clear text - confirmed first. */
    private void exportProfileWithSecrets() {
        Profile profile = selectedProfile();
        if (profile == null) { status("profile.noneSelected"); return; }
        openConfirm("confirm.exportSecrets.title", "confirm.exportSecrets.body",
                new Object[] { profile.name() }, "action.exportSecrets", true, () -> {
                    ProfileTransfer.ExportResult result = transfer.export(studio, profile, true);
                    status(result.messageKey());
                });
    }

    /** Lists local bundles, then shows a preview before anything is written. */
    private void openImportPicker() {
        ChatMacroStudioClient.openScreen(new BundlePickerScreen(transfer, tokens,
                this::openImportPreview, this::returnToStudio));
    }

    private void openImportPreview(String fileName) {
        ProfileTransfer.ImportResult result = transfer.readForImport(studio, fileName);
        if (!result.success()) {
            status(result.messageKey());
            returnToStudio();
            return;
        }
        ChatMacroStudioClient.openScreen(new ImportPreviewScreen(result.preview(), result.json(),
                tokens, studio, () -> {
                    refreshProfiles();
                    status("import.done");
                    returnToStudio();
                }));
    }

    /**
     * Explains the "F30" label. There is no F30 key on a keyboard or in GLFW, so
     * the real binding is Right Shift and this note is reachable from the UI
     * rather than living only in the README.
     */
    private void openF30Note() {
        openConfirm("key.openStudio", "f30.note", null, "action.close", false, () -> { });
    }

    /** Shows the masked diagnostics; they are never sent anywhere. */
    private void openDiagnostics() {
        ChatMacroStudioClient.openScreen(new DiagnosticsScreen(studio, tokens, this::returnToStudio));
    }

    /** Exposed for the HUD so both surfaces describe the state the same way. */
    public String statusLabel() {
        return runStatusLabel();
    }
}
