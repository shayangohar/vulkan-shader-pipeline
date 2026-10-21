package net.chimera.shaderpack;

import net.chimera.render.PackShadowCompute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pack compute passes: declaration lists, frame slots, uniform offsets and the advanced-resource inventory. */
final class ComputePassTest {
    /**
     * Solas declares its two floodfill images, and their samplers, each in one statement. The
     * plan and the runtime translator must see both names: Solas fell to vanilla in game with
     * "unsupported compute image: floodfill_img_copy".
     */
    @Test
    void computeDeclarationLists(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                iris.features.optional=CUSTOM_IMAGES
                image.floodfill_img = floodfillSampler rgba rgba16f half_float false false 8 4 8
                image.floodfill_img_copy = floodfillSamplerCopy rgba rgba16f half_float false false 8 4 8
                """);
        Files.writeString(shaders.resolve("shadowcomp.csh"), """
                #version 430 compatibility
                layout (local_size_x = 2, local_size_y = 2, local_size_z = 2) in;
                uniform int frameCounter;
                writeonly uniform image3D floodfill_img, floodfill_img_copy;
                uniform sampler3D floodfillSampler, floodfillSamplerCopy;
                vec3 gather(sampler3D image, ivec3 pos) {
                    return texelFetch(image, pos + ivec3(1, 0, 0), 0).rgb;
                }
                void main() {
                    ivec3 pos = ivec3(gl_GlobalInvocationID);
                    if ((frameCounter & 1) == 0) {
                        imageStore(floodfill_img_copy, pos, vec4(gather(floodfillSampler, pos), 1.0));
                    } else {
                        imageStore(floodfill_img, pos, vec4(gather(floodfillSamplerCopy, pos), 1.0));
                    }
                }
                """);
        Files.writeString(shaders.resolve("final.fsh"), """
                #version 120
                uniform sampler2D colortex0;
                void main() {
                    gl_FragColor = texture2D(colortex0, vec2(0.5));
                }
                """);
        PackPlan plan = PackProbe.analyze(root).plan();
        PackAdvancedResourcePlan.ComputeSpec compute = plan.advancedResources().computeStages().get("shadowcomp");
        assertTrue(compute != null && compute.supported(), "the list-declared shadowcomp is supported: "
                + (compute == null ? "missing" : compute.deviations()));
        assertEquals(List.of("floodfill_img", "floodfill_img_copy"), compute.imageNames(),
                "the plan records every image of a declaration list");
        assertEquals(List.of("floodfillSampler", "floodfillSamplerCopy"), compute.samplerNames(),
                "the plan records every sampler of a declaration list");
        String translated = PackShadowCompute.translatedModule(plan.advancedResources(),
                shaders, plan.settingsPreprocessorDefines(), plan.settingsOverriddenNames()).source();
        assertTrue(translated.contains("layout(rgba16f, binding = 1) uniform image3D floodfill_img_copy;")
                        && translated.contains("layout(binding = 3) uniform sampler3D floodfillSamplerCopy;"),
                "each listed resource takes its own binding: " + translated);
        String error = PackCompileTest.compileShadowCompute(root, plan);
        assertTrue(error == null, "the translated shadowcomp compiles: " + error);
    }

    /**
     * A compute's uniform block is written while a frame is recorded, so each frame in flight
     * owns one: a single block could be rewritten while the GPU still ran the previous frame's
     * dispatch. Shadowcomp and the pass computes both bind ComputeKernel's current frame slot.
     */
    @Test
    void computeFrameSlots() throws IOException {
        var kernel = MixinBytecode.nativeClass("net/chimera/render/ComputeKernel");
        assertTrue(calls(kernel, "currentFrameIndex", "net/vulkanmod/vulkan/Renderer", "getCurrentFrame")
                        && calls(kernel, "currentFrame", "net/chimera/render/ComputeKernel", "currentFrameIndex"),
                "the kernel's current slot follows the frame in flight");
        assertTrue(kernel.fields.stream().noneMatch(field -> field.name.equals("uniformData")
                        || field.name.equals("descriptorSet")),
                "the kernel keeps no uniform block or descriptor set shared across frames in flight");
        for (String owner : List.of("PackShadowCompute", "PackPassComputes")) {
            var compute = MixinBytecode.nativeClass("net/chimera/render/" + owner);
            assertTrue(calls(compute, "dispatch", "net/chimera/render/ComputeKernel", "currentFrame"),
                    owner + " must bind the current frame slot's uniform block");
        }
    }

    private static boolean calls(ClassNode type, String method, String owner, String name) {
        var body = type.methods.stream().filter(value -> value.name.equals(method))
                .findFirst().orElseThrow(() -> new AssertionError(type.name + "." + method + " is missing"));
        for (var instruction : body.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Solas declares cameraPosition (vec3) before frameCounter. Dispatch wrote frameCounter after
     * the vec3's padding word, implicit std140 packed it into that word, so the compute read 0,
     * wrote only floodfill_img_copy, and every light flickered as the gbuffers alternated images.
     */
    @Test
    void computeUniformOffsets(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                iris.features.optional=CUSTOM_IMAGES
                image.floodfill_img = floodfillSampler rgba rgba16f half_float false false 8 4 8
                image.floodfill_img_copy = floodfillSamplerCopy rgba rgba16f half_float false false 8 4 8
                """);
        Files.writeString(shaders.resolve("shadowcomp.csh"), """
                #version 430 compatibility
                layout (local_size_x = 2, local_size_y = 2, local_size_z = 2) in;
                uniform vec3 cameraPosition;
                uniform int frameCounter;
                uniform float frameTimeCounter;
                uniform vec3 previousCameraPosition;
                uniform float wetness;
                writeonly uniform image3D floodfill_img, floodfill_img_copy;
                uniform sampler3D floodfillSampler, floodfillSamplerCopy;
                void main() {
                    ivec3 pos = ivec3(gl_GlobalInvocationID);
                    vec3 shift = cameraPosition - previousCameraPosition;
                    vec4 value = vec4(shift, frameTimeCounter + wetness);
                    if ((frameCounter & 1) == 0) {
                        imageStore(floodfill_img_copy, pos, value + texelFetch(floodfillSampler, pos, 0));
                    } else {
                        imageStore(floodfill_img, pos, value + texelFetch(floodfillSamplerCopy, pos, 0));
                    }
                }
                """);
        Files.writeString(shaders.resolve("final.fsh"), """
                #version 120
                uniform sampler2D colortex0;
                void main() {
                    gl_FragColor = texture2D(colortex0, vec2(0.5));
                }
                """);
        PackPlan plan = PackProbe.analyze(root).plan();
        var module = PackShadowCompute.translatedModule(plan.advancedResources(),
                shaders, plan.settingsPreprocessorDefines(), plan.settingsOverriddenNames());
        assertEquals(List.of("cameraPosition", "frameCounter", "frameTimeCounter", "previousCameraPosition",
                        "wetness"), module.uniforms().stream().map(uniform -> uniform.name()).toList(),
                "every live value joins the uniform block");
        String error = PackCompileTest.compileShadowCompute(root, plan);
        assertTrue(error == null, "the compiled block reads each value where dispatch writes it: " + error);
        // The block as Chimera declared it before, with implicit offsets, against the offsets
        // dispatch wrote then: frameCounter after the vec3's padding word, at 16.
        String implicit = """
                #version 450
                layout(local_size_x = 1) in;
                layout(std140, binding = 0) uniform ChimeraShadowComputeValues {
                    vec3 cameraPosition;
                    int frameCounter;
                };
                layout(rgba16f, binding = 1) uniform image3D target;
                void main() {
                    imageStore(target, ivec3(0), vec4(cameraPosition, float(frameCounter)));
                }
                """;
        String mismatch = PackCompileTest.compileComputeValues(implicit, "ChimeraShadowComputeValues",
                Map.of("cameraPosition", 0, "frameCounter", 16));
        assertTrue(mismatch != null && mismatch.contains("frameCounter"),
                "the offset check reports the implicit layout's frameCounter: " + mismatch);
    }

    /**
     * Iris reads {@code <pass>.csh}, then {@code _a} on until a suffix is missing or disabled, and
     * binds colorimgN and colortexN to the image the pass reads. Photon's deferred4_a writes the
     * sky light deferred4 reads.
     */
    @Test
    void passComputes(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), "program.deferred1_b.enabled = false\n");
        for (String pass : List.of("deferred", "deferred1")) {
            Files.writeString(shaders.resolve(pass + ".fsh"), "#version 120\nuniform sampler2D colortex4;\n"
                    + "void main() {\n/* RENDERTARGETS: 4 */\n gl_FragData[0] = texture2D(colortex4, vec2(0.5));\n}\n");
        }
        Files.writeString(shaders.resolve("deferred1_a.csh"), """
                #version 430 compatibility
                layout(local_size_x = 8, local_size_y = 8) in;
                const vec2 workGroupsRender = vec2(0.5, 0.5);
                layout(rgba16f) writeonly uniform image2D colorimg4;
                uniform sampler2D colortex4;
                uniform sampler2D colortex9;
                uniform sampler2D depthtex0;
                uniform vec3 cameraPosition;
                uniform int frameCounter;
                float unusedDepth() { return texture2D(depthtex0, vec2(0.5)).r; }
                void main() {
                    vec4 sky = texture2D(colortex4, vec2(0.5)) + vec4(cameraPosition, float(frameCounter));
                    imageStore(colorimg4, ivec2(gl_GlobalInvocationID.xy), sky);
                }
                """);
        String other = "#version 430\nlayout(local_size_x = 1) in;\nuniform sampler3D lut;\n"
                + "layout(rgba16f) writeonly uniform image2D colorimg4;\n"
                + "void main() { imageStore(colorimg4, ivec2(0), texture(lut, vec3(0.5))); }\n";
        for (String name : List.of("deferred1_b", "deferred1_c", "deferred_a", "composite_a")) {
            Files.writeString(shaders.resolve(name + ".csh"), other);
        }
        PackPlan plan = PackProbe.analyze(root).plan();
        PassComputePlan computes = PassComputePlan.build(plan, shaders);
        Map<String, List<String>> deviations = new TreeMap<>();
        computes.computes().forEach(compute -> deviations.put(compute.name(), compute.deviations()));
        assertEquals(List.of("deferred_a", "deferred1_a", "composite_a"),
                computes.computes().stream().map(PassComputePlan.PassCompute::name).toList(),
                "pass order, and nothing after the disabled deferred1_b: " + deviations);
        assertEquals(List.of("PASS_COMPUTE_SAMPLER_UNSUPPORTED:lut"), deviations.get("deferred_a"));
        // composite has no program: its computes run alone (ComputeOnlyPass), so only the 3D sampler stops it.
        assertEquals(List.of("PASS_COMPUTE_SAMPLER_UNSUPPORTED:lut"), deviations.get("composite_a"));
        PassComputePlan.PassCompute sky = computes.before("deferred1").get(0);
        assertEquals(List.of(
                new PassComputePlan.Binding("colorimg4", 0, PassComputePlan.Kind.TARGET_IMAGE, 4, "rgba16f", null),
                new PassComputePlan.Binding("colortex4", 1, PassComputePlan.Kind.TARGET_SAMPLER, 4, null, null)),
                sky.bindings());
        assertEquals(List.of("cameraPosition", "frameCounter"),
                sky.values().stream().map(ComputeValueBlock.Value::name).toList());
        assertEquals(Set.of(4), computes.storageTargets());
        assertTrue(Arrays.equals(new int[] {160, 90, 1}, sky.groups(2560, 1440)),
                "workGroupsRender halves the screen over 8x8 groups");
        Map<String, Integer> written = new LinkedHashMap<>();
        sky.values().forEach(value -> written.put(value.name(), value.offset()));
        String error = PackCompileTest.compileComputeValues(sky.source(), PassComputePlan.VALUES_BLOCK, written);
        assertTrue(error == null, "the translated pass compute compiles: " + error + "\n" + sky.source());
    }

    /**
     * Iris gives a pass compute the pack's images and their samplers (IrisImages.addCustomImages,
     * IrisSamplers.addCustomImages). Alpha Piscium's composite computes read usam_rgba16f and write
     * uimg_rgba16f, a screen-relative RGBA16F image.
     */
    @Test
    void passComputesBindPackImages(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"),
                "image.uimg_rgba16f = usam_rgba16f RGBA RGBA16F HALF_FLOAT false true 1.0 1.0\n");
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nuniform sampler2D colortex0;\n"
                + "/*\nconst int colortex8Format = RGBA32UI;\n*/\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(colortex0, vec2(0.5));\n}\n");
        // colortex8 is an integer target, which Iris binds to a usampler2D as graphics programs do.
        Files.writeString(shaders.resolve("composite_a.csh"), """
                #version 430
                layout(local_size_x = 8, local_size_y = 8) in;
                layout(rgba16f) restrict uniform image2D uimg_rgba16f;
                uniform sampler2D usam_rgba16f;
                uniform usampler2D colortex8;
                void main() {
                    ivec2 texel = ivec2(gl_GlobalInvocationID.xy);
                    vec4 material = vec4(texelFetch(colortex8, texel, 0));
                    imageStore(uimg_rgba16f, texel, texelFetch(usam_rgba16f, texel, 0) * 0.5 + material);
                }
                """);
        PassComputePlan.PassCompute compute = PassComputePlan.build(PackProbe.analyze(root).plan(), shaders)
                .before("composite").get(0);
        assertEquals(List.of(
                new PassComputePlan.Binding("uimg_rgba16f", 0, PassComputePlan.Kind.PACK_IMAGE, -1, "rgba16f",
                        "uimg_rgba16f"),
                new PassComputePlan.Binding("usam_rgba16f", 1, PassComputePlan.Kind.PACK_IMAGE_SAMPLER, -1, null,
                        "uimg_rgba16f"),
                new PassComputePlan.Binding("colortex8", 2, PassComputePlan.Kind.TARGET_SAMPLER, 8, null, null)),
                compute.bindings(), compute.deviations().toString());
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
     * Iris binds bufferObject.N at index N for every program and compute (ShaderStorageBufferHolder).
     * Alpha Piscium's computes share its global data in bufferObject.0. A block no declared buffer
     * serves stops its compute.
     */
    @Test
    void passComputesBindStorageBuffers(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), "bufferObject.0 = 64\nbufferObject.3 = 16\n");
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nuniform sampler2D colortex0;\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(colortex0, vec2(0.5));\n}\n");
        Files.writeString(shaders.resolve("composite_a.csh"), """
                #version 430
                layout(local_size_x = 1) in;
                layout(std430, binding = 3) buffer Exposure { float exposure; };
                layout(std430, binding = 0) buffer Global { vec4 data[]; } global;
                layout(rgba16f) uniform image2D colorimg2;
                void main() {
                    global.data[0] = vec4(exposure);
                    imageStore(colorimg2, ivec2(0), global.data[1]);
                }
                """);
        Files.writeString(shaders.resolve("composite1_a.csh"), """
                #version 430
                layout(local_size_x = 1) in;
                layout(std430, binding = 5) buffer Missing { float value; };
                void main() { value = 1.0; }
                """);
        PassComputePlan computes = PassComputePlan.build(PackProbe.analyze(root).plan(), shaders);
        PassComputePlan.PassCompute compute = computes.compute("composite_a");
        assertEquals(List.of(
                new PassComputePlan.Binding("colorimg2", 0, PassComputePlan.Kind.TARGET_IMAGE, 2, "rgba16f", null),
                new PassComputePlan.Binding("Exposure", 1, PassComputePlan.Kind.STORAGE_BUFFER, 3, null, null),
                new PassComputePlan.Binding("Global", 2, PassComputePlan.Kind.STORAGE_BUFFER, 0, null, null)),
                compute.bindings(), compute.deviations().toString());
        assertEquals(Set.of(0, 3), computes.storageBuffers());
        assertEquals(List.of("PASS_COMPUTE_STORAGE_BUFFER_UNSUPPORTED:Missing"),
                computes.compute("composite1_a").deviations());
        assertTrue(compute.source().contains("binding = 1) buffer Exposure")
                && compute.source().contains("binding = 2) buffer Global"), compute.source());
        String error = PackCompileTest.compileComputeValues(compute.source(), PassComputePlan.VALUES_BLOCK, Map.of());
        assertTrue(error == null, "the translated pass compute compiles: " + error + "\n" + compute.source());
    }

    /**
     * Iris gives a pass's computes the depth samplers its program gets (IrisSamplers
     * addCompositeSamplers). Shrimple's deferred4 compute reads depthtex0 where no program does,
     * so the depth graph must keep that snapshot for it.
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
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(plan.activePrograms(), plan.config(), 64, 64, 8, 4096);
        assertEquals(Set.of(), graph.depth().names(), "no program reads depth");
        PackTargetGraphPlan widened = graph.withComputeDepthReads(Set.of("depthtex0", "depthtex1"));
        assertEquals(Set.of("depthtex0", "depthtex1"), widened.depth().names());
        assertTrue(widened.deviations().contains("DEPTH_COPY_GRAPH_APPLIED"), widened.deviations().toString());
        assertTrue(graph.withComputeDepthReads(Set.of()) == graph, "nothing to add keeps the graph");
        String error = PackCompileTest.compileComputeValues(compute.source(), PassComputePlan.VALUES_BLOCK, Map.of());
        assertTrue(error == null, "the translated pass compute compiles: " + error + "\n" + compute.source());
    }

    /**
     * A pass with computes and no fragment program runs its computes at its place in the chain
     * (Iris CompositeRenderer.ComputeOnlyPass): Alpha Piscium's composite2 is composite2.csh alone.
     * The schedule gives it the window its name gives a program, and the report its translation.
     */
    @Test
    void computeOnlyPasses(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nuniform sampler2D colortex0;\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(colortex0, vec2(0.5));\n}\n");
        String compute = """
                #version 430
                layout(local_size_x = 8, local_size_y = 8) in;
                layout(rgba16f) uniform image2D colorimg2;
                void main() { imageStore(colorimg2, ivec2(gl_GlobalInvocationID.xy), vec4(1.0)); }
                """;
        Files.writeString(shaders.resolve("composite2.csh"), compute);
        Files.writeString(shaders.resolve("deferred3_a.csh"), compute);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PassComputePlan computes = PassComputePlan.build(analysis.plan(), shaders);
        assertTrue(computes.compute("composite2").supported() && computes.compute("deferred3_a").supported(),
                computes.computes().toString());
        PackFrameSchedulePlan schedule = PackFrameSchedulePlan.build(analysis.plan().activePrograms(), null,
                Map.of("composite2", Set.of("depthtex0"), "deferred3", Set.of("depthtex0")));
        assertEquals(PackFrameSchedulePlan.PostWindow.LATE, schedule.postStage("composite2").window());
        assertEquals(PackFrameSchedulePlan.PostWindow.EARLY, schedule.postStage("deferred3").window());
        // A deferred compute reads the opaque depth, captured before the window as for a deferred program.
        assertTrue(schedule.earlyReadsDepthtex0()
                && schedule.deviations().contains("SCHEDULE_DEPTH_TEX0_OPAQUE_AT_DEFERRED:deferred3")
                && !schedule.deviations().contains("SCHEDULE_DEPTH_TEX0_OPAQUE_AT_DEFERRED:composite2"),
                schedule.deviations().toString());
        assertEquals(List.of("deferred3", "composite", "composite2"), schedule.postStages().stream()
                .map(PackFrameSchedulePlan.PostStage::name).toList(), "pass order");
        ConformanceReport.ProgramReport report = analysis.report().program("composite2");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION, report.support(),
                report.deviations().toString());
        assertTrue(report.deviations().contains("PASS_COMPUTE_BRIDGE:composite2"), report.deviations().toString());
    }

    @Test
    void advancedResourceInventory(@TempDir Path root) throws IOException {
        String harmless = "// uniform image2D ignored; buffer Ignored { float x; };\n"
                + "uniform sampler2D colortex0;\n"
                + "vec4 sampleImage(sampler2D image, vec2 coords) {\n"
                + " float imageFoo = 0.0, imageSize = 0.0, bufferColor = 0.0;\n"
                + " return texture2D(image, coords) + imageFoo + imageSize + bufferColor; }\n"
                + "void main() { gl_FragColor = sampleImage(colortex0, vec2(0.5)); }\n";
        assertTrue(!PackAdvancedResourcePlan.containsAdvancedDeclarations(harmless),
                "sampler parameter named image or commented declarations became advanced resources");
        for (String prefix : List.of("", "i", "u")) {
            for (String shape : List.of("1D", "2D", "3D", "Cube", "2DRect", "1DArray",
                    "2DArray", "CubeArray", "Buffer", "2DMS", "2DMSArray")) {
                assertTrue(PackAdvancedResourcePlan.containsAdvancedDeclarations("uniform " + prefix + "image"
                                + shape + " resource;\nvoid main() { int size = imageSize(resource).x; }\n"),
                        "real image type missed: " + prefix + "image" + shape);
            }
        }
        // A declaration nothing reads is inactive, as GL leaves it; preparation drops it.
        assertTrue(!PackAdvancedResourcePlan.containsAdvancedDeclarations("uniform image2D resource;\n"),
                "an unread image declaration became an advanced resource");
        String storage = "layout(std430, binding=0) readonly buffer Data { float x; };\n";
        assertTrue(PackAdvancedResourcePlan.containsAdvancedDeclarations(storage),
                "storage buffer declaration missed");
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Path vertex = shaders.resolve("composite1.vsh");
        Path fragment = shaders.resolve("composite1.fsh");
        try {
            Files.writeString(vertex, "#version 120\nvoid main() { gl_Position = ftransform(); }\n");
            Files.writeString(fragment, "#version 120\n" + harmless);
            ConformanceReport report = PackProbe.probe(root);
            assertTrue(report.shouldAttempt("composite1"),
                    "MakeUp-style sampler helper must remain executable: "
                            + report.program("composite1").deviations());
            for (String declaration : List.of("layout(rgba8) uniform image2D resource;\n", storage)) {
                Files.writeString(fragment, "#version 430\n" + declaration + "void main() { gl_FragColor = vec4(1.0);"
                        + (declaration.contains("image2D") ? " imageStore(resource, ivec2(0), vec4(1.0));" : "")
                        + " }\n");
                report = PackProbe.probe(root);
                assertTrue(report.program("composite1").deviations()
                                .contains("ADVANCED_RESOURCE_UNSUPPORTED"),
                        "unplanned real advanced resource was not flagged: " + declaration);
                assertTrue(!report.shouldAttempt("composite1"),
                        "unplanned real advanced resource was admitted");
            }
        } finally {
            Files.deleteIfExists(fragment);
            Files.deleteIfExists(vertex);
            Files.deleteIfExists(shaders);
            Files.deleteIfExists(root);
        }
    }
}
