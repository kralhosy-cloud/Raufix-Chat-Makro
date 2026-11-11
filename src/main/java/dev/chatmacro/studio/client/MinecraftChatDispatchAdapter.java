package dev.chatmacro.studio.client;

import dev.chatmacro.studio.dispatch.ChatDispatchAdapter;
import dev.chatmacro.studio.dispatch.DispatchOutcome;
import dev.chatmacro.studio.dispatch.DispatchResult;
import dev.chatmacro.studio.model.Macro;
import dev.chatmacro.studio.model.SessionInfo;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;

/**
 * Production dispatch: the ordinary client chat path, nothing else.
 *
 * <p>Chat text goes to {@code sendChatMessage}; commands go to
 * {@code sendChatCommand}, which takes the body <em>without</em> the leading
 * slash - that is why validation strips exactly one slash and never touches
 * slashes inside the arguments.</p>
 *
 * <p>No fake signatures, no other player's name, no signed-chat bypass, no raw
 * packets. Whatever the server decides to do with the text afterwards is out of
 * this mod's hands, which is why a successful call is reported as "handed to the
 * client" and never as "delivered to everyone".</p>
 */
public final class MinecraftChatDispatchAdapter implements ChatDispatchAdapter {

    @Override
    public DispatchResult dispatch(Macro macro, SessionInfo session, long generation) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        if (client.player == null || handler == null || client.world == null) {
            return DispatchResult.of(DispatchOutcome.NOT_IN_GAME, "dispatch.notInGame");
        }
        if (!session.canSend()) {
            return DispatchResult.of(DispatchOutcome.NOT_IN_GAME, "dispatch.notInGame");
        }

        String content = macro.content();
        if (content.isEmpty()) {
            return DispatchResult.of(DispatchOutcome.INVALID_CONTENT, "dispatch.invalidContent");
        }

        try {
            if (macro.isCommand()) {
                handler.sendChatCommand(content);
            } else {
                handler.sendChatMessage(content);
            }
            return DispatchResult.sent();
        } catch (RuntimeException e) {
            // No exception detail is propagated: it could contain the text.
            return DispatchResult.of(DispatchOutcome.FAILED, "dispatch.exception");
        }
    }
}
