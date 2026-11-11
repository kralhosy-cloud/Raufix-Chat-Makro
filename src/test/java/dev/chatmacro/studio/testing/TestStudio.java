package dev.chatmacro.studio.testing;

import dev.chatmacro.studio.Studio;
import dev.chatmacro.studio.dispatch.RecordingDispatchAdapter;
import dev.chatmacro.studio.sched.Clock;
import dev.chatmacro.studio.store.InMemoryByteStore;

/** A fully wired Studio backed by an in-memory store and a controllable clock. */
public final class TestStudio {

    public final Clock.Fake clock = new Clock.Fake();
    public final InMemoryByteStore store = new InMemoryByteStore();
    public final RecordingDispatchAdapter adapter = new RecordingDispatchAdapter();
    public final Studio studio;

    public TestStudio() {
        this.studio = new Studio(store, clock, adapter);
    }

    /** Seconds -> nanos, to keep test arithmetic readable. */
    public static long seconds(long seconds) {
        return seconds * 1_000_000_000L;
    }
}
