package net.chimera.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.mixin.ChimeraGameRendererHandAccess;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import org.joml.Matrix4f;

/** Iris's solid-before-deferred and translucent-before-composite hand schedule. */
public final class ChimeraHandRenderer implements AutoCloseable {
    public static final float DEPTH = 0.125F;
    private static ChimeraHandRenderer instance;
    private static boolean diverted;
    private static Boolean translucentPhase;
    private final ByteBufferBuilder vertices = new ByteBufferBuilder(256 * 1024);
    private final MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(vertices);
    private final SubmitNodeStorage submits = new SubmitNodeStorage();
    private final FeatureRenderDispatcher dispatcher;
    // Separate buffers: a later upload must not overwrite an earlier draw's projection.
    private final PerspectiveProjectionMatrixBuffer solidProjection = new PerspectiveProjectionMatrixBuffer("Chimera solid hand");
    private final PerspectiveProjectionMatrixBuffer translucentProjection = new PerspectiveProjectionMatrixBuffer("Chimera translucent hand");

    private ChimeraHandRenderer(Minecraft minecraft) {
        dispatcher = new FeatureRenderDispatcher(submits, minecraft.getBlockRenderer(), buffers,
                minecraft.getAtlasManager(), minecraft.renderBuffers().outlineBufferSource(), buffers, minecraft.font);
    }

    public static void beginFrame() { diverted = false; }
    public static boolean diverted() { return diverted; }
    public static boolean active() { return translucentPhase != null; }
    public static boolean skip(ItemStack item) {
        return active() && translucent(item) != translucentPhase;
    }
    public static boolean translucent(ItemStack item) {
        return item.getItem() instanceof BlockItem block
                && ItemBlockRenderTypes.getChunkRenderType(block.getBlock().defaultBlockState()) == ChunkSectionLayer.TRANSLUCENT;
    }

    /** Iris's depth squeeze expressed in VulkanMod's forward-Z [0,1] range. */
    public static Matrix4f projection(Matrix4f raster) {
        Matrix4f result = new Matrix4f(raster);
        float offset = (1.0F - DEPTH) * 0.5F;
        result.m02(DEPTH * raster.m02() + offset * raster.m03());
        result.m12(DEPTH * raster.m12() + offset * raster.m13());
        result.m22(DEPTH * raster.m22() + offset * raster.m23());
        result.m32(DEPTH * raster.m32() + offset * raster.m33());
        return result;
    }

    public static void drawSolid() {
        var pass = ChimeraRenderer.getMainPass();
        if (!ChimeraRenderer.segmentsActive() || pass == null || !pass.hasPackHandSchedule()) return;
        if (instance == null) instance = new ChimeraHandRenderer(Minecraft.getInstance());
        diverted = true;
        pass.beginHandSegment();
        instance.draw(false);
    }
    public static void drawTranslucent() {
        if (diverted && instance != null) instance.draw(true);
    }

    private void draw(boolean translucent) {
        var minecraft = Minecraft.getInstance();
        var game = minecraft.gameRenderer;
        var camera = game.getMainCamera();
        var player = minecraft.player;
        if (player == null || minecraft.gameMode == null || camera.isDetached()
                || !minecraft.options.getCameraType().isFirstPerson() || game.isPanoramicMode()
                || minecraft.options.hideGui || player.isSleeping()
                || minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR) return;
        float tick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        var access = (ChimeraGameRendererHandAccess) game;
        var bob = new PoseStack();
        access.chimera$bobHurt(bob, tick);
        if (minecraft.options.bobView().get()) access.chimera$bobView(bob, tick);
        Matrix4f projection = projection(game.getProjectionMatrix(access.chimera$getFov(camera, tick, false)));
        RenderSystem.backupProjectionMatrix();
        RenderSystem.getModelViewStack().pushMatrix();
        translucentPhase = translucent;
        boolean draw = false;
        try {
            RenderSystem.setProjectionMatrix((translucent ? translucentProjection : solidProjection).getBuffer(projection), ProjectionType.PERSPECTIVE);
            RenderSystem.getModelViewStack().set(bob.last().pose());
            draw = ChimeraEntityBridge.beginHandDraw(translucent);
            if (!draw) throw new IllegalStateException("installed hand schedule lost its family pipeline");
            game.itemInHandRenderer.renderHandsWithItems(tick, new PoseStack(), submits, player,
                    minecraft.getEntityRenderDispatcher().getPackedLightCoords(camera.entity(), tick));
            // In 1.21.11 this only submits. Scope both emission and the GPU flush.
            dispatcher.renderAllFeatures();
            buffers.endBatch();
        } finally {
            submits.endFrame();
            dispatcher.endFrame();
            if (draw) ChimeraEntityBridge.endDraw();
            translucentPhase = null;
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    public static void destroy() {
        if (instance != null) { instance.close(); instance = null; }
        diverted = false;
        translucentPhase = null;
    }
    @Override public void close() {
        dispatcher.close();
        solidProjection.close();
        translucentProjection.close();
        vertices.close();
    }
}
