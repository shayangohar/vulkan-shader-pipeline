package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Iris's fogStart and fogEnd follow RenderSystem's fog setters (MixinRenderSystem), and so do Chimera's. */
final class FogRangeMixinTest {
    @Test
    void bothFogSettersServeTheRange() throws IOException {
        MixinBytecode.assertMixinRegistered("ChimeraFogRangeMixin");
        var mixin = MixinBytecode.nativeClass("net/chimera/mixin/ChimeraFogRangeMixin");
        assertTrue(MixinBytecode.mixinTargets(mixin, "com/mojang/blaze3d/systems/RenderSystem"));
        for (String setter : List.of("setShaderFogStart", "setShaderFogEnd")) {
            assertTrue(MixinBytecode.injects(mixin, "Lorg/spongepowered/asm/mixin/injection/Inject;", setter, null),
                    setter);
        }
    }
}
