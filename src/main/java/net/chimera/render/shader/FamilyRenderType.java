package net.chimera.render.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import net.chimera.render.shader.ChimeraEntityBridge.Family;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A vanilla render type drawn by a pack family lane. It keeps the vanilla type's state and
 * ordering flags, writes the lane's widened format, and draws inside the lane's draw window: its
 * setupRenderState opens the draw once vanilla's state (and with it the shader) is set.
 *
 * <p>Instances are cached per lane and type, so a buffer source batches them like any render type;
 * the cache lives as long as the installed lanes ({@link #clear}).</p>
 */
public final class FamilyRenderType extends RenderType {
    private static final Map<Family, Map<RenderType, FamilyRenderType>> CACHE = new HashMap<>();

    private final Family family;
    private final RenderType host;

    private FamilyRenderType(Family family, RenderType host) {
        super("chimera_" + family.name().toLowerCase() + ":" + host.name,
                ChimeraEntityBridge.widenedFormat(family, host.format()), host.mode(), host.bufferSize(),
                host.affectsCrumbling(), host.sortOnUpload(), host::setupRenderState, host::clearRenderState);
        this.family = family;
        this.host = host;
    }

    /** The cached family type for {@code host} drawn by {@code family}. */
    static FamilyRenderType of(Family family, RenderType host) {
        return CACHE.computeIfAbsent(family, ignored -> new HashMap<>())
                .computeIfAbsent(host, type -> new FamilyRenderType(family, type));
    }

    /** Forgets every family type; the buffers holding them are released first (FamilyBufferSource). */
    static void clear() {
        CACHE.clear();
    }

    public Family family() {
        return family;
    }

    @Override
    public void setupRenderState() {
        super.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        if (!ChimeraEntityBridge.beginDraw(family, format(), shader == null ? null : shader.getName())) {
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
