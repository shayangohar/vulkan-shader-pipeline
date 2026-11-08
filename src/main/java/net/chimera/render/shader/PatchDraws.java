package net.chimera.render.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.ShaderInstance;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;

/**
 * An immediate mesh drawn under a tessellated pack pipeline. Chimera builds those for patches of 3
 * (BetweenStages.PATCH_VERTICES), as Iris draws GL_PATCHES of 3, so the mesh draws its own
 * triangle indices, a quad as two triangles, each three vertices one patch. VulkanMod's immediate
 * draw of a tessellated pipeline builds four-vertex patches from QUADS for a GL patch size of 4 and
 * throws for any other draw (PatchGeometry.requireQuads, "Four-vertex patches require complete
 * original QUADS and matching triangle counts"): Shrimple's held item stopped the game as the world
 * loaded. The steps are VulkanMod's own immediate draw (BufferUploaderM.drawWithShader and
 * drawPrepared), with its triangle draw in place of its patch draw.
 */
public final class PatchDraws {
    private PatchDraws() {}

    /** Draws {@code mesh} as patches of 3 under an open tessellated pack pipeline, else through {@code host}. */
    public static void draw(MeshData mesh, Runnable host) {
        GraphicsPipeline pipeline = ChimeraEntityBridge.pipeline();
        if (pipeline == null || !pipeline.hasTessellation()) {
            host.run();
            return;
        }
        try {
            MeshData.DrawState state = mesh.drawState();
            ShaderInstance shader = RenderSystem.getShader();
            if (state.vertexCount() <= 0 || shader == null) return;
            // The host shader binds the draw's textures, which the pack pipeline's samplers read.
            shader.apply();
            Renderer renderer = Renderer.getInstance();
            VRenderSystem.setPrimitiveTopology(state.mode());
            renderer.bindGraphicsPipeline(pipeline);
            VTextureSelector.bindShaderTextures(pipeline);
            renderer.uploadAndBindUBOs(pipeline);
            Renderer.getDrawer().draw(mesh.vertexBuffer(), state, mesh.indexBuffer());
        } finally {
            mesh.close();
        }
    }
}
