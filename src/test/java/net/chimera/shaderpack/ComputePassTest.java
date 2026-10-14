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
        assertTrue(calls(kernel, "currentFrame", "net/vulkanmod/vulkan/Renderer", "getCurrentFrame"),
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
        assertEquals(List.of("PASS_COMPUTE_WITHOUT_PROGRAM:composite"), deviations.get("composite_a"));
        PassComputePlan.PassCompute sky = computes.before("deferred1").get(0);
        assertEquals(List.of(new PassComputePlan.Binding("colorimg4", 0, true, 4, "rgba16f"),
                new PassComputePlan.Binding("colortex4", 1, false, 4, null)), sky.bindings());
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
                assertTrue(PackAdvancedResourcePlan.containsAdvancedDeclarations(
                                "uniform " + prefix + "image" + shape + " resource;"),
                        "real image type missed: " + prefix + "image" + shape);
            }
        }
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
            for (String declaration : List.of("uniform image2D resource;\n", storage)) {
                Files.writeString(fragment, "#version 430\n" + declaration
                        + "void main() { gl_FragColor = vec4(1.0); }\n");
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
