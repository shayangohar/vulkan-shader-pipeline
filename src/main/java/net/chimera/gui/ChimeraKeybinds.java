package net.chimera.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Chimera's key bindings: O opens the shader pack selector, as Iris's does. */
public final class ChimeraKeybinds {
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("chimera", "keybinds"));
    private static KeyMapping shaderPackScreen;

    private ChimeraKeybinds() {}

    public static void register() {
        shaderPackScreen = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "chimera.keybind.shaderPackSelection", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            while (shaderPackScreen.consumeClick()) {
                if (minecraft.screen == null) {
                    minecraft.setScreen(new ShaderPackScreen(null));
                }
            }
        });
    }
}
