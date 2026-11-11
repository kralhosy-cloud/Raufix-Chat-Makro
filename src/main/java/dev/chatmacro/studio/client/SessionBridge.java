package dev.chatmacro.studio.client;

import dev.chatmacro.studio.model.SessionInfo;
import dev.chatmacro.studio.model.SessionKind;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.server.integrated.IntegratedServer;

/**
 * Translates live Minecraft state into the plain {@link SessionInfo} the core
 * understands. This is the only place in the mod that inspects the client to
 * decide whether sending would be safe.
 *
 * <p>Everything that can make a send unsafe is reported here: no world, no
 * player yet, chat screen open, single-player pause, lost window focus. The
 * policy that acts on those flags lives in the scheduler, where it is
 * configurable and tested.</p>
 */
public final class SessionBridge {

    private String cachedSingleplayerWorld = "";

    /**
     * @param generation bumped by the entry point on every join/disconnect
     */
    public SessionInfo snapshot(MinecraftClient client, long generation) {
        if (client == null || client.world == null) {
            cachedSingleplayerWorld = "";
            return SessionInfo.none(generation);
        }

        SessionKind kind;
        String address = "";
        String worldName = "";

        // 1.21.11 dropped MinecraftClient#isConnectedToRealms(); the flag now
        // lives on the ServerInfo of the current connection.
        ServerInfo currentServer = client.getCurrentServerEntry();
        if (currentServer != null && currentServer.isRealm()) {
            kind = SessionKind.REALM;
        } else if (client.isInSingleplayer()) {
            kind = SessionKind.SINGLEPLAYER;
            worldName = singleplayerWorldName(client);
        } else {
            kind = SessionKind.MULTIPLAYER;
            address = serverAddress(client);
        }

        // Short-circuit guarded: player is dereferenced only after the null
        // check in the same && chain. (Recorded so the Semgrep review prompt
        // cms-player-deref-unguarded stays answered in the code.)
        boolean playerReady = client.player != null
                && client.getNetworkHandler() != null
                && client.player.isAlive(); // nosemgrep: cms-player-deref-unguarded

        boolean chatOpen = client.currentScreen instanceof ChatScreen;
        boolean paused = client.isPaused();
        boolean focusLost = !client.isWindowFocused();

        return new SessionInfo(kind, address, worldName, playerReady, chatOpen, paused, focusLost, generation);
    }

    private String singleplayerWorldName(MinecraftClient client) {
        if (!cachedSingleplayerWorld.isEmpty()) return cachedSingleplayerWorld;
        try {
            IntegratedServer server = client.getServer();
            if (server != null && server.getSaveProperties() != null) {
                cachedSingleplayerWorld = server.getSaveProperties().getLevelName();
            }
        } catch (RuntimeException e) {
            cachedSingleplayerWorld = "";
        }
        return cachedSingleplayerWorld;
    }

    private static String serverAddress(MinecraftClient client) {
        try {
            if (client.getCurrentServerEntry() != null) {
                return client.getCurrentServerEntry().address;
            }
        } catch (RuntimeException e) {
            // Fall through: an unknown address simply never matches a
            // SPECIFIC_SERVER scope, which is the safe outcome.
        }
        return "";
    }
}
