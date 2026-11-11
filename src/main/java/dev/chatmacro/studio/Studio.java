package dev.chatmacro.studio;

import dev.chatmacro.studio.config.ConfigDocument;
import dev.chatmacro.studio.config.ConfigException;
import dev.chatmacro.studio.config.ConfigStore;
import dev.chatmacro.studio.config.GlobalSettings;
import dev.chatmacro.studio.config.StudioData;
import dev.chatmacro.studio.dispatch.ChatDispatchAdapter;
import dev.chatmacro.studio.dispatch.DispatchResult;
import dev.chatmacro.studio.io.ProfileBundle;
import dev.chatmacro.studio.model.Macro;
import dev.chatmacro.studio.model.Profile;
import dev.chatmacro.studio.model.ServerScope;
import dev.chatmacro.studio.model.SessionInfo;
import dev.chatmacro.studio.model.ValidationService;
import dev.chatmacro.studio.repo.MacroRepository;
import dev.chatmacro.studio.repo.ProfileRepository;
import dev.chatmacro.studio.repo.RepoResult;
import dev.chatmacro.studio.sched.Clock;
import dev.chatmacro.studio.sched.MacroScheduler;
import dev.chatmacro.studio.sched.TickReport;
import dev.chatmacro.studio.store.ByteStore;
import dev.chatmacro.studio.theme.ThemeManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The composition root: one owner for data, settings, the scheduler and the
 * session bridge.
 *
 * <p>The client layer registers exactly one listener set on this object at mod
 * init. Opening or closing a screen never adds another tick handler, and the
 * scheduler keeps running when no screen is open - it is owned here, not by the
 * UI.</p>
 */
public final class Studio {

    /** UI-facing events. Keys are translation keys. */
    public interface Listener {
        default void onMessage(String messageKey, Object... args) { }
        default void onDocumentChanged(ConfigDocument document, String changeKind) { }
        default void onDispatch(Macro macro, DispatchResult result) { }
        default void onSessionStopped(String reasonKey) { }
    }

    private final Clock clock;
    private final ConfigStore configStore;
    private final StudioData data;
    private final ProfileRepository profiles;
    private final MacroRepository macros;
    private final ValidationService validation;
    private final MacroScheduler scheduler;
    private final ThemeManager themes = new ThemeManager();
    private final ProfileBundle bundles = new ProfileBundle();
    private final List<Listener> listeners = new ArrayList<>();

    private SessionInfo session = SessionInfo.none(0L);
    private boolean loadedSuccessfully;
    private String loadProblemKey = "";

    public Studio(ByteStore store, Clock clock, ChatDispatchAdapter adapter) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.configStore = new ConfigStore(store, clock);

        ConfigStore.LoadOutcome outcome = configStore.load();
        this.data = new StudioData(outcome.document());
        this.loadedSuccessfully = !outcome.failed();
        this.loadProblemKey = outcome.failed() && outcome.fatalKind() != null
                ? "config.error." + outcome.fatalKind().name().toLowerCase(java.util.Locale.ROOT)
                : "";

        GlobalSettings settings = data.document().settings();
        this.validation = new ValidationService(settings.maxChatChars(), settings.maxCommandChars());
        this.profiles = new ProfileRepository(data, clock, validation);
        this.macros = new MacroRepository(data, clock, validation);
        this.scheduler = new MacroScheduler(clock, adapter);
        this.scheduler.setPolicy(settings.toSchedulerPolicy());

        this.data.addListener((document, changeKind) -> {
            for (Listener l : listeners) l.onDocumentChanged(document, changeKind);
        });
        this.scheduler.addListener(new MacroScheduler.Listener() {
            @Override
            public void onDispatch(Macro macro, DispatchResult result) {
                for (Listener l : listeners) l.onDispatch(macro, result);
            }

            @Override
            public void onSessionStopped(String reasonKey) {
                for (Listener l : listeners) l.onSessionStopped(reasonKey);
            }
        });

        // Restore the previously selected profile, but never start sending.
        profiles.selected().ifPresent(p -> scheduler.selectProfile(p, macros.forProfile(p)));
    }

    // ------------------------------------------------------------- accessors

    public Clock clock() { return clock; }
    public ConfigStore configStore() { return configStore; }
    public StudioData data() { return data; }
    public ProfileRepository profiles() { return profiles; }
    public MacroRepository macros() { return macros; }
    public MacroScheduler scheduler() { return scheduler; }
    public ValidationService validation() { return validation; }
    public ThemeManager themes() { return themes; }
    public ProfileBundle bundles() { return bundles; }
    public GlobalSettings settings() { return data.document().settings(); }
    public SessionInfo session() { return session; }
    public boolean loadedSuccessfully() { return loadedSuccessfully; }
    public String loadProblemKey() { return loadProblemKey; }

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void emit(String key, Object... args) {
        for (Listener l : listeners) l.onMessage(key, args);
    }

    // -------------------------------------------------------------- settings

    public void updateSettings(GlobalSettings next) {
        long now = clock.currentTimeMillis();
        data.replace(data.document().withSettings(next, now), "settings");
        scheduler.setPolicy(next.toSchedulerPolicy());
        configStore.markDirty(data.document());
    }

    // -------------------------------------------------------------- profiles

    public RepoResult<Profile> createProfile(String name, String description) {
        RepoResult<Profile> result = profiles.create(name, description);
        if (result.success()) {
            configStore.markDirty(data.document());
            emit(result.messageKeyOrEmpty());
        } else {
            emit(result.messageKeyOrEmpty(), result.args().toArray());
        }
        return result;
    }

    /** Selecting a profile shows its macros; it does not start sending. */
    public RepoResult<Profile> selectProfile(UUID id) {
        RepoResult<Profile> result = profiles.select(id);
        if (!result.success()) {
            emit(result.messageKeyOrEmpty());
            return result;
        }
        Profile profile = result.value();
        if (profile == null) {
            scheduler.clearProfile();
        } else {
            scheduler.selectProfile(profile, macros.forProfile(profile));
        }
        configStore.markDirty(data.document());
        return result;
    }

    public RepoResult<Profile> renameProfile(UUID id, String name) {
        RepoResult<Profile> result = profiles.rename(id, name);
        finish(result);
        return result;
    }

    public RepoResult<Profile> updateProfileDescription(UUID id, String description) {
        RepoResult<Profile> result = profiles.updateDescription(id, description);
        finish(result);
        return result;
    }

    public RepoResult<Profile> updateProfileScope(UUID id, ServerScope scope) {
        RepoResult<Profile> result = profiles.updateScope(id, scope);
        finish(result);
        syncSchedulerAfterEdit();
        return result;
    }

    public RepoResult<Profile> setProfileAutoStart(UUID id, boolean value) {
        RepoResult<Profile> result = profiles.setAutoStartOnJoin(id, value);
        finish(result);
        return result;
    }

    public RepoResult<Profile> deleteProfile(UUID id) {
        RepoResult<Profile> result = profiles.delete(id);
        if (result.success()) {
            // Deleting the active profile leaves nothing selected: the run
            // session is cleared rather than quietly pointing at another one.
            if (scheduler.activeProfileId() != null && scheduler.activeProfileId().equals(id)) {
                scheduler.clearProfile();
            }
            configStore.markDirty(data.document());
        }
        finish(result);
        return result;
    }

    public RepoResult<Profile> duplicateProfile(UUID id, String newName) {
        RepoResult<Profile> result = profiles.duplicate(id, newName);
        finish(result);
        return result;
    }

    // ---------------------------------------------------------------- macros

    public RepoResult<Macro> createMacro(Profile profile, String name, dev.chatmacro.studio.model.MacroContentType type,
                                         String content, long intervalSeconds, long initialDelaySeconds) {
        RepoResult<Macro> result = macros.create(profile, name, type, content, intervalSeconds, initialDelaySeconds);
        finish(result);
        syncSchedulerAfterEdit();
        return result;
    }

    public RepoResult<Macro> updateMacro(Profile profile, UUID id, String name,
                                         dev.chatmacro.studio.model.MacroContentType type, String content,
                                         long intervalSeconds, long initialDelaySeconds) {
        RepoResult<Macro> result = macros.update(profile, id, name, type, content,
                intervalSeconds, initialDelaySeconds);
        finish(result);
        syncSchedulerAfterEdit();
        return result;
    }

    public RepoResult<Macro> setMacroEnabled(Profile profile, UUID id, boolean enabled) {
        RepoResult<Macro> result = macros.setEnabled(profile, id, enabled);
        finish(result);
        syncSchedulerAfterEdit();
        return result;
    }

    public RepoResult<Macro> duplicateMacro(Profile profile, UUID id) {
        RepoResult<Macro> result = macros.duplicate(profile, id);
        finish(result);
        syncSchedulerAfterEdit();
        return result;
    }

    public RepoResult<Macro> deleteMacro(Profile profile, UUID id) {
        RepoResult<Macro> result = macros.delete(profile, id);
        finish(result);
        syncSchedulerAfterEdit();
        return result;
    }

    private <T> void finish(RepoResult<T> result) {
        if (result.success()) {
            configStore.markDirty(data.document());
        }
        if (!result.messageKeyOrEmpty().isEmpty()) {
            emit(result.messageKeyOrEmpty(), result.args().toArray());
        }
    }

    /** Pushes the active profile's current macros into the scheduler. */
    private void syncSchedulerAfterEdit() {
        UUID activeId = scheduler.activeProfileId();
        if (activeId == null) return;
        Optional<Profile> profile = profiles.find(activeId);
        if (profile.isEmpty()) {
            scheduler.clearProfile();
            return;
        }
        scheduler.updateMacros(macros.forProfile(profile.get()));
    }

    // --------------------------------------------------------------- session

    /** Called by the client layer when the world/connection state changes. */
    public void updateSession(SessionInfo next) {
        SessionInfo previous = this.session;
        this.session = Objects.requireNonNull(next, "next");
        if (next.inGame() && next.generation() != previous.generation()) {
            scheduler.bindSession(next);
            // Opt-in auto start only. The default is that joining a world never
            // begins sending.
            profiles.selected().ifPresent(p -> {
                if (p.autoStartOnJoin() && p.scope().matches(next)) scheduler.start(next);
            });
        }
        if (previous.inGame() && !next.inGame()) {
            // Leaving a world stops the run. Merely being on the title screen
            // when the mod loads does not cancel an armed-but-unbound run.
            scheduler.stop("session.leftGame");
        }
    }

    /** Called every client tick. */
    public TickReport tick(SessionInfo current) {
        updateSession(current);
        configStore.tick();
        return scheduler.tick(this.session);
    }

    public void startRun() {
        scheduler.start(session);
        emit("run.started");
    }

    public void stopRun(String reasonKey) {
        scheduler.stop(reasonKey == null ? "run.stoppedByUser" : reasonKey);
        emit("run.stopped");
    }

    public void pauseRun() {
        scheduler.pauseByUser();
        emit("run.paused");
    }

    public void resumeRun() {
        scheduler.resumeByUser();
        emit("run.resumed");
    }

    /** "Try once": a real send through the same budget and checks. */
    public DispatchResult dispatchOnce(Macro macro) {
        return scheduler.dispatchOnce(macro, session);
    }

    // ---------------------------------------------------------------- import

    /**
     * Applies a previewed bundle. Imported macros are disabled, get new ids and
     * land in a new profile unless the caller explicitly asked to overwrite a
     * profile with the same name.
     */
    public RepoResult<Profile> importProfile(ProfileBundle.Preview preview, boolean overwriteSameName) {
        Objects.requireNonNull(preview, "preview");
        ProfileBundle.BundleProfile bundle = preview.profile();
        Optional<Profile> sameName = profiles.all().stream()
                .filter(p -> ProfileRepository.normalize(p.name()).equals(ProfileRepository.normalize(bundle.name())))
                .findFirst();

        Profile target;
        if (sameName.isPresent() && overwriteSameName) {
            target = sameName.get();
        } else {
            String name = bundle.name();
            if (sameName.isPresent()) name = name + " (imported)";
            RepoResult<Profile> created = createProfile(name, bundle.description());
            if (!created.success()) return created;
            target = created.value();
        }

        profiles.updateScope(target.id(), bundle.scope());
        Profile current = profiles.find(target.id()).orElse(target);
        for (ProfileBundle.BundleMacro m : bundle.macros()) {
            createMacro(current, m.name(), m.type(), m.content(), m.intervalSeconds(), m.initialDelaySeconds());
            current = profiles.find(target.id()).orElse(current);
        }
        return RepoResult.ok(profiles.find(target.id()).orElse(current), "import.done");
    }

    public String exportProfile(Profile profile) {
        return bundles.export(profile, macros.forProfile(profile), false);
    }

    public String exportProfileIncludingSensitive(Profile profile) {
        return bundles.export(profile, macros.forProfile(profile), true);
    }

    // ------------------------------------------------------------ persistence

    public boolean saveNow() {
        try {
            configStore.saveNow(data.document());
            return true;
        } catch (ConfigException e) {
            emit("config.saveFailed");
            return false;
        }
    }

    /** Called on client shutdown. */
    public void shutdown() {
        scheduler.stop("client.shutdown");
        configStore.flush();
    }

    public List<Macro> macrosOfSelectedProfile() {
        Optional<Profile> profile = profiles.selected();
        return profile.map(macros::forProfile).orElseGet(List::of);
    }

    /** Convenience for tests and the status bar. */
    public int profileCount() {
        return profiles.count();
    }

    /** Convenience: is any macro in the active profile enabled? */
    public boolean activeProfileHasEnabledMacros() {
        UUID id = scheduler.activeProfileId();
        if (id == null) return false;
        return macros.enabledCountFor(id) > 0;
    }

    /** Snapshot helper used by the HUD. */
    public String activeProfileName() {
        UUID id = scheduler.activeProfileId();
        if (id == null) return "";
        return profiles.find(id).map(Profile::name).orElse("");
    }

    /** Exposed for the diagnostics screen; already masked. */
    public List<String> diagnostics() {
        List<String> out = new ArrayList<>();
        out.add("schemaVersion=" + data.document().schemaVersion());
        out.add("profiles=" + profiles.count());
        out.add("macros=" + data.document().macros().size());
        out.add("running=" + scheduler.isRunning());
        out.add("dispatches=" + scheduler.totalDispatches());
        out.add("readOnly=" + configStore.isReadOnly());
        out.add("server=" + dev.chatmacro.studio.io.DiagnosticsMasker.maskAddress(session.serverAddress()));
        return out;
    }
}
