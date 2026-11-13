package net.chimera;

import net.chimera.command.ChimeraCommands;
import net.chimera.gui.ChimeraKeybinds;
import net.chimera.gui.ShaderPackScreen;
import net.chimera.render.ChimeraMaterialReloadListener;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.PackFailureNotices;
import net.chimera.shaderpack.PackTemporaryRoots;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

@Mod(value = ChimeraMod.MOD_ID, dist = Dist.CLIENT)
public final class ChimeraMod {
    public static final String MOD_ID = "chimera";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static String version = "dev";
    private static final String COMMIT = readCommit();

    public ChimeraMod(IEventBus modBus, ModContainer container) {
        version = container.getModInfo().getVersion().toString();
        LOGGER.info("[chimera] Chimera {} (commit {})", version, COMMIT);
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class,
                event -> ChimeraCommands.register(event.getDispatcher()));
        // A pack failure found before a player is in a world waits for one, as Iris's notice does.
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> PackFailureNotices.flush());
        // F3: the version, pack and profile on the right, the shadow map on the left, as Iris's.
        NeoForge.EVENT_BUS.addListener(CustomizeGuiOverlayEvent.DebugText.class, event -> {
            event.getLeft().addAll(ChimeraRenderer.shadowDebugLines());
            event.getRight().addAll(ChimeraRenderer.debugLines());
        });
        ChimeraKeybinds.register(modBus);
        PackTemporaryRoots.sweepInBackground();
        modBus.addListener(RegisterClientReloadListenersEvent.class,
                event -> event.registerReloadListener(ChimeraMaterialReloadListener.live()));
        // The mod list's Config button opens the shader pack selector, as Iris's does.
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new ShaderPackScreen(parent));
    }

    /** This mod's version, once NeoForge has constructed the mod. */
    public static String version() {
        return version;
    }

    /** The commit this jar was built from (build.gradle stamps chimera-build.properties); unknown in a dev run. */
    public static String commit() {
        return COMMIT;
    }

    private static String readCommit() {
        try (InputStream in = ChimeraMod.class.getResourceAsStream("/chimera-build.properties")) {
            if (in == null) return "unknown";
            Properties properties = new Properties();
            properties.load(in);
            String commit = properties.getProperty("commit", "").strip();
            return commit.isEmpty() || commit.startsWith("$") ? "unknown" : commit.toLowerCase(Locale.ROOT);
        } catch (IOException unreadable) {
            return "unknown";
        }
    }
}
