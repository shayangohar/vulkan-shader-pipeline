package net.chimera.render.shader;

import net.chimera.render.shader.ChimeraEntityBridge.Family;
import net.chimera.shaderpack.PackRenderingPhase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Iris answers renderStage for a held host-lane batch from the wrapper around the render type it
 * was asked for in (OuterWrappedRenderType). Every rendertype_lines draw reported OUTLINE, so a
 * fishing line took Glimmer's, Mellow's, Shrimple's and Photon's block-outline branch.
 */
final class HostLanePhaseTest {
    @Test
    void aHeldBatchDrawsInItsWritersWrapperPhase() {
        assertEquals(PackRenderingPhase.OUTLINE, ChimeraEntityBridge.wrapperPhase(true, null));
        assertEquals(PackRenderingPhase.OUTLINE, ChimeraEntityBridge.wrapperPhase(true, Family.ENTITY),
                "the block outline is asked for outside any entity, and its wrapper is the outline's");
        assertEquals(PackRenderingPhase.ENTITIES, ChimeraEntityBridge.wrapperPhase(false, Family.ENTITY));
        assertEquals(PackRenderingPhase.BLOCK_ENTITIES, ChimeraEntityBridge.wrapperPhase(false, Family.BLOCK));
        assertEquals(PackRenderingPhase.NONE, ChimeraEntityBridge.wrapperPhase(false, null));
    }

    /** The wrapper is the one open when vanilla asks for the batch: a hitbox inside an entity, the outline alone. */
    @Test
    void theWriterIsWhatIsRenderingWhenTheBatchIsAskedFor() {
        try {
            ChimeraEntityBridge.beginWorldWindow();
            assertEquals(PackRenderingPhase.NONE, ChimeraEntityBridge.hostLaneWritePhase());
            ChimeraEntityBridge.beginEntity("minecraft:fishing_bobber");
            assertEquals(PackRenderingPhase.ENTITIES, ChimeraEntityBridge.hostLaneWritePhase());
            ChimeraEntityBridge.endContext();
            ChimeraEntityBridge.beginBlockEntity(0);
            ChimeraEntityBridge.beginEntity("minecraft:pig");
            assertEquals(PackRenderingPhase.BLOCK_ENTITIES, ChimeraEntityBridge.hostLaneWritePhase(),
                    "a spawner's mob is written in the block entity's wrapper");
            ChimeraEntityBridge.endContext();
            ChimeraEntityBridge.endContext();
            ChimeraEntityBridge.beginBlockOutline();
            assertEquals(PackRenderingPhase.OUTLINE, ChimeraEntityBridge.hostLaneWritePhase());
            ChimeraEntityBridge.endBlockOutline();
            assertEquals(PackRenderingPhase.NONE, ChimeraEntityBridge.hostLaneWritePhase());
        } finally {
            ChimeraEntityBridge.endBlockOutline();
            ChimeraEntityBridge.endWorldWindow();
        }
    }

    /** A line drawn as written, outside every Iris wrapper, draws in the phase Iris holds between its windows. */
    @Test
    void aLineOutsideEveryWrapperDrawsInNone() {
        assertEquals(PackRenderingPhase.NONE, HostLayoutLane.LINE.phase());
    }
}
