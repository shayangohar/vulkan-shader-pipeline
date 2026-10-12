package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The shaders.properties switches that decide how terrain vertex colours carry Minecraft's
 * block lighting, read as Iris ShaderProperties reads them and applied as Iris's
 * WorldRenderingSettings applies them. Both default to off (PackDirectives:
 * {@code getOldLighting().orElse(false)}, {@code getSeparateAo().orElse(false)}).
 *
 * <p>Without {@code oldLighting}, Iris meshes terrain and fluids unshaded (ClientLevel.getShade
 * sees {@code shaded = false}), because the pack lights faces itself; the vanilla shade would
 * darken every side face and underside a second time.</p>
 *
 * <p>With {@code separateAo}, Iris keeps the block's tint in the vertex colour's RGB and moves
 * the light pipeline's per-vertex brightness (ambient occlusion times face shade) into its
 * alpha, so the pack applies ambient occlusion its own way. Without it the brightness is
 * multiplied into RGB.</p>
 */
public record TerrainShadingDirectives(boolean oldLighting, boolean separateAo, List<String> deviations) {
    public static final TerrainShadingDirectives DEFAULTS = new TerrainShadingDirectives(false, false, List.of());
    /** Minecraft with no pack: shaded faces and ambient occlusion multiplied into RGB. */
    public static final TerrainShadingDirectives VANILLA = new TerrainShadingDirectives(true, false, List.of());

    public TerrainShadingDirectives {
        deviations = deviations == null ? List.of() : List.copyOf(deviations);
    }

    static TerrainShadingDirectives fromProperties(Map<String, String> properties) {
        List<String> deviations = new ArrayList<>();
        boolean oldLighting = PackBooleanDirective.read(properties, "oldLighting", DEFAULTS.oldLighting(),
                deviations, "OLD_LIGHTING_DIRECTIVE_MALFORMED");
        boolean separateAo = PackBooleanDirective.read(properties, "separateAo", DEFAULTS.separateAo(),
                deviations, "SEPARATE_AO_DIRECTIVE_MALFORMED");
        return new TerrainShadingDirectives(oldLighting, separateAo, deviations);
    }

    /** True when the pack lights faces itself, so the host's directional shade must not apply. */
    public boolean disablesDirectionalShading() {
        return !oldLighting;
    }
}
