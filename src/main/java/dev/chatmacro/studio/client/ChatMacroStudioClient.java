package dev.chatmacro.studio.client;

import dev.chatmacro.studio.ModInfo;
import dev.chatmacro.studio.Studio;
import dev.chatmacro.studio.client.screen.StudioScreen;
import dev.chatmacro.studio.model.SessionInfo;
import dev.chatmacro.studio.sched.Clock;
import dev.chatmacro.studio.store.FileByteStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Client entry point.
 *
 * <p>Everything is registered exactly once here. Opening the screen never adds
 * another tick handler, and the {@link Studio} - including its scheduler - is
 * owned by this class, not by the UI, so macros keep running (or keep being
 * correctly held) while no screen is open.</p>
 */
public final class ChatMacroStudioClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_NAME);

    private static ChatMacroStudioClient instance;

    private Studio studio;
    private SessionBridge sessionBridge;
    private long sessionGeneration;

    @Override
    public void onInitializeClient() {
        if (instance != null) {
            LOGGER.warn("{} initialised twice; ignoring the second call", ModInfo.MOD_NAME);
            return;
        }
        instance = this;

        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(ModInfo.CONFIG_DIR_NAME);
        this.studio = new Studio(new FileByteStore(configDir), Clock.SYSTEM,
                new MinecraftChatDispatchAdapter());
        this.sessionBridge = new SessionBridge();

        StudioKeyBindings.register();
        StudioCommands.register();

        // One listener, registered once. It is the only thing that turns
        // domain events into something the player can see, and it does that
        // through the chat log - a stable Minecraft surface - rather than a
        // custom HUD, so no uncertain rendering API is involved.
        studio.addListener(new Studio.Listener() {
            @Override
            public void onMessage(String messageKey, Object... args) {
                chatStatus(messageKey, args);
            }

            @Override
            public void onSessionStopped(String reasonKey) {
                chatStatus(reasonKey);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            SessionInfo session = sessionBridge.snapshot(client, sessionGeneration);
            studio.tick(session);
            pollKeyBindings(client);
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            sessionGeneration++;
            studio.updateSession(sessionBridge.snapshot(client, sessionGeneration));
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            sessionGeneration++;
            studio.updateSession(SessionInfo.none(sessionGeneration));
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> studio.shutdown());

        if (!studio.loadedSuccessfully()) {
            LOGGER.error("{} could not read its configuration: {}", ModInfo.MOD_NAME, studio.loadProblemKey());
        }
        LOGGER.info("{} {} ready (client only, Fabric Loader {})", ModInfo.MOD_NAME,
                ModInfo.FALLBACK_VERSION, FabricLoader.getInstance()
                        .getModContainer(ModInfo.MOD_ID)
                        .map(c -> c.getMetadata().getVersion().getFriendlyString())
                        .orElse("unknown"));
    }

    /** Echoes a run state change into the chat log, if the user wants that. */
    private void chatStatus(String messageKey, Object... args) {
        if (messageKey == null || messageKey.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.inGameHud == null) return;
        if (!studio.settings().showRunStatusInChat()) return;
        Text text = args == null || args.length == 0
                ? Text.translatable(ModInfo.tr(messageKey))
                : Text.translatable(ModInfo.tr(messageKey), args);
        client.inGameHud.getChatHud().addMessage(text);
    }

    /**
     * Polls both key bindings once per client tick.
     *
     * <p>This is the only place they are read, so a press can never be handled
     * twice. The emergency stop is drained before the open binding, and it does
     * not open the UI: stopping and opening are different actions on different
     * keys. {@code wasPressed()} is only queued by the game while no screen has
     * keyboard focus, so {@link dev.chatmacro.studio.client.screen.StudioScreen}
     * also matches the emergency-stop binding itself.</p>
     */
    private void pollKeyBindings(MinecraftClient client) {
        while (StudioKeyBindings.emergencyStop.wasPressed()) {
            studio.stopRun("run.stoppedByUser");
            if (client.player != null) {
                client.player.sendMessage(Text.translatable(ModInfo.tr("run.stopped")), false);
            }
        }
        while (StudioKeyBindings.openStudio.wasPressed()) {
            client.setScreen(new StudioScreen());
        }
    }

    /**
     * The single guarded doorway to the Minecraft client.
     *
     * <p>{@code MinecraftClient.getInstance()} is null during early startup and
     * after shutdown, and a null dereference there takes the whole game down.
     * Every screen change and every client-thread action in this mod goes
     * through these two methods instead of reaching for the singleton.</p>
     */
    public static void openScreen(Screen screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        client.setScreen(screen);
    }

    /** Queues an action on the client thread; a no-op if the client is gone. */
    public static void onClientThread(Runnable action) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        client.send(action);
    }

    public static ChatMacroStudioClient get() {
        return instance;
    }

    public Studio studio() {
        return studio;
    }

    public SessionBridge sessionBridge() {
        return sessionBridge;
    }

    /** Bumped on every join/disconnect so stale callbacks cannot send. */
    public long sessionGeneration() {
        return sessionGeneration;
    }
}
