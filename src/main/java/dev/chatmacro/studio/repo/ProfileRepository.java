package dev.chatmacro.studio.repo;

import dev.chatmacro.studio.config.ConfigCodec;
import dev.chatmacro.studio.config.ConfigDocument;
import dev.chatmacro.studio.config.StudioData;
import dev.chatmacro.studio.model.Macro;
import dev.chatmacro.studio.model.Profile;
import dev.chatmacro.studio.model.ServerScope;
import dev.chatmacro.studio.model.ValidationService;
import dev.chatmacro.studio.sched.Clock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Profile create / read / update / delete.
 *
 * <p>Identities are UUIDs, so renaming never moves macros and a duplicate name
 * is a decision the caller makes explicitly ({@code allowDuplicateName}) rather
 * than something that silently merges two profiles.</p>
 *
 * <p>Deletion removes the profile <em>and exactly its own macros</em> in one
 * document swap, so there is no window where the profile is gone but its macros
 * still point at it, and no chance of touching another profile's records.</p>
 */
public final class ProfileRepository {

    private final StudioData data;
    private final Clock clock;
    private final ValidationService validation;

    public ProfileRepository(StudioData data, Clock clock, ValidationService validation) {
        this.data = Objects.requireNonNull(data, "data");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.validation = Objects.requireNonNull(validation, "validation");
    }

    // --------------------------------------------------------------- queries

    public List<Profile> all() {
        return data.document().profiles();
    }

    public int count() {
        return data.document().profiles().size();
    }

    public Optional<Profile> find(UUID id) {
        if (id == null) return Optional.empty();
        for (Profile p : data.document().profiles()) {
            if (p.id().equals(id)) return Optional.of(p);
        }
        return Optional.empty();
    }

    public Optional<Profile> selected() {
        String id = data.document().selectedProfileId();
        if (id.isEmpty()) return Optional.empty();
        try {
            return find(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public boolean nameExists(String name, UUID excludingId) {
        String needle = normalize(name);
        for (Profile p : data.document().profiles()) {
            if (excludingId != null && p.id().equals(excludingId)) continue;
            if (normalize(p.name()).equals(needle)) return true;
        }
        return false;
    }

    /** Case-insensitive, locale-stable comparison key. */
    public static String normalize(String text) {
        return text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
    }

    /** Filtered, sorted view for the list widget. Never re-parses anything. */
    public List<Profile> visible(String query, SortMode sort) {
        String needle = normalize(query);
        List<Profile> out = new ArrayList<>();
        for (Profile p : data.document().profiles()) {
            if (needle.isEmpty()
                    || normalize(p.name()).contains(needle)
                    || normalize(p.description()).contains(needle)) {
                out.add(p);
            }
        }
        Comparator<Profile> comparator = switch (sort == null ? SortMode.NAME_ASC : sort) {
            case NAME_ASC -> Comparator.comparing(p -> normalize(p.name()));
            case NAME_DESC -> Comparator.comparing((Profile p) -> normalize(p.name())).reversed();
            case RECENTLY_USED -> Comparator.comparingLong(Profile::lastUsedAtEpochMillis).reversed();
            case CREATED -> Comparator.comparingLong(Profile::createdAtEpochMillis).reversed();
            case MACRO_COUNT -> Comparator.comparingInt(Profile::macroCount).reversed();
        };
        out.sort(comparator);
        return out;
    }

    // -------------------------------------------------------------- commands

    public RepoResult<Profile> create(String rawName, String rawDescription) {
        if (data.document().profiles().size() >= ConfigCodec.MAX_PROFILES) {
            return RepoResult.error("error.profileLimit", ConfigCodec.MAX_PROFILES);
        }
        ValidationService.Result nameCheck = validation.validateProfileName(rawName, true);
        if (!nameCheck.isValid()) return RepoResult.error(nameCheck.firstError(), nameCheck.firstErrorArgs());
        ValidationService.Result descCheck = validation.validateDescription(rawDescription);
        if (!descCheck.isValid()) return RepoResult.error(descCheck.firstError(), descCheck.firstErrorArgs());
        if (nameExists(nameCheck.value(), null)) {
            // Duplicate names are allowed, but the user is told so the list stays
            // distinguishable; the UI can surface this warning.
            return createProfile(nameCheck.value(), descCheck.value(), "validation.name.duplicate");
        }
        return createProfile(nameCheck.value(), descCheck.value(), "");
    }

    private RepoResult<Profile> createProfile(String name, String description, String warningKey) {
        long now = clock.currentTimeMillis();
        // New profiles start empty and passive: nothing is sent until the user
        // presses start.
        Profile profile = new Profile(UUID.randomUUID(), name, description, List.of(),
                ServerScope.everywhere(), now, now, 0L, false);
        List<Profile> profiles = new ArrayList<>(data.document().profiles());
        profiles.add(profile);
        data.replace(data.document().withProfiles(profiles, now), "profile.create");
        return warningKey.isEmpty()
                ? RepoResult.ok(profile, "profile.created")
                : RepoResult.ok(profile, warningKey);
    }

    public RepoResult<Profile> rename(UUID id, String rawName) {
        Optional<Profile> existing = find(id);
        if (existing.isEmpty()) return RepoResult.error("error.profileNotFound");
        ValidationService.Result check = validation.validateProfileName(rawName, true);
        if (!check.isValid()) return RepoResult.error(check.firstError(), check.firstErrorArgs());
        long now = clock.currentTimeMillis();
        Profile updated = existing.get().withName(check.value(), now);
        return replace(updated, now, "profile.rename",
                nameExists(check.value(), id) ? "validation.name.duplicate" : "profile.renamed");
    }

    public RepoResult<Profile> updateDescription(UUID id, String rawDescription) {
        Optional<Profile> existing = find(id);
        if (existing.isEmpty()) return RepoResult.error("error.profileNotFound");
        ValidationService.Result check = validation.validateDescription(rawDescription);
        if (!check.isValid()) return RepoResult.error(check.firstError(), check.firstErrorArgs());
        long now = clock.currentTimeMillis();
        return replace(existing.get().withDescription(check.value(), now), now,
                "profile.description", "profile.saved");
    }

    public RepoResult<Profile> updateScope(UUID id, ServerScope scope) {
        Optional<Profile> existing = find(id);
        if (existing.isEmpty()) return RepoResult.error("error.profileNotFound");
        if (scope == null) return RepoResult.error("validation.scope.invalid");
        long now = clock.currentTimeMillis();
        return replace(existing.get().withScope(scope, now), now, "profile.scope", "profile.saved");
    }

    public RepoResult<Profile> setAutoStartOnJoin(UUID id, boolean value) {
        Optional<Profile> existing = find(id);
        if (existing.isEmpty()) return RepoResult.error("error.profileNotFound");
        long now = clock.currentTimeMillis();
        return replace(existing.get().withAutoStartOnJoin(value, now), now,
                "profile.autoStart", value ? "profile.autoStartOn" : "profile.autoStartOff");
    }

    private RepoResult<Profile> replace(Profile updated, long now, String changeKind, String messageKey) {
        List<Profile> profiles = new ArrayList<>();
        for (Profile p : data.document().profiles()) {
            profiles.add(p.id().equals(updated.id()) ? updated : p);
        }
        data.replace(data.document().withProfiles(profiles, now), changeKind);
        return RepoResult.ok(updated, messageKey);
    }

    /**
     * Deletes a profile together with exactly the macros that belong to it.
     * Both lists change in a single document swap.
     */
    public RepoResult<Profile> delete(UUID id) {
        Optional<Profile> existing = find(id);
        if (existing.isEmpty()) return RepoResult.error("error.profileNotFound");
        long now = clock.currentTimeMillis();
        List<Profile> profiles = new ArrayList<>();
        for (Profile p : data.document().profiles()) {
            if (!p.id().equals(id)) profiles.add(p);
        }
        List<Macro> macros = new ArrayList<>();
        int removed = 0;
        for (Macro m : data.document().macros()) {
            if (m.profileId().equals(id)) { removed++; continue; }
            macros.add(m);
        }
        ConfigDocument next = data.document()
                .withProfiles(profiles, now)
                .withMacros(macros, now);
        if (next.selectedProfileId().equals(id.toString())) {
            next = next.withSelectedProfile("", now);
        }
        data.replace(next, "profile.delete");
        return RepoResult.ok(existing.get(), "profile.deleted");
    }

    /**
     * Copies a profile. The copy gets a new profile UUID, every macro gets a new
     * UUID, and every copied macro starts disabled.
     */
    public RepoResult<Profile> duplicate(UUID id, String rawName) {
        Optional<Profile> source = find(id);
        if (source.isEmpty()) return RepoResult.error("error.profileNotFound");
        if (data.document().profiles().size() >= ConfigCodec.MAX_PROFILES) {
            return RepoResult.error("error.profileLimit", ConfigCodec.MAX_PROFILES);
        }
        String name = rawName == null || rawName.isBlank()
                ? source.get().name() + " (copy)"
                : rawName.strip();
        ValidationService.Result check = validation.validateProfileName(name, true);
        if (!check.isValid()) return RepoResult.error(check.firstError(), check.firstErrorArgs());

        long now = clock.currentTimeMillis();
        Profile copy = source.get().asCopyWithNewIdentity(UUID.randomUUID(), check.value(), now);

        List<UUID> newIds = new ArrayList<>();
        List<Macro> copiedMacros = new ArrayList<>();
        for (Macro m : data.document().macros()) {
            if (!m.profileId().equals(id)) continue;
            if (copiedMacros.size() >= ConfigCodec.MAX_MACROS_PER_PROFILE) break;
            UUID newMacroId = UUID.randomUUID();
            copiedMacros.add(m.asCopyWithNewIdentity(newMacroId, copy.id(), now));
            newIds.add(newMacroId);
        }
        copy = copy.withMacroIdsNoTouch(newIds);

        List<Profile> profiles = new ArrayList<>(data.document().profiles());
        profiles.add(copy);
        List<Macro> macros = new ArrayList<>(data.document().macros());
        macros.addAll(copiedMacros);
        data.replace(data.document().withProfiles(profiles, now).withMacros(macros, now), "profile.duplicate");
        return RepoResult.ok(copy, "profile.duplicated");
    }

    public RepoResult<Profile> select(UUID id) {
        if (id == null) {
            long now = clock.currentTimeMillis();
            data.replace(data.document().withSelectedProfile("", now), "profile.select");
            return new RepoResult<>(null, true, "profile.cleared", List.of());
        }
        Optional<Profile> existing = find(id);
        if (existing.isEmpty()) return RepoResult.error("error.profileNotFound");
        long now = clock.currentTimeMillis();
        Profile marked = existing.get().markedUsed(now);
        List<Profile> profiles = new ArrayList<>();
        for (Profile p : data.document().profiles()) {
            profiles.add(p.id().equals(id) ? marked : p);
        }
        data.replace(data.document().withProfiles(profiles, now)
                .withSelectedProfile(id.toString(), now), "profile.select");
        return RepoResult.ok(marked, "profile.selected");
    }

    /** Total macro count across every profile - used for the status bar. */
    public int totalMacroCount() {
        return data.document().macros().size();
    }
}
