package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.MeshData;
import net.chimera.shaderpack.PackRenderingPhase;
import net.minecraft.client.renderer.RenderType;

import java.util.Locale;
import java.util.Optional;

/**
 * A render type a world host-layout lane draws, held by a level source (FamilyBufferSource) with
 * the Iris rendering phase it was written in ({@link ChimeraEntityBridge#hostLaneWritePhase}).
 * Iris answers renderStage for such a draw from the wrapper around the render type it was asked
 * for in, and vanilla asks for RenderType.lines() for a hitbox and for the block outline alike: one
 * held batch per type drew both in one phase.
 *
 * <p>It keeps the host type's state, format and ordering flags. Its lane still takes the draw
 * where the host shader draws (PackHostDraws), in this type's phase.</p>
 */
final class HeldLaneRenderType extends RenderType {
    private final RenderType host;
    private final PackRenderingPhase phase;
    /** The host type's transparency, as Iris batches it. */
    private final IrisTransparency transparency;

    HeldLaneRenderType(RenderType host, PackRenderingPhase phase) {
        super("chimera_" + phase.name().toLowerCase(Locale.ROOT) + ":" + host.name, host.format(), host.mode(),
                host.bufferSize(), host.affectsCrumbling(), host.sortOnUpload(), host::setupRenderState,
                host::clearRenderState);
        this.host = host;
        this.phase = phase;
        this.transparency = IrisTransparency.of(host);
    }

    /** The host type's transparency, which orders the batch's draw as Iris orders its batches. */
    IrisTransparency transparency() {
        return transparency;
    }

    @Override
    public void draw(MeshData mesh) {
        ChimeraEntityBridge.beginHeldBatch(phase);
        try {
            super.draw(mesh);
        } finally {
            ChimeraEntityBridge.endHeldBatch();
        }
    }

    @Override
    public Optional<RenderType> outline() {
        return host.outline();
    }

    @Override
    public boolean isOutline() {
        return host.isOutline();
    }

    @Override
    public boolean canConsolidateConsecutiveGeometry() {
        return host.canConsolidateConsecutiveGeometry();
    }
}
