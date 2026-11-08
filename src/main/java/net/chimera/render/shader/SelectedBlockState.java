package net.chimera.render.shader;

import net.chimera.mixin.ChimeraGameRendererHandAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.vulkan.util.MappedBuffer;

import java.util.Set;

/**
 * Iris's selected-block uniforms (IrisExclusiveUniforms), captured once per frame. While the game
 * draws the block outline and the crosshair is on a block, currentSelectedBlockId is that block
 * state's block.properties id (-1 when unmapped, as Iris's BlockMaterialMapping default) and
 * currentSelectedBlockPos is the block's centre relative to the camera. Otherwise they are 0 and
 * (-256, -256, -256). The id is 0 for air and outside the world border.
 */
final class SelectedBlockState implements FrameValueSource {
    static final Set<String> NAMES = Set.of("currentSelectedBlockId", "currentSelectedBlockPos");

    private static final float NONE = -256.0F;

    private int id;
    private final float[] position = {NONE, NONE, NONE};

    void capture(Minecraft minecraft) {
        clear();
        if (minecraft.level == null || !(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK
                || !((ChimeraGameRendererHandAccess) minecraft.gameRenderer).chimera$shouldRenderBlockOutline()) {
            return;
        }
        BlockPos block = hit.getBlockPos();
        BlockState state = minecraft.level.getBlockState(block);
        if (!state.isAir() && minecraft.level.getWorldBorder().isWithinBounds(block)) {
            this.id = ChimeraTerrainPipelines.blockStateId(state);
        }
        Vec3 relative = block.getCenter().subtract(minecraft.gameRenderer.getMainCamera().getPosition());
        this.position[0] = (float) relative.x;
        this.position[1] = (float) relative.y;
        this.position[2] = (float) relative.z;
    }

    void clear() {
        this.id = 0;
        this.position[0] = this.position[1] = this.position[2] = NONE;
    }

    @Override
    public Set<String> names() {
        return NAMES;
    }

    @Override
    public double value(String name, int component) {
        return switch (name) {
            case "currentSelectedBlockId" -> component > 0 ? Double.NaN : this.id;
            case "currentSelectedBlockPos" -> component < 3 ? this.position[Math.max(component, 0)] : Double.NaN;
            default -> Double.NaN;
        };
    }

    @Override
    public void write(String name, String type, MappedBuffer target) {
        if (name.equals("currentSelectedBlockId")) {
            target.putInt(0, this.id);
            return;
        }
        for (int component = 0; component < 3; component++) {
            target.putFloat(component * 4, this.position[component]);
        }
    }
}
