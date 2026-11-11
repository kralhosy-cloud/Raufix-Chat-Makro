package dev.chatmacro.studio.testing;

import dev.chatmacro.studio.util.Json;

/** JSON model, parser and writer. */
public final class JsonTests {

    /** Small helper so the assertion above does not need a public util import. */
    private static final class ValidationServiceForTest {
        static boolean containsUnpaired(String text) {
            return dev.chatmacro.studio.model.ValidationService.containsUnpairedSurrogate(text);
        }
    }

    private JsonTests() {
    }

    public static void register(TestRunner r) {
        r.test("json", "round-trips every value kind", () -> {
            Json.Obj root = Json.Obj.of();
            root.with("s", Json.str("hello"));
            root.with("n", Json.num(-42));
            root.with("b", Json.bool(true));
            root.with("nil", Json.nil());
            root.with("arr", Json.Arr.of().add(Json.num(1)).add(Json.str("two")));
            String text = Json.write(root);
            Json.Value parsed = Json.parse(text);
            Json.Obj obj = parsed.asObj();
            Assert.eq("hello", obj.getString("s", null), "string");
            Assert.eq(-42L, obj.getLong("n", 0), "number");
            Assert.isTrue(obj.getBoolean("b", false), "boolean");
            Assert.isTrue(obj.get("nil") instanceof Json.Nil, "null");
            Assert.eq(2, obj.getArr("arr").size(), "array size");
        });

        r.test("json", "keeps Turkish characters and emoji", () -> {
            String text = "Türkçe ıİşğçöü \u2014 \uD83C\uDFAE ok";
            String written = Json.write(Json.str(text));
            Json.Value parsed = Json.parse(written);
            Assert.eq(text, parsed.asStr().value(), "unicode round trip");
        });

        r.test("json", "keeps a surrogate pair intact through a write and parse", () -> {
            String written = Json.write(Json.str("a\uD83C\uDFAEb"));
            Assert.contains(written, "\uD83C\uDFAE", "the pair is written as one character");
            Assert.eq("a\uD83C\uDFAEb", Json.parse(written).asStr().value(), "surrogate pair");
            Assert.isFalse(ValidationServiceForTest.containsUnpaired(written), "still well formed");
        });

        r.test("json", "escapes control characters on write", () -> {
            String written = Json.write(Json.str("a\tb\u0001c"));
            Assert.contains(written, "\\t", "tab escaped");
            Assert.contains(written, "\\u0001", "control escaped");
            Assert.eq("a\tb\u0001c", Json.parse(written).asStr().value(), "control round trip");
        });

        r.test("json", "rejects raw control characters in input", () -> {
            Throwable t = Assert.catchThrowable(() -> Json.parse("{\"a\":\"x\u0000y\"}"), "control char");
            Assert.contains(t.toString(), "control", "reason mentions control characters");
        });

        r.test("json", "rejects trailing content", () -> {
            Throwable t = Assert.catchThrowable(() -> Json.parse("{} {}"), "trailing");
            Assert.contains(t.toString(), "trailing", "reason mentions trailing content");
        });

        r.test("json", "rejects duplicate keys", () -> {
            Throwable t = Assert.catchThrowable(() -> Json.parse("{\"a\":1,\"a\":2}"), "duplicate");
            Assert.contains(t.toString(), "duplicate", "reason mentions duplicate key");
        });

        r.test("json", "rejects unpaired surrogates", () -> {
            Assert.catchThrowable(() -> Json.parse("{\"a\":\"\\ud83c\"}"), "lone high surrogate");
            Assert.catchThrowable(() -> Json.parse("{\"a\":\"\\udfae\"}"), "lone low surrogate");
        });

        r.test("json", "rejects invalid escapes and unterminated input", () -> {
            Assert.catchThrowable(() -> Json.parse("{\"a\":\"\\q\"}"), "bad escape");
            Assert.catchThrowable(() -> Json.parse("{\"a\":\"abc"), "unterminated string");
            Assert.catchThrowable(() -> Json.parse("{\"a\":1"), "unterminated object");
            Assert.catchThrowable(() -> Json.parse(""), "empty document");
        });

        r.test("json", "enforces a nesting limit", () -> {
            StringBuilder deep = new StringBuilder();
            for (int i = 0; i < Json.MAX_DEPTH + 5; i++) deep.append('[');
            for (int i = 0; i < Json.MAX_DEPTH + 5; i++) deep.append(']');
            String input = deep.toString();
            Throwable t = Assert.catchThrowable(() -> Json.parse(input), "too deep");
            Assert.contains(t.toString(), "deep", "reason mentions nesting depth");
        });

        r.test("json", "rejects oversized documents", () -> {
            StringBuilder huge = new StringBuilder("[");
            for (int i = 0; i < 4096; i++) huge.append('a');
            String input = huge.toString();
            Throwable t = Assert.catchThrowable(() -> Json.parse(input, 1024), "too large");
            Assert.contains(t.toString(), "large", "reason mentions size");
            Assert.gt(Json.MAX_INPUT_CHARS, 1_000_000, "the production limit is a real ceiling");
        });

        r.test("json", "control characters escape to ASCII hex in any default locale", () -> {
            // A locale with non-ASCII digits (ar, fa) makes %x emit localised
            // digits, which would write an invalid JSON escape and corrupt the
            // config file. The writer must pin Locale.ROOT.
            java.util.Locale previous = java.util.Locale.getDefault();
            try {
                java.util.Locale[] candidates = {
                        java.util.Locale.ROOT, java.util.Locale.US,
                        new java.util.Locale("tr", "TR"),
                        new java.util.Locale("ar", "EG"),
                        new java.util.Locale("fa", "IR") };
                for (java.util.Locale candidate : candidates) {
                    java.util.Locale.setDefault(candidate);
                    String written = Json.write(Json.str("\u0001\u001F\u007F"));
                    for (int i = 1; i + 6 <= written.length() - 1; i += 6) {
                        String escape = written.substring(i, i + 6);
                        Assert.isTrue(escape.matches("\\\\u[0-9a-f]{4}"),
                                "ASCII hex escape under " + candidate + ", was: " + escape);
                    }
                }
            } finally {
                java.util.Locale.setDefault(previous);
            }
        });

        r.test("json", "type mismatches are reported, not defaulted", () -> {
            Json.Value v = Json.parse("{\"a\":\"text\"}");
            Throwable t = Assert.catchThrowable(() -> v.asObj().get("a").asNum(), "wrong type");
            Assert.contains(t.toString(), "expected number", "reason names the expected type");
        });
    }
}
