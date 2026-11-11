package dev.chatmacro.studio.client;

import com.mojang.brigadier.CommandDispatcher;
import dev.chatmacro.studio.ModInfo;
import dev.chatmacro.studio.Studio;
import dev.chatmacro.studio.client.screen.StudioScreen;
import dev.chatmacro.studio.model.Macro;
import dev.chatmacro.studio.model.Profile;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.CommandBuildContext;
import net.minecraft.text.Text;

/**
 * {@code /chatmacro} client commands.
 *
 * <p>{@code ui} and {@code f30} are the same action: open the studio. {@code f30}
 * exists so a player who thinks in terms of the "F30" label has a command that
 * matches it - there is no physical F30 key (see {@link StudioKeyBindings}).</p>
 */
public final class StudioCommands {

    private StudioCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(
                (CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext registryAccess) ->
                        dispatcher.register(ClientCommandManager.literal(ModInfo.COMMAND_ROOT)
                                .then(ClientCommandManager.literal(ModInfo.COMMAND_UI)
                                        .executes(ctx -> openScreen(ctx.getSource())))
                                .then(ClientCommandManager.literal(ModInfo.COMMAND_F30)
                                        .executes(ctx -> openScreen(ctx.getSource())))
                                .then(ClientCommandManager.literal("start")
                                        .executes(ctx -> start(ctx.getSource())))
                                .then(ClientCommandManager.literal(ModInfo.COMMAND_STOP)
                                        .executes(ctx -> stop(ctx.getSource())))
                                .then(ClientCommandManager.literal("pause")
                                        .executes(ctx -> pause(ctx.getSource())))
                                .then(ClientCommandManager.literal("resume")
                                        .executes(ctx -> resume(ctx.getSource())))
                                .then(ClientCommandManager.literal("list")
                                        .executes(ctx -> list(ctx.getSource())))
                                .then(ClientCommandManager.literal("save")
                                        .executes(ctx -> save(ctx.getSource())))));
    }

    private static Studio studio(FabricClientCommandSource source) {
        ChatMacroStudioClient client = ChatMacroStudioClient.get();
        if (client == null) {
            source.sendError(Text.translatable(ModInfo.tr("error.notInitialised")));
            return null;
        }
        return client.studio();
    }

    private static int openScreen(FabricClientCommandSource source) {
        ChatMacroStudioClient.openScreen(new StudioScreen());
        return 1;
    }

    private static int start(FabricClientCommandSource source) {
        Studio studio = studio(source);
        if (studio == null) return 0;
        if (!studio.activeProfileHasEnabledMacros()) {
            source.sendError(Text.translatable(ModInfo.tr("run.noEnabledMacros")));
            return 0;
        }
        client_send(() -> studio.startRun());
        source.sendFeedback(Text.translatable(ModInfo.tr("run.started")));
        return 1;
    }

    private static int stop(FabricClientCommandSource source) {
        Studio studio = studio(source);
        if (studio == null) return 0;
        client_send(() -> studio.stopRun("run.stoppedByUser"));
        source.sendFeedback(Text.translatable(ModInfo.tr("run.stopped")));
        return 1;
    }

    private static int pause(FabricClientCommandSource source) {
        Studio studio = studio(source);
        if (studio == null) return 0;
        client_send(studio::pauseRun);
        source.sendFeedback(Text.translatable(ModInfo.tr("run.paused")));
        return 1;
    }

    private static int resume(FabricClientCommandSource source) {
        Studio studio = studio(source);
        if (studio == null) return 0;
        client_send(studio::resumeRun);
        source.sendFeedback(Text.translatable(ModInfo.tr("run.resumed")));
        return 1;
    }

    private static int list(FabricClientCommandSource source) {
        Studio studio = studio(source);
        if (studio == null) return 0;
        Profile profile = studio.profiles().selected().orElse(null);
        if (profile == null) {
            source.sendFeedback(Text.translatable(ModInfo.tr("profile.noneSelected")));
            return 0;
        }
        source.sendFeedback(Text.translatable(ModInfo.tr("command.list.header"), profile.name()));
        for (Macro macro : studio.macros().forProfile(profile)) {
            source.sendFeedback(Text.translatable(ModInfo.tr("command.list.entry"),
                    macro.name(),
                    macro.isCommand() ? "COMMAND" : "CHAT",
                    macro.intervalSeconds(),
                    macro.enabled() ? "on" : "off"));
        }
        return 1;
    }

    private static int save(FabricClientCommandSource source) {
        Studio studio = studio(source);
        if (studio == null) return 0;
        boolean saved = studio.saveNow();
        source.sendFeedback(Text.translatable(saved
                ? ModInfo.tr("config.saved") : ModInfo.tr("config.saveFailed")));
        return saved ? 1 : 0;
    }

    /** Keeps screen/state mutations on the render thread. */
    private static void client_send(Runnable action) {
        ChatMacroStudioClient.onClientThread(action);
    }
}
