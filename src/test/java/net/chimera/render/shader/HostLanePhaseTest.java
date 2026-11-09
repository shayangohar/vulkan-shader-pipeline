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
        assertEquals(PackRenderingPhase.ENTITIES, ChimeraEntityBridge.wrapperPhase(Family.ENTITY));
        assertEquals(PackRenderingPhase.BLOCK_ENTITIES, ChimeraEntityBridge.wrapperPhase(Family.BLOCK));
        assertEquals(PackRenderingPhase.NONE, ChimeraEntityBridge.wrapperPhase(null));
    }

    /**
     * The wrapper is the one open when vanilla asks for the batch: a hitbox inside an entity. The
     * block outline is asked for outside both and reports NONE, as the outline's draw reads
     * renderStage 0 in the owner's Iris captures of Glimmer and Mellow: OUTLINE made Glimmer's line
     * transparent and Mellow's opaque black.
     */
    @Test
    void theWriterIsWhatIsRenderingWhenTheBatchIsAskedFor() {
        try {
            ChimeraEntityBridge.beginWorldWindow();
            assertEquals(PackRenderingPhase.NONE, ChimeraEntityBridge.hostLaneWritePhase(), "the block outline");
            ChimeraEntityBridge.beginEntity("minecraft:fishing_bobber");
            assertEquals(PackRenderingPhase.ENTITIES, ChimeraEntityBridge.hostLaneWritePhase());
            ChimeraEntityBridge.endContext();
            ChimeraEntityBridge.beginBlockEntity(0);
            ChimeraEntityBridge.beginEntity("minecraft:pig");
            assertEquals(PackRenderingPhase.BLOCK_ENTITIES, ChimeraEntityBridge.hostLaneWritePhase(),
                    "a spawner's mob is written in the block entity's wrapper");
            ChimeraEntityBridge.endContext();
            ChimeraEntityBridge.endContext();
            assertEquals(PackRenderingPhase.NONE, ChimeraEntityBridge.hostLaneWritePhase());
        } finally {
            ChimeraEntityBridge.endWorldWindow();
        }
    }

    /** A line drawn as written, outside every Iris wrapper, draws in the phase Iris holds between its windows. */
    @Test
    void aLineOutsideEveryWrapperDrawsInNone() {
        assertEquals(PackRenderingPhase.NONE, HostLayoutLane.LINE.phase());
    }
}
