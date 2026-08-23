package net.chimera;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
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
    }
}
