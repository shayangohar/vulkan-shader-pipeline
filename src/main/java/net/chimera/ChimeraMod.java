package net.chimera;

import net.chimera.command.ChimeraCommands;
import net.chimera.render.ChimeraMaterialReloadListener;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.packs.PackType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChimeraMod implements ClientModInitializer {
    public static final String MOD_ID = "chimera";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final String VERSION = FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("dev");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Chimera {} initializing - shaderpack pipeline for VulkanMod", VERSION);
        LOGGER.info("Press F8 in-game to toggle the chimera main pass");
        ChimeraCommands.register();
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloader(
                ChimeraMaterialReloadListener.ID, ChimeraMaterialReloadListener.live());
        ResourceLoader.get(PackType.CLIENT_RESOURCES).addReloaderOrdering(
                ResourceReloaderKeys.Client.TEXTURES, ChimeraMaterialReloadListener.ID);
        LOGGER.info("Use /chimera pack list, load, reload, off, or status to change shaderpacks in-game");
    }
}
