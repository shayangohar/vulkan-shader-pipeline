package net.chimera.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.vulkan.Renderer;

/**
 * What Iris draws just before the sky (MixinLevelRenderer.iris$renderTerrainShadows,
 * IrisRenderingPipeline.renderShadows): the shadow pass, then the prepare passes. A prepare pass
 * so reads this frame's shadow map, and Escheridia's prepare.csh the voxels its shadow pass wrote
 * into a map cleared at frame start.
 *
 * <p>VulkanMod's per-frame section setup (WorldRenderer.setupRenderer) resets the frame's indirect
 * draw buffers, and the shadow pass records indirect draws. LevelRenderer.setupRender calls it
 * after the sky, so a frame that draws shadows here runs the setup first and skips that call
 * (WorldRendererMixin).</p>
 */
public final class PreSkyPasses {
    // Render thread only.
    private static boolean setupRanEarly;

    private PreSkyPasses() {}

    /** Clears an early setup a frame left unconsumed. */
    public static void beginFrame() {
        setupRanEarly = false;
    }

    /**
     * The shadow pass when the frame armed it, then the prepare window. renderSky draws its disc
     * with the shader current as it starts, which vanilla sets just before this; the shadow
     * casters leave theirs current, so it is put back, as Iris sets the position shader again
     * after its shadow pass (MixinLevelRenderer.iris$beginSky). Left over, a particle shader drew
     * the disc of a pack without gbuffers_skybasic from attributes the disc does not have.
     */
    public static void render(ChimeraMainPass pass, Camera camera, Frustum frustum, boolean capturedFrustum,
                              boolean spectator) {
        ShaderInstance hostShader = RenderSystem.getShader();
        try {
            if (pass.consumeShadowPending()) {
                WorldRenderer.getInstance().setupRenderer(camera, frustum, capturedFrustum, spectator);
                setupRanEarly = true;
                Vec3 position = camera.getPosition();
                pass.renderShadowSegment(position.x, position.y, position.z);
            }
            if (pass.packPostChainActive && pass.postChain.hasPrepareWork()) {
                pass.postChain.runPrepareWindow(Renderer.getCommandBuffer());
            }
        } finally {
            RenderSystem.setShader(() -> hostShader);
        }
    }

    /** Whether this frame's section setup already ran; true once, for the call it replaces. */
    public static boolean consumeSetupRanEarly() {
        boolean ran = setupRanEarly;
        setupRanEarly = false;
        return ran;
    }
}
