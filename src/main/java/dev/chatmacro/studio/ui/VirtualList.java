package dev.chatmacro.studio.ui;

/**
 * Bounded windowing for long lists.
 *
 * <p>Profiles and macro cards are drawn through this so a profile list with a
 * thousand entries draws only the visible rows, and so resizing the window can
 * never leave the scroll offset pointing past the end (which is how stale
 * coordinates turn into invisible widgets and phantom hit boxes).</p>
 */
public final class VirtualList {

    /** Which rows to draw, in item indices. */
    public record Window(int firstIndex, int count, int scrollOffset, int contentHeight, int maxScroll) {
        public static final Window EMPTY = new Window(0, 0, 0, 0, 0);
        public boolean isEmpty() { return count == 0; }
        public int lastIndex() { return firstIndex + count - 1; }
    }

    /** Overscan rows drawn above/below the viewport to hide scroll seams. */
    public static final int OVERSCAN = 2;

    private VirtualList() {
    }

    public static Window compute(int itemCount, int rowHeight, int viewportHeight, int scrollOffset) {
        if (itemCount <= 0 || rowHeight <= 0 || viewportHeight <= 0) return Window.EMPTY;
        int contentHeight = itemCount * rowHeight;
        int maxScroll = Math.max(0, contentHeight - viewportHeight);
        int clamped = clamp(scrollOffset, 0, maxScroll);
        int first = Math.max(0, clamped / rowHeight - OVERSCAN);
        int visibleRows = (int) Math.ceil(viewportHeight / (double) rowHeight) + 1;
        int count = Math.min(itemCount - first, visibleRows + OVERSCAN * 2);
        return new Window(first, Math.max(0, count), clamped, contentHeight, maxScroll);
    }

    public static int clampScroll(int scrollOffset, int itemCount, int rowHeight, int viewportHeight) {
        if (itemCount <= 0 || rowHeight <= 0 || viewportHeight <= 0) return 0;
        int maxScroll = Math.max(0, itemCount * rowHeight - viewportHeight);
        return clamp(scrollOffset, 0, maxScroll);
    }

    /** Converts a mouse Y inside the viewport to an item index, or -1. */
    public static int indexAt(int yInViewport, int scrollOffset, int rowHeight, int itemCount) {
        if (itemCount <= 0 || rowHeight <= 0) return -1;
        if (yInViewport < 0) return -1;
        int index = (yInViewport + scrollOffset) / rowHeight;
        return index >= 0 && index < itemCount ? index : -1;
    }

    public static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return value < min ? min : Math.min(value, max);
    }
}
