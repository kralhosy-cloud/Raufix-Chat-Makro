package dev.chatmacro.studio.sched;

import dev.chatmacro.studio.dispatch.DispatchResult;
import dev.chatmacro.studio.model.Macro;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** What one scheduler tick did, for the UI and for tests. */
public final class TickReport {

    /** One dispatch that really happened during this tick. */
    public record Event(UUID macroId, String macroName, DispatchResult result) {
    }

    private final long atNanos;
    private final List<Event> events = new ArrayList<>();
    private String holdReasonKey = "";
    private int budgetBlocked;
    private int dueCount;

    public TickReport(long atNanos) {
        this.atNanos = atNanos;
    }

    public long atNanos() { return atNanos; }
    public List<Event> events() { return List.copyOf(events); }
    public String holdReasonKey() { return holdReasonKey; }
    public int budgetBlocked() { return budgetBlocked; }
    public int dueCount() { return dueCount; }
    public boolean dispatched() { return !events.isEmpty(); }

    void addEvent(UUID macroId, String macroName, DispatchResult result) {
        events.add(new Event(macroId, macroName, result));
    }

    void setHoldReason(String key) {
        this.holdReasonKey = key == null ? "" : key;
    }

    void addBudgetBlocked() {
        budgetBlocked++;
    }

    void setDueCount(int count) {
        this.dueCount = count;
    }
}
