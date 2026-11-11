package dev.chatmacro.studio.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A macro profile: an identity plus an ordered list of the macro ids that belong
 * to it.
 *
 * <p>The profile owns the ordering; each {@link Macro} owns the back-reference
 * ({@code profileId}). Both directions are validated on load, so a macro can
 * never end up displayed under one profile and saved under another.</p>
 *
 * <p>The name is a display field, not a key: renaming never touches the id or
 * the macro list.</p>
 */
public record Profile(
        UUID id,
        String name,
        String description,
        List<UUID> macroIds,
        ServerScope scope,
        long createdAtEpochMillis,
        long updatedAtEpochMillis,
        long lastUsedAtEpochMillis,
        boolean autoStartOnJoin
) {

    public Profile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(scope, "scope");
        macroIds = List.copyOf(Objects.requireNonNull(macroIds, "macroIds"));
        description = description == null ? "" : description;
    }

    public static Profile create(UUID id, String name, long nowEpochMillis) {
        return new Profile(id, name, "", List.of(), ServerScope.everywhere(),
                nowEpochMillis, nowEpochMillis, 0L, false);
    }

    public int macroCount() {
        return macroIds.size();
    }

    public Profile withName(String newName, long nowEpochMillis) {
        return new Profile(id, newName, description, macroIds, scope,
                createdAtEpochMillis, nowEpochMillis, lastUsedAtEpochMillis, autoStartOnJoin);
    }

    public Profile withDescription(String newDescription, long nowEpochMillis) {
        return new Profile(id, name, newDescription, macroIds, scope,
                createdAtEpochMillis, nowEpochMillis, lastUsedAtEpochMillis, autoStartOnJoin);
    }

    public Profile withScope(ServerScope newScope, long nowEpochMillis) {
        return new Profile(id, name, description, macroIds, newScope,
                createdAtEpochMillis, nowEpochMillis, lastUsedAtEpochMillis, autoStartOnJoin);
    }

    public Profile withAutoStartOnJoin(boolean value, long nowEpochMillis) {
        return new Profile(id, name, description, macroIds, scope,
                createdAtEpochMillis, nowEpochMillis, lastUsedAtEpochMillis, value);
    }

    public Profile withMacroIds(List<UUID> newIds, long nowEpochMillis) {
        return new Profile(id, name, description, newIds, scope,
                createdAtEpochMillis, nowEpochMillis, lastUsedAtEpochMillis, autoStartOnJoin);
    }

    public Profile withMacroIdsNoTouch(List<UUID> newIds) {
        return new Profile(id, name, description, newIds, scope,
                createdAtEpochMillis, updatedAtEpochMillis, lastUsedAtEpochMillis, autoStartOnJoin);
    }

    public Profile markedUsed(long nowEpochMillis) {
        return new Profile(id, name, description, macroIds, scope,
                createdAtEpochMillis, updatedAtEpochMillis, nowEpochMillis, autoStartOnJoin);
    }

    /** Deep copy with a fresh profile id and fresh macro ids. */
    public Profile asCopyWithNewIdentity(UUID newProfileId, String newName, long nowEpochMillis) {
        return new Profile(newProfileId, newName, description, List.of(), scope,
                nowEpochMillis, nowEpochMillis, 0L, /* copies never auto-start */ false);
    }
}
