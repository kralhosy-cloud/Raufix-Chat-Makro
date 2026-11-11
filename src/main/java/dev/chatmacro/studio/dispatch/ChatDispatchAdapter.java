package dev.chatmacro.studio.dispatch;

import dev.chatmacro.studio.model.Macro;
import dev.chatmacro.studio.model.SessionInfo;

/**
 * The seam between the scheduler and Minecraft.
 *
 * <p>Production wires this to the client's own
 * {@code sendChatMessage}/{@code sendChatCommand} path (see
 * {@code MinecraftChatDispatchAdapter}). Tests wire
 * {@link RecordingDispatchAdapter}, which never touches a network handler.</p>
 *
 * <p>Implementations must not create fake signatures, must not impersonate
 * another player and must not bypass signed-chat or anti-spam mechanisms. They
 * are the ordinary client path, nothing more.</p>
 */
public interface ChatDispatchAdapter {

    /**
     * Hands one macro's content to the client.
     *
     * @return what happened; never null. An implementation that cannot tell
     *         should return {@link DispatchOutcome#FAILED} rather than claiming
     *         success.
     */
    DispatchResult dispatch(Macro macro, SessionInfo session, long generation);
}
