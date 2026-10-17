package net.chimera.render.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import net.chimera.render.shader.ChimeraEntityBridge.Family;
import net.chimera.shaderpack.PackRenderingPhase;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;

import java.util.Optional;

/**
 * A vanilla render type drawn by a pack family lane. It keeps the vanilla type's state and
 * ordering flags, writes the lane's widened format, and draws inside the lane's draw window: its
 * setupRenderState opens the draw once vanilla's state (and with it the shader) is set, in the
 * Iris rendering phase of the context that wrote it.
 *
 * <p>The buffer source that batches them keeps one instance per lane, phase and type
 * ({@link FamilyBufferSource}).</p>
 */
public final class FamilyRenderType extends RenderType {
    private final Family family;
    private final PackRenderingPhase phase;
    private final RenderType host;
    /** The FamilyBufferSource flush that last drew this type. */
    private long lastDrawn;

    FamilyRenderType(Family family, PackRenderingPhase phase, RenderType host) {
        super("chimera_" + family.name().toLowerCase() + ":" + host.name,
                ChimeraEntityBridge.widenedFormat(family, host.format()), host.mode(), host.bufferSize(),
                host.affectsCrumbling(), host.sortOnUpload(), host::setupRenderState, host::clearRenderState);
        this.family = family;
        this.phase = phase;
        this.host = host;
    }

    public Family family() {
        return family;
    }

    PackRenderingPhase phase() {
        return phase;
    }

    RenderType host() {
        return host;
    }

    long lastDrawn() {
        return lastDrawn;
    }

    void drawnAt(long flush) {
        lastDrawn = flush;
    }

    @Override
    public void setupRenderState() {
        super.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        if (!ChimeraEntityBridge.beginDraw(family, format(), shader == null ? null : shader.getName(), phase)) {
            FamilyDrawTrace.noteDrawRefused(family, host);
        }
    }

    @Override
    public void clearRenderState() {
        if (ChimeraEntityBridge.isDrawActive()) ChimeraEntityBridge.endDraw();
        super.clearRenderState();
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
