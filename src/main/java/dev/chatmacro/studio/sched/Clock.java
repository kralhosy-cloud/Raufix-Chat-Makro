package dev.chatmacro.studio.sched;

/**
 * Time source for the scheduler.
 *
 * <p>Scheduling uses a <em>monotonic</em> clock ({@link #nanoTime()}), never the
 * wall clock: wall clocks move when the user changes the system time or when
 * NTP corrects it, and a jump backwards must not release a burst of queued
 * messages. The wall clock is only used for human timestamps.</p>
 *
 * <p>Injected so tests can advance time deterministically instead of sleeping.</p>
 */
public interface Clock {

    /** Monotonic nanoseconds. Only differences between values are meaningful. */
    long nanoTime();

    /** Wall clock in epoch milliseconds, for created/updated timestamps only. */
    long currentTimeMillis();

    /** Production clock. */
    Clock SYSTEM = new Clock() {
        @Override public long nanoTime() { return System.nanoTime(); }
        @Override public long currentTimeMillis() { return System.currentTimeMillis(); }
    };

    /** Deterministic clock for tests. Advancing is the only way time moves. */
    final class Fake implements Clock {
        private long nanos;
        private long millis;

        public Fake() {
            this(0L, 1_700_000_000_000L);
        }

        public Fake(long startNanos, long startMillis) {
            this.nanos = startNanos;
            this.millis = startMillis;
        }

        public void advanceMillis(long millis) {
            if (millis < 0) throw new IllegalArgumentException("time only moves forward in tests");
            this.nanos += millis * 1_000_000L;
            this.millis += millis;
        }

        /** Simulates a wall-clock correction; monotonic time is untouched. */
        public void jumpWallClock(long deltaMillis) {
            this.millis += deltaMillis;
        }

        @Override public long nanoTime() { return nanos; }
        @Override public long currentTimeMillis() { return millis; }
    }
}
