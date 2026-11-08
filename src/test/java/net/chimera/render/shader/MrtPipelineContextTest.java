package net.chimera.render.shader;

import net.chimera.shaderpack.PackBlendPlan;
import net.chimera.shaderpack.SpirvOutputWrites;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * GraphicsPipelineMrtMixin opens a pipeline-creation scope at createGraphicsPipeline's head and
 * closes it at its return. A creation that throws skips the return, and the scope's write mask and
 * host-pass blend modes outlived the pass's end(), so the next pipeline created took them.
 */
final class MrtPipelineContextTest {
    @Test
    void endClosesAPipelineScopeACreationFailureLeftOpen() throws ReflectiveOperationException {
        try {
            threadLocal("CURRENT_WRITES").set(0b1);
            threadLocal("CURRENT_HOST_PASS_BLENDS").set(new PackBlendPlan.Mode[1]);
            MrtPipelineContext.begin(new int[] {97}, 8);
            MrtPipelineContext.end();
            assertEquals(SpirvOutputWrites.ALL, MrtPipelineContext.writtenOutputs(), "a stale write mask survived");
            assertNull(MrtPipelineContext.hostPassBlends(), "stale host-pass blend modes survived");
            assertNull(MrtPipelineContext.colorFormats(), "the contract survived");
        } finally {
            threadLocal("CURRENT_WRITES").remove();
            threadLocal("CURRENT_HOST_PASS_BLENDS").remove();
            MrtPipelineContext.end();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ThreadLocal<T> threadLocal(String name) throws ReflectiveOperationException {
        Field field = MrtPipelineContext.class.getDeclaredField(name);
        field.setAccessible(true);
        return (ThreadLocal<T>) field.get(null);
    }
}
