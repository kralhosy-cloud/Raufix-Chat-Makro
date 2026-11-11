package dev.chatmacro.studio.dispatch;

import dev.chatmacro.studio.model.Macro;
import dev.chatmacro.studio.model.MacroContentType;
import dev.chatmacro.studio.model.SessionInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Test double for {@link ChatDispatchAdapter}.
 *
 * <p>It records what the scheduler asked for and returns a scripted result. It
 * never touches a network handler, so a green suite can never be mistaken for a
 * proven in-game send.</p>
 */
public final class RecordingDispatchAdapter implements ChatDispatchAdapter {

    public record Call(UUID macroId, UUID profileId, MacroContentType type, String content,
                       long generation, String address) {
    }

    private final List<Call> calls = new ArrayList<>();
    private DispatchResult scripted = DispatchResult.sent();
    private RuntimeException toThrow;

    @Override
    public DispatchResult dispatch(Macro macro, SessionInfo session, long generation) {
        if (toThrow != null) throw toThrow;
        calls.add(new Call(macro.id(), macro.profileId(), macro.contentType(), macro.content(),
                generation, session.serverAddress()));
        return scripted;
    }

    public void script(DispatchResult result) {
        this.scripted = result;
    }

    public void throwOnDispatch(RuntimeException e) {
        this.toThrow = e;
    }

    public void reset() {
        calls.clear();
        scripted = DispatchResult.sent();
        toThrow = null;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public int callCount() {
        return calls.size();
    }

    public int callCountFor(UUID macroId) {
        int n = 0;
        for (Call call : calls) {
            if (call.macroId().equals(macroId)) n++;
        }
        return n;
    }

    public Call lastCall() {
        return calls.isEmpty() ? null : calls.get(calls.size() - 1);
    }
}
