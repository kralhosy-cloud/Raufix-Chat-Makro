package dev.chatmacro.studio.testing;

import dev.chatmacro.studio.dispatch.DispatchOutcome;
import dev.chatmacro.studio.dispatch.DispatchResult;
import dev.chatmacro.studio.dispatch.RecordingDispatchAdapter;
import dev.chatmacro.studio.model.Macro;
import dev.chatmacro.studio.model.MacroContentType;
import dev.chatmacro.studio.model.Profile;
import dev.chatmacro.studio.model.ServerScope;
import dev.chatmacro.studio.model.SessionInfo;
import dev.chatmacro.studio.sched.Clock;
import dev.chatmacro.studio.sched.MacroRuntime;
import dev.chatmacro.studio.sched.MacroScheduler;
import dev.chatmacro.studio.sched.SchedulerPolicy;
import dev.chatmacro.studio.sched.TickReport;

import java.util.List;
import java.util.UUID;

/**
 * Deterministic scheduler tests: a fake clock, a recording adapter, no sleeping
 * and no network.
 */
public final class SchedulerTests {

    private static final SessionInfo MP = SessionInfo.multiplayer("play.example.net", 1L);

    private SchedulerTests() {
    }

    private record Harness(Clock.Fake clock, RecordingDispatchAdapter adapter, MacroScheduler scheduler) {
    }

    private static Harness harness() {
        return harness(SchedulerPolicy.defaults());
    }

    private static Harness harness(SchedulerPolicy policy) {
        Clock.Fake clock = new Clock.Fake();
        RecordingDispatchAdapter adapter = new RecordingDispatchAdapter();
        MacroScheduler scheduler = new MacroScheduler(clock, adapter);
        scheduler.setPolicy(policy);
        return new Harness(clock, adapter, scheduler);
    }

    /**
     * Creates a profile and selects it. With an empty macro list it returns a
     * fresh id that callers can use to build macros; called again with those
     * macros it reuses their profile id so the parent link is correct.
     */
    private static UUID select(MacroScheduler scheduler, String profileName, List<Macro> macros) {
        UUID profileId = macros.isEmpty() ? UUID.randomUUID() : macros.get(0).profileId();
        scheduler.selectProfile(Profile.create(profileId, profileName, 0L), macros);
        return profileId;
    }

    private static Macro macro(UUID profileId, String name, long intervalSeconds,
                               long initialDelaySeconds, boolean enabled) {
        return new Macro(UUID.randomUUID(), profileId, name, MacroContentType.CHAT_MESSAGE, "hello " + name,
                intervalSeconds, initialDelaySeconds, enabled, 0L, 0L);
    }

    public static void register(TestRunner r) {

        r.test("scheduler", "never sends before the user presses start", () -> {
            Harness h = harness();
            UUID pid = select(h.scheduler(), "Main", List.of());
            select(h.scheduler(), "Main", List.of(macro(pid, "a", 5, 0, true)));
            for (int i = 0; i < 20; i++) {
                h.clock().advanceMillis(1000);
                h.scheduler().tick(MP);
            }
            Assert.eq(0, h.adapter().callCount(), "nothing sent while stopped");
        });

        r.test("scheduler", "sends immediately on start and then on the interval", () -> {
            Harness h = harness();
            UUID pid = select(h.scheduler(), "Main", List.of());
            Macro m = macro(pid, "a", 10, 0, true);
            select(h.scheduler(), "Main", List.of(m));
            h.scheduler().start(MP);

            Assert.eq(1, h.scheduler().tick(MP).events().size(), "first send at start");
            for (int i = 0; i < 9; i++) {
                h.clock().advanceMillis(1000);
                Assert.eq(0, h.scheduler().tick(MP).events().size(), "quiet before the interval");
            }
            h.clock().advanceMillis(1000);
            Assert.eq(1, h.scheduler().tick(MP).events().size(), "second send at 10s");
            Assert.eq(2, h.adapter().callCount(), "exactly two sends");
        });

        r.test("scheduler", "honours the initial delay", () -> {
            Harness h = harness();
            UUID pid = select(h.scheduler(), "Main", List.of());
            select(h.scheduler(), "Main", List.of(macro(pid, "a", 10, 5, true)));
            h.scheduler().start(MP);
            for (int i = 0; i < 5; i++) {
                Assert.eq(0, h.scheduler().tick(MP).events().size(), "quiet during the delay");
                h.clock().advanceMillis(1000);
            }
            Assert.eq(1, h.scheduler().tick(MP).events().size(), "sends after the delay");
        });

        r.test("scheduler", "disabled macros never send", () -> {
            Harness h = harness();
            UUID pid = select(h.scheduler(), "Main", List.of());
            select(h.scheduler(), "Main", List.of(macro(pid, "a", 5, 0, false)));
            h.scheduler().start(MP);
            for (int i = 0; i < 10; i++) {
                h.clock().advanceMillis(1000);
                h.scheduler().tick(MP);
            }
            Assert.eq(0, h.adapter().callCount(), "disabled macro is silent");
        });

        r.test("scheduler", "the global budget caps the combined rate", () -> {
            Harness h = harness(SchedulerPolicy.defaults().withGlobalGapMillis(3000));
            UUID pid = select(h.scheduler(), "Main", List.of());
            Macro a = macro(pid, "a", 10, 0, true);
            Macro b = macro(pid, "b", 10, 0, true);
            select(h.scheduler(), "Main", List.of(a, b));
            h.scheduler().start(MP);

            Assert.eq(1, h.scheduler().tick(MP).events().size(), "one send at t=0");
            TickReport blocked = h.scheduler().tick(MP);
            Assert.eq(0, blocked.events().size(), "second send blocked");
            Assert.eq(1, blocked.budgetBlocked(), "reported as budget-blocked, not dropped");

            h.clock().advanceMillis(3000);
            Assert.eq(1, h.scheduler().tick(MP).events().size(), "second macro sends once the gap passed");
            Assert.eq(2, h.adapter().callCount(), "two sends total");
        });

        r.test("scheduler", "fair ordering: the longest waiting macro goes first", () -> {
            Harness h = harness(SchedulerPolicy.defaults().withGlobalGapMillis(1000));
            UUID pid = select(h.scheduler(), "Main", List.of());
            Macro fast = macro(pid, "fast", 2, 0, true);
            Macro slowA = macro(pid, "slowA", 60, 0, true);
            Macro slowB = macro(pid, "slowB", 60, 0, true);
            select(h.scheduler(), "Main", List.of(fast, slowA, slowB));
            h.scheduler().start(MP);

            h.scheduler().tick(MP);                       // fast
            h.clock().advanceMillis(1000);
            h.scheduler().tick(MP);                       // slowA
            h.clock().advanceMillis(1000);
            // fast is due again, but slowB has been waiting since t=0.
            h.scheduler().tick(MP);

            Assert.eq(1, h.adapter().callCountFor(fast.id()), "fast sent once");
            Assert.eq(1, h.adapter().callCountFor(slowA.id()), "slowA sent once");
            Assert.eq(1, h.adapter().callCountFor(slowB.id()), "slowB was not starved");
        });

        r.test("scheduler", "catch-up drops missed repeats instead of bursting", () -> {
            Harness h = harness();
            UUID pid = select(h.scheduler(), "Main", List.of());
            select(h.scheduler(), "Main", List.of(macro(pid, "a", 10, 0, true)));
            h.scheduler().start(MP);
            h.scheduler().tick(MP);

            // Simulate a long stall (suspend, alt-tab, low tick rate).
            h.clock().advanceMillis(100_000);
            Assert.eq(1, h.scheduler().tick(MP).events().size(), "exactly one send after the stall");
            Assert.eq(2, h.adapter().callCount(), "no replay of the ten missed slots");
        });

        r.test("scheduler", "a wall-clock jump does not release queued messages", () -> {
            Harness h = harness();
            UUID pid = select(h.scheduler(), "Main", List.of());
            select(h.scheduler(), "Main", List.of(macro(pid, "a", 10, 0, true)));
            h.scheduler().start(MP);
            h.scheduler().tick(MP);

            h.clock().jumpWallClock(3_600_000);   // user changed the system clock
            Assert.eq(0, h.scheduler().tick(MP).events().size(), "monotonic time still rules");
            h.clock().advanceMillis(500);
            Assert.eq(0, h.scheduler().tick(MP).events().size(), "still waiting");
        });

        r.test("scheduler", "ticking twice at the same instant does not double-send", () -> {
            Harness h = harness(SchedulerPolicy.defaults().withGlobalGapMillis(0));
            UUID pid = select(h.scheduler(), "Main", List.of());
            select(h.scheduler(), "Main", List.of(macro(pid, "a", 10, 0, true)));
            h.scheduler().start(MP);
            Assert.eq(1, h.scheduler().tick(MP).events().size(), "first tick sends");
            Assert.eq(0, h.scheduler().tick(MP).events().size(), "second tick at the same instant does not");
            Assert.eq(1, h.adapter().callCount(), "one dispatch");
        });

        r.test("scheduler", "an interval edit takes effect immediately", () -> {
            Harness h = harness();
            UUID pid = select(h.scheduler(), "Main", List.of());
            Macro m = macro(pid, "a", 60, 0, true);
            select(h.scheduler(), "Main", List.of(m));
            h.scheduler().start(MP);
            h.scheduler().tick(MP);

            Macro shortened = m.withInterval(5, 0L);
            h.scheduler().updateMacros(List.of(shortened));
            for (int i = 0; i < 5; i++) {
                h.clock().advanceMillis(1000);
                h.scheduler().tick(MP);
            }
            Assert.eq(2, h.adapter().callCount(), "the new interval is used, not the old one");
        });

        r.test("scheduler", "disabling a macro cancels its pending work", () -> {
            Harness h = harness();
            UUID pid = select(h.scheduler(), "Main", List.of());
            Macro m = macro(pid, "a", 5, 0, true);
            select(h.scheduler(), "Main", List.of(m));
            h.scheduler().start(MP);
            h.scheduler().tick(MP);

            h.clock().advanceMillis(3000);
            h.scheduler().updateMacros(List.of(m.withEnabled(false, 0L)));
            h.clock().advanceMillis(10_000);
            Assert.eq(0, h.scheduler().tick(MP).events().size(), "nothing pending after disable");
            Assert.eq(1, h.adapter().callCount(), "only the pre-disable send happened");
        });

        r.test("scheduler", "pause holds and resume waits a full interval", () -> {
            Harness h = harness();
            UUID pid = select(h.scheduler(), "Main", List.of());
            select(h.scheduler(), "Main", List.of(macro(pid, "a", 5, 0, true)));
            h.scheduler().start(MP);
            h.scheduler().tick(MP);

            h.scheduler().pauseByUser();
            h.clock().advanceMillis(20_000);
            TickReport held = h.scheduler().tick(MP);
            Assert.eq(0, held.events().size(), "paused, nothing sent");
            Assert.eq("state.paused", held.holdReasonKey(), "reason reported");

            h.scheduler().resumeByUser();
            Assert.eq(0, h.scheduler().tick(MP).events().size(), "resume does not fire immediately");
            h.clock().advanceMillis(5000);
            Assert.eq(1, h.scheduler().tick(MP).events().size(), "sends one interval after resume");
        });

        r.test("scheduler", "an open chat screen holds dispatch", () -> {
            Harness h = harness();
            UUID pid = select(h.scheduler(), "Main", List.of());
            select(h.scheduler(), "Main", List.of(macro(pid, "a", 5, 0, true)));
            h.scheduler().start(MP);
            h.scheduler().tick(MP);
            h.clock().advanceMillis(5000);
            TickReport held = h.scheduler().tick(MP.withChatScreenOpen(true));
            Assert.eq(0, held.events().size(), "held while typing");
            Assert.eq("state.chatOpen", held.holdReasonKey(), "reason reported");
        });

        r.test("scheduler", "a paused game holds dispatch", () -> {
            Harness h = harness();
            UUID pid = select(h.scheduler(), "Main", List.of());
            select(h.scheduler(), "Main", List.of(macro(pid, "a", 5, 0, true)));
            h.scheduler().start(MP);
            h.clock().advanceMillis(5000);
            TickReport held = h.scheduler().tick(SessionInfo.singleplayer("World", 1L).withPaused(true));
            Assert.eq(0, held.events().size(), "held while paused");
            Assert.eq("state.gamePaused", held.holdReasonKey(), "reason reported");
        });

        r.test("scheduler", "focus loss holds dispatch when configured", () -> {
            Harness h = harness();
            UUID pid = select(h.scheduler(), "Main", List.of());
            select(h.scheduler(), "Main", List.of(macro(pid, "a", 5, 0, true)));
            h.scheduler().start(MP);
            h.clock().advanceMillis(5000);
            TickReport held = h.scheduler().tick(MP.withFocusLost(true));
            Assert.eq(0, held.events().size(), "held after focus loss");
            Assert.eq("state.focusLost", held.holdReasonKey(), "reason reported");
        });

        r.test("scheduler", "a scope mismatch holds dispatch", () -> {
            Harness h = harness();
            UUID pid = UUID.randomUUID();
            Profile profile = new Profile(pid, "Only my server", "", List.of(),
                    ServerScope.specificServer("other.example.net"), 0L, 0L, 0L, false);
            Macro m = macro(pid, "a", 5, 0, true);
            h.scheduler().selectProfile(profile, List.of(m));
            h.scheduler().start(MP);
            TickReport held = h.scheduler().tick(MP);
            Assert.eq(0, held.events().size(), "not sent on a different server");
            Assert.eq("state.scopeMismatch", held.holdReasonKey(), "reason reported");
        });

        r.test("scheduler", "a matching scope allows dispatch", () -> {
            Harness h = harness();
            UUID pid = UUID.randomUUID();
            Profile profile = new Profile(pid, "My server", "", List.of(),
                    ServerScope.specificServer("PLAY.Example.NET:25565"), 0L, 0L, 0L, false);
            h.scheduler().selectProfile(profile, List.of(macro(pid, "a", 5, 0, true)));
            h.scheduler().start(SessionInfo.multiplayer("play.example.net:25565", 1L));
            Assert.eq(1, h.scheduler().tick(SessionInfo.multiplayer("play.example.net:25565", 1L))
                    .events().size(), "address match is case and scheme insensitive");
        });

        r.test("scheduler", "a stale session generation is a hard stop", () -> {
            Harness h = harness();
            UUID pid = select(h.scheduler(), "Main", List.of());
            select(h.scheduler(), "Main", List.of(macro(pid, "a", 5, 0, true)));
            h.scheduler().start(MP);
            Assert.eq(1, h.scheduler().tick(MP).events().size(), "sends in its own session");

            SessionInfo newServer = SessionInfo.multiplayer("other.example.net", 2L);
            TickReport report = h.scheduler().tick(newServer);
            Assert.eq(0, report.events().size(), "nothing sent into the new session");
            Assert.isFalse(h.scheduler().isRunning(), "run stopped");
            Assert.eq("session.changed", h.scheduler().stopReasonKey(), "stop reason");
        });

        r.test("scheduler", "binding a session re-arms without replaying", () -> {
            Harness h = harness();
            UUID pid = select(h.scheduler(), "Main", List.of());
            select(h.scheduler(), "Main", List.of(macro(pid, "a", 10, 0, true)));
            h.scheduler().start(SessionInfo.none(0L));
            Assert.eq(0, h.adapter().callCount(), "nothing sent at the title screen");

            h.scheduler().bindSession(MP);
            Assert.eq(1, h.scheduler().tick(MP).events().size(), "sends once in the world");
            h.clock().advanceMillis(60_000);
            Assert.eq(1, h.scheduler().tick(MP).events().size(), "still no burst");
            Assert.eq(2, h.adapter().callCount(), "two sends total");
        });

        r.test("scheduler", "not being in a game holds rather than sends", () -> {
            Harness h = harness();
            UUID pid = select(h.scheduler(), "Main", List.of());
            select(h.scheduler(), "Main", List.of(macro(pid, "a", 5, 0, true)));
            h.scheduler().start(MP);
            h.clock().advanceMillis(5000);
            TickReport held = h.scheduler().tick(SessionInfo.none(1L));
            Assert.eq(0, held.events().size(), "nothing sent outside a world");
            Assert.eq("state.notInGame", held.holdReasonKey(), "reason reported");
        });

        r.test("scheduler", "a client rejection stops the session instead of retrying", () -> {
            Harness h = harness();
            UUID pid = select(h.scheduler(), "Main", List.of());
            select(h.scheduler(), "Main", List.of(macro(pid, "a", 5, 0, true)));
            h.adapter().script(DispatchResult.of(DispatchOutcome.CLIENT_REJECTED, "dispatch.chatDisabled"));
            h.scheduler().start(MP);
            h.scheduler().tick(MP);
            Assert.isFalse(h.scheduler().isRunning(), "stopped after a hard rejection");
            for (int i = 0; i < 10; i++) {
                h.clock().advanceMillis(5000);
                h.scheduler().tick(MP);
            }
            Assert.eq(1, h.adapter().callCount(), "no retry storm");
        });

        r.test("scheduler", "transient failures back off and then stop that macro", () -> {
            Harness h = harness();
            UUID pid = select(h.scheduler(), "Main", List.of());
            Macro m = macro(pid, "a", 5, 0, true);
            select(h.scheduler(), "Main", List.of(m));
            h.adapter().script(DispatchResult.of(DispatchOutcome.FAILED, "dispatch.exception"));
            h.scheduler().start(MP);

            for (int i = 0; i < 40; i++) {
                h.clock().advanceMillis(10_000);
                h.scheduler().tick(MP);
            }
            Assert.eq(SchedulerPolicy.DEFAULT_MAX_FAILURES, h.adapter().callCount(),
                    "stops after the failure limit");
            MacroRuntime runtime = h.scheduler().runtimeSnapshot().get(0);
            Assert.isTrue(runtime.state() == MacroRuntime.State.STOPPED, "macro marked stopped");
        });

        r.test("scheduler", "an adapter exception never escapes the tick", () -> {
            Harness h = harness();
            UUID pid = select(h.scheduler(), "Main", List.of());
            select(h.scheduler(), "Main", List.of(macro(pid, "a", 5, 0, true)));
            h.adapter().throwOnDispatch(new IllegalStateException("simulated"));
            h.scheduler().start(MP);
            TickReport report = h.scheduler().tick(MP);
            Assert.eq(1, report.events().size(), "the failure is reported as an event");
            Assert.isTrue(report.events().get(0).result().outcome() == DispatchOutcome.FAILED, "outcome FAILED");
            Assert.eq("", report.events().get(0).result().detail(),
                    "no detail attached, so no message text can leak into a log");
        });

        r.test("scheduler", "switching profiles deactivates the old timers", () -> {
            Harness h = harness();
            UUID first = select(h.scheduler(), "First", List.of());
            Macro m1 = macro(first, "a", 5, 0, true);
            select(h.scheduler(), "First", List.of(m1));
            h.scheduler().start(MP);
            h.scheduler().tick(MP);

            UUID second = UUID.randomUUID();
            Profile profile2 = Profile.create(second, "Second", 0L);
            h.scheduler().selectProfile(profile2, List.of(macro(second, "b", 5, 0, true)));
            Assert.isFalse(h.scheduler().isRunning(), "the new profile starts passive");

            for (int i = 0; i < 10; i++) {
                h.clock().advanceMillis(5000);
                h.scheduler().tick(MP);
            }
            Assert.eq(1, h.adapter().callCountFor(m1.id()),
                    "only the send from before the switch, nothing after it");
        });

        r.test("scheduler", "try-once is a real send and obeys the budget", () -> {
            Harness h = harness();
            UUID pid = select(h.scheduler(), "Main", List.of());
            Macro m = macro(pid, "a", 60, 0, false);
            select(h.scheduler(), "Main", List.of(m));
            DispatchResult first = h.scheduler().dispatchOnce(m, MP);
            Assert.isTrue(first.succeeded(), "manual send works while stopped");
            DispatchResult second = h.scheduler().dispatchOnce(m, MP);
            Assert.isTrue(second.outcome() == DispatchOutcome.BUDGET_BLOCKED, "budget applies to manual sends");
            Assert.eq(1, h.adapter().callCount(), "only one real send");
        });

        r.test("scheduler", "next-due reporting follows the schedule", () -> {
            Harness h = harness();
            UUID pid = select(h.scheduler(), "Main", List.of());
            select(h.scheduler(), "Main", List.of(macro(pid, "a", 10, 0, true)));
            Assert.eq(-1, h.scheduler().millisUntilNextDispatch(), "nothing scheduled before start");
            h.scheduler().start(MP);
            h.scheduler().tick(MP);
            long millis = h.scheduler().millisUntilNextDispatch();
            Assert.isTrue(millis > 9000 && millis <= 10_000, "about one interval away, was " + millis);
        });

        r.test("scheduler", "policy backoff grows and is capped", () -> {
            SchedulerPolicy p = SchedulerPolicy.defaults();
            Assert.eq(2, p.backoffSecondsFor(1), "first backoff");
            Assert.eq(4, p.backoffSecondsFor(2), "second backoff");
            Assert.eq(8, p.backoffSecondsFor(3), "third backoff");
            Assert.eq(60, p.backoffSecondsFor(30), "capped");
            Assert.eq(0, p.backoffSecondsFor(0), "no failure, no backoff");
        });
    }
}
