package dev.chatmacro.studio.testing;

/**
 * Minimal assertion helpers.
 *
 * <p>The suites are dependency-free on purpose: this project was authored in an
 * environment where Maven Central was unreachable, so JUnit could not be
 * resolved. These assertions are real, they run for real, and a failure really
 * does make {@link TestMain} exit non-zero.</p>
 */
public final class Assert {

    /** Thrown on a failed assertion; the runner reports the message. */
    public static final class AssertionFailure extends RuntimeException {
        public AssertionFailure(String message) {
            super(message);
        }
    }

    private Assert() {
    }

    public static void isTrue(boolean condition, String what) {
        if (!condition) throw new AssertionFailure("expected true: " + what);
    }

    public static void isFalse(boolean condition, String what) {
        if (condition) throw new AssertionFailure("expected false: " + what);
    }

    public static void eq(Object expected, Object actual, String what) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) throw new AssertionFailure(what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    public static void ne(Object notExpected, Object actual, String what) {
        boolean same = notExpected == null ? actual == null : notExpected.equals(actual);
        if (same) throw new AssertionFailure(what + ": expected something other than <" + notExpected + ">");
    }

    public static void eq(long expected, long actual, String what) {
        if (expected != actual) throw new AssertionFailure(what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    public static void eq(double expected, double actual, double tolerance, String what) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionFailure(what + ": expected <" + expected + "> +/- " + tolerance
                    + " but was <" + actual + ">");
        }
    }

    public static void gt(long actual, long bound, String what) {
        if (actual <= bound) throw new AssertionFailure(what + ": expected > " + bound + " but was " + actual);
    }

    public static void lt(long actual, long bound, String what) {
        if (actual >= bound) throw new AssertionFailure(what + ": expected < " + bound + " but was " + actual);
    }

    public static void contains(String haystack, String needle, String what) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new AssertionFailure(what + ": expected to contain <" + needle + "> in <" + haystack + ">");
        }
    }

    public static void fail(String message) {
        throw new AssertionFailure(message);
    }

    /**
     * Runs {@code body} and returns the throwable it threw.
     *
     * <p>Tests then assert on the concrete exception's own fields (for example
     * {@code ConfigException.kind()}), which keeps reflection - and therefore
     * {@code getClass()} - out of the test code.</p>
     */
    public static Throwable catchThrowable(Runnable body, String what) {
        try {
            body.run();
        } catch (Throwable t) {
            return t;
        }
        throw new AssertionFailure(what + ": expected an exception but nothing was thrown");
    }
}
