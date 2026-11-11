package dev.chatmacro.studio.testing;

import dev.chatmacro.studio.ui.VirtualList;

/** Bounded drawing and scroll behaviour for long lists. */
public final class UiModelTests {

    private UiModelTests() {
    }

    public static void register(TestRunner r) {

        r.test("ui", "only the visible rows are in the window", () -> {
            VirtualList.Window w = VirtualList.compute(1000, 40, 300, 0);
            Assert.eq(0, w.firstIndex(), "starts at the top");
            Assert.isTrue(w.count() <= 15, "a handful of rows, not a thousand: " + w.count());
            Assert.eq(40_000, w.contentHeight(), "content height covers every row");
            Assert.eq(39_700, w.maxScroll(), "max scroll is content minus viewport");
        });

        r.test("ui", "scrolling moves the window and keeps the overscan", () -> {
            VirtualList.Window w = VirtualList.compute(1000, 40, 300, 4000);
            Assert.eq(100 - VirtualList.OVERSCAN, w.firstIndex(), "window follows the offset");
            Assert.eq(4000, w.scrollOffset(), "offset preserved");
        });

        r.test("ui", "the scroll offset is clamped when the window shrinks", () -> {
            // A wide window scrolled to the bottom, then the window becomes small.
            int clamped = VirtualList.clampScroll(99_999, 1000, 40, 300);
            Assert.eq(39_700, clamped, "clamped to the new maximum");
            VirtualList.Window w = VirtualList.compute(1000, 40, 300, 99_999);
            Assert.eq(39_700, w.scrollOffset(), "the window clamps too");
            Assert.isTrue(w.lastIndex() <= 999, "no rows past the end: " + w.lastIndex());
        });

        r.test("ui", "empty and degenerate inputs produce an empty window", () -> {
            Assert.isTrue(VirtualList.compute(0, 40, 300, 0).isEmpty(), "no items");
            Assert.isTrue(VirtualList.compute(10, 0, 300, 0).isEmpty(), "zero row height");
            Assert.isTrue(VirtualList.compute(10, 40, 0, 0).isEmpty(), "zero viewport");
            Assert.isTrue(VirtualList.compute(10, 40, -5, 0).isEmpty(), "negative viewport");
        });

        r.test("ui", "hit testing maps a click to the right row", () -> {
            Assert.eq(0, VirtualList.indexAt(10, 0, 40, 100), "first row");
            Assert.eq(3, VirtualList.indexAt(10, 120, 40, 100), "fourth row");
            Assert.eq(-1, VirtualList.indexAt(-5, 0, 40, 100), "above the list");
            Assert.eq(-1, VirtualList.indexAt(10_000, 0, 40, 10), "below the last row");
        });

        r.test("ui", "a viewport taller than the content still works", () -> {
            VirtualList.Window w = VirtualList.compute(3, 40, 1000, 0);
            Assert.eq(0, w.firstIndex(), "top");
            Assert.eq(3, w.count(), "all three rows");
            Assert.eq(0, w.maxScroll(), "nothing to scroll");
        });

        r.test("ui", "clamp never inverts its bounds", () -> {
            Assert.eq(5, VirtualList.clamp(0, 5, 1), "max below min falls back to min");
            Assert.eq(10, VirtualList.clamp(99, 5, 10), "upper bound");
            Assert.eq(7, VirtualList.clamp(7, 5, 10), "inside the bounds");
            Assert.eq(5, VirtualList.clamp(-99, 5, 10), "lower bound");
        });
    }
}
