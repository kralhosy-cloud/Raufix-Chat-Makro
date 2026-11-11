package dev.chatmacro.studio.dispatch;

/**
 * What actually happened to one send attempt.
 *
 * <p>The honest vocabulary matters: {@link #HANDED_TO_CLIENT} means the text was
 * given to Minecraft's own chat/command path. It does <b>not</b> mean every
 * other player read it, that the server accepted it, or that a plugin did not
 * drop it. Anything the client can observe about rejection is reported as its
 * own value instead of being folded into "sent".</p>
 */
public enum DispatchOutcome {
    /** The client's normal chat path accepted the text. */
    HANDED_TO_CLIENT,
    /** No world / no network handler / title screen - nothing was sent. */
    NOT_IN_GAME,
    /** The connection is not in a state where sending is allowed. */
    NOT_CONNECTED,
    /** The client reported the message could not be sent (chat disabled etc.). */
    CLIENT_REJECTED,
    /** Content failed validation at send time. */
    INVALID_CONTENT,
    /** The adapter threw; the macro goes to backoff rather than retry-storming. */
    FAILED,
    /** The scheduler refused because the global budget was exhausted. */
    BUDGET_BLOCKED
}
