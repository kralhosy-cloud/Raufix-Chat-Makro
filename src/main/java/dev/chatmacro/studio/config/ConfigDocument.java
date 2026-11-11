package dev.chatmacro.studio.config;

import dev.chatmacro.studio.model.Macro;
import dev.chatmacro.studio.model.Profile;

import java.util.List;
import java.util.Objects;

/**
 * The whole persisted state: schema version, global settings, profiles and
 * macros.
 *
 * <p>Macros live in one flat list but each carries its {@code profileId}, and
 * each profile carries the ordered ids it owns. The codec cross-checks both
 * directions, so a profile can never end up owning a macro that points
 * somewhere else.</p>
 */
public record ConfigDocument(
        int schemaVersion,
        GlobalSettings settings,
        List<Profile> profiles,
        List<Macro> macros,
        String selectedProfileId,
        long savedAtEpochMillis
) {

    public ConfigDocument {
        Objects.requireNonNull(settings, "settings");
        profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
        macros = List.copyOf(Objects.requireNonNull(macros, "macros"));
        selectedProfileId = selectedProfileId == null ? "" : selectedProfileId;
    }

    public static ConfigDocument empty() {
        return new ConfigDocument(ConfigCodec.CURRENT_SCHEMA_VERSION, GlobalSettings.defaults(),
                List.of(), List.of(), "", 0L);
    }

    public ConfigDocument withSettings(GlobalSettings newSettings, long nowEpochMillis) {
        return new ConfigDocument(schemaVersion, newSettings, profiles, macros, selectedProfileId, nowEpochMillis);
    }

    public ConfigDocument withProfiles(List<Profile> newProfiles, long nowEpochMillis) {
        return new ConfigDocument(schemaVersion, settings, newProfiles, macros, selectedProfileId, nowEpochMillis);
    }

    public ConfigDocument withMacros(List<Macro> newMacros, long nowEpochMillis) {
        return new ConfigDocument(schemaVersion, settings, profiles, newMacros, selectedProfileId, nowEpochMillis);
    }

    public ConfigDocument withSelectedProfile(String profileId, long nowEpochMillis) {
        return new ConfigDocument(schemaVersion, settings, profiles, macros,
                profileId == null ? "" : profileId, nowEpochMillis);
    }
}
