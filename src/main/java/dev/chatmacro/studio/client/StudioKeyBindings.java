package dev.chatmacro.studio.client;

import dev.chatmacro.studio.ModInfo;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Key bindings.
 *
 * <p><b>About "F30":</b> GLFW defines physical function keys only up to
 * {@code F25}; there is no {@code GLFW_KEY_F30} and this mod does not invent one.
 * "F30" survives only as a user-facing label for the <em>action</em> "open the
 * studio", plus the {@code /chatmacro f30} command alias. If a keyboard or a
 * macro pad exposes an F30 button, the player maps it to this binding in
 * Minecraft's Controls screen like any other key. The default is Right Shift.</p>
 *
 * <p>Both bindings are reassignable from Controls and live in their own category
 * so they are easy to find.</p>
 */
public final class StudioKeyBindings {

    /** Controls-screen category; localised by both language files. */
    public static final String CATEGORY = ModInfo.tr("key.category");

    // Both fields are public non-final statics, which the project's Semgrep
    // ruleset flags. Accepted deliberately: this is the documented Fabric key
    // binding pattern, they are assigned exactly once in register() before any
    // tick handler can read them, and nothing else writes to them.
    /** Opens the studio. Logical alias: "F30". */
    public static KeyBinding openStudio; // nosemgrep: cms-public-mutable-static

    /** Emergency stop: cancels the run immediately, wherever the player is. */
    public static KeyBinding emergencyStop; // nosemgrep: cms-public-mutable-static

    private StudioKeyBindings() {
    }

    public static void register() {
        openStudio = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                ModInfo.tr("key.openStudio"),
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                CATEGORY));

        emergencyStop = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                ModInfo.tr("key.emergencyStop"),
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_CONTROL,
                CATEGORY));
    }
}
