package dev.chatmacro.studio.sched;

import dev.chatmacro.studio.dispatch.DispatchOutcome;

/**
 * Non-persisted runtime state for one macro.
 *
 * <p>Kept strictly separate from {@code Macro} so a restart can never resume a
 * half-elapsed timer and so "the user wants this on" ({@code Macro.enabled})
 * stays distinguishable from "this is currently running".</p>
 */
public final class MacroRuntime {

    /** Lifecycle of a macro inside one run session. */
    public enum State {
        /** Not enabled by the user, or not part of the active profile. */
        INACTIVE,
        /** Enabled, session running, waiting for its next slot. */
        WAITING,
        /** Held back by policy (chat open, paused, focus lost, scope mismatch). */
        HELD,
        /** Recovering from a failure; next attempt is pushed back. */
        BACKOFF,
        /** Stopped by the user or by too many failures. */
        STOPPED
    }

    private final java.util.UUID macroId;
    private State state = State.INACTIVE;
    private long nextEligibleNanos = Long.MAX_VALUE;
    private long lastDispatchNanos = Long.MIN_VALUE;
    private long dueSinceNanos = Long.MAX_VALUE;
    private int consecutiveFailures;
    private DispatchOutcome lastOutcome;
    private String lastDetailKey = "";
    private long dispatchCount;

    public MacroRuntime(java.util.UUID macroId) {
        this.macroId = macroId;
    }

    public java.util.UUID macroId() { return macroId; }
    public State state() { return state; }
    public long nextEligibleNanos() { return nextEligibleNanos; }
    public long lastDispatchNanos() { return lastDispatchNanos; }
    /** When this macro first became due without being served - for fairness. */
    public long dueSinceNanos() { return dueSinceNanos; }
    public int consecutiveFailures() { return consecutiveFailures; }
    public DispatchOutcome lastOutcome() { return lastOutcome; }
    public String lastDetailKey() { return lastDetailKey; }
    public long dispatchCount() { return dispatchCount; }

    public boolean isDue(long nowNanos) {
        return (state == State.WAITING || state == State.BACKOFF) && nowNanos - nextEligibleNanos >= 0;
    }

    /** Milliseconds until the next attempt, or -1 when there is none. */
    public long millisUntilNext(long nowNanos) {
        if (nextEligibleNanos == Long.MAX_VALUE) return -1;
        long delta = (nextEligibleNanos - nowNanos) / 1_000_000L;
        return Math.max(0, delta);
    }

    void arm(long nowNanos, long delayNanos) {
        state = State.WAITING;
        nextEligibleNanos = nowNanos + delayNanos;
        dueSinceNanos = Long.MAX_VALUE;
        consecutiveFailures = 0;
        lastOutcome = null;
        lastDetailKey = "";
    }

    void markDue(long nowNanos) {
        if (dueSinceNanos == Long.MAX_VALUE) dueSinceNanos = nowNanos;
    }

    void markDispatched(long nowNanos, long intervalNanos) {
        lastDispatchNanos = nowNanos;
        // Catch-up policy: missed repeats are dropped, the next slot is always
        // "now + interval", never a queue of historical slots.
        nextEligibleNanos = nowNanos + intervalNanos;
        dueSinceNanos = Long.MAX_VALUE;
        consecutiveFailures = 0;
        dispatchCount++;
    }

    void hold(String reasonKey) {
        if (state == State.WAITING) state = State.HELD;
        lastDetailKey = reasonKey;
    }

    void releaseHold() {
        if (state == State.HELD) state = State.WAITING;
    }

    void fail(long nowNanos, long backoffNanos, DispatchOutcome outcome, String detailKey) {
        consecutiveFailures++;
        lastOutcome = outcome;
        lastDetailKey = detailKey;
        state = State.BACKOFF;
        nextEligibleNanos = nowNanos + backoffNanos;
        dueSinceNanos = Long.MAX_VALUE;
    }

    void recoverFromBackoff() {
        if (state == State.BACKOFF) state = State.WAITING;
    }

    void stop() {
        state = State.STOPPED;
        nextEligibleNanos = Long.MAX_VALUE;
        dueSinceNanos = Long.MAX_VALUE;
    }

    void deactivate() {
        state = State.INACTIVE;
        nextEligibleNanos = Long.MAX_VALUE;
        dueSinceNanos = Long.MAX_VALUE;
    }
}
