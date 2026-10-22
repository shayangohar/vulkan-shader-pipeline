package net.chimera.render;

import net.chimera.render.shader.GlslCompiler;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The smoothed centre depth's kernel, checked without a device. */
final class PackCenterDepthTest {
    /**
     * Iris's decay is {@code 1 / ((halfLife * 0.1) / ln 2)}: ln 2 / 0.1 at the default half-life of 1.
     * A half-life of zero makes it infinite; the largest float takes the new depth whole as well.
     */
    @Test
    void decayFollowsTheHalfLife() {
        assertEquals(Math.log(2) / 0.1, Double.parseDouble(PackCenterDepth.decayLiteral(1.0F)), 1e-5);
        assertEquals(Math.log(2) / 2.0, Double.parseDouble(PackCenterDepth.decayLiteral(20.0F)), 1e-6);
        assertEquals(Float.MAX_VALUE, Float.parseFloat(PackCenterDepth.decayLiteral(0.0F)));
    }

    @Test
    void theKernelCompiles() {
        MemoryUtil.memFree(GlslCompiler.compile("chimera_center_depth.csh", PackCenterDepth.source(1.0F),
                Shaderc.shaderc_glsl_compute_shader, options -> {}));
    }
}
