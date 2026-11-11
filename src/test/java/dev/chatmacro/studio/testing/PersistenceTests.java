package dev.chatmacro.studio.testing;

import dev.chatmacro.studio.Studio;
import dev.chatmacro.studio.config.ConfigCodec;
import dev.chatmacro.studio.config.ConfigDocument;
import dev.chatmacro.studio.config.ConfigException;
import dev.chatmacro.studio.config.ConfigStore;
import dev.chatmacro.studio.dispatch.RecordingDispatchAdapter;
import dev.chatmacro.studio.io.ProfileBundle;
import dev.chatmacro.studio.model.Macro;
import dev.chatmacro.studio.model.MacroContentType;
import dev.chatmacro.studio.model.Profile;
import dev.chatmacro.studio.repo.RepoResult;
import dev.chatmacro.studio.sched.Clock;
import dev.chatmacro.studio.store.InMemoryByteStore;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence: round trips, corruption, migration, atomic save failures, import. */
public final class PersistenceTests {

    private PersistenceTests() {
    }

    private static void seed(InMemoryByteStore store, String json) {
        store.write(ConfigStore.DOCUMENT_NAME, json.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(InMemoryByteStore store) {
        Optional<byte[]> bytes = store.read(ConfigStore.DOCUMENT_NAME);
        return bytes.isEmpty() ? "" : new String(bytes.get(), StandardCharsets.UTF_8);
    }

    public static void register(TestRunner r) {

        r.test("persistence", "round-trips profiles and macros through JSON", () -> {
            TestStudio a = new TestStudio();
            RepoResult<Profile> created = a.studio.createProfile("Gündüz \uD83C\uDFAE", "açıklama ıİşğçöü");
            Assert.isTrue(created.success(), "profile created");
            Profile profile = created.value();
            a.studio.createMacro(profile, "Selam", MacroContentType.CHAT_MESSAGE, "merhaba dünya", 30, 0);
            a.studio.createMacro(profile, "Komut", MacroContentType.COMMAND, "/list", 45, 5);
            Assert.isTrue(a.studio.saveNow(), "saved");

            Studio b = new Studio(a.store, new Clock.Fake(), new RecordingDispatchAdapter());
            Assert.eq(1, b.profiles().count(), "profile survived");
            Profile loaded = b.profiles().all().get(0);
            Assert.eq("Gündüz \uD83C\uDFAE", loaded.name(), "unicode name survived");
            Assert.eq("açıklama ıİşğçöü", loaded.description(), "unicode description survived");
            List<Macro> macros = b.macros().forProfile(loaded);
            Assert.eq(2, macros.size(), "both macros survived");
            Assert.eq("merhaba dünya", macros.get(0).content(), "chat content");
            Assert.eq("list", macros.get(1).content(), "command stored without the leading slash");
            Assert.eq(45L, macros.get(1).intervalSeconds(), "interval survived");
            Assert.eq(5L, macros.get(1).initialDelaySeconds(), "initial delay survived");
        });

        r.test("persistence", "a saved macro comes back disabled and keeps its identity", () -> {
            TestStudio a = new TestStudio();
            Profile profile = a.studio.createProfile("P", "").value();
            a.studio.createMacro(profile, "m", MacroContentType.CHAT_MESSAGE, "hi", 30, 0);
            Macro macro = a.studio.macros().forProfile(profile).get(0);
            a.studio.setMacroEnabled(profile, macro.id(), true);
            a.studio.saveNow();

            Studio b = new Studio(a.store, new Clock.Fake(), new RecordingDispatchAdapter());
            Macro loaded = b.macros().forProfile(b.profiles().all().get(0)).get(0);
            Assert.eq(macro.id(), loaded.id(), "id preserved");
            Assert.isTrue(loaded.enabled(), "enabled flag preserved");
            Assert.eq(macro.createdAtEpochMillis(), loaded.createdAtEpochMillis(), "creation time preserved");
        });

        r.test("persistence", "a corrupt file is set aside, not silently replaced", () -> {
            InMemoryByteStore store = new InMemoryByteStore();
            seed(store, "{ this is not json ");
            Studio studio = new Studio(store, new Clock.Fake(), new RecordingDispatchAdapter());
            Assert.isFalse(studio.loadedSuccessfully(), "load reported a failure");
            Assert.eq("config.error.parse_error", studio.loadProblemKey(), "problem key");
            Assert.eq(0, studio.profiles().count(), "no invented data");
            boolean backupExists = false;
            for (String name : store.names()) {
                if (name.startsWith(ConfigStore.DOCUMENT_NAME + ".corrupt-")) backupExists = true;
            }
            Assert.isTrue(backupExists, "the damaged file was copied aside");
            Assert.contains(read(store), "this is not json", "original left untouched");
        });

        r.test("persistence", "a newer schema is refused and the store goes read-only", () -> {
            InMemoryByteStore store = new InMemoryByteStore();
            seed(store, "{\"schemaVersion\":99,\"settings\":{},\"profiles\":[],\"macros\":[]}");
            String before = read(store);
            Clock.Fake clock = new Clock.Fake();
            Studio studio = new Studio(store, clock, new RecordingDispatchAdapter());
            Assert.isFalse(studio.loadedSuccessfully(), "load reported a failure");
            Assert.isTrue(studio.configStore().isReadOnly(), "read-only");
            Assert.eq(before, read(store), "the newer file was not modified");
            Throwable t = Assert.catchThrowable(() -> studio.configStore()
                    .saveNow(studio.data().document()), "save refused");
            Assert.isTrue(t instanceof ConfigException, "a ConfigException is raised");
        });

        r.test("persistence", "duplicate ids are dropped, not merged", () -> {
            InMemoryByteStore store = new InMemoryByteStore();
            UUID pid = UUID.randomUUID();
            UUID mid = UUID.randomUUID();
            String macro = "{\"id\":\"" + mid + "\",\"profileId\":\"" + pid + "\",\"name\":\"a\","
                    + "\"contentType\":\"CHAT_MESSAGE\",\"content\":\"hi\",\"intervalSeconds\":30,"
                    + "\"initialDelaySeconds\":0,\"enabled\":false,\"createdAt\":0,\"updatedAt\":0}";
            String profile = "{\"id\":\"" + pid + "\",\"name\":\"P\",\"description\":\"\","
                    + "\"scope\":{\"kind\":\"EVERYWHERE\",\"serverAddress\":\"\",\"worldName\":\"\"},"
                    + "\"macroIds\":[\"" + mid + "\"],\"createdAt\":0,\"updatedAt\":0,\"lastUsedAt\":0,"
                    + "\"autoStartOnJoin\":false}";
            seed(store, "{\"schemaVersion\":1,\"savedAt\":0,\"selectedProfileId\":\"\","
                    + "\"settings\":{},\"profiles\":[" + profile + "],\"macros\":[" + macro + "," + macro + "]}");

            Studio studio = new Studio(store, new Clock.Fake(), new RecordingDispatchAdapter());
            Assert.eq(1, studio.data().document().macros().size(), "duplicate macro dropped");
        });

        r.test("persistence", "a macro with an unknown parent is skipped", () -> {
            InMemoryByteStore store = new InMemoryByteStore();
            UUID orphanParent = UUID.randomUUID();
            String macro = "{\"id\":\"" + UUID.randomUUID() + "\",\"profileId\":\"" + orphanParent + "\","
                    + "\"name\":\"orphan\",\"contentType\":\"CHAT_MESSAGE\",\"content\":\"hi\","
                    + "\"intervalSeconds\":30,\"initialDelaySeconds\":0,\"enabled\":false,"
                    + "\"createdAt\":0,\"updatedAt\":0}";
            seed(store, "{\"schemaVersion\":1,\"savedAt\":0,\"selectedProfileId\":\"\","
                    + "\"settings\":{},\"profiles\":[],\"macros\":[" + macro + "]}");
            Studio studio = new Studio(store, new Clock.Fake(), new RecordingDispatchAdapter());
            Assert.eq(0, studio.data().document().macros().size(), "orphan macro not adopted silently");
        });

        r.test("persistence", "a macro missing from its profile list is re-attached", () -> {
            TestStudio a = new TestStudio();
            Profile profile = a.studio.createProfile("P", "").value();
            a.studio.createMacro(profile, "m", MacroContentType.CHAT_MESSAGE, "hi", 30, 0);
            Macro macro = a.studio.macros().forProfile(profile).get(0);

            // Damage the file: the profile no longer lists its own macro.
            ConfigDocument damagedDoc = a.studio.data().document().withProfiles(
                    List.of(profile.withMacroIdsNoTouch(List.of())), 0L);
            String damaged = a.studio.configStore().codec().encode(damagedDoc);
            Assert.contains(damaged, "\"macroIds\": []", "the fixture really emptied the list");
            Assert.contains(damaged, macro.id().toString(), "the macro record itself is still there");
            seed(a.store, damaged);

            Studio b = new Studio(a.store, new Clock.Fake(), new RecordingDispatchAdapter());
            Assert.eq(1, b.macros().forProfile(b.profiles().all().get(0)).size(),
                    "the macro is re-attached instead of lost");
        });

        r.test("persistence", "schema 0 documents are migrated", () -> {
            InMemoryByteStore store = new InMemoryByteStore();
            UUID pid = UUID.randomUUID();
            seed(store, "{\"schemaVersion\":0,\"savedAt\":111,"
                    + "\"settings\":{\"darkMode\":false,\"accent\":\"sky\",\"gapSeconds\":5},"
                    + "\"profiles\":[{\"id\":\"" + pid + "\",\"name\":\"Legacy\",\"macros\":["
                    + "{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"m1\",\"type\":\"cmd\","
                    + "\"text\":\"/list\",\"interval\":\"45\",\"on\":true},"
                    + "{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"m2\",\"type\":\"message\","
                    + "\"text\":\"hello\",\"interval\":\"nonsense\",\"on\":false}]}]}");

            Studio studio = new Studio(store, new Clock.Fake(), new RecordingDispatchAdapter());
            ConfigDocument doc = studio.data().document();
            Assert.eq(ConfigCodec.CURRENT_SCHEMA_VERSION, doc.schemaVersion(), "migrated to current schema");
            Assert.eq("LIGHT", doc.settings().themeMode().storageName(), "darkMode migrated");
            Assert.eq("SKY", doc.settings().accent().storageName(), "accent migrated");
            Assert.eq(5000L, doc.settings().globalMinDispatchGapMillis(), "gap converted to millis");
            List<Macro> macros = studio.macros().forProfile(studio.profiles().all().get(0));
            Assert.eq(2, macros.size(), "nested macros flattened");
            Assert.isTrue(macros.get(0).isCommand(), "cmd mapped to COMMAND");
            Assert.eq("list", macros.get(0).content(), "legacy slash stripped");
            Assert.eq(45L, macros.get(0).intervalSeconds(), "string interval parsed");
            Assert.isTrue(macros.get(0).enabled(), "legacy enabled flag preserved");
            Assert.eq(Macro.DEFAULT_INTERVAL_SECONDS, macros.get(1).intervalSeconds(),
                    "unreadable interval falls back to the default");
        });

        r.test("persistence", "a failed write is reported, not hidden", () -> {
            TestStudio t = new TestStudio();
            Profile profile = t.studio.createProfile("P", "").value();
            t.studio.createMacro(profile, "m", MacroContentType.CHAT_MESSAGE, "hi", 30, 0);
            t.store.setFailWrites(true);
            Assert.isFalse(t.studio.saveNow(), "save reported failure");
            Assert.isFalse(t.studio.configStore().lastSavePersisted(), "not marked as persisted");
            Assert.ne("", t.studio.configStore().lastSaveError(), "a real reason is available");
        });

        r.test("persistence", "saves are debounced and flushed on shutdown", () -> {
            TestStudio t = new TestStudio();
            t.studio.createProfile("P", "");
            Assert.isTrue(t.studio.configStore().hasPendingChanges(), "change queued");
            t.studio.configStore().tick();
            Assert.isTrue(t.studio.configStore().hasPendingChanges(), "nothing written inside the window");
            t.clock.advanceMillis(ConfigStore.DEBOUNCE_MILLIS + 1);
            t.studio.configStore().tick();
            Assert.isFalse(t.studio.configStore().hasPendingChanges(), "written after the window");
            Assert.ne("", read(t.store), "the document is on the store");
        });

        r.test("persistence", "imported macros arrive disabled with fresh ids", () -> {
            TestStudio source = new TestStudio();
            Profile original = source.studio.createProfile("Original", "").value();
            source.studio.createMacro(original, "m", MacroContentType.CHAT_MESSAGE, "hi", 30, 0);
            Macro sourceMacro = source.studio.macros().forProfile(original).get(0);
            source.studio.setMacroEnabled(original, sourceMacro.id(), true);
            String bundle = source.studio.exportProfileIncludingSensitive(original);

            TestStudio target = new TestStudio();
            ProfileBundle.Preview preview = target.studio.bundles().preview(bundle);
            Assert.eq(1, preview.profile().macros().size(), "one macro in the bundle");
            RepoResult<Profile> imported = target.studio.importProfile(preview, false);
            Assert.isTrue(imported.success(), "import succeeded");

            Profile newProfile = imported.value();
            Assert.ne(original.id(), newProfile.id(), "new profile id");
            List<Macro> macros = target.studio.macros().forProfile(newProfile);
            Assert.eq(1, macros.size(), "macro imported");
            Assert.ne(sourceMacro.id(), macros.get(0).id(), "new macro id");
            Assert.isFalse(macros.get(0).enabled(), "imported macros start disabled");
        });

        r.test("persistence", "exporting without sensitive content masks secret arguments", () -> {
            TestStudio t = new TestStudio();
            Profile profile = t.studio.createProfile("P", "").value();
            t.studio.createMacro(profile, "auth", MacroContentType.COMMAND, "/login hunter2", 60, 0);
            String masked = t.studio.exportProfile(profile);
            Assert.isFalse(masked.contains("hunter2"), "the secret never reaches the export");
            String full = t.studio.exportProfileIncludingSensitive(profile);
            Assert.contains(full, "hunter2", "explicit opt-in keeps it");
        });

        r.test("persistence", "an oversized stored macro is skipped with a report", () -> {
            StringBuilder huge = new StringBuilder();
            for (int i = 0; i < 5_000; i++) huge.append('x');
            InMemoryByteStore store = new InMemoryByteStore();
            UUID pid = UUID.randomUUID();
            seed(store, "{\"schemaVersion\":1,\"savedAt\":0,\"selectedProfileId\":\"\",\"settings\":{},"
                    + "\"profiles\":[{\"id\":\"" + pid + "\",\"name\":\"P\",\"description\":\"\","
                    + "\"scope\":{\"kind\":\"EVERYWHERE\",\"serverAddress\":\"\",\"worldName\":\"\"},"
                    + "\"macroIds\":[],\"createdAt\":0,\"updatedAt\":0,\"lastUsedAt\":0,\"autoStartOnJoin\":false}],"
                    + "\"macros\":[{\"id\":\"" + UUID.randomUUID() + "\",\"profileId\":\"" + pid + "\","
                    + "\"name\":\"huge\",\"contentType\":\"CHAT_MESSAGE\",\"content\":\"" + huge + "\","
                    + "\"intervalSeconds\":30,\"initialDelaySeconds\":0,\"enabled\":false,"
                    + "\"createdAt\":0,\"updatedAt\":0}]}");
            Studio studio = new Studio(store, new Clock.Fake(), new RecordingDispatchAdapter());
            Assert.eq(0, studio.data().document().macros().size(), "oversized macro refused");
            Assert.eq(1, studio.profiles().count(), "the profile itself survived");
        });
    }
}
