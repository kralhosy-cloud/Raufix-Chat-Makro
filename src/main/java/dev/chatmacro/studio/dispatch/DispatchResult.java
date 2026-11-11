package dev.chatmacro.studio.dispatch;

import java.util.Objects;

/**
 * One send attempt, with the reason attached.
 *
 * <p>{@code detailKey} is a translation key (never raw server text) so the UI
 * can be localised; {@code detail} is optional diagnostic text that is masked
 * before it ever reaches a log or an export.</p>
 */
public record DispatchResult(DispatchOutcome outcome, String detailKey, String detail) {

    public DispatchResult {
        Objects.requireNonNull(outcome, "outcome");
        detailKey = detailKey == null ? "" : detailKey;
        detail = detail == null ? "" : detail;
    }

    public static DispatchResult sent() {
        return new DispatchResult(DispatchOutcome.HANDED_TO_CLIENT, "dispatch.handedToClient", "");
    }

    public static DispatchResult of(DispatchOutcome outcome, String detailKey) {
        return new DispatchResult(outcome, detailKey, "");
    }

    public static DispatchResult of(DispatchOutcome outcome, String detailKey, String detail) {
        return new DispatchResult(outcome, detailKey, detail);
    }

    public boolean succeeded() {
        return outcome == DispatchOutcome.HANDED_TO_CLIENT;
    }

    /**
     * Outcomes that mean "the server or client is telling us to stop", as
     * opposed to a one-off failure worth a backoff.
     */
    public boolean isHardStop() {
        return outcome == DispatchOutcome.NOT_IN_GAME
                || outcome == DispatchOutcome.NOT_CONNECTED
                || outcome == DispatchOutcome.CLIENT_REJECTED
                || outcome == DispatchOutcome.INVALID_CONTENT;
    }
}
