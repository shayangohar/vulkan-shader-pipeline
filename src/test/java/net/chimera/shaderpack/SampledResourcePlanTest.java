package net.chimera.shaderpack;

import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sampled-resource plan and its fallbacks. */
final class SampledResourcePlanTest {
    @Test
    void sampledResources() throws Exception {
        Path root = TestPacks.fixtures();
        Path supportedPath = root.resolve("m7_5/resources");
        Path unsupportedPath = root.resolve("m7_5/unsupported_resources");

        PackProbe.Analysis supported = PackProbe.analyze(supportedPath);
        PackProbe.Analysis supportedAgain = PackProbe.analyze(supportedPath);
        assertEquals(supported.report().toJson(), supportedAgain.report().toJson(),
                "supported report stability");
        assertEquals(supported.plan().resources().snapshot(),
                supportedAgain.plan().resources().snapshot(),
                "supported resource snapshot stability");
        verifySupported(supported);
        verifyTargetAliasAllocation(supported);
        verifySnapshot(supported.plan().resources().snapshot());
        verifyZipEquivalence(supportedPath, supported.plan().resources().fingerprint());

        PackProbe.Analysis unsupported = PackProbe.analyze(unsupportedPath);
        verifyUnsupported(unsupported);
        List<String> imageDeviations = unsupported.plan().advancedResources().deviations();
        assertTrue(imageDeviations.contains("ADVANCED_IMAGE_UNSUPPORTED:albedo"),
                "the malformed image declaration was not named: " + imageDeviations);

        verifyBaseline(root.resolve("baselines/m7_5.json"), supported, unsupported);
    }

    /**
     * Spring declares texture.deferred.noisetex and texture.composite.noisetex but no texture.noise,
     * so its gbuffers programs read Iris's generated noise. Every noisetex shared one resource key,
     * and the owner loaded whichever binding came first for all of them.
     */
    @Test
    void aDeclaredNoiseIsItsOwnImage(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.createDirectories(shaders.resolve("texture"));
        Files.write(shaders.resolve("texture/Noise2D.png"), new byte[] {0});
        Files.writeString(shaders.resolve("shaders.properties"), "texture.deferred.noisetex = texture/Noise2D.png\n");
        String vertex = "#version 120\nvarying vec2 uv;\n"
                + "void main() { uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }\n";
        String fragment = "#version 120\nuniform sampler2D noisetex;\nvarying vec2 uv;\n"
                + "void main() { gl_FragData[0] = texture2D(noisetex, uv); }\n";
        for (String program : List.of("gbuffers_terrain", "deferred")) {
            Files.writeString(shaders.resolve(program + ".vsh"), vertex);
            Files.writeString(shaders.resolve(program + ".fsh"), fragment);
        }
        PackResourcePlan resources = PackProbe.analyze(root).plan().resources();
        PackResourceBinding generated = resources.binding("gbuffers_terrain", "noisetex");
        PackResourceBinding declared = resources.binding("deferred", "noisetex");
        assertTrue(generated != null && generated.status() == PackResourceStatus.GENERATED
                && generated.resourceKey().equals("noisetex"), String.valueOf(generated));
        assertTrue(declared != null && declared.status() == PackResourceStatus.PACK_FILE
                && declared.resourceKey().equals("noisetex:texture/Noise2D.png") && declared.slot() == 7,
                String.valueOf(declared));
        // Iris samples a PNG without .mcmeta nearest and repeating, a declared noise too; only
        // the generated noise is linear.
        assertEquals("nearest", declared.filter(), "a declared noise without .mcmeta");
        assertEquals("repeat", declared.wrap(), "a declared noise without .mcmeta");
        assertEquals("linear", generated.filter(), "the generated noise");

        Files.writeString(shaders.resolve("texture/Noise2D.png.mcmeta"), "{\"texture\":{\"blur\":true}}");
        PackResourceBinding blurred = PackProbe.analyze(root).plan().resources().binding("deferred", "noisetex");
        assertEquals("linear", blurred.filter(), "a declared noise whose .mcmeta sets blur");
    }

    /**
     * Iris skips a custom texture it cannot read (ShaderPack.readTexture) and runs the program:
     * a stage texture leaves the sampler its own target, a customTexture name reads unit 0, and a
     * missing texture.noise leaves the generated noise. A raw texture still refuses its program.
     * Mellow's and Reverie's LUT_FILE options name files only some of them ship.
     */
    @Test
    void aMissingTextureIsSkippedAsIrisSkipsIt(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                texture.composite.gaux4 = lut/missing.png
                customTexture.lutTexture = lut/missing.png
                texture.noise = tex/missing.png
                texture.composite.lut = lut/missing.dat TEXTURE_2D R8 4 4 RED UNSIGNED_BYTE
                """);
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 120
                uniform sampler2D gaux4;
                uniform sampler2D lutTexture;
                uniform sampler2D noisetex;
                varying vec2 texcoord;
                void main() {
                    gl_FragData[0] = texture2D(gaux4, texcoord) + texture2D(lutTexture, texcoord)
                            + texture2D(noisetex, texcoord);
                }
                """);
        Files.writeString(shaders.resolve("composite1.fsh"), """
                #version 120
                uniform sampler2D lut;
                varying vec2 texcoord;
                void main() { gl_FragData[0] = texture2D(lut, texcoord); }
                """);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackPlan plan = analysis.plan();
        PackResourcePlan resources = plan.resources();
        assertTrue(plan.shouldAttempt("composite"), "a missing PNG refused its program: "
                + resources.bindings("composite"));
        PackResourceBinding stage = resources.binding("composite", "gaux4");
        assertTrue(stage != null && stage.kind() == PackResourceKind.TARGET, "gaux4 reads its target: " + stage);
        PackResourceBinding noise = resources.binding("composite", "noisetex");
        assertTrue(noise != null && noise.status() == PackResourceStatus.GENERATED, "the generated noise: " + noise);
        PackResourceBinding custom = resources.binding("composite", "lutTexture");
        assertTrue(custom == null || custom.status() != PackResourceStatus.UNAVAILABLE, "lutTexture: " + custom);
        assertTrue(!plan.shouldAttempt("composite1"), "a missing raw texture still refuses its program");
        for (String key : List.of("texture.composite.gaux4", "customTexture.lutTexture", "texture.noise")) {
            assertTrue(analysis.report().deviations().contains("PACK_TEXTURE_DROPPED:" + key),
                    key + " was not reported: " + analysis.report().deviations());
        }
    }

    private static void verifySupported(PackProbe.Analysis analysis) {
        PackPlan plan = analysis.plan();
        PackResourcePlan resources = plan.resources();
        PackResourceBinding marker = resources.binding("composite", "marker");
        PackResourceBinding globalMarker = resources.binding("composite1", "marker");
        PackResourceBinding noise = resources.binding("composite", "noisetex");
        PackResourceBinding aux = resources.binding("composite", "gaux1");
        PackResourceBinding depth = resources.binding("composite", "depthtex0");
        PackResourceBinding shadow = resources.binding("composite", "shadowtex0");
        PackResourceBinding scene = resources.binding("composite", "colortex0");

        // Iris binds customTexture.marker straight to the program, while texture.composite.marker
        // only replaces what a registration through the sampler interceptor binds, and no built-in
        // sampler or image is named marker (CompositeRenderer.createProgram, IrisSamplerOrder).
        assertTrue(marker != null && marker.status() == PackResourceStatus.PACK_FILE,
                "marker was not loaded");
        assertEquals("tex/custom.png", marker.source(),
                "a stage texture took a name no interceptor registration binds");
        assertTrue(globalMarker != null && globalMarker.status() == PackResourceStatus.PACK_FILE,
                "composite1 marker was not loaded");
        assertEquals("tex/custom.png", globalMarker.source(),
                "a stage texture took composite1's marker");
        assertTrue(noise != null && noise.slot() == 7
                        && noise.status() == PackResourceStatus.PACK_FILE,
                "noisetex plan is not a pack file at slot 7");
        assertTrue(aux != null && aux.resourceKey().equals("colortex4") && aux.slot() == 8,
                "gaux1 alias did not resolve to colortex4 slot 8");
        assertTrue(depth != null && depth.status() == PackResourceStatus.HOST_ALIAS
                        && depth.slot() == 6,
                "depthtex0 alias changed");
        assertTrue(shadow != null && shadow.status() == PackResourceStatus.HOST_ALIAS
                        && shadow.slot() == 5,
                "shadowtex0 alias changed");
        assertTrue(scene != null && scene.dependentPrograms().equals(
                        List.of("composite", "composite1", "final")),
                "resource dependent-program inventory is not stable");
        assertTrue(plan.shouldAttempt("composite") && plan.shouldAttempt("composite1")
                        && plan.shouldAttempt("final"),
                "valid resource-dependent programs were rejected");
        assertEquals(plan.shouldAttempt("composite"),
                analysis.report().shouldAttempt("composite"),
                "supported report and plan eligibility disagree");
        assertTrue(resources.declarations().size() == 3,
                "declaration inventory is not deterministic");
    }

    private static void verifyUnsupported(PackProbe.Analysis analysis) {
        PackPlan plan = analysis.plan();
        PackResourcePlan resources = plan.resources();
        PackResourceBinding missing = resources.binding("composite", "missing");
        PackResourceBinding unsafe = resources.binding("composite", "unsafe");
        // Iris skips a texture it cannot read: the name then reads unit 0, colortex0 in a pass.
        assertTrue(missing == null || missing.status() != PackResourceStatus.UNAVAILABLE,
                "a missing texture still refused its program: " + missing);
        assertTrue(analysis.report().deviations().contains("PACK_TEXTURE_DROPPED:customTexture.missing"),
                "the dropped texture was not reported: " + analysis.report().deviations());
        assertTrue(unsafe != null && unsafe.deviations().stream().anyMatch(value ->
                        value.startsWith("PACK_TEXTURE_PATH_UNSAFE:")),
                "unsafe path was not named");
        String reason = resources.unavailableReason("composite");
        assertTrue(resources.bindings("composite").stream()
                        .filter(binding -> !binding.available())
                        .anyMatch(binding -> binding.deviations().contains(reason)),
                "resource failure reason must identify an unavailable binding");
        assertTrue(!plan.shouldAttempt("composite"),
                "dependent program was not rejected");
        assertTrue(plan.shouldAttempt("composite1") && plan.shouldAttempt("final"),
                "unrelated valid program was rejected");
        assertEquals(plan.shouldAttempt("composite"),
                analysis.report().shouldAttempt("composite"),
                "unsupported composite report and plan eligibility disagree");
        assertEquals(plan.shouldAttempt("composite1"),
                analysis.report().shouldAttempt("composite1"),
                "unsupported report and plan eligibility disagree");
    }

    private static void verifyTargetAliasAllocation(PackProbe.Analysis analysis) {
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                analysis.plan().programs(), analysis.config(), analysis.plan().resources(),
                2560, 1440, 8, 16384);
        assertTrue(graph.target(4) != null,
                "gaux1 did not allocate canonical colortex4");
        TargetStep composite = graph.step("composite");
        assertTrue(composite != null && composite.readTargets().contains(4),
                "gaux1 did not schedule a colortex4 read");
    }

    /**
     * Iris preprocesses shaders.properties as C, so a raw texture's size may be a macro the file
     * defines: Alpha Piscium sizes customTexture.usam_restirReuseTex0 with RESTIR_REUSE_TEX_WIDTH.
     */
    @Test
    void resourceValuesReadPropertyMacros(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                #define REUSE_SIZE 2
                customTexture.reuse = reuse.bin TEXTURE_2D RG8UI REUSE_SIZE REUSE_SIZE RG_INTEGER UNSIGNED_BYTE
                """);
        Files.write(shaders.resolve("reuse.bin"), new byte[8]);
        Files.writeString(shaders.resolve("composite.fsh"), "#version 130\nuniform usampler2D reuse;\n"
                + "void main() {\n/* RENDERTARGETS: 0 */\n gl_FragData[0] = vec4(texture(reuse, vec2(0.5)));\n}\n");
        PackPlan plan = PackProbe.analyze(root).plan();
        assertEquals("reuse.bin TEXTURE_2D RG8UI 2 2 RG_INTEGER UNSIGNED_BYTE",
                plan.settings().resourceDeclarations().get("customTexture.reuse").source());
        PackResourceBinding binding = plan.resources().binding("composite", "reuse");
        assertEquals(PackResourceStatus.PACK_FILE, binding.status(), binding.deviations().toString());
    }

    /**
     * Iris applies a texture.<stage> entry only by replacing a registration (KNOW-801): a name nothing
     * registers keeps unit 0, which is colortex0 in a fullscreen pass. World programs register
     * colortex4 and up and the DH depth textures (IrisSamplers.addRenderTargetSamplers).
     */
    @Test
    void stageTexturesOnlyReplaceRegisteredNames(@TempDir Path root) throws IOException {
        assertEquals(IrisSamplerOrder.Source.TARGET, IrisSamplerOrder.POST.last("colortex1", false, false));
        assertEquals(null, IrisSamplerOrder.WORLD.last("colortex1", false, false), "colortex1 in gbuffers");
        assertEquals(null, IrisSamplerOrder.WORLD.last("gcolor", false, false), "gcolor in gbuffers");
        assertEquals(IrisSamplerOrder.Source.TARGET, IrisSamplerOrder.WORLD.last("gaux1", false, false));
        assertEquals(IrisSamplerOrder.Source.TARGET, IrisSamplerOrder.WORLD.last("dhDepthTex1", false, false));
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), "texture.composite.marker = marker.png\n");
        Files.write(shaders.resolve("marker.png"), new byte[8]);
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nuniform sampler2D colortex0;\n"
                + "uniform sampler2D marker;\nuniform sampler2D shadowtex0DH;\nvoid main() {\n/* RENDERTARGETS: 0 */\n"
                + " gl_FragData[0] = texture2D(colortex0, vec2(0.5)) + texture2D(marker, vec2(0.5))"
                + " + texture2D(shadowtex0DH, vec2(0.5));\n}\n");
        PackProgramPlan composite = PackProbe.analyze(root).plan().program("composite");
        assertTrue(composite.deviations().containsAll(List.of("SAMPLER_UNIT0_COLORTEX0:marker",
                "SAMPLER_UNIT0_COLORTEX0:shadowtex0DH")), composite.deviations().toString());
    }

    @Test
    void aliases() {
        assertEquals("colortex0", PackResourcePlan.canonicalResource("gcolor"),
                "gcolor alias");
        assertEquals("colortex3", PackResourcePlan.canonicalResource("composite"),
                "composite alias");
        assertEquals("colortex7", PackResourcePlan.canonicalResource("gaux4"),
                "gaux4 alias");
        assertEquals(4, PackResourcePlan.targetIndex("gaux1"),
                "gaux target index");
        // Iris texture stages: texture.<stage>.<sampler> covers numbered programs.
        assertEquals("deferred", PackResourcePlan.textureStage("deferred1"), "deferred stage");
        assertEquals("composite", PackResourcePlan.textureStage("composite5"), "composite stage");
        assertEquals("composite", PackResourcePlan.textureStage("final"), "final stage");
        assertEquals("shadowcomp", PackResourcePlan.textureStage("shadowcomp1"), "shadowcomp stage");
        assertEquals("gbuffers", PackResourcePlan.textureStage("gbuffers_water"), "gbuffers stage");
        assertEquals("gbuffers", PackResourcePlan.textureStage("shadow"), "shadow stage");
        assertEquals(null, PackResourcePlan.textureStage("deferred_extra"), "unknown stage");
    }

    private static void verifySnapshot(String snapshot) {
        assertTrue(!snapshot.contains("C:\\") && !snapshot.contains("/\\"),
                "resource snapshot contains an absolute path");
        assertTrue(!snapshot.toLowerCase().contains("timestamp"),
                "resource snapshot contains unstable metadata");
    }

    private static void verifyZipEquivalence(Path directory, String expectedResourceFingerprint)
            throws IOException {
        Path archive = Files.createTempFile("chimera-m75-resources-", ".zip");
        try {
            try (OutputStream output = Files.newOutputStream(archive);
                 ZipOutputStream zip = new ZipOutputStream(output)) {
                List<Path> files;
                try (var stream = Files.walk(directory.resolve("shaders"))) {
                    files = stream.filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(path ->
                                    directory.resolve("shaders").relativize(path).toString()))
                            .toList();
                }
                Path shaderRoot = directory.resolve("shaders");
                for (Path file : files) {
                    String name = "shaders/" + shaderRoot.relativize(file)
                            .toString().replace('\\', '/');
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(Files.readAllBytes(file));
                    zip.closeEntry();
                }
            }
            PackProbe.Analysis zipped = PackProbe.analyze(archive);
            assertEquals(expectedResourceFingerprint, zipped.plan().resources().fingerprint(),
                    "directory and ZIP resource fingerprints differ");
            assertTrue(zipped.plan().shouldAttempt("composite"),
                    "ZIP resource program was rejected");
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    private static void verifyBaseline(
            Path baseline,
            PackProbe.Analysis supported,
            PackProbe.Analysis unsupported
    ) throws IOException {
        String expected = Files.readString(baseline, StandardCharsets.UTF_8).trim();
        assertTrue(expected.contains(supported.report().sha256()),
                "supported report baseline mismatch: " + supported.report().sha256());
        assertTrue(expected.contains(supported.plan().resources().fingerprint()),
                "supported resource baseline mismatch: " + supported.plan().resources().fingerprint());
        assertTrue(expected.contains(unsupported.report().sha256()),
                "unsupported report baseline mismatch: " + unsupported.report().sha256());
        assertTrue(expected.contains(unsupported.plan().resources().fingerprint()),
                "unsupported resource baseline mismatch: " + unsupported.plan().resources().fingerprint());
    }

    /**
     * PackResourceOwner makes one image per resource key, so every loaded binding under a key must ask
     * for the same texture: Spring's declared noise once read as the generated one (audit FD1). A
     * binding that is not loaded does not count.
     */
    @Test
    void aResourceKeyNamesOneTexture() {
        PackResourceBinding composite = noise("composite", "tex/a.png", "nearest", PackResourceStatus.PACK_FILE);
        PackResourceBinding deferred = noise("deferred", "tex/a.png", "nearest", PackResourceStatus.PACK_FILE);
        assertEquals(Set.of(), PackResourcePlan.keyConflicts(List.of(composite, deferred)));
        assertEquals(Set.of("noisetex"), PackResourcePlan.keyConflicts(
                List.of(composite, noise("deferred", "tex/b.png", "nearest", PackResourceStatus.PACK_FILE))));
        assertEquals(Set.of("noisetex"), PackResourcePlan.keyConflicts(
                List.of(composite, noise("deferred", "tex/a.png", "linear", PackResourceStatus.PACK_FILE))));
        assertEquals(Set.of(), PackResourcePlan.keyConflicts(
                List.of(composite, noise("deferred", "tex/b.png", "nearest", PackResourceStatus.UNAVAILABLE))));
        // The generated noise and a game texture are made or looked up by key too.
        PackResourceBinding generated = noise("final", "generated-noise:256", "linear", PackResourceStatus.GENERATED);
        assertEquals(Set.of("noisetex"), PackResourcePlan.keyConflicts(List.of(composite, generated)));
        PackResourceBinding game = noise("final", "minecraft:textures/a.png", "nearest",
                PackResourceStatus.GAME_RESOURCE);
        assertEquals(Set.of("noisetex"), PackResourcePlan.keyConflicts(List.of(composite, game)));
        PackResourceBinding clamped = new PackResourceBinding("final", "noisetex", "noisetex", PackResourceKind.NOISE,
                "tex/a.png", 7, "nearest", "clamp", PackResourceStatus.PACK_FILE, List.of());
        assertEquals(Set.of("noisetex"), PackResourcePlan.keyConflicts(List.of(composite, clamped)));
        PackResourceBinding asTexture = new PackResourceBinding("final", "noisetex", "noisetex",
                PackResourceKind.PACK_TEXTURE, "tex/a.png", 7, "nearest", "repeat", PackResourceStatus.PACK_FILE,
                List.of());
        assertEquals(Set.of("noisetex"), PackResourcePlan.keyConflicts(List.of(composite, asTexture)));
    }

    private static PackResourceBinding noise(String program, String source, String filter, PackResourceStatus status) {
        return new PackResourceBinding(program, "noisetex", "noisetex", PackResourceKind.NOISE, source, 7, filter,
                "repeat", status, List.of());
    }
}
