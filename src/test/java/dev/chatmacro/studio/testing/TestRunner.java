package dev.chatmacro.studio.testing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects and runs the suites, printing one line per test.
 *
 * <p>Deliberately tiny: no annotations, no discovery, no classpath scanning. A
 * suite registers its cases with {@link #test(String, String, Body)} and
 * {@link TestMain} calls {@link #runAll()}, which exits non-zero on the first
 * failing count.</p>
 */
public final class TestRunner {

    /** A test body. Declared to allow checked exceptions so tests stay readable. */
    public interface Body {
        void run() throws Exception;
    }

    private record Case(String suite, String name, Body body) { }

    private final List<Case> cases = new ArrayList<>();
    private final Map<String, Integer> suiteTotals = new LinkedHashMap<>();
    private final Map<String, Integer> suiteFailures = new LinkedHashMap<>();

    public void test(String suite, String name, Body body) {
        cases.add(new Case(suite, name, body));
        suiteTotals.merge(suite, 1, Integer::sum);
    }

    public int count() {
        return cases.size();
    }

    /** Runs everything. Returns the number of failures. */
    public int runAll() {
        String currentSuite = null;
        int failures = 0;
        int passed = 0;

        for (Case testCase : cases) {
            if (!testCase.suite().equals(currentSuite)) {
                currentSuite = testCase.suite();
                System.out.println();
                System.out.println("== " + currentSuite + " ==");
            }
            try {
                testCase.body().run();
                passed++;
                System.out.println("  PASS  " + testCase.name());
            } catch (Throwable t) {
                failures++;
                suiteFailures.merge(testCase.suite(), 1, Integer::sum);
                System.out.println("  FAIL  " + testCase.name());
                System.out.println("        " + describe(t));
            }
        }

        System.out.println();
        System.out.println("---------------------------------------------");
        for (Map.Entry<String, Integer> entry : suiteTotals.entrySet()) {
            int failed = suiteFailures.getOrDefault(entry.getKey(), 0);
            System.out.println("  " + entry.getKey() + ": " + entry.getValue()
                    + " tests, " + failed + " failed");
        }
        System.out.println("---------------------------------------------");
        System.out.println("TOTAL " + passed + " passed, " + failures + " failed, "
                + cases.size() + " tests");
        return failures;
    }

    private static String describe(Throwable t) {
        String text = t.toString();
        return text.length() > 400 ? text.substring(0, 400) + "\u2026" : text;
    }
}
