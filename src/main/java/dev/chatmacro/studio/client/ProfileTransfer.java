package dev.chatmacro.studio.client;

import dev.chatmacro.studio.Studio;
import dev.chatmacro.studio.config.ConfigException;
import dev.chatmacro.studio.io.ProfileBundle;
import dev.chatmacro.studio.model.Profile;
import dev.chatmacro.studio.repo.RepoResult;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Reads and writes profile bundles (".cmsprofile" JSON) in the game directory.
 *
 * <p>This is the only place in the mod that touches a file outside the config
 * document, so the rules are stated once here:</p>
 * <ul>
 *   <li>exports always land in {@code config/chatmacro_studio/exports/}, never
 *       anywhere the caller chooses;</li>
 *   <li>the file name is derived from the profile name and then sanitised, and
 *       the resulting path is checked to still be inside the exports
 *       directory;</li>
 *   <li>an existing export file is never overwritten - a counter is
 *       appended;</li>
 *   <li>imports are read as text only. Nothing is written until the user has
 *       confirmed the preview, and imported macros are disabled with new
 *       UUIDs by {@link Studio#importProfile}.</li>
 * </ul>
 */
public final class ProfileTransfer {

    public record ExportResult(boolean success, String messageKey, Path path) {
    }

    public record ImportResult(boolean success, String messageKey, ProfileBundle.Preview preview,
                               String json) {
    }

    private final Path exportsDir;

    public ProfileTransfer() {
        MinecraftClient client = MinecraftClient.getInstance();
        this.exportsDir = client == null
                ? Paths.get("config", "chatmacro_studio", "exports")
                : client.runDirectory.toPath()
                        .resolve("config").resolve("chatmacro_studio").resolve("exports");
    }

    public Path exportsDirectory() {
        return exportsDir;
    }

    // ---------------------------------------------------------------- export

    /**
     * Writes the profile as a bundle. Secrets such as {@code /login} arguments
     * are masked by default; {@code includeSensitive} is only used by the
     * explicit "export including secrets" action, which is confirmed first.
     */
    public ExportResult export(Studio studio, Profile profile, boolean includeSensitive) {
        try {
            Files.createDirectories(exportsDir);
            Path target = freePath(sanitise(profile.name()));
            if (target == null) return new ExportResult(false, "export.failed", null);
            String json = includeSensitive
                    ? studio.exportProfileIncludingSensitive(profile)
                    : studio.exportProfile(profile);
            Files.writeString(target, json, StandardCharsets.UTF_8);
            return new ExportResult(true, includeSensitive ? "export.doneWithSecrets" : "export.done",
                    exportsDir.relativize(target));
        } catch (IOException e) {
            return new ExportResult(false, "export.failed", null);
        }
    }

    /** Never overwrites; returns null if a sane name cannot be found. */
    private Path freePath(String base) {
        for (int i = 0; i < 1000; i++) {
            String name = (i == 0 ? base : base + "-" + i) + ".cmsprofile";
            Path candidate = exportsDir.resolve(name).normalize();
            if (!candidate.startsWith(exportsDir)) return null;      // traversal guard
            if (!Files.exists(candidate)) return candidate;
        }
        return null;
    }

    private static String sanitise(String profileName) {
        StringBuilder out = new StringBuilder();
        for (char c : profileName.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') out.append(c);
            else if (c == ' ') out.append('-');
        }
        String trimmed = out.toString().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? "profile" : trimmed.substring(0, Math.min(trimmed.length(), 48));
    }

    // ---------------------------------------------------------------- import

    /** Reads a bundle file and returns its preview. Writes nothing. */
    public ImportResult readForImport(Studio studio, String fileName) {
        Path candidate = exportsDir.resolve(fileName).normalize();
        if (!candidate.startsWith(exportsDir)) {
            return new ImportResult(false, "import.pathRejected", null, "");
        }
        try {
            if (!Files.isRegularFile(candidate)) {
                return new ImportResult(false, "import.fileNotFound", null, "");
            }
            String json = Files.readString(candidate, StandardCharsets.UTF_8);
            ProfileBundle.Preview preview = studio.bundles().preview(json);
            return new ImportResult(true, "import.previewReady", preview, json);
        } catch (IOException e) {
            return new ImportResult(false, "import.ioError", null, "");
        } catch (ConfigException e) {
            return new ImportResult(false, "import.invalid", null, "");
        }
    }

    /** Lists existing bundle file names so the user can pick one. */
    public java.util.List<String> availableBundles() {
        java.util.List<String> names = new java.util.ArrayList<>();
        try (var stream = Files.list(exportsDir)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".cmsprofile"))
                    .sorted()
                    .forEach(names::add);
        } catch (IOException e) {
            return java.util.List.of();
        }
        return names;
    }

    /** Confirms and performs the import. Imported macros are disabled. */
    public RepoResult<Profile> confirmImport(Studio studio, String json, boolean overwriteSameName) {
        try {
            ProfileBundle.Preview preview = studio.bundles().preview(json);
            return studio.importProfile(preview, overwriteSameName);
        } catch (ConfigException e) {
            return RepoResult.error("import.invalid");
        }
    }

    /** Relative paths only, for display; never absolute paths in the UI. */
    public String relativeDisplay(Path path) {
        return path == null ? "" : Paths.get("").relativize(path).toString();
    }
}
