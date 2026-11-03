package net.chimera.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.shader.PackUniformProvider;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * Draws a pack's clouds over Sodium 0.8.13's cloud mesh (CloudMesh), the clouds Iris 1.8.14 on
 * 1.21.1 runs its cloud program on, where VulkanMod leaves vanilla's 96-block tile sheets. As
 * Sodium does, it drifts the layer 0.03 blocks a tick toward -X, rebuilds the mesh only when the
 * camera's cell, the reach, the side of the layer or the cloud setting changes, sets the terrain fog
 * at 8 blocks per cell of reach, and gives the cloud colour at 0.8 opacity as the shader colour.
 *
 * <p>It draws through vanilla's cloud render types, so the pack's cloud program and the cloud
 * target serve it as they served vanilla's sheets. FANCY lays depth, then colour, each culling back
 * faces as Sodium's draw does: with culling off, a cell's inner walls would blend over its outer
 * faces. FAST draws colour alone with culling off, as Sodium does.</p>
 */
public final class ChimeraCloudRenderer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChimeraCloudRenderer.class);
    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace(
            "textures/environment/clouds.png");
    private static final ChimeraCloudRenderer SHARED = new ChimeraCloudRenderer();

    private CloudMesh.Cells cells;
    private boolean cellsRead;
    private Mesh built;
    private VertexBuffer buffer;

    /** What the mesh was built for; any change rebuilds it. */
    private record Mesh(int originX, int originZ, int radius, CloudMesh.Orientation orientation,
                        CloudStatus status) {}

    private ChimeraCloudRenderer() {}

    public static ChimeraCloudRenderer shared() {
        return SHARED;
    }

    /** Reads the cloud texture again at its next draw, after a resource reload. */
    public void reload() {
        cells = null;
        cellsRead = false;
        built = null;
    }

    /**
     * Draws the clouds under renderClouds' pose, view rotation and projection; nothing where the
     * level has no cloud height or the texture has no cloud.
     */
    public void draw(ClientLevel level, int ticks, PoseStack pose, Matrix4f frustum, Matrix4f projection,
                     float partialTick, double cameraX, double cameraY, double cameraZ) {
        float height = level.effects().getCloudHeight();
        CloudMesh.Cells texture = cells();
        if (Float.isNaN(height) || texture == null) return;
        float base = height + 0.33F;
        double x = cameraX + ((float) ticks + partialTick) * 0.03F;
        double z = cameraZ + 0.33F;
        Minecraft minecraft = Minecraft.getInstance();
        CloudStatus status = minecraft.options.getCloudsType();
        boolean fancy = status == CloudStatus.FANCY;
        Mesh mesh = new Mesh(Mth.floor(x / CloudMesh.CELL), Mth.floor(z / CloudMesh.CELL),
                CloudMesh.radius(minecraft.options.getEffectiveRenderDistance()),
                fancy ? CloudMesh.Orientation.of(cameraY, base) : null, status);
        if (!mesh.equals(built)) build(texture, mesh);
        if (buffer == null) return;
        pose.pushPose();
        pose.mulPose(frustum);
        pose.translate(-(float) (x - CloudMesh.CELL * mesh.originX()), -(float) (cameraY - base),
                -(float) (z - CloudMesh.CELL * mesh.originZ()));
        try {
            drawInCloudFog(level, minecraft, pose.last().pose(), projection, partialTick, cameraX, cameraZ,
                    mesh.radius(), fancy);
        } finally {
            pose.popPose();
        }
    }

    /** Sodium's cloud fog and colour around the draw; the frame's fog comes back after it. */
    private void drawInCloudFog(ClientLevel level, Minecraft minecraft, Matrix4f modelView, Matrix4f projection,
                                float partialTick, double cameraX, double cameraZ, int radius, boolean fancy) {
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        FogShape fogShape = RenderSystem.getShaderFogShape();
        boolean thick = level.effects().isFoggyAt(Mth.floor(cameraX), Mth.floor(cameraZ))
                || minecraft.gui.getBossOverlay().shouldCreateWorldFog();
        FogRenderer.setupFog(minecraft.gameRenderer.getMainCamera(), FogRenderer.FogMode.FOG_TERRAIN,
                8.0F * radius, thick, partialTick);
        Vec3 color = level.getCloudColor(partialTick);
        RenderSystem.setShaderColor((float) color.x, (float) color.y, (float) color.z, 0.8F);
        buffer.bind();
        try {
            if (fancy) drawPass(RenderType.cloudsDepthOnly(), true, modelView, projection);
            drawPass(RenderType.clouds(), fancy, modelView, projection);
        } finally {
            VertexBuffer.unbind();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.setShaderFogShape(fogShape);
            PackUniformProvider.captureFogShape();
        }
    }

    private void drawPass(RenderType type, boolean cull, Matrix4f modelView, Matrix4f projection) {
        type.setupRenderState();
        // The cloud types turn culling off for vanilla's sheets, which Sodium's FANCY boxes need on.
        if (cull) RenderSystem.enableCull();
        try {
            buffer.drawWithShader(modelView, projection, RenderSystem.getShader());
        } finally {
            type.clearRenderState();
        }
    }

    private void build(CloudMesh.Cells texture, Mesh mesh) {
        built = mesh;
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
        CloudMesh.build(texture, mesh.originX(), mesh.originZ(), mesh.radius(), mesh.orientation(),
                (x, y, z, u, v, argb, normalX, normalY, normalZ) -> builder.addVertex(x, y, z).setUv(u, v)
                        .setColor(argb).setNormal(normalX, normalY, normalZ));
        MeshData data = builder.build();
        if (data == null) {
            closeBuffer();
            return;
        }
        if (buffer == null) buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        buffer.bind();
        buffer.upload(data);
        VertexBuffer.unbind();
    }

    private CloudMesh.Cells cells() {
        if (!cellsRead) {
            cellsRead = true;
            cells = readCells();
        }
        return cells;
    }

    private static CloudMesh.Cells readCells() {
        try (InputStream stream = Minecraft.getInstance().getResourceManager().open(TEXTURE);
             NativeImage image = NativeImage.read(stream)) {
            CloudMesh.Cells read = CloudMesh.Cells.of(image.getWidth(), image.getHeight(), image::getPixelRGBA);
            if (read == null) LOGGER.warn("[chimera] clouds: {} has no cloud texel", TEXTURE);
            return read;
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("[chimera] clouds: cannot read {}", TEXTURE, failure);
            return null;
        }
    }

    private void closeBuffer() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
    }

    @Override public void close() {
        closeBuffer();
        built = null;
    }
}
