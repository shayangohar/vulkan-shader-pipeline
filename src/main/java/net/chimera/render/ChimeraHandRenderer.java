package net.chimera.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.chimera.mixin.ChimeraGameRendererHandAccess;
import net.chimera.render.shader.FamilyBufferSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/** Iris's solid-before-deferred and translucent-before-composite hand schedule. */
public final class ChimeraHandRenderer implements AutoCloseable {
    public static final float DEPTH = 0.125F;
    private static final RandomSource MODEL_RANDOM = RandomSource.create();
    private static ChimeraHandRenderer instance;
    private static boolean diverted;
    private static Boolean translucentPhase;
    private final ByteBufferBuilder vertices = new ByteBufferBuilder(256 * 1024);
    /**
     * Vanilla RenderBuffers' fixed buffers, in its order. A foil item writes its glint
     * and its own layer at once (VertexMultiConsumer); in one shared buffer, opening
     * the second ends the first ("Not building!").
     */
    private final SequencedMap<RenderType, ByteBufferBuilder> fixed = fixedBuffers();
    private final FamilyBufferSource buffers =
            new FamilyBufferSource(MultiBufferSource.immediateWithBuffers(fixed, vertices));

    public static void beginFrame() { diverted = false; }
    public static boolean diverted() { return diverted; }
    public static boolean active() { return translucentPhase != null; }
    /** True while the translucent half of the hand schedule draws. */
    public static boolean translucentPhase() { return Boolean.TRUE.equals(translucentPhase); }
    public static boolean skip(ItemStack item) {
        return active() && translucent(item) != translucentPhase;
    }

    /**
     * A held block whose model renders in the translucent chunk layer, as Iris decides. NeoForge
     * models name their layers (a model's "render_type"), so the model is asked rather than the
     * vanilla block-to-layer table.
     */
    public static boolean translucent(ItemStack item) {
        if (!(item.getItem() instanceof BlockItem block)) return false;
        BlockState state = block.getBlock().defaultBlockState();
        MODEL_RANDOM.setSeed(42L);
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(state)
                .getRenderTypes(state, MODEL_RANDOM, ModelData.EMPTY).contains(RenderType.translucent());
    }

    /**
     * Iris's depth squeeze on Minecraft's GL projection (HandRenderer). The host maps the result
     * onto Vulkan's [0,1] depth, which puts the hand at Iris's 0.5 +/- DEPTH / 2.
     */
    public static Matrix4f projection(Matrix4f raster) {
        return new Matrix4f().scale(1.0F, 1.0F, DEPTH).mul(raster);
    }

    public static void drawSolid() {
        var pass = ChimeraRenderer.getMainPass();
        if (!ChimeraRenderer.segmentsActive() || pass == null || !pass.hasPackHandSchedule()) return;
        if (instance == null) instance = new ChimeraHandRenderer();
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
        float tick = minecraft.getTimer().getGameTimeDeltaPartialTick(true);
        var access = (ChimeraGameRendererHandAccess) game;
        var bob = new PoseStack();
        access.chimera$bobHurt(bob, tick);
        if (minecraft.options.bobView().get()) access.chimera$bobView(bob, tick);
        Matrix4f projection = projection(game.getProjectionMatrix(access.chimera$getFov(camera, tick, false)));
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        RenderSystem.backupProjectionMatrix();
        modelView.pushMatrix();
        translucentPhase = translucent;
        try {
            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
            modelView.set(bob.last().pose());
            RenderSystem.applyModelViewMatrix();
            game.lightTexture().turnOnLightLayer();
            game.itemInHandRenderer.renderHandsWithItems(tick, new PoseStack(), buffers, player,
                    minecraft.getEntityRenderDispatcher().getPackedLightCoords(player, tick));
            game.lightTexture().turnOffLightLayer();
            // Hand models, items and maps draw in their pack lanes first. Glint then draws in
            // its own lane (Iris ShaderKey.GLINT under the hand projection), over the depth
            // they wrote.
            buffers.endFamilyBatches();
            buffers.endBatch();
        } finally {
            translucentPhase = null;
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    public static void destroy() {
        if (instance != null) { instance.close(); instance = null; }
        diverted = false;
        translucentPhase = null;
    }

    /** Vanilla RenderBuffers' fixed buffers, for any Chimera-owned source a foil item may write to. */
    static SequencedMap<RenderType, ByteBufferBuilder> fixedBuffers() {
        var result = new LinkedHashMap<RenderType, ByteBufferBuilder>();
        for (RenderType type : List.of(Sheets.solidBlockSheet(), Sheets.cutoutBlockSheet(),
                Sheets.bannerSheet(), Sheets.translucentCullBlockSheet(), Sheets.shieldSheet(),
                Sheets.bedSheet(), Sheets.shulkerBoxSheet(), Sheets.signSheet(), Sheets.hangingSignSheet(),
                Sheets.chestSheet(), RenderType.armorEntityGlint(), RenderType.glint(),
                RenderType.glintTranslucent(), RenderType.entityGlint(), RenderType.entityGlintDirect(),
                RenderType.waterMask())) {
            result.put(type, new ByteBufferBuilder(type.bufferSize()));
        }
        return result;
    }

    @Override public void close() {
        buffers.close();
        vertices.close();
        fixed.values().forEach(ByteBufferBuilder::close);
    }
}
