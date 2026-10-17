package net.chimera.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.chimera.mixin.ChimeraLevelRendererAccessor;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.FamilyBufferSource;
import net.chimera.shaderpack.ShadowCasterDirectives;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.vulkanmod.render.chunk.RenderSection;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.util.Set;
import java.util.SequencedMap;

/**
 * Entities and block entities in the pack shadow map, as Iris ShadowRenderer draws them: after
 * the opaque terrain, so shadowtex1 holds them, and before the translucent layer.
 *
 * <p>Like Iris it owns its buffers, draws every entity the light's view holds (the camera entity
 * included, so the player casts in first person) and the block entities of the sections inside the
 * light volume, with the light view on the model-view stack and the light projection as the
 * projection. The bridge's caster window routes every entity-format batch to shadow_entities.</p>
 */
public final class ChimeraShadowCasters implements AutoCloseable {
    private static ChimeraShadowCasters instance;

    private final ByteBufferBuilder vertices = new ByteBufferBuilder(256 * 1024);
    private final SequencedMap<RenderType, ByteBufferBuilder> fixed = ChimeraHandRenderer.fixedBuffers();
    private final FamilyBufferSource buffers =
            new FamilyBufferSource(MultiBufferSource.immediateWithBuffers(fixed, vertices));

    /**
     * Draws this frame's casters into the open shadow map. The light matrices are
     * camera-relative, like the terrain casters'; culling is Iris's entity shadow frustum.
     */
    static void render(double cameraX, double cameraY, double cameraZ, Matrix4f lightView,
                       Matrix4f lightProjection, ShadowCasterDirectives directives,
                       ShadowCullingFrustum culling) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        if (instance == null) instance = new ChimeraShadowCasters();
        instance.draw(minecraft, minecraft.level, cameraX, cameraY, cameraZ, lightView, lightProjection,
                directives, culling);
    }

    public static void destroy() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    private void draw(Minecraft minecraft, ClientLevel level, double cameraX, double cameraY, double cameraZ,
                      Matrix4f lightView, Matrix4f lightProjection, ShadowCasterDirectives directives,
                      ShadowCullingFrustum culling) {
        if (!ChimeraEntityBridge.beginShadowCasters()) return;
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        RenderSystem.backupProjectionMatrix();
        modelView.pushMatrix();
        try {
            RenderSystem.setProjectionMatrix(lightProjection, VertexSorting.ORTHOGRAPHIC_Z);
            modelView.set(lightView);
            RenderSystem.applyModelViewMatrix();
            CasterFrustum frustum = new CasterFrustum(lightView, lightProjection, culling);
            frustum.prepare(cameraX, cameraY, cameraZ);
            PoseStack poses = new PoseStack();
            DeltaTracker delta = minecraft.getTimer();
            drawEntities(minecraft, level, frustum, delta, directives, poses, cameraX, cameraY, cameraZ);
            if (directives.anyBlockEntities()) {
                drawBlockEntities(minecraft, delta.getGameTimeDeltaPartialTick(false), !directives.blockEntities(),
                        poses, cameraX, cameraY, cameraZ);
            }
            buffers.endFamilyBatches();
            buffers.endBatch();
        } finally {
            ChimeraEntityBridge.endShadowCasters();
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    /**
     * Iris ShadowRenderer.renderEntities: every entity the light volume sees, without vanilla's
     * camera-entity exclusion; spectators cast nothing. With shadowEntities off, shadowPlayer
     * still draws the player and its vehicle.
     */
    private void drawEntities(Minecraft minecraft, ClientLevel level, Frustum frustum, DeltaTracker delta,
                              ShadowCasterDirectives directives, PoseStack poses,
                              double cameraX, double cameraY, double cameraZ) {
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        var renderer = (ChimeraLevelRendererAccessor) minecraft.levelRenderer;
        if (directives.entities()) {
            for (Entity entity : level.entitiesForRendering()) {
                if (entity instanceof AbstractClientPlayer player && player.isSpectator()) continue;
                if (dispatcher.shouldRender(entity, frustum, cameraX, cameraY, cameraZ)
                        || entity.hasIndirectPassenger(minecraft.player)) {
                    draw(renderer, level, delta, entity, poses, cameraX, cameraY, cameraZ);
                }
            }
        } else if (directives.player() && minecraft.player != null) {
            Player player = minecraft.player;
            if (!player.isSpectator() && !player.isInvisible()) {
                draw(renderer, level, delta, player, poses, cameraX, cameraY, cameraZ);
            }
            if (player.getVehicle() != null) {
                draw(renderer, level, delta, player.getVehicle(), poses, cameraX, cameraY, cameraZ);
            }
        }
    }

    /** One entity as LevelRenderer.renderLevel draws it, without the outline source a glowing one takes. */
    private void draw(ChimeraLevelRendererAccessor renderer, ClientLevel level, DeltaTracker delta, Entity entity,
                      PoseStack poses, double cameraX, double cameraY, double cameraZ) {
        if (entity.tickCount == 0) {
            entity.xOld = entity.getX();
            entity.yOld = entity.getY();
            entity.zOld = entity.getZ();
        }
        float partialTick = delta.getGameTimeDeltaPartialTick(!level.tickRateManager().isEntityFrozen(entity));
        renderer.chimera$renderEntity(entity, cameraX, cameraY, cameraZ, partialTick, poses, buffers);
    }

    /**
     * Block entities in the sections inside the light volume, and the level's globally rendered
     * ones, as Iris draws them from its shadow-culled sections. lightsOnly keeps only those whose
     * block emits light (shadowLightBlockEntities without shadowBlockEntities). Block cracks are
     * not shadow casters.
     */
    private void drawBlockEntities(Minecraft minecraft, float partialTick, boolean lightsOnly, PoseStack poses,
                                   double cameraX, double cameraY, double cameraZ) {
        BlockEntityRenderDispatcher dispatcher = minecraft.getBlockEntityRenderDispatcher();
        for (RenderSection section : ShadowSectionQueue.blockEntitySections()) {
            for (BlockEntity blockEntity : section.getCompiledSection().getBlockEntities()) {
                draw(dispatcher, blockEntity, partialTick, lightsOnly, poses, cameraX, cameraY, cameraZ);
            }
        }
        Set<BlockEntity> global = ((ChimeraLevelRendererAccessor) minecraft.levelRenderer).chimera$globalBlockEntities();
        synchronized (global) {
            for (BlockEntity blockEntity : global) {
                if (!blockEntity.isRemoved()) {
                    draw(dispatcher, blockEntity, partialTick, lightsOnly, poses, cameraX, cameraY, cameraZ);
                }
            }
        }
    }

    private void draw(BlockEntityRenderDispatcher dispatcher, BlockEntity blockEntity, float partialTick,
                      boolean lightsOnly, PoseStack poses, double cameraX, double cameraY, double cameraZ) {
        if (lightsOnly && blockEntity.getBlockState().getLightEmission() == 0) return;
        BlockPos position = blockEntity.getBlockPos();
        poses.pushPose();
        poses.translate(position.getX() - cameraX, position.getY() - cameraY, position.getZ() - cameraZ);
        dispatcher.render(blockEntity, partialTick, poses, buffers);
        poses.popPose();
    }

    @Override
    public void close() {
        buffers.close();
        vertices.close();
        fixed.values().forEach(ByteBufferBuilder::close);
    }

    /**
     * The light volume, narrowed by Iris's entity shadow frustum (ShadowRenderer
     * .createShadowFrustum); its safe zone is kept whole, as for terrain. Entities that ignore
     * culling, such as lightning bolts, never reach this test, as in Iris.
     */
    private static final class CasterFrustum extends Frustum {
        private final ShadowCullingFrustum culling;
        private double cameraX;
        private double cameraY;
        private double cameraZ;

        CasterFrustum(Matrix4f view, Matrix4f projection, ShadowCullingFrustum culling) {
            super(view, projection);
            this.culling = culling;
        }

        @Override
        public void prepare(double x, double y, double z) {
            super.prepare(x, y, z);
            this.cameraX = x;
            this.cameraY = y;
            this.cameraZ = z;
        }

        @Override
        public boolean isVisible(AABB box) {
            float minX = (float) (box.minX - cameraX);
            float minY = (float) (box.minY - cameraY);
            float minZ = (float) (box.minZ - cameraZ);
            float maxX = (float) (box.maxX - cameraX);
            float maxY = (float) (box.maxY - cameraY);
            float maxZ = (float) (box.maxZ - cameraZ);
            if (culling.forced(minX, minY, minZ, maxX, maxY, maxZ)) return true;
            return culling.visible(minX, minY, minZ, maxX, maxY, maxZ) && super.isVisible(box);
        }
    }
}
