package dev.chatmacro.studio.store;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Production store: one directory under {@code .minecraft/config/chatmacro_studio}.
 *
 * <p>Save order is: write a uniquely named temp file in the same directory, then
 * rename it over the target. A rename inside one directory is atomic on the
 * filesystems Minecraft players actually use, so an interrupted save leaves the
 * previous document intact instead of a truncated one. If the filesystem refuses
 * an atomic move the code falls back to a plain replace and records that it did,
 * rather than claiming atomicity it did not get.</p>
 *
 * <p>Durability note: this deliberately does not call {@code fsync}. The rename
 * is what protects the previous document; an {@code fsync} would additionally
 * protect against a power cut losing the newest save. See
 * KNOWN_LIMITATIONS.md.</p>
 */
public final class FileByteStore implements ByteStore {

    /** Refuses absurd documents; a safety limit, not a product quota. */
    public static final long MAX_DOCUMENT_BYTES = 8L * 1024 * 1024;

    private static final int READ_BUFFER = 8192;

    private final Path directory;
    private boolean lastMoveWasNotAtomic;

    public FileByteStore(Path directory) {
        this.directory = directory;
    }

    public Path directory() {
        return directory;
    }

    /** True when the last save had to fall back to a non-atomic replace. */
    public boolean lastMoveWasNotAtomic() {
        return lastMoveWasNotAtomic;
    }

    private void ensureDirectory() throws IOException {
        if (!Files.exists(directory)) Files.createDirectories(directory);
    }

    private Path resolve(String name) {
        // Reject anything that could escape the config directory.
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")
                || name.contains("..")) {
            throw new StoreException("illegal document name");
        }
        return directory.resolve(name);
    }

    @Override
    public Optional<byte[]> read(String name) throws StoreException {
        Path path = resolve(name);
        if (!Files.exists(path)) return Optional.empty();
        try {
            long size = Files.size(path);
            if (size > MAX_DOCUMENT_BYTES) {
                throw new StoreException("document too large: " + size + " bytes");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16, (int) size));
            try (InputStream in = Files.newInputStream(path, StandardOpenOption.READ)) {
                byte[] buffer = new byte[READ_BUFFER];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            }
            return Optional.of(out.toByteArray());
        } catch (IOException e) {
            throw new StoreException("could not read " + name, e);
        }
    }

    @Override
    public void write(String name, byte[] data) throws StoreException {
        Path target = resolve(name);
        try {
            ensureDirectory();
        } catch (IOException e) {
            throw new StoreException("could not create config directory " + directory, e);
        }
        Path temp = directory.resolve(name + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temp, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                lastMoveWasNotAtomic = false;
            } catch (IOException atomicFailure) {
                // Not every filesystem supports ATOMIC_MOVE. Fall back and say so.
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                lastMoveWasNotAtomic = true;
            }
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new StoreException("could not write " + name, e);
        }
    }

    @Override
    public boolean backup(String name, String suffix) throws StoreException {
        Path source = resolve(name);
        if (!Files.exists(source)) return false;
        Path target = resolve(name + suffix);
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            throw new StoreException("could not back up " + name, e);
        }
    }

    @Override
    public boolean exists(String name) {
        try {
            return Files.exists(resolve(name));
        } catch (StoreException e) {
            return false;
        }
    }

    @Override
    public List<String> names() throws StoreException {
        List<String> out = new ArrayList<>();
        try {
            if (!Files.exists(directory)) return out;
            try (Stream<Path> stream = Files.list(directory)) {
                stream.forEach(p -> {
                    String fileName = p.getFileName().toString();
                    if (!fileName.contains(".tmp")) out.add(fileName);
                });
            }
            return out;
        } catch (IOException e) {
            throw new StoreException("could not list " + directory, e);
        }
    }

    public static String asText(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Nothing useful to do while already reporting a failure.
        }
    }
}
