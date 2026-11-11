package dev.chatmacro.studio.testing;

import dev.chatmacro.studio.model.MacroContentType;
import dev.chatmacro.studio.model.ValidationService;

/** Input validation: names, content, commands, intervals, unicode. */
public final class ValidationTests {

    private static final ValidationService V = new ValidationService();

    private ValidationTests() {
    }

    public static void register(TestRunner r) {
        r.test("validation", "rejects empty and blank names", () -> {
            Assert.isFalse(V.validateProfileName("", true).isValid(), "empty name");
            Assert.isFalse(V.validateProfileName("   ", true).isValid(), "blank name");
            Assert.isFalse(V.validateProfileName(null, true).isValid(), "null name");
        });

        r.test("validation", "trims names and keeps unicode", () -> {
            ValidationService.Result ok = V.validateProfileName("  Gündüz \u0130\u015Fleri  ", true);
            Assert.isTrue(ok.isValid(), "valid name");
            Assert.eq("Gündüz \u0130\u015Fleri", ok.value(), "trimmed name");
        });

        r.test("validation", "flags duplicate names without rejecting them", () -> {
            ValidationService.Result result = V.validateProfileName("Main", false);
            Assert.isTrue(result.isValid(), "duplicate is still valid");
            Assert.isTrue(result.hasWarnings(), "duplicate raises a warning");
            Assert.eq("validation.name.duplicate", result.warnings().get(0), "warning key");
        });

        r.test("validation", "rejects overly long names", () -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ValidationService.MAX_NAME_CHARS + 1; i++) sb.append('a');
            Assert.isFalse(V.validateProfileName(sb.toString(), true).isValid(), "long name");
        });

        r.test("validation", "counts emoji as one character, not two", () -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ValidationService.MAX_NAME_CHARS; i++) sb.append("\uD83C\uDFAE");
            Assert.isTrue(V.validateProfileName(sb.toString(), true).isValid(),
                    "emoji length counted in code points");
            sb.append("\uD83C\uDFAE");
            Assert.isFalse(V.validateProfileName(sb.toString(), true).isValid(), "one emoji too many");
        });

        r.test("validation", "rejects empty content", () -> {
            ValidationService.Result result = V.validateContent(MacroContentType.CHAT_MESSAGE, "   ");
            Assert.isFalse(result.isValid(), "blank content");
            Assert.eq("validation.content.empty", result.firstError(), "error key");
        });

        r.test("validation", "rejects newlines so one field cannot become several sends", () -> {
            ValidationService.Result result = V.validateContent(MacroContentType.CHAT_MESSAGE, "hello\n/pay 100");
            Assert.isFalse(result.isValid(), "newline rejected");
            Assert.eq("validation.content.newline", result.firstError(), "error key");
        });

        r.test("validation", "rejects control characters and the section sign", () -> {
            Assert.isFalse(V.validateContent(MacroContentType.CHAT_MESSAGE, "a\u0000b").isValid(), "NUL");
            Assert.isFalse(V.validateContent(MacroContentType.CHAT_MESSAGE, "a\u00a7cb").isValid(), "section sign");
        });

        r.test("validation", "reports the exact overflow instead of truncating", () -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ValidationService.DEFAULT_MAX_CHAT_CHARS + 5; i++) sb.append('x');
            ValidationService.Result result = V.validateContent(MacroContentType.CHAT_MESSAGE, sb.toString());
            Assert.isFalse(result.isValid(), "too long");
            Assert.eq("validation.content.tooLong", result.firstError(), "error key");
            Assert.eq(2, result.firstErrorArgs().length, "carries actual and limit");
        });

        r.test("validation", "strips exactly one leading slash from a command", () -> {
            ValidationService.Result result = V.validateContent(MacroContentType.COMMAND, "/pay 100 a/b");
            Assert.isTrue(result.isValid(), "valid command");
            Assert.eq("pay 100 a/b", result.value(), "one slash removed, inner slashes kept");
        });

        r.test("validation", "accepts a command typed without a slash", () -> {
            ValidationService.Result result = V.validateContent(MacroContentType.COMMAND, "list");
            Assert.isTrue(result.isValid(), "valid command");
            Assert.eq("list", result.value(), "body unchanged");
        });

        r.test("validation", "rejects a lone slash", () -> {
            ValidationService.Result result = V.validateContent(MacroContentType.COMMAND, "/");
            Assert.isFalse(result.isValid(), "slash only");
            Assert.eq("validation.command.slashOnly", result.firstError(), "error key");
        });

        r.test("validation", "display helper adds the slash back once", () -> {
            Assert.eq("/list", ValidationService.displayCommand("list"), "adds slash");
            Assert.eq("/list", ValidationService.displayCommand("/list"), "does not double");
        });

        r.test("validation", "rejects zero, negative, NaN and infinite intervals", () -> {
            Assert.eq("validation.interval.notPositive",
                    V.validateIntervalSeconds(0).firstError(), "zero");
            Assert.eq("validation.interval.notPositive",
                    V.validateIntervalSeconds(-5).firstError(), "negative");
            Assert.eq("validation.interval.nan",
                    V.validateIntervalSeconds(Double.NaN).firstError(), "NaN");
            Assert.eq("validation.interval.infinite",
                    V.validateIntervalSeconds(Double.POSITIVE_INFINITY).firstError(), "infinity");
        });

        r.test("validation", "rejects sub-second and absurd intervals", () -> {
            Assert.eq("validation.interval.tooSmall",
                    V.validateIntervalSeconds(0.5).firstError(), "half a second");
            Assert.eq("validation.interval.tooLarge",
                    V.validateIntervalSeconds(ValidationService.MAX_INTERVAL_SECONDS + 1).firstError(),
                    "beyond a day");
            Assert.isFalse(V.validateIntervalSeconds(Long.MAX_VALUE).isValid(), "long overflow input");
        });

        r.test("validation", "accepts the documented bounds", () -> {
            Assert.isTrue(V.validateIntervalSeconds(1).isValid(), "1 second");
            Assert.isTrue(V.validateIntervalSeconds(ValidationService.MAX_INTERVAL_SECONDS).isValid(), "24 hours");
            Assert.eq(30L, V.validateIntervalSeconds(30).valueAsLong(), "default-ish value");
        });

        r.test("validation", "initial delay allows zero but not negative", () -> {
            Assert.isTrue(V.validateInitialDelaySeconds(0).isValid(), "zero delay");
            Assert.isFalse(V.validateInitialDelaySeconds(-1).isValid(), "negative delay");
        });

        r.test("validation", "content limits are clamped into a safe range", () -> {
            ValidationService wide = new ValidationService(1, 999_999);
            Assert.gt(wide.maxChatChars(), 1, "lower clamp");
            Assert.eq(ValidationService.ABSOLUTE_MAX_CHARS, wide.maxCommandChars(), "upper clamp");
        });
    }
}
