package dev.chatmacro.studio.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory store used by tests and as a fallback when the disk is unusable. */
public final class InMemoryByteStore implements ByteStore {

    private final Map<String, byte[]> data = new LinkedHashMap<>();
    private boolean failWrites;

    public void setFailWrites(boolean value) {
        this.failWrites = value;
    }

    @Override
    public Optional<byte[]> read(String name) {
        byte[] bytes = data.get(name);
        return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
    }

    @Override
    public void write(String name, byte[] bytes) {
        if (failWrites) throw new StoreException("simulated write failure");
        data.put(name, bytes.clone());
    }

    @Override
    public boolean backup(String name, String suffix) {
        byte[] bytes = data.get(name);
        if (bytes == null) return false;
        data.put(name + suffix, bytes.clone());
        return true;
    }

    @Override
    public boolean exists(String name) {
        return data.containsKey(name);
    }

    @Override
    public List<String> names() {
        return new ArrayList<>(data.keySet());
    }
}
