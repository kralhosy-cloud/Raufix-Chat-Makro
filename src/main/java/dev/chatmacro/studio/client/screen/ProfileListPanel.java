package dev.chatmacro.studio.client.screen;

import dev.chatmacro.studio.ModInfo;
import dev.chatmacro.studio.model.Profile;
import dev.chatmacro.studio.repo.SortMode;
import dev.chatmacro.studio.theme.ThemeTokens;
import dev.chatmacro.studio.ui.Animation;
import dev.chatmacro.studio.ui.VirtualList;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;
import java.util.UUID;

/**
 * The profile list on the left (or in the drop-down panel on narrow windows).
 *
 * <p>Rows are drawn through {@link VirtualList}, so a list with a thousand
 * profiles still draws only what is on screen, and a window resize clamps the
 * scroll offset instead of leaving rows drawn outside the panel.</p>
 */
public final class ProfileListPanel {

    public static final int ROW_HEIGHT = 30;

    /** Called when a row is activated. */
    public interface Listener {
        void onProfileChosen(Profile profile);
    }

    private final TextRenderer text;
    private ThemeTokens tokens;
    private final Listener listener;
    private final Animation hover = new Animation(120, Animation.Easing.SMOOTH, false);

    private List<Profile> profiles = List.of();
    private UUID selectedId;
    private int scroll;
    private int hoveredRow = -1;
    private boolean reducedMotion;
    private long nowMillis;

    private int x;
    private int y;
    private int width;
    private int height;

    public ProfileListPanel(TextRenderer text, ThemeTokens tokens, Listener listener) {
        this.text = text;
        this.tokens = tokens;
        this.listener = listener;
        this.hover.snapTo(0f);
    }

    public void setReducedMotion(boolean value) {
        this.reducedMotion = value;
    }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.scroll = VirtualList.clampScroll(scroll, profiles.size(), ROW_HEIGHT, height);
    }

    /** Rebinds the visible data. Called on document change, never during render. */
    public void bind(List<Profile> profiles, UUID selectedId, long nowMillis) {
        this.profiles = List.copyOf(profiles);
        this.selectedId = selectedId;
        this.nowMillis = nowMillis;
        this.scroll = VirtualList.clampScroll(scroll, profiles.size(), ROW_HEIGHT, height);
    }

    public void setSort(SortMode sort) {
        // The screen owns the sort mode and re-binds the sorted list.
    }

    public void render(DrawContext ctx, int mouseX, int mouseY) {
        StudioPalette.panel(ctx, x, y, width, height, tokens.panel(), tokens.border(), tokens.background());

        int listTop = y + 4;
        int listHeight = height - 8;
        if (listHeight <= 0) return;

        ctx.enableScissor(x + 1, listTop, x + width - 1, listTop + listHeight);
        VirtualList.Window window = VirtualList.compute(profiles.size(), ROW_HEIGHT, listHeight, scroll);
        hoveredRow = -1;
        for (int i = 0; i < window.count(); i++) {
            int index = window.firstIndex() + i;
            if (index >= profiles.size()) break;
            int rowY = listTop + index * ROW_HEIGHT - window.scrollOffset();
            if (rowY + ROW_HEIGHT < listTop || rowY > listTop + listHeight) continue;
            drawRow(ctx, profiles.get(index), index, rowY, mouseX, mouseY);
        }
        ctx.disableScissor();

        if (profiles.isEmpty()) {
            String message = Text.translatable(ModInfo.tr("profile.empty")).getString();
            ctx.drawTextWithShadow(text, StudioPalette.ellipsize(text, message, width - 16),
                    x + 8, y + height / 2 - 4, tokens.mutedText());
        }
        drawScrollbar(ctx, window, listTop, listHeight);
    }

    private void drawRow(DrawContext ctx, Profile profile, int index, int rowY, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x + 2 && mouseX <= x + width - 2
                && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
        if (hovered) hoveredRow = index;
        boolean selected = profile.id().equals(selectedId);

        float hoverProgress = reducedMotion ? (hovered ? 1f : 0f) : hover.valueAt(nowMillis);
        int surface = dev.chatmacro.studio.theme.Colors.blend(tokens.panel(), tokens.raisedPanel(),
                hoverProgress * 0.8f);
        // selection carries alpha; flatten it onto the row instead of replacing it.
        if (selected) surface = dev.chatmacro.studio.theme.Colors.composite(tokens.selection(), surface);

        StudioPalette.panel(ctx, x + 2, rowY + 1, width - 4, ROW_HEIGHT - 2, surface,
                selected ? tokens.accent() : 0, tokens.panel(), 2);

        String name = StudioPalette.ellipsize(text, profile.name(), width - 26);
        ctx.drawTextWithShadow(text, name, x + 8, rowY + 4,
                tokens.textOn(surface));

        String meta = Text.translatable(ModInfo.tr("profile.row.macros"), profile.macroCount()).getString();
        ctx.drawTextWithShadow(text, meta, x + 8, rowY + 16, tokens.mutedOn(surface));
    }

    private void drawScrollbar(DrawContext ctx, VirtualList.Window window, int listTop, int listHeight) {
        if (profiles.size() * ROW_HEIGHT <= listHeight) return;
        int trackX = x + width - 4;
        ctx.fill(trackX, listTop, trackX + 2, listTop + listHeight, tokens.border());
        int thumbHeight = Math.max(12, listHeight * listHeight / (profiles.size() * ROW_HEIGHT));
        int maxScroll = Math.max(1, window.maxScroll());
        int thumbY = listTop + (listHeight - thumbHeight) * scroll / maxScroll;
        ctx.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, tokens.accent());
    }

    /** Called from the screen with the already-decoded click position. */
    public boolean mouseClicked(double mouseX, double mouseY) {
        if (mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + height) return false;
        if (hoveredRow >= 0 && hoveredRow < profiles.size()) {
            listener.onProfileChosen(profiles.get(hoveredRow));
            return true;
        }
        return true;
    }

    public boolean mouseScrolled(double amount) {
        int previous = scroll;
        scroll = VirtualList.clampScroll(scroll - (int) Math.signum(amount) * ROW_HEIGHT * 3,
                profiles.size(), ROW_HEIGHT, height);
        return scroll != previous;
    }

    /** Moves the selection with the keyboard and scrolls it into view. */
    public void moveSelection(int delta) {
        if (profiles.isEmpty()) return;
        int current = 0;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id().equals(selectedId)) { current = i; break; }
        }
        int next = VirtualList.clamp(current + delta, 0, profiles.size() - 1);
        listener.onProfileChosen(profiles.get(next));
        int rowTop = next * ROW_HEIGHT;
        if (rowTop < scroll) scroll = rowTop;
        if (rowTop + ROW_HEIGHT > scroll + height) scroll = rowTop + ROW_HEIGHT - height;
    }
/** Swaps the palette live, so a theme change does not rebuild the screen. */
    public void setTokens(ThemeTokens next) {
        this.tokens = next;
    }
}
