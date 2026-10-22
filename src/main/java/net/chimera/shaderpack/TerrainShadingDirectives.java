package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the pack changes in chunk meshes, as Iris's WorldRenderingSettings applies it: how terrain
 * vertex colours carry Minecraft's block lighting, through the shaders.properties switches
 * oldLighting and separateAo (ShaderProperties, both default off) and the ambientOcclusionLevel
 * directive (PackDirectives, default 1), and whether light blocks get a voxel quad
 * (voxelizeLightBlocks, default off). A change rebuilds the meshes.
 *
 * <p>Without {@code oldLighting}, Iris meshes terrain and fluids unshaded (ClientLevel.getShade
 * sees {@code shaded = false}), because the pack lights faces itself; the vanilla shade would
 * darken every side face and underside a second time.</p>
 *
 * <p>With {@code separateAo}, Iris keeps the block's tint in the vertex colour's RGB and moves
 * the light pipeline's per-vertex brightness (ambient occlusion times face shade) into its
 * alpha, so the pack applies ambient occlusion its own way. Without it the brightness is
 * multiplied into RGB.</p>
 *
 * <p>{@code ambientOcclusionLevel} scales how far a block's shade brightness falls below 1
 * (Iris's BlockStateBase.getShadeBrightness hook): 0 meshes terrain with no ambient occlusion.</p>
 *
 * <p>With {@code voxelizeLightBlocks}, each light block (minecraft:light), which has no model,
 * is meshed as one zero-area cutout-mipped quad carrying its id and emission, for a pack that
 * voxelizes terrain in its shadow pass (MixinChunkMeshBuildTask.iris$setLightBlock).</p>
 */
public record TerrainShadingDirectives(boolean oldLighting, boolean separateAo, float ambientOcclusionLevel,
                                       boolean voxelizeLightBlocks, List<String> deviations) {
    public static final TerrainShadingDirectives DEFAULTS = new TerrainShadingDirectives(false, false, List.of());
    /** Minecraft with no pack: shaded faces and ambient occlusion multiplied into RGB at full strength. */
    public static final TerrainShadingDirectives VANILLA = new TerrainShadingDirectives(true, false, List.of());

    public TerrainShadingDirectives {
        deviations = deviations == null ? List.of() : List.copyOf(deviations);
    }

    /** The switches with Iris's full ambient occlusion. */
    public TerrainShadingDirectives(boolean oldLighting, boolean separateAo, List<String> deviations) {
        this(oldLighting, separateAo, 1.0F, deviations);
    }

    /** The lighting switches, with no light-block voxels. */
    public TerrainShadingDirectives(boolean oldLighting, boolean separateAo, float ambientOcclusionLevel,
                                    List<String> deviations) {
        this(oldLighting, separateAo, ambientOcclusionLevel, false, deviations);
    }

    static TerrainShadingDirectives fromProperties(Map<String, String> properties) {
        return fromProperties(properties, PackDirectives.DEFAULTS.ambientOcclusionLevel());
    }

    static TerrainShadingDirectives fromProperties(Map<String, String> properties, float ambientOcclusionLevel) {
        List<String> deviations = new ArrayList<>();
        boolean oldLighting = PackBooleanDirective.read(properties, "oldLighting", DEFAULTS.oldLighting(),
                deviations, "OLD_LIGHTING_DIRECTIVE_MALFORMED");
        boolean separateAo = PackBooleanDirective.read(properties, "separateAo", DEFAULTS.separateAo(),
                deviations, "SEPARATE_AO_DIRECTIVE_MALFORMED");
        boolean voxelizeLightBlocks = PackBooleanDirective.read(properties, "voxelizeLightBlocks",
                DEFAULTS.voxelizeLightBlocks(), deviations, "VOXELIZE_LIGHT_BLOCKS_DIRECTIVE_MALFORMED");
        return new TerrainShadingDirectives(oldLighting, separateAo, ambientOcclusionLevel, voxelizeLightBlocks,
                deviations);
    }

    /** True when the pack lights faces itself, so the host's directional shade must not apply. */
    public boolean disablesDirectionalShading() {
        return !oldLighting;
    }

    /** A block's shade brightness at this level: Iris's {@code 1 - level * (1 - original)}. */
    public float shadeBrightness(float original) {
        return 1.0F - ambientOcclusionLevel * (1.0F - original);
    }
}
