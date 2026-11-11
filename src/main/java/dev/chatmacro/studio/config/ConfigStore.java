package dev.chatmacro.studio.config;

import dev.chatmacro.studio.sched.Clock;
import dev.chatmacro.studio.store.ByteStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Loads and saves {@link ConfigDocument}, with debounced writes.
 *
 * <p>Behaviour the user can rely on:</p>
 * <ul>
 *   <li>Writing is atomic (see {@code FileByteStore}); the file is never
 *       rewritten on every tick, only after a quiet period or on shutdown.</li>
 *   <li>A corrupt file is copied aside and reported. It is never silently
 *       replaced by empty defaults.</li>
 *   <li>A file written by a <em>newer</em> schema puts the store into read-only
 *       mode, so this build cannot delete fields it does not understand.</li>
 *   <li>A failed save is reported with the real reason; in-memory state is not
 *       presented as if it had been persisted.</li>
 * </ul>
 */
public final class ConfigStore {

    public static final String DOCUMENT_NAME = "chatmacro_studio.json";
    public static final String BACKUP_SUFFIX = ".bak";
    public static final long DEBOUNCE_MILLIS = 1_500;

    /** What a load produced, including anything the user must be told. */
    public record LoadOutcome(
            ConfigDocument document,
            ConfigException.Kind fatalKind,
            String fatalMessage,
            List<String> problems,
            String preservedCopy,
            boolean readOnly
    ) {
        public boolean failed() { return fatalKind != null; }
    }

    private final ByteStore store;
    private final ConfigCodec codec = new ConfigCodec();
    private final Clock clock;

    private ConfigDocument pending;
    private long dirtyAtMillis = Long.MIN_VALUE;
    private boolean readOnly;
    private String lastSaveError = "";
    private boolean lastSavePersisted;

    public ConfigStore(ByteStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public String lastSaveError() {
        return lastSaveError;
    }

    /** True when the most recent save really reached the store. */
    public boolean lastSavePersisted() {
        return lastSavePersisted;
    }

    public boolean hasPendingChanges() {
        return pending != null;
    }

    public LoadOutcome load() {
        List<String> problems = new ArrayList<>();
        if (!store.exists(DOCUMENT_NAME)) {
            return new LoadOutcome(ConfigDocument.empty(), null, "", List.of(), "", false);
        }
        Optional<byte[]> bytes;
        try {
            bytes = store.read(DOCUMENT_NAME);
        } catch (ByteStore.StoreException e) {
            readOnly = true;
            return new LoadOutcome(ConfigDocument.empty(), ConfigException.Kind.IO_ERROR,
                    e.toString(), List.of(), "", true);
        }
        if (bytes.isEmpty()) {
            return new LoadOutcome(ConfigDocument.empty(), null, "", List.of(), "", false);
        }
        String json = new String(bytes.get(), StandardCharsets.UTF_8);
        try {
            ConfigCodec.DecodeResult result = codec.decode(json);
            problems.addAll(result.problems());
            return new LoadOutcome(result.document(), null, "", List.copyOf(problems), "", false);
        } catch (ConfigException e) {
            String preserved = preserveCopy(e.kind());
            if (e.kind() == ConfigException.Kind.SCHEMA_TOO_NEW) {
                // Do not touch the file: a newer build may hold data we cannot
                // represent, and saving would erase it.
                readOnly = true;
                return new LoadOutcome(ConfigDocument.empty(), e.kind(), e.toString(),
                        List.of(), preserved, true);
            }
            problems.add("configuration could not be read and was set aside as " + preserved);
            return new LoadOutcome(ConfigDocument.empty(), e.kind(), e.toString(),
                    List.copyOf(problems), preserved, false);
        }
    }

    /** Copies the damaged file aside so recovery stays possible. */
    private String preserveCopy(ConfigException.Kind kind) {
        String suffix = kind == ConfigException.Kind.SCHEMA_TOO_NEW
                ? ".newer-version"
                : ".corrupt-" + clock.currentTimeMillis();
        try {
            return store.backup(DOCUMENT_NAME, suffix) ? DOCUMENT_NAME + suffix : "";
        } catch (ByteStore.StoreException e) {
            return "";
        }
    }

    /** Immediate save. Throws so the caller can show a real error. */
    public void saveNow(ConfigDocument document) throws ConfigException {
        Objects.requireNonNull(document, "document");
        if (readOnly) {
            lastSavePersisted = false;
            lastSaveError = "config.readOnly";
            throw new ConfigException(ConfigException.Kind.IO_ERROR,
                    "store is read-only because the file on disk was written by a newer version");
        }
        String json;
        try {
            json = codec.encode(document);
        } catch (RuntimeException e) {
            lastSavePersisted = false;
            lastSaveError = "config.encodeFailed";
            throw new ConfigException(ConfigException.Kind.IO_ERROR, "could not encode configuration: " + e);
        }
        try {
            store.write(DOCUMENT_NAME, json.getBytes(StandardCharsets.UTF_8));
            pending = null;
            dirtyAtMillis = Long.MIN_VALUE;
            lastSavePersisted = true;
            lastSaveError = "";
        } catch (ByteStore.StoreException e) {
            lastSavePersisted = false;
            lastSaveError = e.toString();
            throw new ConfigException(ConfigException.Kind.IO_ERROR, "could not write configuration: " + e);
        }
    }

    /** Queues a save; the write happens after the debounce window or on flush. */
    public void markDirty(ConfigDocument document) {
        this.pending = Objects.requireNonNull(document, "document");
        if (dirtyAtMillis == Long.MIN_VALUE) dirtyAtMillis = clock.currentTimeMillis();
    }

    /** Called from the client tick; performs the debounced write. */
    public void tick() {
        if (pending == null || dirtyAtMillis == Long.MIN_VALUE) return;
        if (clock.currentTimeMillis() - dirtyAtMillis < DEBOUNCE_MILLIS) return;
        try {
            saveNow(pending);
        } catch (ConfigException e) {
            // Keep the pending document so the next attempt can still succeed,
            // and surface the reason through lastSaveError().
            dirtyAtMillis = clock.currentTimeMillis();
        }
    }

    /** Writes anything outstanding; used on shutdown and before a screen closes. */
    public void flush() {
        if (pending == null) return;
        try {
            saveNow(pending);
        } catch (ConfigException e) {
            // Reported through lastSaveError(); there is nothing else safe to do.
        }
    }

    public ConfigCodec codec() {
        return codec;
    }
}
