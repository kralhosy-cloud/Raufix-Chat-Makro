package dev.chatmacro.studio.testing;

import dev.chatmacro.studio.Studio;
import dev.chatmacro.studio.dispatch.RecordingDispatchAdapter;
import dev.chatmacro.studio.model.Macro;
import dev.chatmacro.studio.model.MacroContentType;
import dev.chatmacro.studio.model.Profile;
import dev.chatmacro.studio.model.ServerScope;
import dev.chatmacro.studio.model.SessionInfo;
import dev.chatmacro.studio.repo.RepoResult;
import dev.chatmacro.studio.sched.Clock;

import java.util.List;

/**
 * The regression the brief asks for by name: profiles must be genuinely
 * separate. Editing, enabling or deleting in one profile must never touch a
 * same-named record in another.
 */
public final class IsolationRegressionTests {

    private static final SessionInfo MP = SessionInfo.multiplayer("play.example.net", 1L);

    private IsolationRegressionTests() {
    }

    public static void register(TestRunner r) {

        r.test("isolation", "disabling a macro in one profile does not touch a same-named macro elsewhere", () -> {
            TestStudio t = new TestStudio();
            Profile first = t.studio.createProfile("First", "").value();
            Profile second = t.studio.createProfile("Second", "").value();
            t.studio.createMacro(first, "greet", MacroContentType.CHAT_MESSAGE, "hi from first", 30, 0);
            t.studio.createMacro(second, "greet", MacroContentType.CHAT_MESSAGE, "hi from second", 30, 0);

            Macro firstMacro = t.studio.macros().forProfile(first).get(0);
            Macro secondMacro = t.studio.macros().forProfile(second).get(0);
            Assert.ne(firstMacro.id(), secondMacro.id(), "distinct identities");

            RepoResult<Macro> result = t.studio.setMacroEnabled(first, firstMacro.id(), false);
            Assert.isTrue(result.success(), "toggle applied");

            Macro reloadedFirst = t.studio.macros().find(firstMacro.id()).orElseThrow();
            Macro reloadedSecond = t.studio.macros().find(secondMacro.id()).orElseThrow();
            Assert.isFalse(reloadedFirst.enabled(), "the targeted macro changed");
            Assert.isFalse(reloadedSecond.enabled(), "macros are created disabled...");
            // ...so enable the other one explicitly and prove it survives.
            t.studio.setMacroEnabled(second, secondMacro.id(), true);
            t.studio.setMacroEnabled(first, firstMacro.id(), false);
            Assert.isTrue(t.studio.macros().find(secondMacro.id()).orElseThrow().enabled(),
                    "the other profile's macro keeps its own state");
            Assert.eq("hi from second",
                    t.studio.macros().find(secondMacro.id()).orElseThrow().content(), "content untouched");
        });

        r.test("isolation", "editing a macro cannot re-parent it", () -> {
            TestStudio t = new TestStudio();
            Profile first = t.studio.createProfile("First", "").value();
            Profile second = t.studio.createProfile("Second", "").value();
            t.studio.createMacro(first, "greet", MacroContentType.CHAT_MESSAGE, "hi", 30, 0);
            Macro macro = t.studio.macros().forProfile(first).get(0);

            RepoResult<Macro> wrong = t.studio.updateMacro(second, macro.id(), "greet",
                    MacroContentType.CHAT_MESSAGE, "hacked", 30, 0);
            Assert.isFalse(wrong.success(), "cross-profile edit refused");
            Assert.eq("error.macroWrongProfile", wrong.messageKeyOrEmpty(), "reason");
            Assert.eq("hi", t.studio.macros().find(macro.id()).orElseThrow().content(), "content unchanged");
            Assert.eq(first.id(), t.studio.macros().find(macro.id()).orElseThrow().profileId(), "parent unchanged");
        });

        r.test("isolation", "deleting a profile removes only its own macros", () -> {
            TestStudio t = new TestStudio();
            Profile keep = t.studio.createProfile("Keep", "").value();
            Profile drop = t.studio.createProfile("Drop", "").value();
            t.studio.createMacro(keep, "a", MacroContentType.CHAT_MESSAGE, "keep me", 30, 0);
            t.studio.createMacro(keep, "b", MacroContentType.CHAT_MESSAGE, "keep me too", 30, 0);
            t.studio.createMacro(drop, "c", MacroContentType.CHAT_MESSAGE, "drop me", 30, 0);

            Assert.isTrue(t.studio.deleteProfile(drop.id()).success(), "profile deleted");
            Assert.eq(1, t.studio.profiles().count(), "one profile left");
            Assert.eq(2, t.studio.macros().forProfile(keep).size(), "the other profile kept both macros");
            Assert.eq(2, t.studio.data().document().macros().size(), "no stray macro records");
        });

        r.test("isolation", "duplicating a profile copies macros with new ids, all disabled", () -> {
            TestStudio t = new TestStudio();
            Profile original = t.studio.createProfile("Original", "").value();
            t.studio.createMacro(original, "a", MacroContentType.CHAT_MESSAGE, "hi", 30, 0);
            Macro sourceMacro = t.studio.macros().forProfile(original).get(0);
            t.studio.setMacroEnabled(original, sourceMacro.id(), true);

            Profile copy = t.studio.duplicateProfile(original.id(), null).value();
            Assert.ne(original.id(), copy.id(), "new profile id");
            Assert.eq(original.name() + " (copy)", copy.name(), "default copy name");
            List<Macro> copies = t.studio.macros().forProfile(copy);
            Assert.eq(1, copies.size(), "macro copied");
            Assert.ne(sourceMacro.id(), copies.get(0).id(), "new macro id");
            Assert.isFalse(copies.get(0).enabled(), "copies start disabled");
            Assert.isTrue(t.studio.macros().find(sourceMacro.id()).orElseThrow().enabled(),
                    "the original stays enabled");
        });

        r.test("isolation", "renaming a profile keeps its macros", () -> {
            TestStudio t = new TestStudio();
            Profile profile = t.studio.createProfile("Before", "").value();
            t.studio.createMacro(profile, "a", MacroContentType.CHAT_MESSAGE, "hi", 30, 0);
            Assert.isTrue(t.studio.renameProfile(profile.id(), "After").success(), "renamed");
            Profile renamed = t.studio.profiles().find(profile.id()).orElseThrow();
            Assert.eq("After", renamed.name(), "new name");
            Assert.eq(profile.id(), renamed.id(), "identity kept");
            Assert.eq(1, t.studio.macros().forProfile(renamed).size(), "macros kept");
        });

        r.test("isolation", "only the active profile can send", () -> {
            TestStudio t = new TestStudio();
            Profile first = t.studio.createProfile("First", "").value();
            Profile second = t.studio.createProfile("Second", "").value();
            t.studio.createMacro(first, "a", MacroContentType.CHAT_MESSAGE, "from first", 5, 0);
            t.studio.createMacro(second, "b", MacroContentType.CHAT_MESSAGE, "from second", 5, 0);
            Macro firstMacro = t.studio.macros().forProfile(first).get(0);
            Macro secondMacro = t.studio.macros().forProfile(second).get(0);
            t.studio.setMacroEnabled(first, firstMacro.id(), true);
            t.studio.setMacroEnabled(second, secondMacro.id(), true);

            t.studio.selectProfile(first.id());
            t.studio.updateSession(MP);
            t.studio.startRun();
            for (int i = 0; i < 4; i++) {
                t.clock.advanceMillis(5000);
                t.studio.tick(MP);
            }
            int firstSends = t.adapter.callCountFor(firstMacro.id());
            Assert.gt(firstSends, 0, "the active profile sent");
            Assert.eq(0, t.adapter.callCountFor(secondMacro.id()), "the inactive profile stayed silent");

            t.studio.selectProfile(second.id());
            Assert.isFalse(t.studio.scheduler().isRunning(), "selecting a profile does not start sending");
            for (int i = 0; i < 4; i++) {
                t.clock.advanceMillis(5000);
                t.studio.tick(MP);
            }
            Assert.eq(firstSends, t.adapter.callCountFor(firstMacro.id()),
                    "the old profile stays silent after the switch");
        });

        r.test("isolation", "the default run needs an explicit start", () -> {
            TestStudio t = new TestStudio();
            Profile profile = t.studio.createProfile("Main", "").value();
            t.studio.createMacro(profile, "a", MacroContentType.CHAT_MESSAGE, "hi", 5, 0);
            t.studio.setMacroEnabled(profile, t.studio.macros().forProfile(profile).get(0).id(), true);
            t.studio.selectProfile(profile.id());
            t.studio.updateSession(MP);
            for (int i = 0; i < 20; i++) {
                t.clock.advanceMillis(1000);
                t.studio.tick(MP);
            }
            Assert.eq(0, t.adapter.callCount(), "joining a world sends nothing");
            Assert.isFalse(t.studio.scheduler().isRunning(), "the run is not active");
        });

        r.test("isolation", "auto start on join is opt-in per profile", () -> {
            TestStudio t = new TestStudio();
            Profile profile = t.studio.createProfile("Main", "").value();
            t.studio.createMacro(profile, "a", MacroContentType.CHAT_MESSAGE, "hi", 5, 0);
            t.studio.setMacroEnabled(profile, t.studio.macros().forProfile(profile).get(0).id(), true);
            t.studio.selectProfile(profile.id());
            t.studio.setProfileAutoStart(profile.id(), true);

            t.studio.updateSession(MP);
            Assert.isTrue(t.studio.scheduler().isRunning(), "opt-in profile starts on join");
            Assert.eq(1, t.studio.tick(MP).events().size(), "and sends");
        });

        r.test("isolation", "leaving the world stops the run", () -> {
            TestStudio t = new TestStudio();
            Profile profile = t.studio.createProfile("Main", "").value();
            t.studio.createMacro(profile, "a", MacroContentType.CHAT_MESSAGE, "hi", 5, 0);
            t.studio.setMacroEnabled(profile, t.studio.macros().forProfile(profile).get(0).id(), true);
            t.studio.selectProfile(profile.id());
            t.studio.updateSession(MP);
            t.studio.startRun();
            t.studio.tick(MP);

            t.studio.tick(SessionInfo.none(2L));
            Assert.isFalse(t.studio.scheduler().isRunning(), "run stopped after disconnect");
            for (int i = 0; i < 10; i++) {
                t.clock.advanceMillis(5000);
                t.studio.tick(SessionInfo.none(2L));
            }
            Assert.eq(1, t.adapter.callCount(), "no sends after leaving");
        });

        r.test("isolation", "profile scope decides where it may run", () -> {
            TestStudio t = new TestStudio();
            Profile profile = t.studio.createProfile("Home", "").value();
            t.studio.createMacro(profile, "a", MacroContentType.CHAT_MESSAGE, "hi", 5, 0);
            t.studio.setMacroEnabled(profile, t.studio.macros().forProfile(profile).get(0).id(), true);
            t.studio.updateProfileScope(profile.id(), ServerScope.specificServer("home.example.net"));
            t.studio.selectProfile(profile.id());
            t.studio.updateSession(MP);
            t.studio.startRun();

            for (int i = 0; i < 5; i++) {
                t.clock.advanceMillis(5000);
                t.studio.tick(MP);
            }
            Assert.eq(0, t.adapter.callCount(), "not sent on a server outside the scope");

            SessionInfo home = SessionInfo.multiplayer("home.example.net", 2L);
            t.studio.updateSession(home);
            t.studio.startRun();
            Assert.eq(1, t.studio.tick(home).events().size(), "sent on the scoped server");
        });

        r.test("isolation", "reloading keeps the selected profile and stays passive", () -> {
            TestStudio t = new TestStudio();
            Profile profile = t.studio.createProfile("Main", "").value();
            t.studio.createMacro(profile, "a", MacroContentType.CHAT_MESSAGE, "hi", 30, 0);
            t.studio.selectProfile(profile.id());
            t.studio.saveNow();

            Studio reloaded = new Studio(t.store, new Clock.Fake(), new RecordingDispatchAdapter());
            Assert.eq(profile.id().toString(), reloaded.data().document().selectedProfileId(),
                    "selection persisted");
            Assert.isFalse(reloaded.scheduler().isRunning(), "reload does not resume sending");
        });
    }
}
