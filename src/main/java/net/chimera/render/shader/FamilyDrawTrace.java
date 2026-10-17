package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.ChimeraMod;
import net.chimera.render.shader.ChimeraEntityBridge.Family;
import net.minecraft.client.renderer.RenderType;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * One-time diagnostics for the family lanes: the first draw of each lane, a refused family batch
 * and each host shader the shadow pass leaves out are logged once per pack session, so a log
 * shows which path each lane took.
 */
final class FamilyDrawTrace {
    private static final EnumSet<Family> drawn = EnumSet.noneOf(Family.class);
    private static final EnumSet<Family> refused = EnumSet.noneOf(Family.class);
    private static final Set<String> dropped = new HashSet<>();

    private FamilyDrawTrace() {}

    /** Forgets what was logged, so a newly installed pack logs each lane's path again. */
    static void reset() {
        drawn.clear();
        refused.clear();
        dropped.clear();
    }

    static void noteDraw(Family family, String shader, VertexFormat format) {
        if (drawn.add(family)) {
            ChimeraMod.LOGGER.info("[chimera] family bridge: lane={} drawing shader={} stride={}B",
                    family.name().toLowerCase(), shader, format.getVertexSize());
        }
    }

    /** A family batch whose lane had no pipeline when it drew; the pipeline seam skips it. */
    static void noteDrawRefused(Family family, RenderType host) {
        if (refused.add(family)) {
            ChimeraMod.LOGGER.warn("[chimera] family bridge: lane={} refused its batch type={}",
                    family.name().toLowerCase(), host.name);
        }
    }

    /** A shadow-pass draw no shadow lane serves, left out of the shadow map as Iris draws none like it. */
    static void noteShadowDropped(String shader) {
        if (dropped.add(String.valueOf(shader))) {
            ChimeraMod.LOGGER.info("[chimera] family bridge: shadow pass left out shader={}", shader);
        }
    }
}
