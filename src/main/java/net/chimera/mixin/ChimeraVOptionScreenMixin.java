package net.chimera.mixin;

import net.chimera.gui.ShaderPackScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.VOptionScreen;
import net.vulkanmod.config.gui.util.VGuiConstants;
import net.vulkanmod.config.gui.widget.VButtonWidget;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * VulkanMod replaces Video Settings with its own screen, so Iris's Video Settings entry point has
 * no home there. Add "Shader Packs..." to the bottom of its page column instead.
 */
@Mixin(value = VOptionScreen.class, remap = false)
public abstract class ChimeraVOptionScreenMixin extends Screen {
    @Shadow
    @Final
    private List<VButtonWidget> buttons;

    protected ChimeraVOptionScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "addButtonsWithSearchBar", at = @At("TAIL"), require = 1)
    private void chimera$addShaderPackButton(CallbackInfo callback) {
        VButtonWidget shaderPacks = new VButtonWidget(VOptionScreen.MARGIN,
                this.height - VGuiConstants.WIDGET_HEIGHT - 7, VGuiConstants.PAGE_BUTTON_WIDTH,
                VGuiConstants.WIDGET_HEIGHT, Component.translatable("options.chimera.shaderPackSelection"),
                button -> Minecraft.getInstance().setScreen(new ShaderPackScreen(this)));
        this.buttons.add(shaderPacks);
        this.addWidget(shaderPacks);
    }
}
