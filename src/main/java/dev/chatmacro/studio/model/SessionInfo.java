package dev.chatmacro.studio.model;

import java.util.Objects;

/**
 * A snapshot of "where and how" the client is right now, as seen by the core.
 *
 * <p>The client layer builds this every tick from Minecraft state; the core
 * never touches Minecraft. Everything that can make sending unsafe is a field
 * here, so the policy is testable: world null, title screen, disconnected,
 * player not ready, death screen, chat screen open, single-player pause.</p>
 *
 * <p>{@code generation} identifies the connection. It changes on every join, so
 * a callback captured before a disconnect can never send into the new session.</p>
 */
public record SessionInfo(
        SessionKind kind,
        String serverAddress,
        String worldName,
        boolean playerReady,
        boolean chatScreenOpen,
        boolean gamePaused,
        boolean focusLost,
        long generation
) {

    public SessionInfo {
        Objects.requireNonNull(kind, "kind");
        serverAddress = ServerScope.normalizeAddress(serverAddress);
        worldName = worldName == null ? "" : worldName;
    }

    /** Not in a world at all - main menu, disconnect screen, loading. */
    public static SessionInfo none(long generation) {
        return new SessionInfo(SessionKind.NONE, "", "", false, false, false, false, generation);
    }

    public static SessionInfo singleplayer(String worldName, long generation) {
        return new SessionInfo(SessionKind.SINGLEPLAYER, "", worldName, true, false, false, false, generation);
    }

    public static SessionInfo multiplayer(String address, long generation) {
        return new SessionInfo(SessionKind.MULTIPLAYER, address, "", true, false, false, false, generation);
    }

    public boolean inGame() {
        return kind != SessionKind.NONE;
    }

    /**
     * True when it is structurally safe to hand a message to the client's chat
     * path. Safety <em>policy</em> (chat open, pause, focus) is layered on top by
     * the scheduler, because those are user-configurable.
     */
    public boolean canSend() {
        return inGame() && playerReady;
    }

    public SessionInfo withChatScreenOpen(boolean value) {
        return new SessionInfo(kind, serverAddress, worldName, playerReady, value, gamePaused, focusLost, generation);
    }

    public SessionInfo withPaused(boolean value) {
        return new SessionInfo(kind, serverAddress, worldName, playerReady, chatScreenOpen, value, focusLost, generation);
    }

    public SessionInfo withFocusLost(boolean value) {
        return new SessionInfo(kind, serverAddress, worldName, playerReady, chatScreenOpen, gamePaused, value, generation);
    }

    public SessionInfo withPlayerReady(boolean value) {
        return new SessionInfo(kind, serverAddress, worldName, value, chatScreenOpen, gamePaused, focusLost, generation);
    }
}
