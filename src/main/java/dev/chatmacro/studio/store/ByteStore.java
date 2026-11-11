package dev.chatmacro.studio.store;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Where configuration bytes live.
 *
 * <p>An interface so persistence can be tested against an in-memory store: the
 * JSON codec, the atomic-save ordering, the corruption recovery and the
 * migration logic are all exercised by real tests without touching a disk.
 * {@link FileByteStore} is the production implementation.</p>
 */
public interface ByteStore {

    /** Reads a stored document, or empty when it does not exist. */
    Optional<byte[]> read(String name) throws StoreException;

    /**
     * Writes durably and atomically: write a temp file, flush it, then replace
     * the target in one rename. A crash mid-write must not truncate the old
     * document.
     */
    void write(String name, byte[] data) throws StoreException;

    /** Copies the current document to {@code name + suffix} if it exists. */
    boolean backup(String name, String suffix) throws StoreException;

    boolean exists(String name);

    List<String> names() throws StoreException;

    /** Wraps IO failures so callers can report a real reason to the user. */
    class StoreException extends RuntimeException {
        public StoreException(String message) { super(message); }
        public StoreException(String message, IOException cause) { super(message, cause); }
    }
}
