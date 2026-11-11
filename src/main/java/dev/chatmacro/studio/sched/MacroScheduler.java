package dev.chatmacro.studio.sched;

import dev.chatmacro.studio.dispatch.ChatDispatchAdapter;
import dev.chatmacro.studio.dispatch.DispatchOutcome;
import dev.chatmacro.studio.dispatch.DispatchResult;
import dev.chatmacro.studio.model.Macro;
import dev.chatmacro.studio.model.Profile;
import dev.chatmacro.studio.model.ServerScope;
import dev.chatmacro.studio.model.SessionInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One scheduler for the whole mod, driven by the client tick.
 *
 * <p>Design points that matter for safety, all covered by tests:</p>
 * <ul>
 *   <li>Time is monotonic; a wall-clock jump or a low tick rate can never
 *       release a burst.</li>
 *   <li>Catch-up drops missed repeats: after a stall, exactly one send happens
 *       and the next slot is {@code now + interval}.</li>
 *   <li>One global budget caps the combined rate of every enabled macro.</li>
 *   <li>Fair ordering by "waiting since" means no macro starves.</li>
 *   <li>A stale session generation is a hard stop, so a callback captured before
 *       a disconnect cannot send into the next server.</li>
 *   <li>Switching profile deactivates the old profile's timers completely.</li>
 * </ul>
 */
public final class MacroScheduler {

    /** Notified about real dispatches and about session stops. */
    public interface Listener {
        default void onDispatch(Macro macro, DispatchResult result) { }
        default void onSessionStopped(String reasonKey) { }
        default void onMacroStopped(UUID macroId, String reasonKey) { }
    }

    private final Clock clock;
    private final ChatDispatchAdapter adapter;
    private final List<Listener> listeners = new ArrayList<>();

    private SchedulerPolicy policy = SchedulerPolicy.defaults();

    private final Map<UUID, Macro> macrosById = new LinkedHashMap<>();
    private final Map<UUID, MacroRuntime> runtimes = new LinkedHashMap<>();
    private final List<UUID> order = new ArrayList<>();

    private UUID activeProfileId;
    private ServerScope activeScope = ServerScope.everywhere();
    private boolean running;
    private boolean pausedByUser;
    private long sessionGeneration = Long.MIN_VALUE;
    private long lastDispatchNanos = Long.MIN_VALUE;
    private long totalDispatches;
    private String holdReasonKey = "";
    private String stopReasonKey = "";

    public MacroScheduler(Clock clock, ChatDispatchAdapter adapter) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void setPolicy(SchedulerPolicy newPolicy) {
        this.policy = Objects.requireNonNull(newPolicy);
    }

    public SchedulerPolicy policy() {
        return policy;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isPausedByUser() {
        return pausedByUser;
    }

    public UUID activeProfileId() {
        return activeProfileId;
    }

    public long totalDispatches() {
        return totalDispatches;
    }

    public String holdReasonKey() {
        return holdReasonKey;
    }

    public String stopReasonKey() {
        return stopReasonKey;
    }

    // ------------------------------------------------------------- profile

    /**
     * Selects a profile. Selecting never starts sending: the new profile is
     * passive until the player presses start, and the previous profile's timers
     * are deactivated rather than merely hidden.
     */
    public void selectProfile(Profile profile, List<Macro> profileMacros) {
        Objects.requireNonNull(profile, "profile");
        boolean wasRunning = running;
        deactivateAll();
        activeProfileId = profile.id();
        activeScope = profile.scope();
        macrosById.clear();
        order.clear();
        for (Macro macro : profileMacros) {
            if (!macro.profileId().equals(profile.id())) {
                // A macro whose parent does not match the profile it is listed
                // under is data corruption; it is skipped, not re-parented.
                continue;
            }
            macrosById.put(macro.id(), macro);
            runtimes.computeIfAbsent(macro.id(), MacroRuntime::new).deactivate();
            order.add(macro.id());
        }
        running = false;
        pausedByUser = false;
        stopReasonKey = wasRunning ? "session.profileChanged" : "";
        holdReasonKey = "";
    }

    /** Clears the active profile (last profile deleted, or nothing selected). */
    public void clearProfile() {
        deactivateAll();
        activeProfileId = null;
        activeScope = ServerScope.everywhere();
        macrosById.clear();
        order.clear();
        running = false;
    }

    /**
     * Re-syncs after an edit. Runtimes whose cadence did not change keep their
     * place in the schedule; anything that changed is re-armed, so the scheduler
     * can never keep running with a stale interval.
     */
    public void updateMacros(List<Macro> profileMacros) {
        Map<UUID, Macro> next = new LinkedHashMap<>();
        for (Macro macro : profileMacros) {
            if (activeProfileId != null && !macro.profileId().equals(activeProfileId)) continue;
            next.put(macro.id(), macro);
        }
        // Removed or re-parented macros lose their runtime entirely, including
        // any pending work.
        List<UUID> removed = new ArrayList<>(macrosById.keySet());
        removed.removeAll(next.keySet());
        for (UUID id : removed) {
            MacroRuntime runtime = runtimes.remove(id);
            if (runtime != null) runtime.deactivate();
        }
        List<UUID> newOrder = new ArrayList<>();
        for (Macro macro : profileMacros) {
            if (!next.containsKey(macro.id())) continue;
            Macro previous = macrosById.get(macro.id());
            MacroRuntime runtime = runtimes.computeIfAbsent(macro.id(), MacroRuntime::new);
            if (!macro.enabled()) {
                runtime.deactivate();
            } else if (previous == null || cadenceChanged(previous, macro)) {
                if (running) arm(runtime, macro);
            } else if (running && runtime.state() == MacroRuntime.State.INACTIVE) {
                arm(runtime, macro);
            }
            newOrder.add(macro.id());
        }
        macrosById.clear();
        macrosById.putAll(next);
        order.clear();
        order.addAll(newOrder);
    }

    private static boolean cadenceChanged(Macro before, Macro after) {
        return before.intervalSeconds() != after.intervalSeconds()
                || before.initialDelaySeconds() != after.initialDelaySeconds();
    }

    // --------------------------------------------------------- run session

    /**
     * Explicit start. There is no automatic start: opening the game, loading the
     * mod or selecting a profile never sends anything.
     */
    public void start(SessionInfo session) {
        Objects.requireNonNull(session, "session");
        running = true;
        pausedByUser = false;
        stopReasonKey = "";
        // Only a real session can own the run. Starting from the main menu arms
        // the session but binds nothing until the client reports a join.
        sessionGeneration = session.inGame() ? session.generation() : Long.MIN_VALUE;
        lastDispatchNanos = Long.MIN_VALUE;
        for (UUID id : order) {
            Macro macro = macrosById.get(id);
            MacroRuntime runtime = runtimes.computeIfAbsent(id, MacroRuntime::new);
            if (macro != null && macro.enabled()) {
                arm(runtime, macro);
            } else {
                runtime.deactivate();
            }
        }
    }

    /**
     * Binds the run to a freshly joined session and re-arms every enabled macro
     * from "now". Nothing is sent as a side effect, and a reconnect never
     * replays what was missed.
     */
    public void bindSession(SessionInfo session) {
        Objects.requireNonNull(session, "session");
        sessionGeneration = session.generation();
        if (!running || !session.inGame()) return;
        for (UUID id : order) {
            Macro macro = macrosById.get(id);
            MacroRuntime runtime = runtimes.computeIfAbsent(id, MacroRuntime::new);
            if (macro != null && macro.enabled()) arm(runtime, macro);
            else runtime.deactivate();
        }
    }

    /** Stops everything and cancels pending work. Already-sent text is not undone. */
    public void stop(String reasonKey) {
        if (!running) {
            stopReasonKey = reasonKey == null ? "" : reasonKey;
            return;
        }
        running = false;
        stopReasonKey = reasonKey == null ? "" : reasonKey;
        deactivateAll();
        for (Listener listener : listeners) listener.onSessionStopped(stopReasonKey);
    }

    public void pauseByUser() {
        pausedByUser = true;
    }

    public void resumeByUser() {
        pausedByUser = false;
        // Re-arm everything that is still enabled so the first send after a
        // resume is one full interval away, not immediate.
        if (running) {
            for (UUID id : order) {
                Macro macro = macrosById.get(id);
                MacroRuntime runtime = runtimes.computeIfAbsent(id, MacroRuntime::new);
                if (macro != null && macro.enabled()) {
                    runtime.arm(clock.nanoTime(), Math.max(1, macro.intervalSeconds()) * 1_000_000_000L);
                }
            }
        }
    }

    private void deactivateAll() {
        for (MacroRuntime runtime : runtimes.values()) runtime.deactivate();
    }

    private void arm(MacroRuntime runtime, Macro macro) {
        long delayNanos = Math.max(0, macro.initialDelaySeconds()) * 1_000_000_000L;
        runtime.arm(clock.nanoTime(), delayNanos);
    }

    // ---------------------------------------------------------------- tick

    /**
     * Called once per client tick. Returns a report; performs at most one
     * dispatch, and never dispatches when policy holds.
     */
    public TickReport tick(SessionInfo session) {
        long now = clock.nanoTime();
        TickReport report = new TickReport(now);

        if (session == null) {
            if (running) stop("session.lost");
            holdReasonKey = "state.notInGame";
            report.setHoldReason(holdReasonKey);
            return report;
        }

        if (!running) {
            report.setHoldReason(stopReasonKey.isEmpty() ? "state.stopped" : stopReasonKey);
            return report;
        }

        if (!session.inGame()) {
            // Title screen, disconnect screen, loading. Hold - do not stop, so
            // an in-game start survives a short loading pause, and never send.
            holdReasonKey = "state.notInGame";
            for (MacroRuntime runtime : runtimes.values()) runtime.hold(holdReasonKey);
            report.setHoldReason(holdReasonKey);
            return report;
        }

        if (session.generation() != sessionGeneration) {
            if (sessionGeneration == Long.MIN_VALUE) {
                bindSession(session);
            } else {
                // A different connection than the one the run was started for.
                stop("session.changed");
                report.setHoldReason("session.changed");
                return report;
            }
        }

        String hold = evaluatePolicy(session);
        if (hold != null) {
            holdReasonKey = hold;
            for (MacroRuntime runtime : runtimes.values()) runtime.hold(hold);
            report.setHoldReason(hold);
            return report;
        }
        holdReasonKey = "";
        for (MacroRuntime runtime : runtimes.values()) runtime.releaseHold();

        // Mark everything that is due, so fairness can use "waiting since".
        List<MacroRuntime> due = new ArrayList<>();
        for (UUID id : order) {
            MacroRuntime runtime = runtimes.get(id);
            if (runtime == null) continue;
            if (runtime.isDue(now)) {
                runtime.markDue(now);
                due.add(runtime);
            }
        }
        report.setDueCount(due.size());
        if (due.isEmpty()) return report;

        // Fairness: longest waiting first; ties fall back to list order.
        MacroRuntime chosen = null;
        for (MacroRuntime runtime : due) {
            if (chosen == null || runtime.dueSinceNanos() < chosen.dueSinceNanos()) chosen = runtime;
        }
        Macro macro = macrosById.get(chosen.macroId());
        if (macro == null) {
            chosen.deactivate();
            return report;
        }

        long gapNanos = policy.globalMinDispatchGapMillis() * 1_000_000L;
        if (lastDispatchNanos != Long.MIN_VALUE && now - lastDispatchNanos < gapNanos) {
            // Global budget: hold the due macro (keeping dueSince) rather than
            // dropping it, so it is still first in line next time.
            report.addBudgetBlocked();
            return report;
        }

        DispatchResult result = send(macro, session, now);
        report.addEvent(macro.id(), macro.name(), result);
        for (Listener listener : listeners) listener.onDispatch(macro, result);

        MacroRuntime runtime = runtimes.get(macro.id());
        if (runtime == null) return report;

        if (result.succeeded()) {
            lastDispatchNanos = now;
            totalDispatches++;
            runtime.markDispatched(now, Math.max(1, macro.intervalSeconds()) * 1_000_000_000L);
            return report;
        }

        if (result.isHardStop()) {
            stop(result.detailKey().isEmpty() ? "dispatch.hardStop" : result.detailKey());
            return report;
        }

        int failures = runtime.consecutiveFailures() + 1;
        if (failures >= policy.maxConsecutiveFailures()) {
            runtime.fail(now, 0, result.outcome(), result.detailKey());
            runtime.stop();
            for (Listener listener : listeners) listener.onMacroStopped(macro.id(), "dispatch.tooManyFailures");
            return report;
        }
        long backoffNanos = policy.backoffSecondsFor(failures) * 1_000_000_000L;
        runtime.fail(now, backoffNanos, result.outcome(), result.detailKey());
        return report;
    }

    /**
     * Policy gate. Returns null when sending is allowed, otherwise a
     * localisation key describing why it is being held.
     */
    private String evaluatePolicy(SessionInfo session) {
        if (!session.canSend()) return "state.playerNotReady";
        if (!activeScope.matches(session)) return "state.scopeMismatch";
        if (pausedByUser) return "state.paused";
        if (policy.holdWhenChatOpen() && session.chatScreenOpen()) return "state.chatOpen";
        if (policy.pauseWhenGamePaused() && session.gamePaused()) return "state.gamePaused";
        if (policy.pauseOnFocusLoss() && session.focusLost()) return "state.focusLost";
        return null;
    }

    private DispatchResult send(Macro macro, SessionInfo session, long nowNanos) {
        Objects.requireNonNull(nowNanos);
        if (!session.canSend()) {
            return DispatchResult.of(DispatchOutcome.NOT_IN_GAME, "dispatch.notInGame");
        }
        if (macro.content().isEmpty()) {
            return DispatchResult.of(DispatchOutcome.INVALID_CONTENT, "dispatch.invalidContent");
        }
        try {
            DispatchResult result = adapter.dispatch(macro, session, sessionGeneration);
            return result == null
                    ? DispatchResult.of(DispatchOutcome.FAILED, "dispatch.adapterReturnedNull")
                    : result;
        } catch (RuntimeException e) {
            // No detail is attached on purpose: an exception message can contain
            // the text that was being sent, and that never reaches a log.
            return DispatchResult.of(DispatchOutcome.FAILED, "dispatch.exception");
        }
    }

    /**
     * "Try once" from the UI. This is a real send: it goes through the same
     * validation, the same global budget and the same session checks as a
     * scheduled send. It is not a decorative preview.
     */
    public DispatchResult dispatchOnce(Macro macro, SessionInfo session) {
        Objects.requireNonNull(macro, "macro");
        if (session == null || !session.canSend()) {
            return DispatchResult.of(DispatchOutcome.NOT_IN_GAME, "dispatch.notInGame");
        }
        long now = clock.nanoTime();
        long gapNanos = policy.globalMinDispatchGapMillis() * 1_000_000L;
        if (lastDispatchNanos != Long.MIN_VALUE && now - lastDispatchNanos < gapNanos) {
            return DispatchResult.of(DispatchOutcome.BUDGET_BLOCKED, "dispatch.budgetBlocked");
        }
        DispatchResult result = send(macro, session, now);
        if (result.succeeded()) {
            lastDispatchNanos = now;
            totalDispatches++;
        }
        return result;
    }

    /** Milliseconds until the next scheduled attempt, or -1. */
    public long millisUntilNextDispatch() {
        long now = clock.nanoTime();
        long best = -1;
        for (UUID id : order) {
            MacroRuntime runtime = runtimes.get(id);
            if (runtime == null) continue;
            long millis = runtime.millisUntilNext(now);
            if (millis < 0) continue;
            if (best < 0 || millis < best) best = millis;
        }
        return best;
    }

    /** Read-only snapshot of the runtimes, in profile order. */
    public List<MacroRuntime> runtimeSnapshot() {
        List<MacroRuntime> out = new ArrayList<>(order.size());
        for (UUID id : order) {
            MacroRuntime runtime = runtimes.get(id);
            if (runtime != null) out.add(runtime);
        }
        return out;
    }

    public ServerScope activeScope() {
        return activeScope;
    }

    public long sessionGeneration() {
        return sessionGeneration;
    }
}
