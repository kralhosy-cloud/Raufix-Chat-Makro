package dev.chatmacro.studio.model;

/** Where the client currently is. */
public enum SessionKind {
    /** No world loaded / main menu. Sending is never allowed here. */
    NONE,
    /** Integrated server, single player. */
    SINGLEPLAYER,
    /** Connected to a remote server by address. */
    MULTIPLAYER,
    /** Connected to a Realm. Treated as its own kind on purpose. */
    REALM
}
