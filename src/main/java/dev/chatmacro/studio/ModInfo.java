package dev.chatmacro.studio;

/**
 * Single source of truth for every identifier that has to stay consistent
 * across the mod id, the resource namespace, the command namespace, the config
 * folder and the user-visible product name.
 *
 * <p>Nothing in here is a Mojang/Microsoft endorsement: Chat Macro Studio is an
 * independent, unofficial client-side Fabric mod.</p>
 */
public final class ModInfo {

    /** Mod id and resource namespace. Lowercase, underscores only. */
    public static final String MOD_ID = "chatmacro_studio";

    /** User-visible product name. */
    public static final String MOD_NAME = "Chat Macro Studio";

    /** Version is injected at build time; this fallback is only used in tests. */
    public static final String FALLBACK_VERSION = "1.0.0";

    /** Folder under {@code .minecraft/config}. */
    public static final String CONFIG_DIR_NAME = MOD_ID;

    /** Command namespace: {@code /chatmacro}. */
    public static final String COMMAND_ROOT = "chatmacro";

    /** Translation key prefix used by both language files. */
    public static final String TRANSLATION_PREFIX = MOD_ID;

    /**
     * Logical alias for "open the studio", requested by the user as "F30".
     *
     * <p>GLFW only defines physical keys up to {@code F25}; there is no
     * {@code GLFW_KEY_F30} constant and this mod does not invent one. "F30" is
     * kept purely as a visible label plus the {@code /chatmacro f30} command
     * alias, so that a user whose keyboard or macro pad exposes an F30 button
     * can map it to the real binding in Minecraft's Controls screen. The actual
     * default binding is Right Shift - see {@code StudioKeyBindings}.</p>
     */
    public static final String UI_KEY_ALIAS_LABEL = "F30";

    /** Emergency-stop command sub-name. */
    public static final String COMMAND_STOP = "stop";

    /** Alias sub-command that opens the same screen as the key binding. */
    public static final String COMMAND_UI = "ui";

    /** The F30 alias sub-command. */
    public static final String COMMAND_F30 = "f30";

    /** Resource location helper: {@code chatmacro_studio:<path>}. */
    public static String resourcePath(String path) {
        return MOD_ID + ":" + path;
    }

    /** Translation key helper: {@code chatmacro_studio.<dotted.key>}. */
    public static String tr(String dottedKey) {
        return MOD_ID + "." + dottedKey;
    }

    private ModInfo() {
    }
}
