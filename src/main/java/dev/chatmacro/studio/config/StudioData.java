package dev.chatmacro.studio.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Mutable holder for the current {@link ConfigDocument}.
 *
 * <p>Every mutation goes through {@link #replace(ConfigDocument)}, which is a
 * single atomic swap of an immutable document plus one change notification.
 * There is no half-updated state to observe, and no code path that can write
 * profiles without macros or the other way round.</p>
 */
public final class StudioData {

    /** Notified after every successful mutation. */
    public interface Listener {
        void onDocumentChanged(ConfigDocument document, String changeKind);
    }

    private final List<Listener> listeners = new ArrayList<>();
    private ConfigDocument document;

    public StudioData(ConfigDocument initial) {
        this.document = Objects.requireNonNull(initial, "initial");
    }

    public ConfigDocument document() {
        return document;
    }

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Swaps in a new document and notifies. {@code changeKind} is for the UI. */
    public void replace(ConfigDocument next, String changeKind) {
        this.document = Objects.requireNonNull(next, "next");
        for (Listener listener : listeners) listener.onDocumentChanged(document, changeKind);
    }
}
