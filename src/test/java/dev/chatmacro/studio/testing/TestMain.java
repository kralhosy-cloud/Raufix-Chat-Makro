package dev.chatmacro.studio.testing;

/**
 * Entry point for the project's test suites.
 *
 * <p>Run with {@code ./gradlew offlineTests}. Exits non-zero when anything
 * fails, so it is usable as a CI gate.</p>
 */
public final class TestMain {

    private TestMain() {
    }

    public static void main(String[] args) {
        TestRunner runner = new TestRunner();

        JsonTests.register(runner);
        ValidationTests.register(runner);
        SchedulerTests.register(runner);
        PersistenceTests.register(runner);
        IsolationRegressionTests.register(runner);
        ThemeTests.register(runner);
        UiModelTests.register(runner);
        DispatchAndPrivacyTests.register(runner);

        System.out.println("Chat Macro Studio - offline test suites");
        System.out.println("suites registered: " + runner.count() + " tests");

        int failures = runner.runAll();
        if (failures == 0) {
            System.out.println("RESULT: PASS");
            return;
        }
        System.out.println("RESULT: FAIL");
        // Throwing out of main makes the JVM exit with a non-zero status, which
        // is what `./gradlew offlineTests` and any CI gate look at.
        throw new IllegalStateException(failures + " test(s) failed");
    }
}
