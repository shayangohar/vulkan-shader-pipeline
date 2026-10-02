package net.chimera.mixin;

import net.chimera.gui.ShaderPackScreen;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Iris's entry point on the vanilla Video Settings screen (which VulkanMod still opens with
 * Shift+P): a "Shader Packs..." option that opens the selector.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class ChimeraVideoSettingsScreenMixin extends Screen {
    protected ChimeraVideoSettingsScreenMixin(Component title) {
        super(title);
    }

    @ModifyArg(
            method = "addOptions",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/OptionsList;addSmall([Lnet/minecraft/client/OptionInstance;)V"
            ),
            index = 0,
            require = 0
    )
    private OptionInstance<?>[] chimera$addShaderPackButton(OptionInstance<?>[] options) {
        OptionInstance<?>[] result = new OptionInstance<?>[options.length + 1];
        System.arraycopy(options, 0, result, 0, options.length);
        result[options.length] = new OptionInstance<>("options.chimera.shaderPackSelection",
                OptionInstance.cachedConstantTooltip(Component.empty()), (caption, value) -> Component.empty(),
                OptionInstance.BOOLEAN_VALUES, true,
                value -> this.minecraft.setScreen(new ShaderPackScreen(this)));
        return result;
    }
}
