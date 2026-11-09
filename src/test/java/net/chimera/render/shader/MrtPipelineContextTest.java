package net.chimera.render.shader;

import net.chimera.shaderpack.PackBlendPlan;
import net.chimera.shaderpack.SpirvOutputWrites;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * GraphicsPipelineMrtMixin opens a pipeline-creation scope at createGraphicsPipeline's head and
 * closes it at its return. A registered pipeline's creation swaps its own contract in; when the
 * creation threw, the return never ran, and the pass's later variants read that contract.
 */
final class MrtPipelineContextTest {
    /** The next creation restores the contract a throwing creation left swapped in. */
    @Test
    void aCreationThatThrewHandsTheNextOneThePassContract() throws ReflectiveOperationException {
        try {
            MrtPipelineContext.begin(new int[] {37}, 8);
            Object passContract = threadLocal("CURRENT").get();
            // A registered pipeline's creation swapped its contract in, then threw before its return.
            MrtPipelineContext.begin(new int[] {97}, 8);
            threadLocal("PREVIOUS").set(passContract);
            threadLocal("PIPELINE_SCOPE").set(Boolean.TRUE);
            MrtPipelineContext.beginPipeline(null);
            assertArrayEquals(new int[] {37}, MrtPipelineContext.colorFormats(), "the pass's own contract");
            MrtPipelineContext.endPipeline();
            assertArrayEquals(new int[] {37}, MrtPipelineContext.colorFormats(), "still the pass's own after it");
        } finally {
            MrtPipelineContext.end();
        }
    }

    /** end() clears every thread-local, so nothing outlives the pass. */
    @Test
    void endClearsTheScopeWithTheContract() throws ReflectiveOperationException {
        try {
            threadLocal("CURRENT_WRITES").set(0b1);
            threadLocal("CURRENT_HOST_PASS_BLENDS").set(new PackBlendPlan.Mode[1]);
            MrtPipelineContext.begin(new int[] {97}, 8);
            MrtPipelineContext.end();
            assertEquals(SpirvOutputWrites.ALL, MrtPipelineContext.writtenOutputs());
            assertNull(MrtPipelineContext.hostPassBlends());
            assertNull(MrtPipelineContext.colorFormats());
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
