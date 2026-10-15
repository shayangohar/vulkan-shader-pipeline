package net.chimera.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.chimera.config.ChimeraConfig;
import net.chimera.config.ShaderpackDirectory;
import net.chimera.render.ChimeraRenderer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

/**
 * Chimera's key bindings, Iris's set: R reloads the shader pack, K turns shaders off and back on,
 * and O opens the shader pack selector. One binding acts per tick, in that order, and each reports
 * in chat as Iris does. A reload or toggle is queued for the next safe frame boundary.
 */
public final class ChimeraKeybinds {
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("chimera", "keybinds"));
    private static KeyMapping reload;
    private static KeyMapping toggleShaders;
    private static KeyMapping shaderPackScreen;

    private ChimeraKeybinds() {}

    public static void register() {
        reload = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "chimera.keybind.reload", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY));
        toggleShaders = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "chimera.keybind.toggleShaders", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY));
        shaderPackScreen = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "chimera.keybind.shaderPackSelection", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(ChimeraKeybinds::handle);
    }

    private static void handle(Minecraft minecraft) {
        if (reload.consumeClick()) {
            reloadPack(minecraft);
        } else if (toggleShaders.consumeClick()) {
            toggleShaders(minecraft);
        } else if (shaderPackScreen.consumeClick() && minecraft.screen == null) {
            minecraft.setScreen(new ShaderPackScreen(null));
        }
    }

    /** Reloads the running pack from disk, option file included; with no pack there is nothing to do. */
    private static void reloadPack(Minecraft minecraft) {
        if (ChimeraRenderer.reloadPack() == ChimeraRenderer.PackRequestResult.NOT_READY) {
            tell(minecraft, failure("chimera.shaders.reloaded.failure"));
        } else {
            tell(minecraft, Component.translatable("chimera.shaders.reloaded"));
        }
    }

    /**
     * Turns a running pack off, or turns the saved pack back on, and remembers the choice as the
     * selector's toggle does.
     */
    private static void toggleShaders(Minecraft minecraft) {
        ChimeraConfig config = ChimeraConfig.get();
        if (ChimeraRenderer.selectedPackPath() != null) {
            if (ChimeraRenderer.disablePack() == ChimeraRenderer.PackRequestResult.NOT_READY) {
                tell(minecraft, failure("chimera.shaders.toggled.failure"));
                return;
            }
            config.setSelection(config.shaderPack().orElse(null), false);
            tell(minecraft, Component.translatable("chimera.shaders.disabled"));
            return;
        }
        String name = config.shaderPack().orElse(null);
        Path pack = name == null ? null : ShaderpackDirectory.resolve(ShaderpackDirectory.root(), name).orElse(null);
        if (pack == null) {
            tell(minecraft, Component.translatable("chimera.shaders.toggled.failure",
                    Component.translatable("chimera.shaders.toggled.noPack")).withStyle(ChatFormatting.RED));
            return;
        }
        if (ChimeraRenderer.requestPack(pack) == ChimeraRenderer.PackRequestResult.NOT_READY) {
            tell(minecraft, failure("chimera.shaders.toggled.failure"));
            return;
        }
        config.setSelection(name, true);
        tell(minecraft, Component.translatable("chimera.shaders.toggled", name));
    }

    private static Component failure(String key) {
        return Component.translatable(key, Component.translatable("chimera.shaders.notReady"))
                .withStyle(ChatFormatting.RED);
    }

    private static void tell(Minecraft minecraft, Component message) {
        if (minecraft.player != null) minecraft.player.displayClientMessage(message, false);
    }
}
