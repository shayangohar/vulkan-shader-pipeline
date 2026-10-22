package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a pass compute's samplers read: pack textures and images, depth and shadow maps, in Iris's order. */
final class PassComputeSamplerTest {
    /**
     * A sampler only a local of its name is read through is inactive in GL and binds nothing: Rethinking
     * Voxels' prepare4 compute declares the specular sampler and computes a local float specular.
     */
    @Test
    void passComputesDropSamplersOnlyLocalsShadow(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nuniform sampler2D colortex0;\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(colortex0, vec2(0.5));\n}\n");
        Files.writeString(shaders.resolve("composite_a.csh"), """
                #version 430
                layout(local_size_x = 1) in;
                layout(rgba16f) uniform image2D colorimg2;
                uniform sampler2D specular;
                float shade(float light) {
                    float specular = light * 0.5;
                    return specular / (0.125 * specular + 1.0);
                }
                void main() { imageStore(colorimg2, ivec2(0), vec4(shade(1.0))); }
                """);
        PassComputePlan.PassCompute compute = PassComputePlan.build(PackProbe.analyze(root).plan(), shaders)
                .compute("composite_a");
        assertTrue(compute.supported(), compute.deviations().toString());
        assertEquals(List.of("colorimg2"), compute.bindings().stream().map(PassComputePlan.Binding::symbol).toList());
        String error = PackCompileTest.compileComputeValues(compute.source(), PassComputePlan.VALUES_BLOCK, Map.of());
        assertTrue(error == null, "the translated pass compute compiles: " + error + "\n" + compute.source());
    }

    /**
     * Iris binds a pass's computes as its program (CompositeRenderer.createComputes): customTexture
     * entries, the stage's textures behind the pass's flippedAtLeastOnce snapshot, and the noise.
     * Alpha Piscium's computes read customTexture.usam_stbnVec1. composite1 writes colortex5, so
     * composite2's compute samples the target, not texture.composite.colortex5.
     */
    @Test
    void passComputesBindPackTextures(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                customTexture.lut = lut.dat TEXTURE_3D RGBA8 2 2 2 RGBA UNSIGNED_BYTE
                texture.composite.colortex5 = blue.png
                """);
        Files.write(shaders.resolve("lut.dat"), new byte[32]);
        Files.write(shaders.resolve("blue.png"), new byte[] {1});
        for (String pass : List.of("composite", "composite1")) {
            String target = pass.equals("composite") ? "0" : "5";
            Files.writeString(shaders.resolve(pass + ".fsh"), "#version 120\nuniform sampler2D colortex0;\n"
                    + "void main() {\n/* RENDERTARGETS: " + target + " */\n"
                    + " gl_FragData[0] = texture2D(colortex0, vec2(0.5));\n}\n");
        }
        String compute = """
                #version 430
                layout(local_size_x = 8, local_size_y = 8) in;
                layout(rgba16f) uniform image2D colorimg4;
                uniform sampler3D lut;
                uniform sampler2D colortex5;
                uniform sampler2D noisetex;
                // Vulkan GLSL reserves 'sampler'; Reverie's prepare computes name a parameter so.
                vec4 centre(sampler2D sampler) { return texture(sampler, vec2(0.5)); }
                void main() {
                    vec4 value = texture(lut, vec3(0.5)) + centre(colortex5) + texture(noisetex, vec2(0.5));
                    imageStore(colorimg4, ivec2(gl_GlobalInvocationID.xy), value);
                }
                """;
        Files.writeString(shaders.resolve("composite_a.csh"), compute);
        Files.writeString(shaders.resolve("composite2_a.csh"), compute);
        PassComputePlan computes = PassComputePlan.build(PackProbe.analyze(root).plan(), shaders);
        PassComputePlan.PassCompute early = computes.compute("composite_a");
        assertEquals(List.of(
                new PassComputePlan.Binding("colorimg4", 0, PassComputePlan.Kind.TARGET_IMAGE, 4, "rgba16f", null),
                new PassComputePlan.Binding("lut", 1, PassComputePlan.Kind.TEXTURE, -1, null, "customTexture.lut"),
                new PassComputePlan.Binding("colortex5", 2, PassComputePlan.Kind.TEXTURE, -1, null,
                        "texture.composite.colortex5"),
                new PassComputePlan.Binding("noisetex", 3, PassComputePlan.Kind.TEXTURE, -1, null, "noisetex")),
                early.bindings(), early.deviations().toString());
        assertEquals(List.of(PackResourceKind.RAW_TEXTURE, PackResourceKind.PACK_TEXTURE, PackResourceKind.NOISE),
                early.textures().stream().map(PackResourceBinding::kind).toList());
        assertEquals(new PassComputePlan.Binding("colortex5", 2, PassComputePlan.Kind.TARGET_SAMPLER, 5, null, null),
                computes.compute("composite2_a").bindings().get(2), "composite1 wrote colortex5 before composite2");
        String error = PackCompileTest.compileComputeValues(early.source(), PassComputePlan.VALUES_BLOCK, Map.of());
        assertTrue(error == null, "the translated pass compute compiles: " + error + "\n" + early.source());
    }

    /**
     * Iris gives a compute that reads shadow samplers the shadow maps (IrisSamplers
     * addShadowSamplers): shadowtexN as depth, a sampler2DShadow such as shadowtex1HW through a
     * comparison, shadowcolorN, and shadow as shadowtex1 where watershadow is active. A legacy
     * shadow2D reads the bound sampler as programs read it (Rethinking Voxels' deferred1).
     */
    @Test
    void passComputesReadShadowMaps(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nuniform sampler2D colortex0;\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(colortex0, vec2(0.5));\n}\n");
        Files.writeString(shaders.resolve("composite_a.csh"), """
                #version 430
                layout(local_size_x = 1) in;
                layout(rgba16f) uniform image2D colorimg2;
                uniform sampler2D shadowtex0;
                uniform sampler2DShadow shadowtex1HW;
                uniform sampler2D shadowcolor0;
                uniform sampler2D shadow;
                uniform sampler2D watershadow;
                void main() {
                    vec4 value = texture(shadowtex0, vec2(0.5)) + texture(shadowcolor0, vec2(0.5))
                            + vec4(texture(shadowtex1HW, vec3(0.5))) + texture(shadow, vec2(0.5))
                            + texture(watershadow, vec2(0.5)) + shadow2D(shadowtex1HW, vec3(0.5)).x;
                    imageStore(colorimg2, ivec2(0), value);
                }
                """);
        PassComputePlan.PassCompute compute = PassComputePlan.build(PackProbe.analyze(root).plan(), shaders)
                .compute("composite_a");
        assertEquals(List.of(
                new PassComputePlan.Binding("colorimg2", 0, PassComputePlan.Kind.TARGET_IMAGE, 2, "rgba16f", null),
                new PassComputePlan.Binding("shadowtex0", 1, PassComputePlan.Kind.SHADOW_DEPTH, -1, null,
                        "shadowtex0"),
                new PassComputePlan.Binding("shadowtex1HW", 2, PassComputePlan.Kind.SHADOW_COMPARE, -1, null,
                        "shadowtex1"),
                new PassComputePlan.Binding("shadowcolor0", 3, PassComputePlan.Kind.SHADOW_COLOR, -1, null,
                        "shadowcolor0"),
                new PassComputePlan.Binding("shadow", 4, PassComputePlan.Kind.SHADOW_DEPTH, -1, null, "shadowtex1"),
                new PassComputePlan.Binding("watershadow", 5, PassComputePlan.Kind.SHADOW_DEPTH, -1, null,
                        "shadowtex0")),
                compute.bindings(), compute.deviations().toString());
        String error = PackCompileTest.compileComputeValues(compute.source(), PassComputePlan.VALUES_BLOCK, Map.of());
        assertTrue(error == null, "the translated pass compute compiles: " + error + "\n" + compute.source());
    }

    /**
     * Iris registers custom images after customTexture entries, so an image's sampler keeps its name
     * against a customTexture of it; a texture.stage entry overrides both through the sampler
     * interceptor (CompositeRenderer.createComputes and createProgram alike).
     */
    @Test
    void imageSamplerPrecedence(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                image.imgA = samA RGBA RGBA16F HALF_FLOAT false true 1.0 1.0
                image.imgB = samB RGBA RGBA16F HALF_FLOAT false true 1.0 1.0
                customTexture.samA = blue.png
                texture.composite.samB = blue.png
                """);
        Files.write(shaders.resolve("blue.png"), new byte[] {1});
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nuniform sampler2D samA;\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(samA, vec2(0.5));\n}\n");
        Files.writeString(shaders.resolve("composite_a.csh"), """
                #version 430
                layout(local_size_x = 1) in;
                layout(rgba16f) uniform image2D colorimg2;
                uniform sampler2D samA;
                uniform sampler2D samB;
                void main() { imageStore(colorimg2, ivec2(0), texture(samA, vec2(0.5)) + texture(samB, vec2(0.5))); }
                """);
        PackPlan plan = PackProbe.analyze(root).plan();
        PassComputePlan.PassCompute compute = PassComputePlan.build(plan, shaders).compute("composite_a");
        assertEquals(List.of(
                new PassComputePlan.Binding("colorimg2", 0, PassComputePlan.Kind.TARGET_IMAGE, 2, "rgba16f", null),
                new PassComputePlan.Binding("samA", 1, PassComputePlan.Kind.PACK_IMAGE_SAMPLER, -1, null, "imgA"),
                new PassComputePlan.Binding("samB", 2, PassComputePlan.Kind.TEXTURE, -1, null,
                        "texture.composite.samB")),
                compute.bindings(), compute.deviations().toString());
        assertEquals(PackResourceKind.ADVANCED_IMAGE, plan.resources().binding("composite", "samA").kind(),
                "the program's samA reads the image too");
    }

    /**
     * The last registration of a name is what it reads, in an order each renderer sets
     * (IrisSamplerOrder): a post program or any compute registers noise after images, the final
     * program its customTexture entries after images, and a world program images after noise.
     */
    @Test
    void samplerRegistrationOrder(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                image.imgA = samA RGBA RGBA16F HALF_FLOAT false true 1.0 1.0
                image.imgN = noisetex RGBA RGBA16F HALF_FLOAT false true 1.0 1.0
                customTexture.samA = blue.png
                """);
        Files.write(shaders.resolve("blue.png"), new byte[] {1});
        String post = "#version 120\nuniform sampler2D samA;\nuniform sampler2D noisetex;\nvoid main() {\n"
                + "/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(samA, vec2(0.5))"
                + " + texture2D(noisetex, vec2(0.5));\n}\n";
        Files.writeString(shaders.resolve("composite.fsh"), post);
        Files.writeString(shaders.resolve("final.fsh"), post);
        Files.writeString(shaders.resolve("composite_a.csh"), """
                #version 430
                layout(local_size_x = 1) in;
                layout(rgba16f) uniform image2D colorimg2;
                uniform sampler2D samA;
                uniform sampler2D noisetex;
                void main() {
                    imageStore(colorimg2, ivec2(0), texture(samA, vec2(0.5)) + texture(noisetex, vec2(0.5)));
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_terrain.vsh"), "#version 120\nvarying vec2 uv;\n"
                + "void main() {\n gl_Position = ftransform();\n uv = gl_MultiTexCoord0.xy;\n}\n");
        Files.writeString(shaders.resolve("gbuffers_terrain.fsh"), "#version 120\nuniform sampler2D noisetex;\n"
                + "varying vec2 uv;\nvoid main() {\n/* DRAWBUFFERS:0 */\n"
                + " gl_FragData[0] = texture2D(noisetex, uv);\n}\n");
        PackPlan plan = PackProbe.analyze(root).plan();
        assertEquals(PackResourceKind.ADVANCED_IMAGE, plan.resources().binding("composite", "samA").kind());
        assertEquals(PackResourceKind.NOISE, plan.resources().binding("composite", "noisetex").kind());
        assertEquals(PackResourceKind.PACK_TEXTURE, plan.resources().binding("final", "samA").kind());
        assertEquals(PackResourceKind.ADVANCED_IMAGE, plan.resources().binding("gbuffers_terrain", "noisetex").kind());
        PassComputePlan.PassCompute compute = PassComputePlan.build(plan, shaders).compute("composite_a");
        Map<String, PassComputePlan.Kind> kinds = new TreeMap<>();
        compute.bindings().forEach(binding -> kinds.put(binding.symbol(), binding.kind()));
        assertEquals(PassComputePlan.Kind.PACK_IMAGE_SAMPLER, kinds.get("samA"), compute.deviations().toString());
        assertEquals(PassComputePlan.Kind.TEXTURE, kinds.get("noisetex"), compute.deviations().toString());
    }

    /**
     * A stage texture replaces only what a registration through the sampler interceptor binds. A
     * composite registers its customTexture entries on its own builder, so samC reads the entry; a
     * world program registers them through the interceptor, so samC reads texture.gbuffers.samC; and
     * a world program's normals, registered on the builder, never reads texture.gbuffers.normals.
     */
    @Test
    void stageTexturesReplaceOnlyInterceptedRegistrations(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                customTexture.samC = blue.png
                texture.composite.samC = red.png
                texture.gbuffers.samC = red.png
                texture.gbuffers.normals = red.png
                """);
        Files.write(shaders.resolve("blue.png"), new byte[] {1});
        Files.write(shaders.resolve("red.png"), new byte[] {1});
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nuniform sampler2D samC;\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(samC, vec2(0.5));\n}\n");
        Files.writeString(shaders.resolve("gbuffers_terrain.vsh"), "#version 120\nvarying vec2 uv;\n"
                + "void main() {\n gl_Position = ftransform();\n uv = gl_MultiTexCoord0.xy;\n}\n");
        Files.writeString(shaders.resolve("gbuffers_terrain.fsh"), "#version 120\nuniform sampler2D samC;\n"
                + "uniform sampler2D normals;\nvarying vec2 uv;\nvoid main() {\n/* DRAWBUFFERS:0 */\n"
                + " gl_FragData[0] = texture2D(samC, uv) + texture2D(normals, uv);\n}\n");
        PackResourcePlan resources = PackProbe.analyze(root).plan().resources();
        assertEquals("blue.png", resources.binding("composite", "samC").source());
        assertEquals("red.png", resources.binding("gbuffers_terrain", "samC").source());
        assertTrue(!"red.png".equals(resources.binding("gbuffers_terrain", "normals").source()),
                resources.binding("gbuffers_terrain", "normals").toString());
    }

    /**
     * Iris gives a pass's computes the depth samplers its program gets (IrisSamplers
     * addCompositeSamplers). Shrimple's deferred4 compute reads depthtex0 where no program does,
     * so the target graph keeps that snapshot, and the colour target it writes, for it.
     */
    @Test
    void passComputesReadDepth(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nuniform sampler2D colortex0;\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(colortex0, vec2(0.5));\n}\n");
        Files.writeString(shaders.resolve("deferred4_a.csh"), """
                #version 430
                layout(local_size_x = 8, local_size_y = 8) in;
                layout(rgba16f) uniform image2D colorimg3;
                uniform sampler2D depthtex0;
                uniform sampler2D depthtex1;
                void main() {
                    ivec2 texel = ivec2(gl_GlobalInvocationID.xy);
                    float depth = texelFetch(depthtex0, texel, 0).r - texelFetch(depthtex1, texel, 0).r;
                    imageStore(colorimg3, texel, vec4(depth));
                }
                """);
        PackPlan plan = PackProbe.analyze(root).plan();
        PassComputePlan.PassCompute compute = PassComputePlan.build(plan, shaders).compute("deferred4_a");
        assertEquals(List.of(
                new PassComputePlan.Binding("colorimg3", 0, PassComputePlan.Kind.TARGET_IMAGE, 3, "rgba16f", null),
                new PassComputePlan.Binding("depthtex0", 1, PassComputePlan.Kind.DEPTH, -1, null, "depthtex0"),
                new PassComputePlan.Binding("depthtex1", 2, PassComputePlan.Kind.DEPTH, -1, null, "depthtex1")),
                compute.bindings(), compute.deviations().toString());
        PackTargetGraphPlan.Limits limits = new PackTargetGraphPlan.Limits(64, 64, 8, 4096);
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(plan.activePrograms(), plan.config(), plan.resources(),
                limits, PackTargetGraphPlan.ComputeUse.NONE);
        assertEquals(Set.of(), graph.depth().names(), "no program reads depth");
        assertTrue(graph.targets().stream().noneMatch(target -> target.index() == 3), "no program uses colortex3");
        // Iris creates a target a compute names (IrisImages.addRenderTargetImages: createIfUnsure).
        PackTargetGraphPlan used = PackTargetGraphPlan.build(plan.activePrograms(), plan.config(), plan.resources(),
                limits, new PackTargetGraphPlan.ComputeUse(Set.of(3), Set.of("depthtex0", "depthtex1")));
        assertEquals(Set.of("depthtex0", "depthtex1"), used.depth().names());
        assertTrue(used.targets().stream().anyMatch(target -> target.index() == 3), used.targets().toString());
        assertTrue(used.deviations().contains("DEPTH_COPY_GRAPH_APPLIED"), used.deviations().toString());
        String error = PackCompileTest.compileComputeValues(compute.source(), PassComputePlan.VALUES_BLOCK, Map.of());
        assertTrue(error == null, "the translated pass compute compiles: " + error + "\n" + compute.source());
    }
}
