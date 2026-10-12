package net.chimera.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.mixin.ChimeraLevelRenderStateAccessor;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.shaderpack.ShadowCasterDirectives;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.vulkanmod.render.chunk.RenderSection;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Entities and block entities in the pack shadow map, as Iris ShadowRenderer draws them: after
 * the opaque terrain, so shadowtex1 holds them, and before the translucent layer.
 *
 * <p>Like Iris it owns its submissions and feature dispatcher, extracts entities from the light's
 * view (the camera entity included, so the player casts in first person) and block entities from
 * the sections inside the light volume, and draws with the light view on the model-view stack and
 * the light projection as the projection (IrisRenderSystem.setShadowProjection). The bridge's
 * caster window routes every entity-format batch to shadow_entities.</p>
 */
public final class ChimeraShadowCasters implements AutoCloseable {
    private static ChimeraShadowCasters instance;

    private final ByteBufferBuilder vertices = new ByteBufferBuilder(256 * 1024);
    private final java.util.SequencedMap<RenderType, ByteBufferBuilder> fixed = ChimeraHandRenderer.fixedBuffers();
    private final MultiBufferSource.BufferSource buffers = MultiBufferSource.immediateWithBuffers(fixed, vertices);
    // Casters carry no outline; this source only keeps a stray outline out of the frame's own.
    private final OutlineBufferSource outlines = new OutlineBufferSource();
    private final SubmitNodeStorage submits = new SubmitNodeStorage();
    private final FeatureRenderDispatcher dispatcher;
    private final PerspectiveProjectionMatrixBuffer projection = new PerspectiveProjectionMatrixBuffer("Chimera shadow casters");
    private final List<EntityRenderState> entities = new ArrayList<>();
    private final List<BlockEntityRenderState> blockEntities = new ArrayList<>();

    private ChimeraShadowCasters(Minecraft minecraft) {
        dispatcher = new FeatureRenderDispatcher(submits, minecraft.getBlockRenderer(), buffers,
                minecraft.getAtlasManager(), outlines, buffers, minecraft.font);
    }

    /**
     * Draws this frame's casters into the open shadow map. The light matrices are
     * camera-relative, like the terrain casters'; culling is Iris's entity shadow frustum.
     */
    static void render(double cameraX, double cameraY, double cameraZ, Matrix4f lightView,
                       Matrix4f lightProjection, ShadowCasterDirectives directives,
                       ShadowCullingFrustum culling) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null
                || !(minecraft.levelRenderer instanceof ChimeraLevelRenderStateAccessor access)) {
            return;
        }
        CameraRenderState camera = access.chimera$levelRenderState().cameraRenderState;
        if (camera == null || !camera.initialized) return;
        if (instance == null) instance = new ChimeraShadowCasters(minecraft);
        instance.draw(minecraft, minecraft.level, camera, cameraX, cameraY, cameraZ, lightView,
                lightProjection, directives, culling);
    }

    public static void destroy() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    private void draw(Minecraft minecraft, ClientLevel level, CameraRenderState camera,
                      double cameraX, double cameraY, double cameraZ, Matrix4f lightView,
                      Matrix4f lightProjection, ShadowCasterDirectives directives,
                      ShadowCullingFrustum culling) {
        // Submission and drawing both sit in the caster window: the storage mixins hold
        // each submission for the shadow family as it arrives.
        if (!ChimeraEntityBridge.beginShadowCasters()) return;
        RenderSystem.backupProjectionMatrix();
        RenderSystem.getModelViewStack().pushMatrix();
        try {
            DeltaTracker delta = minecraft.getDeltaTracker();
            CasterFrustum frustum = new CasterFrustum(lightView, lightProjection, culling);
            frustum.prepare(cameraX, cameraY, cameraZ);
            extractEntities(minecraft, level, frustum, delta, directives, cameraX, cameraY, cameraZ);
            if (directives.anyBlockEntities()) {
                extractBlockEntities(minecraft, level, delta.getGameTimeDeltaPartialTick(false),
                        !directives.blockEntities());
            }
            submit(minecraft, camera, cameraX, cameraY, cameraZ);
            RenderSystem.setProjectionMatrix(projection.getBuffer(lightProjection), ProjectionType.ORTHOGRAPHIC);
            RenderSystem.getModelViewStack().set(lightView);
            dispatcher.renderAllFeatures();
            buffers.endBatch();
            outlines.endOutlineBatch();
        } finally {
            ChimeraEntityBridge.endShadowCasters();
            entities.clear();
            blockEntities.clear();
            submits.clear();
            submits.endFrame();
            dispatcher.endFrame();
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    /**
     * Iris ShadowRenderer.extractVisibleEntities: every entity the light volume sees, without
     * vanilla's camera-entity exclusion; spectators cast nothing. With shadowEntities off,
     * shadowPlayer still adds the player and its vehicle.
     */
    private void extractEntities(Minecraft minecraft, ClientLevel level, Frustum frustum, DeltaTracker delta,
                                 ShadowCasterDirectives directives, double cameraX, double cameraY, double cameraZ) {
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        if (directives.entities()) {
            for (Entity entity : level.entitiesForRendering()) {
                if (entity instanceof AbstractClientPlayer player && player.isSpectator()) continue;
                if (dispatcher.shouldRender(entity, frustum, cameraX, cameraY, cameraZ)
                        || entity.hasIndirectPassenger(minecraft.player)) {
                    extract(dispatcher, level, delta, entity);
                }
            }
        } else if (directives.player() && minecraft.player != null) {
            Player player = minecraft.player;
            if (!player.isSpectator() && !player.isInvisible()) extract(dispatcher, level, delta, player);
            if (player.getVehicle() != null) extract(dispatcher, level, delta, player.getVehicle());
        }
    }

    private void extract(EntityRenderDispatcher dispatcher, ClientLevel level, DeltaTracker delta, Entity entity) {
        if (entity.tickCount == 0) {
            entity.xOld = entity.getX();
            entity.yOld = entity.getY();
            entity.zOld = entity.getZ();
        }
        float partialTick = delta.getGameTimeDeltaPartialTick(!level.tickRateManager().isEntityFrozen(entity));
        EntityRenderState state = dispatcher.extractEntity(entity, partialTick);
        // A glowing entity's outline belongs to the camera's frame, not the shadow map.
        state.outlineColor = 0;
        entities.add(state);
    }

    /**
     * Block entities in the sections inside the light volume, and the level's globally rendered
     * ones, as Iris extracts them from its shadow-culled sections. lightsOnly keeps only those
     * whose block emits light (shadowLightBlockEntities without shadowBlockEntities).
     */
    private void extractBlockEntities(Minecraft minecraft, ClientLevel level, float partialTick, boolean lightsOnly) {
        BlockEntityRenderDispatcher dispatcher = minecraft.getBlockEntityRenderDispatcher();
        for (RenderSection section : ShadowSectionQueue.blockEntitySections()) {
            for (BlockEntity blockEntity : section.getCompiledSection().getBlockEntities()) {
                extract(dispatcher, blockEntity, partialTick, lightsOnly);
            }
        }
        for (BlockEntity blockEntity : level.getGloballyRenderedBlockEntities()) {
            if (!blockEntity.isRemoved()) extract(dispatcher, blockEntity, partialTick, lightsOnly);
        }
    }

    private void extract(BlockEntityRenderDispatcher dispatcher, BlockEntity blockEntity, float partialTick,
                         boolean lightsOnly) {
        // No destroy overlay: block cracks are not shadow casters.
        BlockEntityRenderState state = dispatcher.tryExtractRenderState(blockEntity, partialTick, null);
        if (state != null && (!lightsOnly || state.blockState.getLightEmission() > 0)) blockEntities.add(state);
    }

    private void submit(Minecraft minecraft, CameraRenderState camera, double cameraX, double cameraY, double cameraZ) {
        PoseStack pose = new PoseStack();
        EntityRenderDispatcher entityDispatcher = minecraft.getEntityRenderDispatcher();
        for (EntityRenderState state : entities) {
            entityDispatcher.submit(state, camera, state.x - cameraX, state.y - cameraY, state.z - cameraZ,
                    pose, submits);
        }
        BlockEntityRenderDispatcher blockDispatcher = minecraft.getBlockEntityRenderDispatcher();
        for (BlockEntityRenderState state : blockEntities) {
            BlockPos position = state.blockPos;
            pose.pushPose();
            pose.translate(position.getX() - cameraX, position.getY() - cameraY, position.getZ() - cameraZ);
            blockDispatcher.submit(state, pose, submits, camera);
            pose.popPose();
        }
    }

    @Override
    public void close() {
        dispatcher.close();
        projection.close();
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
