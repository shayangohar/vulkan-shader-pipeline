package net.chimera;

import net.chimera.command.ChimeraCommands;
import net.chimera.gui.ChimeraKeybinds;
import net.chimera.gui.ShaderPackScreen;
import net.chimera.render.ChimeraMaterialReloadListener;
import net.chimera.shaderpack.PackTemporaryRoots;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = ChimeraMod.MOD_ID, dist = Dist.CLIENT)
public final class ChimeraMod {
    public static final String MOD_ID = "chimera";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static String version = "dev";

    public ChimeraMod(IEventBus modBus, ModContainer container) {
        version = container.getModInfo().getVersion().toString();
        LOGGER.info("Chimera {} initializing - shaderpack pipeline for VulkanMod", version);
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class,
                event -> ChimeraCommands.register(event.getDispatcher()));
        ChimeraKeybinds.register(modBus);
        PackTemporaryRoots.sweepInBackground();
        modBus.addListener(RegisterClientReloadListenersEvent.class,
                event -> event.registerReloadListener(ChimeraMaterialReloadListener.live()));
        // The mod list's Config button opens the shader pack selector, as Iris's does.
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new ShaderPackScreen(parent));
        LOGGER.info("Press O (or use /chimera pack) to choose a shader pack in-game");
    }

    /** This mod's version, once NeoForge has constructed the mod. */
    public static String version() {
        return version;
    }
}
