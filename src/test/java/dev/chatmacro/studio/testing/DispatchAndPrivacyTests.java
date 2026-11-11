package dev.chatmacro.studio.testing;

import dev.chatmacro.studio.config.ConfigException;
import dev.chatmacro.studio.io.DiagnosticsMasker;
import dev.chatmacro.studio.io.ProfileBundle;
import dev.chatmacro.studio.io.SensitiveCommands;
import dev.chatmacro.studio.model.Macro;
import dev.chatmacro.studio.model.MacroContentType;

import java.util.List;
import java.util.UUID;

/** Dispatch recording, privacy defaults and bundle handling. */
public final class DispatchAndPrivacyTests {

    private DispatchAndPrivacyTests() {
    }

    private static Macro macro(MacroContentType type, String content) {
        return new Macro(UUID.randomUUID(), UUID.randomUUID(), "m", type, content, 30, 0, false, 0L, 0L);
    }

    public static void register(TestRunner r) {

        r.test("privacy", "secret command arguments are detected", () -> {
            Assert.isTrue(SensitiveCommands.hasSecretArguments("/login hunter2"), "login");
            Assert.isTrue(SensitiveCommands.hasSecretArguments("register secret"), "register without slash");
            Assert.isFalse(SensitiveCommands.hasSecretArguments("/list"), "list is not secret");
        });

        r.test("privacy", "high impact commands are flagged for confirmation", () -> {
            Assert.isTrue(SensitiveCommands.isHighImpact("/pay Steve 100"), "pay");
            Assert.isTrue(SensitiveCommands.isHighImpact("/tpaccept"), "tpaccept");
            Assert.isFalse(SensitiveCommands.isHighImpact("/help"), "help");
        });

        r.test("privacy", "masking keeps the command name and drops the arguments", () -> {
            Assert.eq("/login \u2022\u2022\u2022", SensitiveCommands.mask("/login hunter2"), "masked login");
            Assert.eq("pay \u2022\u2022\u2022", SensitiveCommands.mask("pay Steve 100"), "masked pay");
            Assert.eq("/list", SensitiveCommands.mask("/list"), "no arguments to mask");
        });

        r.test("privacy", "free text scrubbing hides secret arguments", () -> {
            String scrubbed = DiagnosticsMasker.scrub("Server said: /login hunter2 was wrong");
            Assert.isFalse(scrubbed.contains("hunter2"), "the secret is gone");
            Assert.contains(scrubbed, "/login", "the command name is still visible");
        });

        r.test("privacy", "a macro description never contains the message body", () -> {
            Macro secret = macro(MacroContentType.COMMAND, "/login hunter2");
            String description = DiagnosticsMasker.describe(secret);
            Assert.isFalse(description.contains("hunter2"), "no secret in the description");
            Assert.contains(description, "COMMAND", "the type is reported");
            Assert.contains(description, "interval=30s", "the cadence is reported");
        });

        r.test("privacy", "server addresses are masked for diagnostics", () -> {
            Assert.eq("***.example.net", DiagnosticsMasker.maskAddress("play.example.net"), "host masked");
            Assert.eq("***.example.net", DiagnosticsMasker.maskAddress("PLAY.Example.NET:25565"), "port dropped");
            Assert.eq("", DiagnosticsMasker.maskAddress(""), "empty stays empty");
        });

        r.test("privacy", "card previews do not split surrogate pairs", () -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 20; i++) sb.append("\uD83C\uDFAE");
            Macro m = macro(MacroContentType.CHAT_MESSAGE, sb.toString());
            String preview = m.preview(5);
            Assert.isFalse(preview.contains("\uD83C\u2026"), "no torn surrogate");
            Assert.contains(preview, "\u2026", "ellipsis present");
            Assert.eq(5, preview.codePointCount(0, preview.length()) - 1, "five emoji plus the ellipsis");
        });

        r.test("bundle", "a non-bundle document is refused", () -> {
            ProfileBundle bundles = new ProfileBundle();
            Throwable t = Assert.catchThrowable(() -> bundles.preview("{\"format\":\"other\"}"), "wrong format");
            Assert.isTrue(t instanceof ConfigException, "a ConfigException is raised");
        });

        r.test("bundle", "a newer bundle version is refused", () -> {
            ProfileBundle bundles = new ProfileBundle();
            Throwable t = Assert.catchThrowable(() -> bundles.preview(
                    "{\"format\":\"chatmacro_studio.export\",\"version\":99}"), "too new");
            Assert.isTrue(t instanceof ConfigException, "a ConfigException is raised");
            Assert.eq(ConfigException.Kind.SCHEMA_TOO_NEW, ((ConfigException) t).kind(), "kind");
        });

        r.test("bundle", "imported intervals are pulled back into range", () -> {
            ProfileBundle bundles = new ProfileBundle();
            ProfileBundle.Preview preview = bundles.preview("{\"format\":\"chatmacro_studio.export\","
                    + "\"version\":1,\"profileName\":\"P\",\"macros\":["
                    + "{\"name\":\"fast\",\"contentType\":\"CHAT_MESSAGE\",\"content\":\"hi\","
                    + "\"intervalSeconds\":0,\"initialDelaySeconds\":-5},"
                    + "{\"name\":\"slow\",\"contentType\":\"COMMAND\",\"content\":\"/list\","
                    + "\"intervalSeconds\":999999,\"initialDelaySeconds\":0}]}");
            List<ProfileBundle.BundleMacro> macros = preview.profile().macros();
            Assert.eq(2, macros.size(), "both macros parsed");
            Assert.eq(1L, macros.get(0).intervalSeconds(), "raised to the minimum");
            Assert.eq(0L, macros.get(0).initialDelaySeconds(), "negative delay removed");
            Assert.isTrue(macros.get(1).intervalSeconds() <= 86_400L, "lowered to the maximum");
            Assert.eq("list", macros.get(1).content(), "leading slash removed");
            Assert.isTrue(preview.problems().size() >= 2, "the corrections are reported");
        });

        r.test("bundle", "bundles report sensitive content before importing", () -> {
            ProfileBundle bundles = new ProfileBundle();
            ProfileBundle.Preview preview = bundles.preview("{\"format\":\"chatmacro_studio.export\","
                    + "\"version\":1,\"profileName\":\"P\",\"macros\":["
                    + "{\"name\":\"a\",\"contentType\":\"COMMAND\",\"content\":\"/login hunter2\","
                    + "\"intervalSeconds\":30},{\"name\":\"b\",\"contentType\":\"COMMAND\","
                    + "\"content\":\"/pay Steve 5\",\"intervalSeconds\":30}]}");
            Assert.isTrue(preview.containsSecrets(), "secrets detected");
            Assert.isTrue(preview.containsSensitive(), "sensitive content flagged");
        });

        r.test("bundle", "empty and malformed macro entries are skipped, not guessed at", () -> {
            ProfileBundle bundles = new ProfileBundle();
            ProfileBundle.Preview preview = bundles.preview("{\"format\":\"chatmacro_studio.export\","
                    + "\"version\":1,\"profileName\":\"P\",\"macros\":["
                    + "{\"name\":\"a\",\"contentType\":\"COMMAND\",\"content\":\"/\",\"intervalSeconds\":30},"
                    + "{\"name\":\"b\",\"contentType\":\"TELEPATHY\",\"content\":\"hi\",\"intervalSeconds\":30},"
                    + "{\"name\":\"c\",\"contentType\":\"CHAT_MESSAGE\",\"content\":\"one\\ntwo\","
                    + "\"intervalSeconds\":30},"
                    + "{\"name\":\"d\",\"contentType\":\"CHAT_MESSAGE\",\"content\":\"ok\",\"intervalSeconds\":30}]}");
            Assert.eq(1, preview.profile().macros().size(), "only the clean entry survives");
            Assert.eq(3, preview.problems().size(), "each rejection is reported");
        });
    }
}
