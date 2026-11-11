package dev.chatmacro.studio.config;

import dev.chatmacro.studio.sched.SchedulerPolicy;
import dev.chatmacro.studio.theme.Accent;
import dev.chatmacro.studio.theme.ThemeMode;

/**
 * Global, user-level preferences.
 *
 * <p>These are deliberately separate from profile content: changing the accent
 * colour or the light/dark theme is not a profile edit, does not mark a profile
 * dirty and never touches a macro.</p>
 */
public record GlobalSettings(
        ThemeMode themeMode,
        Accent accent,
        boolean reducedMotion,
        long globalMinDispatchGapMillis,
        boolean holdDispatchWhileChatOpen,
        boolean pauseOnGamePaused,
        boolean pauseOnFocusLoss,
        /**
         * Whether run state changes (started, stopped, paused, held, stopped by
         * the scheduler) are echoed into the chat log, so a session that keeps
         * running after the studio is closed is never invisible.
         */
        boolean showRunStatusInChat,
        boolean confirmDangerousCommands,
        boolean maskSensitivePreview,
        boolean verboseDiagnostics,
        int maxChatChars,
        int maxCommandChars,
        int selectedProfileIndexHint
) {

    public GlobalSettings {
        if (themeMode == null) themeMode = ThemeMode.DARK;
        if (accent == null) accent = Accent.BLUE;
        if (globalMinDispatchGapMillis < 0) globalMinDispatchGapMillis = 0;
        if (maxChatChars < 16) maxChatChars = 16;
        if (maxCommandChars < 16) maxCommandChars = 16;
    }

    public static GlobalSettings defaults() {
        return new GlobalSettings(
                ThemeMode.DARK,
                Accent.BLUE,
                /* reducedMotion */ false,
                SchedulerPolicy.DEFAULT_GLOBAL_GAP_MILLIS,
                /* holdDispatchWhileChatOpen */ true,
                /* pauseOnGamePaused */ true,
                /* pauseOnFocusLoss */ true,
                /* showRunStatusInChat */ true,
                /* confirmDangerousCommands */ true,
                /* maskSensitivePreview */ true,
                /* verboseDiagnostics */ false,
                512,
                512,
                -1
        );
    }

    public SchedulerPolicy toSchedulerPolicy() {
        return new SchedulerPolicy(globalMinDispatchGapMillis, holdDispatchWhileChatOpen,
                pauseOnGamePaused, pauseOnFocusLoss, SchedulerPolicy.DEFAULT_MAX_FAILURES, 2, 60);
    }

    public GlobalSettings withThemeMode(ThemeMode mode) {
        return new GlobalSettings(mode, accent, reducedMotion, globalMinDispatchGapMillis,
                holdDispatchWhileChatOpen, pauseOnGamePaused, pauseOnFocusLoss, showRunStatusInChat,
                confirmDangerousCommands, maskSensitivePreview, verboseDiagnostics,
                maxChatChars, maxCommandChars, selectedProfileIndexHint);
    }

    public GlobalSettings withAccent(Accent newAccent) {
        return new GlobalSettings(themeMode, newAccent, reducedMotion, globalMinDispatchGapMillis,
                holdDispatchWhileChatOpen, pauseOnGamePaused, pauseOnFocusLoss, showRunStatusInChat,
                confirmDangerousCommands, maskSensitivePreview, verboseDiagnostics,
                maxChatChars, maxCommandChars, selectedProfileIndexHint);
    }

    public GlobalSettings withReducedMotion(boolean value) {
        return new GlobalSettings(themeMode, accent, value, globalMinDispatchGapMillis,
                holdDispatchWhileChatOpen, pauseOnGamePaused, pauseOnFocusLoss, showRunStatusInChat,
                confirmDangerousCommands, maskSensitivePreview, verboseDiagnostics,
                maxChatChars, maxCommandChars, selectedProfileIndexHint);
    }

    public GlobalSettings withGlobalGapMillis(long millis) {
        return new GlobalSettings(themeMode, accent, reducedMotion, millis,
                holdDispatchWhileChatOpen, pauseOnGamePaused, pauseOnFocusLoss, showRunStatusInChat,
                confirmDangerousCommands, maskSensitivePreview, verboseDiagnostics,
                maxChatChars, maxCommandChars, selectedProfileIndexHint);
    }

    public GlobalSettings withHoldDispatchWhileChatOpen(boolean value) {
        return new GlobalSettings(themeMode, accent, reducedMotion, globalMinDispatchGapMillis,
                value, pauseOnGamePaused, pauseOnFocusLoss, showRunStatusInChat,
                confirmDangerousCommands, maskSensitivePreview, verboseDiagnostics,
                maxChatChars, maxCommandChars, selectedProfileIndexHint);
    }

    public GlobalSettings withPauseOnGamePaused(boolean value) {
        return new GlobalSettings(themeMode, accent, reducedMotion, globalMinDispatchGapMillis,
                holdDispatchWhileChatOpen, value, pauseOnFocusLoss, showRunStatusInChat,
                confirmDangerousCommands, maskSensitivePreview, verboseDiagnostics,
                maxChatChars, maxCommandChars, selectedProfileIndexHint);
    }

    public GlobalSettings withPauseOnFocusLoss(boolean value) {
        return new GlobalSettings(themeMode, accent, reducedMotion, globalMinDispatchGapMillis,
                holdDispatchWhileChatOpen, pauseOnGamePaused, value, showRunStatusInChat,
                confirmDangerousCommands, maskSensitivePreview, verboseDiagnostics,
                maxChatChars, maxCommandChars, selectedProfileIndexHint);
    }

    public GlobalSettings withShowRunStatusInChat(boolean value) {
        return new GlobalSettings(themeMode, accent, reducedMotion, globalMinDispatchGapMillis,
                holdDispatchWhileChatOpen, pauseOnGamePaused, pauseOnFocusLoss, value,
                confirmDangerousCommands, maskSensitivePreview, verboseDiagnostics,
                maxChatChars, maxCommandChars, selectedProfileIndexHint);
    }

    public GlobalSettings withConfirmDangerousCommands(boolean value) {
        return new GlobalSettings(themeMode, accent, reducedMotion, globalMinDispatchGapMillis,
                holdDispatchWhileChatOpen, pauseOnGamePaused, pauseOnFocusLoss, showRunStatusInChat,
                value, maskSensitivePreview, verboseDiagnostics,
                maxChatChars, maxCommandChars, selectedProfileIndexHint);
    }

    public GlobalSettings withMaskSensitivePreview(boolean value) {
        return new GlobalSettings(themeMode, accent, reducedMotion, globalMinDispatchGapMillis,
                holdDispatchWhileChatOpen, pauseOnGamePaused, pauseOnFocusLoss, showRunStatusInChat,
                confirmDangerousCommands, value, verboseDiagnostics,
                maxChatChars, maxCommandChars, selectedProfileIndexHint);
    }

    public GlobalSettings withVerboseDiagnostics(boolean value) {
        return new GlobalSettings(themeMode, accent, reducedMotion, globalMinDispatchGapMillis,
                holdDispatchWhileChatOpen, pauseOnGamePaused, pauseOnFocusLoss, showRunStatusInChat,
                confirmDangerousCommands, maskSensitivePreview, value,
                maxChatChars, maxCommandChars, selectedProfileIndexHint);
    }

    public GlobalSettings withContentLimits(int chat, int command) {
        return new GlobalSettings(themeMode, accent, reducedMotion, globalMinDispatchGapMillis,
                holdDispatchWhileChatOpen, pauseOnGamePaused, pauseOnFocusLoss, showRunStatusInChat,
                confirmDangerousCommands, maskSensitivePreview, verboseDiagnostics,
                chat, command, selectedProfileIndexHint);
    }
}
