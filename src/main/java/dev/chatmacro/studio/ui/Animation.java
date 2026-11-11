package dev.chatmacro.studio.ui;

/**
 * Time-based transition, shared by hover, selection and panel open.
 *
 * <p>Progress is derived from elapsed milliseconds, never from a frame count, so
 * a drop in FPS slows nothing down and speeds nothing up. With reduced motion
 * enabled the value snaps to its target immediately.</p>
 */
public final class Animation {

    /** Easing curves. All are monotonic and land exactly on 0/1. */
    public enum Easing {
        LINEAR,
        /** Smooth start and stop; the default for hover/selection. */
        SMOOTH,
        /** Slightly faster settle, used for panel opacity. */
        OUT_CUBIC
    }

    private final long durationMillis;
    private final Easing easing;
    private final boolean reducedMotion;

    private float from;
    private float to;
    private long startedAtMillis;
    private boolean running;

    public Animation(long durationMillis, Easing easing, boolean reducedMotion) {
        this.durationMillis = Math.max(0, durationMillis);
        this.easing = easing == null ? Easing.SMOOTH : easing;
        this.reducedMotion = reducedMotion;
        this.from = 0;
        this.to = 0;
    }

    public void setTo(float target, long nowMillis) {
        if (target == to && running) return;
        if (reducedMotion || durationMillis == 0) {
            from = target;
            to = target;
            running = false;
            return;
        }
        from = valueAt(nowMillis);
        to = target;
        startedAtMillis = nowMillis;
        running = true;
    }

    public void snapTo(float value) {
        from = value;
        to = value;
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    public float target() {
        return to;
    }

    public float valueAt(long nowMillis) {
        if (!running) return to;
        long elapsed = nowMillis - startedAtMillis;
        if (elapsed <= 0) return from;
        if (elapsed >= durationMillis) {
            running = false;
            from = to;
            return to;
        }
        float t = elapsed / (float) durationMillis;
        return from + (to - from) * apply(t);
    }

    private float apply(float t) {
        return switch (easing) {
            case LINEAR -> t;
            case SMOOTH -> t * t * (3 - 2 * t);
            case OUT_CUBIC -> {
                float inv = 1 - t;
                yield 1 - inv * inv * inv;
            }
        };
    }

    /** True when no animation is in flight - used to stop redrawing. */
    public boolean isSettled() {
        return !running;
    }
}
