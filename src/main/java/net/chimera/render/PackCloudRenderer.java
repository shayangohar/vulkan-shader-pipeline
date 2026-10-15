package net.chimera.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.ChimeraMod;
import net.chimera.mixin.ChimeraVanillaCloudsAccessor;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.shaderpack.GeometryOutputPlan;
import net.chimera.shaderpack.PackPipelines;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.vulkanmod.render.VBO;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

/**
 * Draws the clouds with the pack's gbuffers_clouds program, as Iris does on 1.21.11: vanilla's
 * cloud faces (CloudFaces) in one draw under vanilla's cloud pipeline state, which is
 * RenderPipelines.CLOUDS for fancy clouds and FLAT_CLOUDS (no culling) for fast ones.
 *
 * <p>VulkanMod replaces vanilla's cloud pass with an older mesh that has no normals and a depth
 * pre-pass. A pack reads the face normal to shade cloud sides, so while a pack cloud program is
 * installed this renderer takes VulkanMod's place. The frame's values follow
 * CloudRenderer.render: the cloud offset rides in ModelOffset, the cloud colour in
 * ColorModulator, and texture unit 0 holds a white texel, as Iris binds one.
 */
public final class PackCloudRenderer {
    private static VBO mesh;
    private static boolean meshEmpty;
    private static CloudRenderer.TextureData builtFrom;
    private static int builtX;
    private static int builtZ;
    private static int builtRadius;
    private static CloudFaces.CameraPos builtCamera;
    private static boolean builtFancy;
    private static boolean announced;

    private PackCloudRenderer() {}

    /** One frame's placement of the cloud layer, as CloudRenderer.render derives it. */
    private record Frame(int cellX, int cellZ, float offsetX, float relativeHeight, float offsetZ,
                         CloudFaces.CameraPos camera, boolean fancy, int radius) {}

    /**
     * Draws this frame's clouds with the pack program. Returns false when no pack cloud program
     * is installed, so VulkanMod draws its own clouds.
     */
    public static boolean render(float cloudHeight, int cloudColor, double cameraX, double cameraY,
                                 double cameraZ, long gameTime, float partialTick) {
        PackPipelines.PackSky clouds = ChimeraSkyBridge.cloudPipeline();
        ChimeraMainPass pass = ChimeraRenderer.getMainPass();
        if (clouds == null || pass == null) return false;
        Minecraft minecraft = Minecraft.getInstance();
        CloudRenderer.TextureData texture =
                ((ChimeraVanillaCloudsAccessor) minecraft.levelRenderer.getCloudRenderer()).chimera$texture();
        // Vanilla draws nothing until its cloud texture has loaded.
        if (texture == null) return true;
        Frame frame = frame(texture, cloudHeight, cameraX, cameraY, cameraZ, gameTime, partialTick,
                minecraft.options.getCloudsType() == CloudStatus.FANCY,
                CloudFaces.radius(minecraft.options.cloudRange().get()));
        if (!prepareMesh(texture, frame)) return true;
        if (!announced) {
            announced = true;
            ChimeraMod.LOGGER.info("[chimera] pack gbuffers_clouds: drawing vanilla cloud faces ({})",
                    frame.fancy() ? "fancy" : "fast");
        }
        draw(pass, clouds, frame, cloudColor);
        return true;
    }

    /** Drops the cloud mesh, as when the pack session ends. */
    public static void reset() {
        if (mesh != null) mesh.close();
        mesh = null;
        meshEmpty = false;
        builtFrom = null;
        announced = false;
    }

    private static Frame frame(CloudRenderer.TextureData texture, float cloudHeight, double cameraX,
                               double cameraY, double cameraZ, long gameTime, float partialTick,
                               boolean fancy, int radius) {
        float relativeHeight = (float) (cloudHeight - cameraY);
        float ticks = (float) (gameTime % (texture.width() * 400L)) + partialTick;
        double x = cameraX + ticks * 0.030000001F;
        double z = cameraZ + 3.9600000381469727;
        double width = texture.width() * 12.0;
        double height = texture.height() * 12.0;
        x -= Mth.floor(x / width) * width;
        z -= Mth.floor(z / height) * height;
        int cellX = Mth.floor(x / 12.0);
        int cellZ = Mth.floor(z / 12.0);
        return new Frame(cellX, cellZ, (float) (x - cellX * CloudFaces.CELL_WIDTH), relativeHeight,
                (float) (z - cellZ * CloudFaces.CELL_WIDTH), CloudFaces.cameraPos(relativeHeight), fancy, radius);
    }

    /** Rebuilds the faces when vanilla would; false when there is nothing to draw. */
    private static boolean prepareMesh(CloudRenderer.TextureData texture, Frame frame) {
        boolean current = builtFrom == texture && builtX == frame.cellX() && builtZ == frame.cellZ()
                && builtCamera == frame.camera() && builtFancy == frame.fancy() && builtRadius == frame.radius();
        if (!current) {
            if (mesh != null) mesh.close();
            mesh = null;
            builtFrom = texture;
            builtX = frame.cellX();
            builtZ = frame.cellZ();
            builtCamera = frame.camera();
            builtFancy = frame.fancy();
            builtRadius = frame.radius();
            MeshData faces = buildFaces(texture, frame);
            meshEmpty = faces == null;
            if (faces != null) {
                mesh = new VBO(true);
                mesh.upload(faces);
            }
        }
        return !meshEmpty && mesh != null;
    }

    private static MeshData buildFaces(CloudRenderer.TextureData texture, Frame frame) {
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_COLOR_NORMAL);
        CloudFaces.VertexSink vertices = (x, y, z, argb, normalX, normalY, normalZ) ->
                builder.addVertex(x, y, z).setColor(argb).setNormal(normalX, normalY, normalZ);
        CloudFaces.build(texture.cells(), texture.width(), texture.height(), frame.cellX(), frame.cellZ(),
                frame.camera(), frame.fancy(), frame.radius(),
                (x, z, direction, inside, topColor) -> CloudFaces.emit(x, z, direction, inside, topColor, vertices));
        return builder.build();
    }

    private static void draw(ChimeraMainPass pass, PackPipelines.PackSky clouds, Frame frame, int cloudColor) {
        Renderer.getInstance().getMainPass().rebindMainTarget();
        applyState(frame, cloudColor);
        VulkanImage previous = VTextureSelector.getBoundTexture(0);
        VulkanImage white = VTextureSelector.getWhiteTexture();
        VTextureSelector.bindTexture(0, white);
        PackUniformProvider.updateDrawAtlasSize(white);
        GeometryOutputPlan outputs = clouds.outputPlan();
        boolean window = false;
        try {
            pass.prepareProgramImages(clouds.pipeline());
            if (outputs != null && outputs.requiresDynamicAttachments()) {
                window = pass.beginPackFamilyWindow(outputs);
                if (!window) return;
                pass.rebindMainTarget();
            }
            // Not VBO.bind: its VTextureSelector.bindShaderTextures refills unit 0 from
            // RenderSystem's shader texture, which is still the block atlas here, and the
            // pack would shade the clouds with an atlas texel instead of Iris's white one.
            Renderer renderer = Renderer.getInstance();
            renderer.bindGraphicsPipeline(clouds.pipeline());
            renderer.uploadAndBindUBOs(clouds.pipeline());
            mesh.draw();
        } finally {
            if (window) pass.endPackFamilyWindow(outputs);
            if (previous != null) VTextureSelector.bindTexture(0, previous);
            PackUniformProvider.restoreFrameAtlasSize();
            VRenderSystem.enableCull();
            VRenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            VRenderSystem.setModelOffset(0.0F, 0.0F, 0.0F);
        }
    }

    /**
     * RenderPipelines.CLOUDS and FLAT_CLOUDS: translucent blending, depth tested and written,
     * culled only for fancy clouds; the model view carries no cloud translation.
     */
    private static void applyState(Frame frame, int cloudColor) {
        VRenderSystem.applyModelViewMatrix(RenderSystem.getModelViewStack());
        VRenderSystem.calculateMVP();
        VRenderSystem.setModelOffset(-frame.offsetX(), frame.relativeHeight(), -frame.offsetZ());
        Vector4f color = ARGB.vector4fFromARGB32(cloudColor);
        VRenderSystem.setShaderColor(color.x(), color.y(), color.z(), color.w());
        VRenderSystem.enableBlend();
        VRenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        VRenderSystem.enableDepthTest();
        VRenderSystem.depthFunc(GL11.GL_LEQUAL);
        GlStateManager._enableDepthTest();
        GlStateManager._depthMask(true);
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._disablePolygonOffset();
        VRenderSystem.setPolygonModeGL(GL11.GL_FILL);
        VRenderSystem.setPrimitiveTopologyGL(GL11.GL_TRIANGLES);
        if (frame.fancy()) {
            VRenderSystem.enableCull();
        } else {
            VRenderSystem.disableCull();
        }
    }
}
