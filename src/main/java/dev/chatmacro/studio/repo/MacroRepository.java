package dev.chatmacro.studio.repo;

import dev.chatmacro.studio.config.ConfigCodec;
import dev.chatmacro.studio.config.StudioData;
import dev.chatmacro.studio.model.Macro;
import dev.chatmacro.studio.model.MacroContentType;
import dev.chatmacro.studio.model.Profile;
import dev.chatmacro.studio.model.ValidationService;
import dev.chatmacro.studio.sched.Clock;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Macro create / read / update / delete, always scoped to one profile.
 *
 * <p>Every write re-checks {@code macro.profileId() == profile.id} before it is
 * accepted, so a macro can never be moved under a different parent by accident
 * and a same-named macro in another profile is never touched.</p>
 */
public final class MacroRepository {

    private final StudioData data;
    private final Clock clock;
    private final ValidationService validation;

    public MacroRepository(StudioData data, Clock clock, ValidationService validation) {
        this.data = Objects.requireNonNull(data, "data");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.validation = Objects.requireNonNull(validation, "validation");
    }

    // --------------------------------------------------------------- queries

    /**
     * Macros belonging to one profile, in the profile's own order.
     *
     * <p>The profile handle may be stale (callers often hold the record they got
     * back from an earlier call), so the live record is looked up by id first.
     * Reading a stale list would silently report "no macros".</p>
     */
    public List<Macro> forProfile(Profile profile) {
        if (profile == null) return List.of();
        Profile live = current(profile);
        List<Macro> out = new ArrayList<>(live.macroIds().size());
        for (UUID id : live.macroIds()) {
            find(id).filter(m -> m.profileId().equals(live.id())).ifPresent(out::add);
        }
        return out;
    }

    /** Resolves a possibly stale handle to the record currently in the document. */
    private Profile current(Profile profile) {
        for (Profile candidate : data.document().profiles()) {
            if (candidate.id().equals(profile.id())) return candidate;
        }
        return profile;
    }

    public List<Macro> forProfileId(UUID profileId) {
        if (profileId == null) return List.of();
        List<Macro> out = new ArrayList<>();
        for (Macro m : data.document().macros()) {
            if (m.profileId().equals(profileId)) out.add(m);
        }
        return out;
    }

    public Optional<Macro> find(UUID id) {
        if (id == null) return Optional.empty();
        for (Macro m : data.document().macros()) {
            if (m.id().equals(id)) return Optional.of(m);
        }
        return Optional.empty();
    }

    public int countFor(UUID profileId) {
        int n = 0;
        for (Macro m : data.document().macros()) {
            if (m.profileId().equals(profileId)) n++;
        }
        return n;
    }

    public int enabledCountFor(UUID profileId) {
        int n = 0;
        for (Macro m : data.document().macros()) {
            if (m.profileId().equals(profileId) && m.enabled()) n++;
        }
        return n;
    }

    // -------------------------------------------------------------- commands

    /**
     * Creates a macro. New macros are always created <b>disabled</b>, and saving
     * never sends anything.
     */
    public RepoResult<Macro> create(Profile profile, String rawName, MacroContentType type,
                                    String rawContent, long intervalSeconds, long initialDelaySeconds) {
        if (profile == null) return RepoResult.error("error.profileNotFound");
        profile = current(profile);
        if (countFor(profile.id()) >= ConfigCodec.MAX_MACROS_PER_PROFILE) {
            return RepoResult.error("error.macroLimit", ConfigCodec.MAX_MACROS_PER_PROFILE);
        }
        ValidationService.Result nameCheck = validation.validateMacroName(rawName);
        if (!nameCheck.isValid()) return RepoResult.error(nameCheck.firstError(), nameCheck.firstErrorArgs());
        ValidationService.Result contentCheck = validation.validateContent(type, rawContent);
        if (!contentCheck.isValid()) {
            return RepoResult.error(contentCheck.firstError(), contentCheck.firstErrorArgs());
        }
        ValidationService.Result intervalCheck = validation.validateIntervalSeconds(intervalSeconds);
        if (!intervalCheck.isValid()) {
            return RepoResult.error(intervalCheck.firstError(), intervalCheck.firstErrorArgs());
        }
        ValidationService.Result delayCheck = validation.validateInitialDelaySeconds(initialDelaySeconds);
        if (!delayCheck.isValid()) {
            return RepoResult.error(delayCheck.firstError(), delayCheck.firstErrorArgs());
        }

        long now = clock.currentTimeMillis();
        Macro macro = new Macro(UUID.randomUUID(), profile.id(), nameCheck.value(), type,
                contentCheck.value(), intervalCheck.valueAsLong(), delayCheck.valueAsLong(),
                false, now, now);
        return attach(profile, macro, now, "macro.create", "macro.created");
    }

    /**
     * Applies an edit. The replacement keeps the id and the profile link, so an
     * edit can never re-parent a record.
     */
    public RepoResult<Macro> update(Profile profile, UUID macroId, String rawName, MacroContentType type,
                                    String rawContent, long intervalSeconds, long initialDelaySeconds) {
        if (profile == null) return RepoResult.error("error.profileNotFound");
        profile = current(profile);
        Optional<Macro> existing = find(macroId);
        if (existing.isEmpty()) return RepoResult.error("error.macroNotFound");
        if (!existing.get().profileId().equals(profile.id())) {
            return RepoResult.error("error.macroWrongProfile");
        }
        ValidationService.Result nameCheck = validation.validateMacroName(rawName);
        if (!nameCheck.isValid()) return RepoResult.error(nameCheck.firstError(), nameCheck.firstErrorArgs());
        ValidationService.Result contentCheck = validation.validateContent(type, rawContent);
        if (!contentCheck.isValid()) {
            return RepoResult.error(contentCheck.firstError(), contentCheck.firstErrorArgs());
        }
        ValidationService.Result intervalCheck = validation.validateIntervalSeconds(intervalSeconds);
        if (!intervalCheck.isValid()) {
            return RepoResult.error(intervalCheck.firstError(), intervalCheck.firstErrorArgs());
        }
        ValidationService.Result delayCheck = validation.validateInitialDelaySeconds(initialDelaySeconds);
        if (!delayCheck.isValid()) {
            return RepoResult.error(delayCheck.firstError(), delayCheck.firstErrorArgs());
        }

        long now = clock.currentTimeMillis();
        Macro previous = existing.get();
        // One replacement built from the stored record: the enabled flag, the
        // creation timestamp and the identity survive the edit untouched.
        Macro updated = new Macro(previous.id(), previous.profileId(), nameCheck.value(), type,
                contentCheck.value(), intervalCheck.valueAsLong(), delayCheck.valueAsLong(),
                previous.enabled(), previous.createdAtEpochMillis(), now);
        return replaceMacro(updated, now, "macro.update", "macro.saved");
    }

    /** Toggling only ever touches the one macro, in the one profile. */
    public RepoResult<Macro> setEnabled(Profile profile, UUID macroId, boolean enabled) {
        if (profile == null) return RepoResult.error("error.profileNotFound");
        profile = current(profile);
        Optional<Macro> existing = find(macroId);
        if (existing.isEmpty()) return RepoResult.error("error.macroNotFound");
        if (!existing.get().profileId().equals(profile.id())) {
            return RepoResult.error("error.macroWrongProfile");
        }
        long now = clock.currentTimeMillis();
        Macro updated = existing.get().withEnabled(enabled, now);
        return replaceMacro(updated, now, "macro.enabled",
                enabled ? "macro.enabledOn" : "macro.enabledOff");
    }

    public RepoResult<Macro> duplicate(Profile profile, UUID macroId) {
        if (profile == null) return RepoResult.error("error.profileNotFound");
        profile = current(profile);
        Optional<Macro> existing = find(macroId);
        if (existing.isEmpty()) return RepoResult.error("error.macroNotFound");
        if (!existing.get().profileId().equals(profile.id())) {
            return RepoResult.error("error.macroWrongProfile");
        }
        if (countFor(profile.id()) >= ConfigCodec.MAX_MACROS_PER_PROFILE) {
            return RepoResult.error("error.macroLimit", ConfigCodec.MAX_MACROS_PER_PROFILE);
        }
        long now = clock.currentTimeMillis();
        Macro copy = existing.get().asCopyWithNewIdentity(UUID.randomUUID(), profile.id(), now)
                .withName(existing.get().name() + " (copy)", now);
        return attach(profile, copy, now, "macro.duplicate", "macro.duplicated");
    }

    /** Deletes one macro and detaches it from its profile in a single swap. */
    public RepoResult<Macro> delete(Profile profile, UUID macroId) {
        if (profile == null) return RepoResult.error("error.profileNotFound");
        profile = current(profile);
        Optional<Macro> existing = find(macroId);
        if (existing.isEmpty()) return RepoResult.error("error.macroNotFound");
        if (!existing.get().profileId().equals(profile.id())) {
            return RepoResult.error("error.macroWrongProfile");
        }
        long now = clock.currentTimeMillis();
        List<Macro> macros = new ArrayList<>();
        for (Macro m : data.document().macros()) {
            if (!m.id().equals(macroId)) macros.add(m);
        }
        List<UUID> ids = new ArrayList<>();
        for (UUID id : profile.macroIds()) {
            if (!id.equals(macroId)) ids.add(id);
        }
        List<dev.chatmacro.studio.model.Profile> profiles = new ArrayList<>();
        for (dev.chatmacro.studio.model.Profile p : data.document().profiles()) {
            profiles.add(p.id().equals(profile.id()) ? p.withMacroIdsNoTouch(ids) : p);
        }
        data.replace(data.document().withMacros(macros, now).withProfiles(profiles, now), "macro.delete");
        return RepoResult.ok(existing.get(), "macro.deleted");
    }

    // --------------------------------------------------------------- helpers

    private RepoResult<Macro> attach(Profile profile, Macro macro, long now,
                                     String changeKind, String messageKey) {
        profile = current(profile);
        List<Macro> macros = new ArrayList<>(data.document().macros());
        macros.add(macro);
        List<UUID> ids = new ArrayList<>(profile.macroIds());
        ids.add(macro.id());
        List<dev.chatmacro.studio.model.Profile> profiles = new ArrayList<>();
        for (dev.chatmacro.studio.model.Profile p : data.document().profiles()) {
            profiles.add(p.id().equals(profile.id()) ? p.withMacroIdsNoTouch(ids) : p);
        }
        data.replace(data.document().withMacros(macros, now).withProfiles(profiles, now), changeKind);
        return RepoResult.ok(macro, messageKey);
    }

    private RepoResult<Macro> replaceMacro(Macro updated, long now, String changeKind, String messageKey) {
        List<Macro> macros = new ArrayList<>();
        for (Macro m : data.document().macros()) {
            macros.add(m.id().equals(updated.id()) ? updated : m);
        }
        data.replace(data.document().withMacros(macros, now), changeKind);
        return RepoResult.ok(updated, messageKey);
    }
}
