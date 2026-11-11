package dev.chatmacro.studio.sched;

/**
 * Tunable safety knobs for the scheduler.
 *
 * <p>{@code globalMinDispatchGapMillis} is a <em>local product limit</em>, not a
 * universal server rule: no mod can know what a given server allows, and the
 * server's own rules always win. It exists so several enabled macros cannot
 * combine into a burst that no single interval would have produced.</p>
 */
public record SchedulerPolicy(
        long globalMinDispatchGapMillis,
        boolean holdWhenChatOpen,
        boolean pauseWhenGamePaused,
        boolean pauseOnFocusLoss,
        int maxConsecutiveFailures,
        long backoffBaseSeconds,
        long backoffMaxSeconds
) {

    /** Conservative default: at most one send every 3 seconds across the whole mod. */
    public static final long DEFAULT_GLOBAL_GAP_MILLIS = 3_000;
    public static final int DEFAULT_MAX_FAILURES = 5;

    public static SchedulerPolicy defaults() {
        return new SchedulerPolicy(DEFAULT_GLOBAL_GAP_MILLIS, true, true, true,
                DEFAULT_MAX_FAILURES, 2, 60);
    }

    public SchedulerPolicy {
        if (globalMinDispatchGapMillis < 0) globalMinDispatchGapMillis = 0;
        if (maxConsecutiveFailures < 1) maxConsecutiveFailures = 1;
        if (backoffBaseSeconds < 1) backoffBaseSeconds = 1;
        if (backoffMaxSeconds < backoffBaseSeconds) backoffMaxSeconds = backoffBaseSeconds;
    }

    public SchedulerPolicy withGlobalGapMillis(long millis) {
        return new SchedulerPolicy(millis, holdWhenChatOpen, pauseWhenGamePaused, pauseOnFocusLoss,
                maxConsecutiveFailures, backoffBaseSeconds, backoffMaxSeconds);
    }

    /** Exponential backoff capped at {@link #backoffMaxSeconds()}. */
    public long backoffSecondsFor(int consecutiveFailures) {
        if (consecutiveFailures <= 0) return 0;
        int exponent = Math.min(consecutiveFailures - 1, 20);
        long seconds = backoffBaseSeconds * (1L << exponent);
        if (seconds <= 0 || seconds > backoffMaxSeconds) return backoffMaxSeconds;
        return Math.min(seconds, backoffMaxSeconds);
    }
}
