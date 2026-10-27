package net.chimera.shaderpack;

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
        PassComputePlan.PassCompute compute = PassComputePlan.build(plan, shaders).compute("shadowcomp");
        assertTrue(compute != null && compute.supported(), "the list-declared shadowcomp is supported: "
                + (compute == null ? "missing" : compute.deviations()));
        List<String> symbols = compute.bindings().stream().map(PassComputePlan.Binding::symbol).toList();
        assertTrue(symbols.containsAll(List.of("floodfill_img", "floodfill_img_copy", "floodfillSampler",
                "floodfillSamplerCopy")) && symbols.size() == Set.copyOf(symbols).size(),
                "each listed resource takes its own binding: " + compute.bindings());
        Map<String, Integer> written = new LinkedHashMap<>();
        for (var value : compute.values()) written.put(value.name(), value.offset());
        String error = PackCompileTest.compileComputeValues(compute.source(), PassComputePlan.VALUES_BLOCK, written);
        assertTrue(error == null, "the translated shadowcomp compiles: " + error);
    }

    /**
     * shadowcomp.csh is a pass compute like every other (ShadowCompositeRenderer.createComputes): one
     * that writes a pack image runs here, and one the pack points at a buffer dispatches from it.
     */
    @Test
    void shadowcompIsAPassCompute(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        String properties = """
                iris.features.optional=CUSTOM_IMAGES
                image.voxels = voxelSampler rgba rgba16f half_float false false 8 4 8
                bufferObject.0 = 16
                """;
        Files.writeString(shaders.resolve("shaders.properties"), properties);
        Files.writeString(shaders.resolve("shadowcomp.csh"), """
                #version 430
                layout (local_size_x = 2, local_size_y = 2, local_size_z = 2) in;
                writeonly uniform image3D voxels;
                void main() {
                    imageStore(voxels, ivec3(gl_GlobalInvocationID), vec4(1.0));
                }
                """);
        Files.writeString(shaders.resolve("final.fsh"), "#version 120\nuniform sampler2D colortex0;\n"
                + "void main() {\n gl_FragColor = texture2D(colortex0, vec2(0.5));\n}\n");
        PassComputePlan.PassCompute direct = PassComputePlan.build(PackProbe.analyze(root).plan(), shaders)
                .compute("shadowcomp");
        assertTrue(direct != null && direct.supported() && direct.indirect() == null,
                direct == null ? "missing" : direct.deviations().toString());
        Files.writeString(shaders.resolve("shaders.properties"), properties + "indirect.shadowcomp = 0 0\n");
        PassComputePlan.PassCompute indirect = PassComputePlan.build(PackProbe.analyze(root).plan(), shaders)
                .compute("shadowcomp");
        assertTrue(indirect != null && indirect.supported() && indirect.indirect() != null,
                indirect == null ? "missing" : indirect.deviations().toString());
    }

    /**
     * A compute's uniform block is written while a frame is recorded, so each frame in flight
     * owns one: a single block could be rewritten while the GPU still ran the previous frame's
     * dispatch. The pass computes, shadowcomp.csh included, bind ComputeKernel's current frame slot.
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
        var compute = MixinBytecode.nativeClass("net/chimera/render/PackPassComputes");
        assertTrue(calls(compute, "dispatch", "net/chimera/render/ComputeKernel", "currentFrame"),
                "PackPassComputes must bind the current frame slot's uniform block");
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
        PassComputePlan.PassCompute compute = PassComputePlan.build(PackProbe.analyze(root).plan(), shaders)
                .compute("shadowcomp");
        assertTrue(compute.values().stream().map(ComputeValueBlock.Value::name).toList().containsAll(List.of(
                        "cameraPosition", "frameCounter", "frameTimeCounter", "previousCameraPosition", "wetness")),
                "every live value joins the uniform block: " + compute.values());
        Map<String, Integer> written = new LinkedHashMap<>();
        for (var value : compute.values()) written.put(value.name(), value.offset());
        String error = PackCompileTest.compileComputeValues(compute.source(), PassComputePlan.VALUES_BLOCK, written);
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
            Files.writeString(shaders.resolve(pass + ".fsh"), "#version 120\nconst int colortex4Format = RGBA16F;\n"
                    + "uniform sampler2D colortex4;\n"
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
     * colorimgN binds the target in its own format, so an integer target is an integer image:
     * Rethinking Voxels' prepare4 compute writes its RGBA16I colortex11 as iimage2D. A layout naming
     * another format of the target's texel size reads it through a view in that format, as for a
     * pack image: Alpha Piscium's begin2_b clears its R32UI colortex9 as rgba8, and a float target can
     * be read as integers of its size. Another size is refused.
     */
    @Test
    void passComputesWriteIntegerTargets(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nconst int colortex2Format = RGBA16F;\n"
                + "uniform sampler2D colortex0;\n"
                + "/*\nconst int colortex11Format = RGBA16I;\nconst int colortex9Format = R32UI;\n*/\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(colortex0, vec2(0.5));\n}\n");
        Files.writeString(shaders.resolve("composite_a.csh"), """
                #version 430
                layout(local_size_x = 1) in;
                layout(rgba16i) uniform iimage2D colorimg11;
                layout(rgba8) uniform writeonly image2D colorimg9;
                void main() { imageStore(colorimg11, ivec2(0), ivec4(1)); imageStore(colorimg9, ivec2(0), vec4(0.0)); }
                """);
        Files.writeString(shaders.resolve("composite1_a.csh"), """
                #version 430
                layout(local_size_x = 1) in;
                layout(r32i) uniform iimage2D colorimg2;
                void main() { imageStore(colorimg2, ivec2(0), ivec4(1)); }
                """);
        Files.writeString(shaders.resolve("composite2_a.csh"), """
                #version 430
                layout(local_size_x = 1) in;
                layout(rgba16i) uniform iimage2D colorimg2;
                void main() { imageStore(colorimg2, ivec2(0), ivec4(1)); }
                """);
        PassComputePlan computes = PassComputePlan.build(PackProbe.analyze(root).plan(), shaders);
        assertEquals(List.of(new PassComputePlan.Binding("colorimg2", 0, PassComputePlan.Kind.TARGET_IMAGE, 2,
                "rgba16i", null)), computes.compute("composite2_a").bindings(), "RGBA16F colortex2 as rgba16i");
        PassComputePlan.PassCompute compute = computes.compute("composite_a");
        assertEquals(List.of(
                new PassComputePlan.Binding("colorimg11", 0, PassComputePlan.Kind.TARGET_IMAGE, 11, "rgba16i", null),
                new PassComputePlan.Binding("colorimg9", 1, PassComputePlan.Kind.TARGET_IMAGE, 9, "rgba8", null)),
                compute.bindings(), compute.deviations().toString());
        assertEquals(List.of("PASS_COMPUTE_IMAGE_UNSUPPORTED:colorimg2"), computes.compute("composite1_a").deviations(),
                "RGBA16F colortex2 has no 4-byte view");
        String error = PackCompileTest.compileComputeValues(compute.source(), PassComputePlan.VALUES_BLOCK, Map.of());
        assertTrue(error == null, "the translated pass compute compiles: " + error + "\n" + compute.source());
    }

    /**
     * A compute binds shadowcolorimgN, the shadow colour target in its format
     * (IrisImages.addShadowColorImages), and shadowcolorN, up to Iris's eight targets with
     * HIGHER_SHADOWCOLOR: Alpha Piscium's shadowcomp1 writes shadowcolorimg6, which no program draws,
     * and its composite7 compute samples shadowcolor6. Without the feature Iris has two.
     */
    @Test
    void passComputesBindShadowColorTargets(@TempDir Path root) throws IOException {
        String composite = "#version 120\nuniform sampler2D colortex0;\n"
                + "/*\nconst int shadowcolor6Format = RGB10_A2;\n*/\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(colortex0, vec2(0.5));\n}\n";
        String shadowcomp = """
                #version 430
                layout(local_size_x = 1) in;
                layout(rgb10_a2) uniform image2D shadowcolorimg6;
                uniform sampler2D shadowcolor6;
                void main() { imageStore(shadowcolorimg6, ivec2(0), texelFetch(shadowcolor6, ivec2(0), 0)); }
                """;
        Path higher = Files.createDirectories(root.resolve("higher/shaders"));
        Files.writeString(higher.resolve("shaders.properties"), "iris.features.required = HIGHER_SHADOWCOLOR\n");
        Files.writeString(higher.resolve("composite.fsh"), composite);
        Files.writeString(higher.resolve("shadowcomp1.csh"), shadowcomp);
        PassComputePlan.PassCompute compute = PassComputePlan.build(PackProbe.analyze(higher.getParent()).plan(),
                higher).compute("shadowcomp1");
        assertEquals(List.of(
                new PassComputePlan.Binding("shadowcolorimg6", 0, PassComputePlan.Kind.SHADOW_COLOR_IMAGE, 6,
                        "rgb10_a2", "shadowcolor6"),
                new PassComputePlan.Binding("shadowcolor6", 1, PassComputePlan.Kind.SHADOW_COLOR, -1, null,
                        "shadowcolor6")), compute.bindings(), compute.deviations().toString());
        String error = PackCompileTest.compileComputeValues(compute.source(), PassComputePlan.VALUES_BLOCK, Map.of());
        assertTrue(error == null, "the translated pass compute compiles: " + error + "\n" + compute.source());
        Path legacy = Files.createDirectories(root.resolve("legacy/shaders"));
        Files.writeString(legacy.resolve("composite.fsh"), composite);
        Files.writeString(legacy.resolve("shadowcomp1.csh"), shadowcomp);
        assertEquals(List.of("PASS_COMPUTE_IMAGE_UNSUPPORTED:shadowcolorimg6",
                "PASS_COMPUTE_SAMPLER_UNSUPPORTED:shadowcolor6"), PassComputePlan.build(
                PackProbe.analyze(legacy.getParent()).plan(), legacy).compute("shadowcomp1").deviations());
    }

    /**
     * A GL image unit reinterprets an image declared in another format of its texel size: Alpha
     * Piscium reads its R32F uimg_r32f as layout(r32i) iimage2D for integer atomics. The compute
     * binds a view in that format, which the session creates; another size is refused.
     */
    @Test
    void passComputesReadPackImagesThroughViews(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"),
                "image.uimg_r32f = usam_r32f RED R32F FLOAT false true 1.0 1.0\n");
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nuniform sampler2D colortex0;\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(colortex0, vec2(0.5));\n}\n");
        Files.writeString(shaders.resolve("composite_a.csh"), """
                #version 430
                layout(local_size_x = 1) in;
                layout(r32i) uniform iimage2D uimg_r32f;
                void main() { imageAtomicMax(uimg_r32f, ivec2(0), 1); }
                """);
        Files.writeString(shaders.resolve("composite1_a.csh"), """
                #version 430
                layout(local_size_x = 1) in;
                layout(rg32i) uniform iimage2D uimg_r32f;
                void main() { imageStore(uimg_r32f, ivec2(0), ivec4(1)); }
                """);
        PassComputePlan computes = PassComputePlan.build(PackProbe.analyze(root).plan(), shaders);
        PassComputePlan.PassCompute compute = computes.compute("composite_a");
        assertEquals(List.of(new PassComputePlan.Binding("uimg_r32f", 0, PassComputePlan.Kind.PACK_IMAGE, -1, "r32i",
                "uimg_r32f")), compute.bindings(), compute.deviations().toString());
        assertEquals(Map.of("uimg_r32f", Set.of(IrisColorFormats.of("r32i").vkFormat())), computes.imageFormats());
        assertEquals(List.of("PASS_COMPUTE_IMAGE_UNSUPPORTED:uimg_r32f"),
                computes.compute("composite1_a").deviations());
        String error = PackCompileTest.compileComputeValues(compute.source(), PassComputePlan.VALUES_BLOCK, Map.of());
        assertTrue(error == null, "the translated pass compute compiles: " + error + "\n" + compute.source());
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
     * Iris binds bufferObject.N at index N for every program and compute (ShaderStorageBufferHolder).
     * Alpha Piscium's computes share its global data in bufferObject.0. A block no declared buffer
     * serves stops its compute.
     */
    @Test
    void passComputesBindStorageBuffers(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), "bufferObject.0 = 64\nbufferObject.3 = 16\n");
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nconst int colortex2Format = RGBA16F;\n"
                + "uniform sampler2D colortex0;\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(colortex0, vec2(0.5));\n}\n");
        Files.writeString(shaders.resolve("composite_a.csh"), """
                #version 430
                layout(local_size_x = 1) in;
                layout(std430, binding = 3) buffer Exposure { float exposure; };
                layout(std430, binding = 1) buffer Stale { float stale; };
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
        // Blocks nothing reads are inactive in GL, whatever they name; a shared one has no Vulkan layout.
        Files.writeString(shaders.resolve("composite2_a.csh"), """
                #version 430
                layout(local_size_x = 1) in;
                layout(std430, binding = 6) buffer Unread { float unused[]; } unread;
                layout(std430, binding = 7) buffer Anonymous { float alsoUnused; };
                layout(shared, binding = 0) buffer Shared { float shared0; };
                void main() { shared0 = 1.0; }
                """);
        PassComputePlan computes = PassComputePlan.build(PackProbe.analyze(root).plan(), shaders);
        assertEquals(List.of("PASS_COMPUTE_STORAGE_BUFFER_UNSUPPORTED:Shared",
                "STORAGE_BUFFER_LAYOUT_UNSUPPORTED:Shared"), computes.compute("composite2_a").deviations());
        PassComputePlan.PassCompute compute = computes.compute("composite_a");
        assertEquals(List.of(
                new PassComputePlan.Binding("colorimg2", 0, PassComputePlan.Kind.TARGET_IMAGE, 2, "rgba16f", null),
                new PassComputePlan.Binding("Exposure", 1, PassComputePlan.Kind.STORAGE_BUFFER, 3, null, null),
                new PassComputePlan.Binding("Global", 2, PassComputePlan.Kind.STORAGE_BUFFER, 0, null, null)),
                compute.bindings(), compute.deviations().toString());
        assertEquals(Set.of(0, 3), computes.storageBuffers());
        assertEquals(List.of("PASS_COMPUTE_STORAGE_BUFFER_UNSUPPORTED:Missing",
                "STORAGE_BUFFER_BLOCK_UNMAPPED:composite1_a:Missing"), computes.compute("composite1_a").deviations());
        assertTrue(compute.source().contains("binding = 1) buffer Exposure")
                && compute.source().contains("binding = 2) buffer Global") && !compute.source().contains("Stale"),
                compute.source());
        String error = PackCompileTest.compileComputeValues(compute.source(), PassComputePlan.VALUES_BLOCK, Map.of());
        assertTrue(error == null, "the translated pass compute compiles: " + error + "\n" + compute.source());
    }

    /**
     * Iris runs CommonTransformer on computes too: gl_Fog reads iris_Fog, built from the fog
     * uniforms, and GL's built-ins take scalars. Rethinking Voxels' deferred1 compute reads
     * gl_Fog.start and gl_Fog.scale.
     */
    @Test
    void passComputesReadLegacyFog(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nconst int colortex2Format = RGBA16F;\n"
                + "uniform sampler2D colortex0;\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(colortex0, vec2(0.5));\n}\n");
        Files.writeString(shaders.resolve("composite_a.csh"), """
                #version 430 compatibility
                layout(local_size_x = 1) in;
                layout(rgba16f) uniform image2D colorimg2;
                void main() {
                    float fog = (3.0 - gl_Fog.start) * gl_Fog.scale;
                    if (any(isnan(fog))) fog = 0.0;
                    imageStore(colorimg2, ivec2(0), vec4(fog));
                }
                """);
        PassComputePlan.PassCompute compute = PassComputePlan.build(PackProbe.analyze(root).plan(), shaders)
                .compute("composite_a");
        assertTrue(compute.supported(), compute.deviations().toString());
        assertEquals(List.of("fogEnd", "fogStart"),
                compute.values().stream().map(ComputeValueBlock.Value::name).sorted().toList());
        Map<String, Integer> offsets = new LinkedHashMap<>();
        compute.values().forEach(value -> offsets.put(value.name(), value.offset()));
        String error = PackCompileTest.compileComputeValues(compute.source(), PassComputePlan.VALUES_BLOCK, offsets);
        assertTrue(error == null, "the translated pass compute compiles: " + error + "\n" + compute.source());
        // iris_Fog has no other field: a compute reading one does not compile under Iris either.
        Files.writeString(shaders.resolve("composite_b.csh"), """
                #version 430 compatibility
                layout(local_size_x = 1) in;
                layout(rgba16f) uniform image2D colorimg2;
                void main() {
                    imageStore(colorimg2, ivec2(0), vec4(gl_Fog.index));
                }
                """);
        PassComputePlan.PassCompute unknown = PassComputePlan.build(PackProbe.analyze(root).plan(), shaders)
                .compute("composite_b");
        assertTrue(!unknown.supported() && unknown.deviations()
                        .contains("PASS_COMPUTE_NORMALIZATION:LEGACY_FOG_FIELD_UNSUPPORTED:index"),
                unknown.deviations().toString());
    }

    /**
     * colorimg0 is colortex0 as a storage image, in its format, as every other target (Iris
     * IrisImages.addRenderTargetImages). Alpha Piscium's composite36 and composite42 bind it. The session
     * moves colortex0 out of the HDR image into its storage graph image before such a compute.
     */
    @Test
    void passComputesWriteColortex0(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nconst int colortex0Format = RGBA16F;\n"
                + "uniform sampler2D colortex0;\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(colortex0, vec2(0.5));\n}\n");
        // GLSL 4.20 takes qualifiers in any order: RenderPearl writes uniform before layout.
        Files.writeString(shaders.resolve("composite_a.csh"), """
                #version 430
                layout(local_size_x = 8, local_size_y = 8) in;
                uniform layout(rgba16f) restrict image2D colorimg0;
                void main() {
                    ivec2 texel = ivec2(gl_GlobalInvocationID.xy);
                    imageStore(colorimg0, texel, imageLoad(colorimg0, texel) * 0.5);
                }
                """);
        PassComputePlan computes = PassComputePlan.build(PackProbe.analyze(root).plan(), shaders);
        PassComputePlan.PassCompute compute = computes.compute("composite_a");
        assertEquals(List.of(
                new PassComputePlan.Binding("colorimg0", 0, PassComputePlan.Kind.TARGET_IMAGE, 0, "rgba16f", null)),
                compute.bindings(), compute.deviations().toString());
        assertEquals(Set.of(0), computes.storageTargets());
        assertTrue(compute.source().contains("layout(rgba16f, binding = 0) uniform restrict image2D colorimg0;"),
                compute.source());
        String error = PackCompileTest.compileComputeValues(compute.source(), PassComputePlan.VALUES_BLOCK, Map.of());
        assertTrue(error == null, "the translated pass compute compiles: " + error + "\n" + compute.source());
    }

    /**
     * Iris ShaderProperties {@code indirect.<compute> = <buffer> <offset>} dispatches the counts that
     * buffer holds. Iris ignores a value it cannot parse and fails on a buffer the pack lacks; Vulkan
     * reads the counts at a multiple of 4 inside the buffer.
     */
    @Test
    void passComputesDispatchIndirect(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                bufferObject.2 = 32
                indirect.composite_a = 2 16
                indirect.composite1_a = 7 0
                indirect.composite2_a = 2 6
                indirect.composite3_a = 2 sixteen
                """);
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nconst int colortex2Format = RGBA16F;\n"
                + "uniform sampler2D colortex0;\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(colortex0, vec2(0.5));\n}\n");
        String compute = """
                #version 430
                layout(local_size_x = 1) in;
                layout(rgba16f) uniform image2D colorimg2;
                void main() { imageStore(colorimg2, ivec2(gl_GlobalInvocationID.xy), vec4(1.0)); }
                """;
        for (String pass : List.of("composite", "composite1", "composite2", "composite3")) {
            Files.writeString(shaders.resolve(pass + "_a.csh"), compute);
        }
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PassComputePlan computes = PassComputePlan.build(analysis.plan(), shaders);
        assertEquals(new PassComputePlan.Indirect(2, 16), computes.compute("composite_a").indirect());
        PassComputePlan.PassCompute pointed = computes.compute("composite_a");
        assertTrue(pointed.supported(), pointed.deviations().toString());
        assertEquals(List.of("PASS_COMPUTE_INDIRECT_BUFFER_UNAVAILABLE:7"),
                computes.compute("composite1_a").deviations());
        assertEquals(List.of("PASS_COMPUTE_INDIRECT_OFFSET_UNSUPPORTED:6"),
                computes.compute("composite2_a").deviations());
        assertTrue(computes.compute("composite3_a").supported() && computes.compute("composite3_a").indirect() == null,
                "an unparsable pointer is ignored");
        assertEquals(Set.of(2), computes.storageBuffers(), "the dispatch buffer is allocated");
        assertTrue(analysis.plan().settings().deviations().stream().noneMatch(value -> value.contains("indirect.")),
                analysis.plan().settings().deviations().toString());
    }

    /**
     * A buffer only pass computes bind is allocated from what the storage budget leaves once the
     * images and the fixed-size buffers programs bind are charged.
     */
    @Test
    void computeBufferBudget(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), "bufferObject.0 = 64\nbufferObject.1 = 128\n");
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 430
                layout(std430, binding = 0) buffer Exposure { float exposure; };
                uniform sampler2D colortex0;
                layout(location = 0) out vec4 color;
                void main() {
                /* RENDERTARGETS: 0 */
                    color = texture(colortex0, vec2(0.5)) * exposure;
                }
                """);
        PackAdvancedResourcePlan plan = PackProbe.analyze(root).plan().advancedResources();
        assertEquals(Set.of(0), plan.programBuffers(), plan.storageBuffers().toString());
        assertEquals(PackAdvancedResourcePlan.MAX_TOTAL_BYTES - 64, plan.computeBufferBudget());
    }

    /**
     * Iris clears every target on its first frame (ClearPassCreator: fullClear), so a clear=false
     * target only a compute binds starts defined: the graph seeds it, as it does one the world reads.
     */
    @Test
    void passComputeTargetsAreSeeded(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nuniform sampler2D colortex0;\n"
                + "const bool colortex3Clear = false;\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(colortex0, vec2(0.5));\n}\n");
        PackPlan plan = PackProbe.analyze(root).plan();
        PackTargetGraphPlan.Limits limits = new PackTargetGraphPlan.Limits(64, 64, 8, 4096);
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(plan.activePrograms(), plan.config(), plan.resources(),
                limits, new PackTargetGraphPlan.ComputeUse(Set.of(3), Set.of()));
        assertTrue(graph.target(3) != null && graph.target(3).persistent(), graph.targets().toString());
        assertTrue(graph.requiresInitialSeed(3), "a compute's clear=false target is seeded");
        assertTrue(!graph.geometryReads().contains(3), "no world program reads colortex3");
    }

    /**
     * A shadowcomp the pack switches off is no source to Iris: BSL switches shadowcomp off with its
     * coloured lighting, whose shadowcomp.csh then stops at an #error.
     */
    @Test
    void switchedOffShadowcompIsNoStage(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), "program.shadowcomp.enabled = false\n");
        Files.writeString(shaders.resolve("shadowcomp.csh"), "#version 430\n#error colored lighting is off\n");
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nuniform sampler2D colortex0;\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(colortex0, vec2(0.5));\n}\n");
        PackPlan plan = PackProbe.analyze(root).plan();
        assertTrue(plan.isProgramDisabled("shadowcomp"));
        assertTrue(PassComputePlan.build(plan, shaders).compute("shadowcomp") == null
                && plan.advancedResources().deviations().stream().noneMatch(value -> value.contains("shadowcomp")),
                plan.advancedResources().deviations().toString());
    }

    @Test
    void passesRunAlone(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"),
                "program.composite2.enabled = false\nprogram.composite5.enabled = false\n");
        // A pass of computes alone switched off runs nothing: Shrimple switches off its composite1.csh.
        Files.writeString(shaders.resolve("composite5.csh"), "#version 430\nlayout(local_size_x = 1) in;\n"
                + "void main() {}\n");
        String fragment = "#version 120\nuniform sampler2D colortex0;\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(colortex0, vec2(0.5));\n}\n";
        for (String pass : List.of("composite", "composite2")) {
            Files.writeString(shaders.resolve(pass + ".fsh"), fragment);
        }
        // A vertex stage alone is no program (ProgramSource.isValid); Iris supplies a missing one.
        Files.writeString(shaders.resolve("composite1.vsh"),
                "#version 120\nvoid main() { gl_Position = ftransform(); }\n");
        Files.writeString(shaders.resolve("composite4.vsh"),
                "#version 120\nvoid main() { gl_Position = ftransform(); }\n");
        Files.writeString(shaders.resolve("composite4.fsh"), fragment);
        Files.writeString(shaders.resolve("composite4.csh"), """
                #version 430
                layout(local_size_x = 1) in;
                layout(rgba16f) uniform image2D colorimg2;
                void main() { imageStore(colorimg2, ivec2(0), vec4(1.0)); }
                """);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackPlan plan = analysis.plan();
        assertTrue(!PassComputePlan.runsAlone(plan, "composite") && !PassComputePlan.runsAlone(plan, "composite4"),
                "a pass with a fragment stage draws");
        assertTrue(PassComputePlan.runsAlone(plan, "composite1") && PassComputePlan.runsAlone(plan, "composite2")
                && PassComputePlan.runsAlone(plan, "composite3"), "vertex only, switched off, missing");
        assertTrue(plan.isProgramDisabled("composite5") && !plan.isProgramDisabled("composite3"),
                "a switched-off pass of computes alone is disabled; a missing one is not");
        assertTrue(PassComputePlan.build(plan, shaders).compute("composite5") == null, "its compute does not run");
        // composite4.csh shares composite4's row, which stays the program's.
        assertTrue(analysis.report().program("composite4").deviations().stream()
                .noneMatch(deviation -> deviation.startsWith("PASS_COMPUTE_")),
                analysis.report().program("composite4").deviations().toString());
    }

    /**
     * A pass with computes and no fragment program runs its computes at its place in the chain
     * (Iris CompositeRenderer.ComputeOnlyPass): Alpha Piscium's composite2 is composite2.csh alone.
     * The schedule gives it the window its name gives a program, and the report its translation.
     */
    @Test
    void computeOnlyPasses(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nconst int colortex2Format = RGBA16F;\n"
                + "uniform sampler2D colortex0;\n"
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

    /**
     * Iris reads setup.csh and setup1..99.csh, each alone with no _a.._z computes, and runs them in
     * that order once the pipeline is made and after each resize, apart from the frame's passes
     * (IrisRenderingPipeline.createSetupComputes). They bind customTexture entries and the depth
     * samplers on the program's builder, where no stage texture replaces them.
     */
    @Test
    void setupComputesRunApart(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nconst int colortex2Format = RGBA16F;\n"
                + "uniform sampler2D colortex0;\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(colortex0, vec2(0.5));\n}\n");
        Files.writeString(shaders.resolve("shaders.properties"), "uniform.float.myScale = 2.0\n");
        for (String name : List.of("setup", "setup2", "setup10", "setup2_a")) {
            Files.writeString(shaders.resolve(name + ".csh"), """
                    #version 430
                    layout(local_size_x = 1) in;
                    const ivec3 workGroups = ivec3(4, 1, 1);
                    layout(rgba16f) uniform image2D colorimg2;
                    uniform float myScale;
                    void main() { imageStore(colorimg2, ivec2(gl_GlobalInvocationID.xy), vec4(myScale)); }
                    """);
        }
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PassComputePlan computes = PassComputePlan.build(analysis.plan(), shaders);
        assertEquals(List.of("setup", "setup2", "setup10"),
                computes.computes().stream().map(PassComputePlan.PassCompute::name).toList());
        assertTrue(computes.computes().stream().allMatch(compute -> compute.supported()
                && compute.pass().equals(compute.name()) && PassComputePlan.isSetup(compute.pass())
                && Arrays.equals(new int[] {4, 1, 1}, compute.groups(1, 1))), computes.computes().toString());
        // Iris pushes no custom uniform to a setup compute, so myScale reads zero, as GL leaves it.
        PassComputePlan.PassCompute setup = computes.compute("setup");
        assertTrue(setup.deviations().contains("PASS_COMPUTE_CUSTOM_UNIFORM_UNSET:myScale")
                && setup.values().stream().noneMatch(value -> value.name().equals("myScale")),
                setup.deviations() + " " + setup.values());
        assertEquals(IrisSamplerOrder.SETUP, IrisSamplerOrder.of("setup2", true));
        assertTrue(!IrisSamplerOrder.SETUP.intercepted(IrisSamplerOrder.Source.DEPTH));
        ConformanceReport.ProgramReport report = analysis.report().program("setup10");
        assertTrue(report.deviations().contains("PASS_COMPUTE_BRIDGE:setup10")
                && !report.deviations().contains("UNSUPPORTED_PACK_STAGE"), report.deviations().toString());
    }

    /**
     * Iris runs a shadowcomp pass's computes after the shadow pass (ShadowCompositeRenderer):
     * shadowcomp.csh, then shadowcomp_a..z, then shadowcomp1 and its own. They bind noise, custom
     * textures and images and the shadow maps, and no colour target, colorimg or depth texture
     * (createComputes).
     */
    @Test
    void shadowcompComputesRunAfterTheShadowPass(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nuniform sampler2D colortex0;\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(colortex0, vec2(0.5));\n}\n");
        String shadow = """
                #version 430
                layout(local_size_x = 1) in;
                const ivec3 workGroups = ivec3(1, 1, 1);
                uniform sampler2D shadowcolor0;
                layout(std430, binding = 0) buffer Counts { float total; };
                void main() { total = texture(shadowcolor0, vec2(0.5)).r; }
                """;
        for (String name : List.of("shadowcomp", "shadowcomp_a", "shadowcomp1")) {
            Files.writeString(shaders.resolve(name + ".csh"), shadow);
        }
        Files.writeString(shaders.resolve("shadowcomp1_a.csh"), """
                #version 430
                layout(local_size_x = 1) in;
                uniform sampler2D colortex2;
                layout(rgba16f) uniform image2D colorimg2;
                void main() { imageStore(colorimg2, ivec2(0), texture(colortex2, vec2(0.5))); }
                """);
        Files.writeString(shaders.resolve("shaders.properties"), "bufferObject.0 = 16\n");
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PassComputePlan computes = PassComputePlan.build(analysis.plan(), shaders);
        assertEquals(List.of("shadowcomp", "shadowcomp_a", "shadowcomp1", "shadowcomp1_a"),
                computes.computes().stream().map(PassComputePlan.PassCompute::name).toList());
        assertTrue(computes.compute("shadowcomp").supported()
                && analysis.report().program("shadowcomp").deviations().contains("PASS_COMPUTE_BRIDGE:shadowcomp"),
                analysis.report().program("shadowcomp").deviations().toString());
        assertTrue(computes.compute("shadowcomp_a").supported() && PassComputePlan.isShadowcomp("shadowcomp1"),
                computes.compute("shadowcomp_a").deviations().toString());
        PassComputePlan.PassCompute unbound = computes.compute("shadowcomp1_a");
        assertTrue(!unbound.supported() && unbound.deviations().contains("PASS_COMPUTE_IMAGE_UNSUPPORTED:colorimg2")
                && unbound.deviations().contains("PASS_COMPUTE_SAMPLER_UNSUPPORTED:colortex2"),
                unbound.deviations().toString());
        assertEquals(IrisSamplerOrder.SHADOWCOMP, IrisSamplerOrder.of("shadowcomp1", true));
    }

    /**
     * Iris runs the begin passes each frame after its clear passes and before the sky
     * (IrisRenderingPipeline.beginLevelRendering), each one's computes as a composite's: Shrimple's
     * begin1 is begin1_a.csh and begin1_b.csh alone. They run in the opening window, before prepare.
     */
    @Test
    void beginComputesRunFirst(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nconst int colortex2Format = RGBA16F;\n"
                + "uniform sampler2D colortex0;\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = texture2D(colortex0, vec2(0.5));\n}\n");
        for (String name : List.of("begin1_a", "begin1_b", "prepare_a")) {
            Files.writeString(shaders.resolve(name + ".csh"), """
                    #version 430
                    layout(local_size_x = 8, local_size_y = 8) in;
                    layout(rgba16f) uniform image2D colorimg2;
                    void main() { imageStore(colorimg2, ivec2(gl_GlobalInvocationID.xy), vec4(1.0)); }
                    """);
        }
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PassComputePlan computes = PassComputePlan.build(analysis.plan(), shaders);
        assertEquals(List.of("begin1_a", "begin1_b", "prepare_a"),
                computes.computes().stream().map(PassComputePlan.PassCompute::name).toList());
        assertTrue(computes.compute("begin1_b").supported() && computes.compute("begin1_b").pass().equals("begin1")
                && PassComputePlan.runsAlone(analysis.plan(), "begin1"), computes.computes().toString());
        PackFrameSchedulePlan schedule = PackFrameSchedulePlan.build(analysis.plan().activePrograms(), null,
                Map.of("begin1", Set.of(), "prepare", Set.of()));
        assertEquals(List.of("begin1", "prepare", "composite"), schedule.postStages().stream()
                .map(PackFrameSchedulePlan.PostStage::name).toList(), "pass order");
        assertEquals(PackFrameSchedulePlan.PostWindow.BEGIN, schedule.postStage("begin1").window());
        assertEquals(List.of(PackFrameSchedulePlan.Phase.BEGIN, PackFrameSchedulePlan.Phase.SHADOW,
                PackFrameSchedulePlan.Phase.PREPARE), schedule.phases().subList(0, 3));
        ConformanceReport.ProgramReport report = analysis.report().program("begin1_a");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION, report.support(),
                report.deviations().toString());
        assertTrue(report.deviations().contains("PASS_COMPUTE_BRIDGE:begin1")
                && !report.deviations().contains("UNSUPPORTED_PACK_STAGE"), report.deviations().toString());
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
