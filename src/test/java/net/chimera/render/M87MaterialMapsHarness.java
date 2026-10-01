package net.chimera.render;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.imageio.ImageIO;

import net.vulkanmod.vulkan.texture.VulkanImage;

/**
 * M8.7 material-map gates from the checked-in mini resource pack: pixel
 * policies, exact layout and padding, sibling mapping, labPBR detection,
 * per-sprite failure isolation, descriptor resolution and rollback, simple
 * entity textures, reload generations, and animated maps (schedule,
 * interpolation, lockstep ticking, mip and padding rewrites). Everything
 * runs without natives or a GPU: PNGs decode through ImageIO and
 * companions land in a recording fake factory.
 */
public final class M87MaterialMapsHarness {
    private M87MaterialMapsHarness() {}

    private static final Path FIXTURE = Path.of("src/test/resources/m87_material_pack");
    private static final int FALLBACK_NORMALS = 0xFFFF7F7F;
    private static final int FALLBACK_SPECULAR = 0x00000000;

    public static void main(String[] args) throws Exception {
        verifyPixelPolicies();
        verifySiblingMapping();
        verifyLabPbrDetection();
        verifyEntityFixturePlan();
        verifyOwnerBuildLinear();
        verifyOwnerBuildLabPbr();
        verifyPixelPadding();
        verifyPaddedTopology();
        verifyAsymmetricPaddingIsolated();
        verifyFailureIsolation();
        verifyReplacementAndLifecycle();
        verifyResolveDrawMaterial();
        verifyTwoAtlasDispatch();
        verifyOwnedMaterialSamplers();
        verifySimpleSamplerClamp();
        verifyFirstTerrainDrawWins();
        verifyTransactionRollback();
        verifySimpleTextures();
        verifyGenerations();
        verifyAnimationSchedules();
        verifyAnimatedCompanions();
        verifyInterpolatedCompanion();
        verifyAnimationLockstep();
        System.out.println("[chimera] m8.7 material maps conformance: PASS");
    }

    // ------------------------------------------------------------------
    // Entity fixture: a supported gbuffers_entities program plans material maps
    // ------------------------------------------------------------------

    private static void verifyEntityFixturePlan() throws Exception {
        Path root = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        net.chimera.shaderpack.PackProbe.Analysis analysis =
                net.chimera.shaderpack.PackProbe.analyze(root.resolve("m87_entity"));
        net.chimera.shaderpack.PackProgramPlan entities =
                analysis.plan().program("gbuffers_entities");
        assertTrue(entities != null, "entity fixture program is missing");
        Map<String, Integer> slots = new java.util.TreeMap<>();
        entities.interfacePlan()
                .effective(net.chimera.shaderpack.UniformRegistry.Stage.ENTITY).samplers()
                .forEach(value -> slots.put(value.name(), value.slot()));
        assertEquals(25, slots.get("normals"), "entity fixture normals slot");
        assertEquals(26, slots.get("specular"), "entity fixture specular slot");

        List<net.chimera.shaderpack.PackResourceBinding> bindings =
                analysis.plan().resources().bindings("gbuffers_entities");
        for (String sampler : List.of("normals", "specular")) {
            var binding = bindings.stream().filter(value -> value.sampler().equals(sampler))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("missing entity binding " + sampler));
            assertTrue(binding.kind() == net.chimera.shaderpack.PackResourceKind.MATERIAL_MAP
                    && binding.status() == net.chimera.shaderpack.PackResourceStatus.MATERIAL_MAP
                    && binding.available(), "entity binding is not servable: " + sampler);
        }
        assertTrue(entities.deviations().stream().noneMatch(value ->
                        value.contains("MATERIAL_MAP_DEFERRED") || value.contains("NOT_MAPPED")),
                "entity material deviations: " + entities.deviations());
    }

    // ------------------------------------------------------------------
    // Pixel policies
    // ------------------------------------------------------------------

    private static void verifyPixelPolicies() throws Exception {
        int[] lab = readFixtureAbgr("assets/minecraft/textures/block/m87_lab_s.png");
        assertEquals(4 * 4, lab.length, "lab fixture size");

        int[] level1 = MaterialMapPixels.reduceLabPbr(lab, 4, 4, 2, 2);
        assertEquals(pack(255, 10, 200, 25), level1[0], "lab quad 0,0");
        assertEquals(pack(255, 70, 0, 100), level1[1], "lab quad 1,0");
        assertEquals(pack(254, 64, 229, 77), level1[2], "lab quad 0,1");
        // Bottom-right quad holds four distinct green classes (229, 230,
        // 231, 255): the four-way tie keeps the earliest texel's own metal
        // ID instead of merging into a third value.
        assertEquals(pack(255, 110, 230, 6), level1[3], "lab quad split metals stay discrete");

        // Two distinct metal IDs never average into a third: 231 wins the
        // majority outright here, and the earliest ID wins a pure metal tie.
        int[] metals = {
                pack(255, 0, 231, 10), pack(255, 0, 235, 20),
                pack(255, 0, 231, 30), pack(255, 0, 200, 40),
        };
        assertEquals(231, MaterialMapPixels.green(
                MaterialMapPixels.reduceLabPbr(metals, 2, 2, 1, 1)[0]), "metal majority");
        int[] metalTie = {
                pack(255, 0, 231, 10), pack(255, 0, 235, 20),
                pack(255, 0, 231, 30), pack(255, 0, 235, 40),
        };
        assertEquals(231, MaterialMapPixels.green(
                MaterialMapPixels.reduceLabPbr(metalTie, 2, 2, 1, 1)[0]), "metal tie keeps earliest");

        // Asymmetric reduction averages the whole footprint: a 4x4 source
        // with distinct texels per 2x2 quad must not select top-left texels.
        int[] blocks = new int[16];
        for (int i = 0; i < 16; i++) {
            blocks[i] = pack(255, i, i, i);
        }
        int[] small = MaterialMapPixels.scale(blocks, 4, 4, 2, 2);
        assertEquals(pack(255, 2, 2, 2), small[0], "weighted reduction averages");
        assertEquals(pack(255, 12, 12, 12), small[3], "weighted reduction far quad");

        int[] level2 = MaterialMapPixels.reduceLabPbr(level1, 2, 2, 1, 1);
        assertEquals(pack(255, 37, 143, 52), level2[0], "lab quad final");

        int[] quad = {
                pack(30, 20, 10, 0), pack(70, 60, 50, 40),
                pack(110, 100, 90, 80), pack(150, 140, 130, 120),
        };
        int[] mean = MaterialMapPixels.reduceLinear(quad, 2, 2, 1, 1);
        assertEquals(pack(90, 80, 70, 60), mean[0], "linear mean");

        int[] brickN = readFixtureAbgr("assets/minecraft/textures/block/m87_brick_n.png");
        int[] down = MaterialMapPixels.scale(brickN, 32, 32, 16, 16);
        assertEquals(pack(255, 200, 100, 0), down[0], "point downscale origin");
        assertEquals(pack(255, 193, 105, 87), down[7 * 16 + 5], "point downscale interior");

        int[] brickS = readFixtureAbgr("assets/minecraft/textures/block/m87_brick_s.png");
        int[] up = MaterialMapPixels.scale(brickS, 8, 8, 16, 16);
        assertEquals(pack(255, 128, 0, 0), up[0], "point upscale origin");
        assertEquals(pack(255, 128, 32, 32), up[2 * 16 + 2], "point upscale interior");
        assertEquals(pack(255, 128, 32, 32), up[3 * 16 + 3], "point upscale replication");

        int[] tiny = {pack(255, 0, 0, 10), pack(255, 0, 0, 20), pack(255, 0, 0, 30), pack(255, 0, 0, 40)};
        int[] grown = MaterialMapPixels.scale(tiny, 2, 2, 3, 3);
        assertEquals(9, grown.length, "box upscale size");
        assertEquals(tiny[0], grown[0], "box upscale corner");
        assertEquals(tiny[3], grown[8], "box upscale far corner");
        assertEquals(tiny[0], grown[4], "box upscale center");

        int[] identical = MaterialMapPixels.scale(tiny, 2, 2, 2, 2);
        assertTrue(identical != tiny && java.util.Arrays.equals(identical, tiny), "identical copy");

        assertEquals(8, MaterialMapPixels.levelSize(64, 3), "level size");
        assertEquals(1, MaterialMapPixels.levelSize(4, 3), "level size floor");

        boolean sized = false;
        try {
            MaterialMapPixels.checkSize(0, 5, "zero");
        } catch (IllegalArgumentException expected) {
            sized = true;
        }
        assertTrue(sized, "zero size did not fail");
    }

    // ------------------------------------------------------------------
    // Mapping and detection
    // ------------------------------------------------------------------

    private static void verifySiblingMapping() {
        assertEquals("minecraft:textures/block/stone_n.png",
                MaterialMapPixels.siblingResource("minecraft", "block/stone", "_n"), "ordinary sibling");
        assertEquals("minecraft:textures/block/stone_s.png",
                MaterialMapPixels.siblingResource("minecraft", "block/stone", "_s"), "ordinary specular");
        assertEquals("minecraft:optifine/cit/ruby_sword_s.png",
                MaterialMapPixels.siblingResource("minecraft", "optifine/cit/ruby_sword", "_s"),
                "CIT sibling keeps its directory");
        assertEquals("minecraft:textures/entity/pig_n.png",
                MaterialMapPixels.siblingResource("minecraft", "entity/pig", "_n"),
                "entity sibling");
    }

    private static void verifyLabPbrDetection() throws Exception {
        try (InputStream fixture = Files.newInputStream(
                FIXTURE.resolve("assets/minecraft/optifine/texture.properties"))) {
            assertTrue(!MaterialMapPixels.detectLabPbr(fixture), "fixture is linear mode");
        }
        assertTrue(MaterialMapPixels.detectLabPbr(stream("format=lab-pbr\n")), "lab-pbr flag");
        assertTrue(MaterialMapPixels.detectLabPbr(stream("format=lab-pbr/1.1\n")), "lab-pbr versioned");
        assertTrue(!MaterialMapPixels.detectLabPbr(stream("format=old-pbr\n")), "other format");
        assertTrue(!MaterialMapPixels.detectLabPbr(stream("# empty\n")), "missing key");
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // Owner build: linear mode
    // ------------------------------------------------------------------

    private static MaterialMapOwner.AtlasUpload layout() {
        return new MaterialMapOwner.AtlasUpload("minecraft:blocks", 64, 64, 4, List.of(
                sprite("minecraft:block/m87_brick", 0, 0, 16, 16, 0, 0),
                sprite("minecraft:block/m87_edge", 16, 0, 16, 16, 0, 0),
                sprite("minecraft:block/m87_plain", 32, 0, 16, 16, 0, 0),
                sprite("minecraft:block/m87_lab", 48, 0, 4, 4, 0, 0)));
    }

    /**
     * Padded topology: brick owns slot (8,8,20,20) with a 2-texel border,
     * edge starts exactly where brick ends, plain is missing but keeps its
     * padded slot at fallback, lab rides a 1-texel border. Nothing may
     * write outside its own padded rectangle.
     */
    private static MaterialMapOwner.AtlasUpload layoutPadded() {
        return new MaterialMapOwner.AtlasUpload("minecraft:padded", 64, 64, 4, List.of(
                sprite("minecraft:block/m87_brick", 8, 8, 16, 16, 2, 2),
                sprite("minecraft:block/m87_edge", 28, 8, 16, 16, 2, 2),
                sprite("minecraft:block/m87_plain", 48, 8, 8, 8, 2, 2),
                sprite("minecraft:block/m87_lab", 8, 32, 4, 4, 1, 1)));
    }

    private static MaterialMapOwner.AtlasSprite sprite(String id, int x, int y, int w, int h) {
        return sprite(id, x, y, w, h, 0, 0);
    }

    private static MaterialMapOwner.AtlasSprite sprite(String id, int x, int y, int w, int h,
            int padX, int padY) {
        int separator = id.indexOf(':');
        return new MaterialMapOwner.AtlasSprite(id, id.substring(0, separator),
                id.substring(separator + 1), x, y, w, h, padX, padY);
    }

    private static Map<String, byte[]> fixtureFiles() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Files.walk(FIXTURE.resolve("assets")).filter(Files::isRegularFile).forEach(path -> {
            try {
                String relative = FIXTURE.resolve("assets").relativize(path).toString()
                        .replace('\\', '/');
                int slash = relative.indexOf('/');
                files.put(relative.substring(0, slash) + ":" + relative.substring(slash + 1),
                        Files.readAllBytes(path));
            } catch (IOException failure) {
                throw new RuntimeException(failure);
            }
        });
        return files;
    }

    private static MaterialMapOwner.ResourceLookup mapLookup(Map<String, byte[]> files) {
        return (namespace, path) -> Optional.ofNullable(files.get(namespace + ":" + path))
                .map(content -> (InputStream) new ByteArrayInputStream(content));
    }

    private static void verifyOwnerBuildLinear() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner = MaterialMapOwner.withImages(markerImage(), markerImage(), factory);
        VulkanImage base = markerImage();
        try {
            owner.noteAtlasUpload(layout(), base);
            owner.pumpPendingBuilds(mapLookup(files), M87MaterialMapsHarness::decodeImageIO);

            FakeSet normals = factory.set("minecraft:blocks",
                    MaterialMapOwner.MaterialMapKind.NORMALS);
            FakeSet specular = factory.set("minecraft:blocks",
                    MaterialMapOwner.MaterialMapKind.SPECULAR);
            assertEquals(64, normals.levels.get(0).width(), "normals width");
            assertEquals(4, normals.levels.size(), "normals levels");
            assertEquals(8, normals.levels.get(3).width(), "normals last level");

            int[] n0 = normals.levels.get(0).pixels();
            assertEquals(pack(255, 200, 100, 0), n0[0], "brick normals origin");
            assertEquals(pack(255, 193, 105, 87), n0[7 * 64 + 5], "brick normals interior");
            assertEquals(pack(255, 255, 255, 255), n0[16], "edge normals border");
            assertEquals(pack(255, 0, 0, 0), n0[8 * 64 + 24], "edge normals interior");
            assertEquals(FALLBACK_NORMALS, n0[8 * 64 + 40], "plain normals fallback");
            assertEquals(FALLBACK_NORMALS, n0[2 * 64 + 48], "lab normals fallback");
            // Zero padding means each sprite owns exactly its logical rect:
            // nothing replicates into gaps, rows below, or neighbor rects.
            assertEquals(pack(255, 195, 115, 245), n0[5 * 64 + 15], "brick rect keeps its edge");
            // (16,5) is the edge sprite's own white border, untouched by brick.
            assertEquals(pack(255, 255, 255, 255), n0[5 * 64 + 16], "edge rect keeps its border");
            assertEquals(FALLBACK_NORMALS, n0[32], "gap right of edge stays fallback");
            assertEquals(FALLBACK_NORMALS, n0[5 * 64 + 32], "gap holds no replicated edge");
            assertEquals(FALLBACK_NORMALS, n0[16 * 64 + 5], "row below brick stays fallback");
            assertEquals(FALLBACK_NORMALS, n0[16 * 64 + 16], "shared corner stays fallback");

            int[] n1 = normals.levels.get(1).pixels();
            assertEquals(32, normals.levels.get(1).width(), "normals level 1 width");
            assertEquals(pack(255, 199, 100, 8), n1[0], "brick normals mip");
            // Plain has no maps: its level-1 slot (16,0,8,8) stays fallback.
            assertEquals(FALLBACK_NORMALS, n1[2 * 32 + 20], "plain normals mip interior");

            int[] s0 = specular.levels.get(0).pixels();
            assertEquals(pack(255, 128, 0, 0), s0[0], "brick specular origin");
            assertEquals(pack(255, 128, 32, 32), s0[2 * 64 + 2], "brick specular interior");
            // in(7,1) is RGBA(224,32,128,255): asymmetric, catches channel swaps.
            assertEquals(FALLBACK_SPECULAR, s0[2 * 64 + 16], "no ring leaks past the rect");
            assertEquals(FALLBACK_SPECULAR, s0[8 * 64 + 40], "plain specular fallback");
            assertEquals(pack(255, 10, 200, 10), s0[48], "lab specular texel");

            int[] s1 = specular.levels.get(1).pixels();
            // Lab slot at level 1 is (24,0,2,2).
            assertEquals(pack(255, 10, 225, 25), s1[24], "lab specular linear mip");
            assertEquals(pack(191, 98, 236, 6), s1[32 + 25], "lab specular linear far quad");

            // Descriptors still answer flat fallbacks in Slice B.
            assertTrue(owner.snapshot("normals").image() != null, "flat dispatch alive");
            // The Slice C seam answers by exact albedo identity.
            assertTrue(owner.companionFor(base, "normals").image() == normals.image,
                    "companion by identity");
            assertTrue(owner.companionFor(markerImage(), "normals") == null,
                    "unknown base has no companion");
        } finally {
            owner.close();
        }
        assertTrue(factory.allClosed(), "close released every companion");
    }

    // ------------------------------------------------------------------
    // Owner build: labPBR mode
    // ------------------------------------------------------------------

    private static void verifyOwnerBuildLabPbr() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        files.put("minecraft:optifine/texture.properties", "format=lab-pbr\n".getBytes(StandardCharsets.UTF_8));
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner = MaterialMapOwner.withImages(markerImage(), markerImage(), factory);
        try {
            owner.noteAtlasUpload(layout(), markerImage());
            owner.pumpPendingBuilds(mapLookup(files), M87MaterialMapsHarness::decodeImageIO);
            FakeSet specular = factory.set("minecraft:blocks",
                    MaterialMapOwner.MaterialMapKind.SPECULAR);
            int[] s1 = specular.levels.get(1).pixels();
            assertEquals(pack(255, 10, 200, 25), s1[24], "lab quad keeps its class");
            assertEquals(pack(255, 70, 0, 100), s1[25], "lab quad majority class");
            assertEquals(pack(254, 64, 229, 77), s1[32 + 24], "lab quad uniform class");
            assertEquals(pack(255, 110, 230, 6), s1[32 + 25], "lab quad split metals stay discrete");
            int[] s2 = specular.levels.get(2).pixels();
            assertEquals(16, specular.levels.get(2).width(), "lab chain canvas width");
            // Lab slot at level 2 is (12,0,1,1).
            assertEquals(pack(255, 37, 143, 52), s2[12], "lab chain final texel");
            // Normals ignore labPBR and stay linear.
            FakeSet normals = factory.set("minecraft:blocks",
                    MaterialMapOwner.MaterialMapKind.NORMALS);
            assertEquals(pack(255, 199, 100, 8), normals.levels.get(1).pixels()[0],
                    "normals stay linear under labPBR");
        } finally {
            owner.close();
        }
    }

    // ------------------------------------------------------------------
    // Failure isolation, replacement, lifecycle
    // ------------------------------------------------------------------

    private static void verifyFailureIsolation() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        files.put("minecraft:textures/block/m87_edge_n.png", "not a png".getBytes(StandardCharsets.UTF_8));
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner = MaterialMapOwner.withImages(markerImage(), markerImage(), factory);
        try {
            owner.noteAtlasUpload(layout(), markerImage());
            owner.pumpPendingBuilds(mapLookup(files), M87MaterialMapsHarness::decodeImageIO);
            FakeSet normals = factory.set("minecraft:blocks",
                    MaterialMapOwner.MaterialMapKind.NORMALS);
            int[] n0 = normals.levels.get(0).pixels();
            assertEquals(FALLBACK_NORMALS, n0[8 * 64 + 24], "bad sprite stays fallback");
            assertEquals(pack(255, 200, 100, 0), n0[0], "good sprite survives a bad sibling");
        } finally {
            owner.close();
        }
    }

    private static void verifyReplacementAndLifecycle() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner = MaterialMapOwner.withImages(markerImage(), markerImage(), factory);
        VulkanImage baseA = markerImage();
        VulkanImage baseB = markerImage();
        try {
            // Empty and degenerate uploads never reach the factory.
            owner.noteAtlasUpload(new MaterialMapOwner.AtlasUpload("minecraft:empty", 64, 64, 1,
                    List.of()), baseA);
            owner.noteAtlasUpload(new MaterialMapOwner.AtlasUpload("minecraft:zero", 64, 64, 1,
                    List.of(sprite("minecraft:block/m87_brick", 0, 0, 0, 0))), baseA);
            // Latest upload per location wins.
            owner.noteAtlasUpload(layout(), baseA);
            owner.noteAtlasUpload(layout(), baseA);
            owner.pumpPendingBuilds(mapLookup(files), M87MaterialMapsHarness::decodeImageIO);
            assertEquals(1, factory.buildsFor("minecraft:blocks"), "latest upload wins");
            assertTrue(factory.set("minecraft:blocks",
                    MaterialMapOwner.MaterialMapKind.NORMALS) != null, "first build installed");
            FakeSet first = factory.set("minecraft:blocks",
                    MaterialMapOwner.MaterialMapKind.NORMALS);

            // A new base image retires the old companions.
            owner.noteAtlasUpload(layout(), baseB);
            owner.pumpPendingBuilds(mapLookup(files), M87MaterialMapsHarness::decodeImageIO);
            assertTrue(first.closed, "replacement closed the old companions");
            assertTrue(owner.companionFor(baseA, "normals") == null, "old base no longer resolves");
            assertTrue(owner.companionFor(baseB, "normals") != null, "new base resolves");

            // Post-close pumps and notes are silent no-ops.
            owner.close();
            owner.close();
            owner.noteAtlasUpload(layout(), markerImage());
            owner.pumpPendingBuilds(mapLookup(files), M87MaterialMapsHarness::decodeImageIO);
            assertEquals(2, factory.buildsFor("minecraft:blocks"), "no build after close");
        } finally {
            owner.close();
        }
        assertTrue(factory.allClosed(), "shutdown closed every companion");
    }

    // ------------------------------------------------------------------
    // Draw-time resolution and sampler overrides
    // ------------------------------------------------------------------

    private static DrawMaterialContext contextFor(VulkanImage base, long samplerId) {
        return new DrawMaterialContext(base, SamplerProfile.ofId(samplerId));
    }

    private static void verifyResolveDrawMaterial() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        FakeFactory factory = new FakeFactory();
        FakeSamplers samplers = new FakeSamplers();
        VulkanImage flatNormals = markerImage();
        VulkanImage flatSpecular = markerImage();
        MaterialMapOwner owner =
                MaterialMapOwner.withImages(flatNormals, flatSpecular, factory, samplers);
        VulkanImage base = markerImage();
        try {
            // Pre-pump draws answer flat fallbacks, exactly like Slice B.
            var flat = owner.resolveDrawMaterial(contextFor(base, 0x7777L), "normals");
            assertTrue(flat.image() == flatNormals, "unresolved base is not flat");
            assertEquals(0x7777L, flat.samplerOverride(), "context sampler lost");

            boolean rejected = false;
            try {
                owner.resolveDrawMaterial(contextFor(base, 0x7777L), "bogus");
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            assertTrue(rejected, "unknown material name did not fail loudly");
            assertTrue(owner.resolveDrawMaterial(new DrawMaterialContext(null, null), "normals").image()
                            == flatNormals,
                    "null base is not flat");

            // Linear fixtures resolve with the captured context sampler.
            owner.noteAtlasUpload(layout(), base);
            owner.pumpPendingBuilds(mapLookup(files), M87MaterialMapsHarness::decodeImageIO);
            var resolved = owner.resolveDrawMaterial(contextFor(base, 0x7777L), "normals");
            assertTrue(resolved.image() == factory.set("minecraft:blocks",
                    MaterialMapOwner.MaterialMapKind.NORMALS).image,
                    "context draw missed its companion");
            assertEquals(0x7777L, resolved.samplerOverride(), "context sampler override lost");

            // Unmarked image samplers fall back to the albedo image sampler.
            var unmarked = owner.resolveDrawMaterial(contextFor(base, 0L), "specular");
            assertTrue(unmarked.image() == factory.set("minecraft:blocks",
                    MaterialMapOwner.MaterialMapKind.SPECULAR).image,
                    "unmarked draw missed its companion");
            assertEquals(base.getSampler(), unmarked.samplerOverride(), "albedo sampler fallback lost");
        } finally {
            ChimeraTextureBindingState.reset();
            owner.close();
        }
    }

    /**
     * Two atlases built side by side: each draw resolves the companion of
     * the exact albedo image it binds, never the other atlas's, and an
     * image neither atlas owns stays flat.
     */
    private static void verifyTwoAtlasDispatch() throws Exception {
        FakeFactory factory = new FakeFactory();
        VulkanImage flatNormals = markerImage();
        MaterialMapOwner owner = MaterialMapOwner.withImages(flatNormals, markerImage(), factory);
        MaterialMapOwner.ResourceLookup lookup = mapLookup(fixtureFiles());
        VulkanImage blocks = markerImage();
        VulkanImage animated = markerImage();
        try {
            owner.noteAtlasUpload(layout(), blocks);
            owner.noteAtlasUpload(layoutAnimated("minecraft:animated"), animated);
            owner.pumpPendingBuilds(lookup, M87MaterialMapsHarness::decodeImageIO);
            for (MaterialMapOwner.MaterialMapKind kind : MaterialMapOwner.MaterialMapKind.values()) {
                assertTrue(owner.resolveDrawMaterial(contextFor(blocks, 0L), kind.sampler()).image()
                                == factory.set("minecraft:blocks", kind).image,
                        "blocks draw missed its own " + kind.sampler());
                assertTrue(owner.resolveDrawMaterial(contextFor(animated, 0L), kind.sampler()).image()
                                == factory.set("minecraft:animated", kind).image,
                        "animated draw missed its own " + kind.sampler());
            }
            assertTrue(owner.resolveDrawMaterial(contextFor(markerImage(), 0L), "normals").image()
                    == flatNormals, "an unowned image did not stay flat");
        } finally {
            owner.close();
        }
    }
    private static final com.mojang.blaze3d.textures.AddressMode REPEAT =
            com.mojang.blaze3d.textures.AddressMode.REPEAT;
    private static final com.mojang.blaze3d.textures.FilterMode LINEAR =
            com.mojang.blaze3d.textures.FilterMode.LINEAR;
    private static final com.mojang.blaze3d.textures.FilterMode NEAREST =
            com.mojang.blaze3d.textures.FilterMode.NEAREST;

    private static DrawMaterialContext profiledContext(VulkanImage base, float maxLod) {
        return new DrawMaterialContext(base,
                new SamplerProfile(0x7777L, REPEAT, REPEAT, LINEAR, LINEAR, 4, maxLod));
    }

    private static void verifyOwnedMaterialSamplers() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        files.put("minecraft:optifine/texture.properties", "format=lab-pbr\n".getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        FakeFactory factory = new FakeFactory();
        FakeSamplers samplers = new FakeSamplers();
        MaterialMapOwner owner =
                MaterialMapOwner.withImages(markerImage(), markerImage(), factory, samplers);
        VulkanImage base = markerImage();
        try {
            owner.noteAtlasUpload(layout(), base);
            owner.pumpPendingBuilds(mapLookup(files), M87MaterialMapsHarness::decodeImageIO);
            DrawMaterialContext lod4 = profiledContext(base, 4.0f);
            DrawMaterialContext lod8 = profiledContext(base, 8.0f);

            // Normals keep the exact albedo sampler ID and own nothing.
            var normals = owner.resolveDrawMaterial(lod4, "normals");
            assertEquals(0x7777L, normals.samplerOverride(), "normals left the albedo sampler");
            assertTrue(samplers.requests.isEmpty(), "normals allocated a sampler");

            // Atlas labPBR specular derives NEAREST/NEAREST with the source ceiling.
            var specular4 = owner.resolveDrawMaterial(lod4, "specular");
            var specular8 = owner.resolveDrawMaterial(lod8, "specular");
            assertEquals(2, samplers.requests.size(), "max-LOD variants aliased in the cache");
            FakeSamplers.Request first = samplers.requests.get(0);
            assertEquals(NEAREST, first.minFilter(), "labPBR min filter");
            assertEquals(NEAREST, first.magFilter(), "labPBR mag filter");
            assertEquals(REPEAT, first.addressU(), "labPBR address U");
            assertEquals(REPEAT, first.addressV(), "labPBR address V");
            assertEquals(4, first.anisotropy(), "labPBR anisotropy");
            assertEquals(4.0f, first.maxLod(), "labPBR max LOD not preserved");
            assertEquals(8.0f, samplers.requests.get(1).maxLod(), "second ceiling not preserved");
            assertTrue(specular4.samplerOverride() != specular8.samplerOverride(),
                    "different ceilings shared one sampler");

            // Reuse: the same profile never builds twice.
            owner.resolveDrawMaterial(lod4, "specular");
            assertEquals(2, samplers.requests.size(), "identical profile rebuilt");

            // Incomplete profiles cannot derive: the base ID wins instead.
            var incomplete = owner.resolveDrawMaterial(
                    new DrawMaterialContext(base, SamplerProfile.ofId(0x7777L)), "specular");
            assertEquals(0x7777L, incomplete.samplerOverride(), "incomplete profile derived");
            assertEquals(2, samplers.requests.size(), "incomplete profile allocated");
        } finally {
            owner.close();
        }
        assertTrue(samplers.allClosed(), "shutdown closed owned samplers");
    }

    private static void verifySimpleSamplerClamp() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        files.put("minecraft:optifine/texture.properties", "format=lab-pbr\n".getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        byte[] mobN = Files.readAllBytes(
                FIXTURE.resolve("assets/minecraft/textures/block/m87_brick_n.png"));
        byte[] mobS = Files.readAllBytes(
                FIXTURE.resolve("assets/minecraft/textures/block/m87_brick_s.png"));
        files.put("minecraft:textures/entity/m87_mob_n.png", mobN);
        files.put("minecraft:textures/entity/m87_mob_s.png", mobS);
        FakeFactory factory = new FakeFactory();
        FakeSamplers samplers = new FakeSamplers();
        MaterialMapOwner owner =
                MaterialMapOwner.withImages(markerImage(), markerImage(), factory, samplers);
        VulkanImage mob = markerImage();
        MaterialMapOwner.SimpleTextureSource source = () -> List.of(
                new MaterialMapOwner.SimpleEntry(mob, "minecraft",
                        SimpleTextureIndex.relativeSpritePath("textures/entity/m87_mob.png")));
        try {
            owner.resolveDrawMaterial(contextFor(mob, 0L), "normals");
            owner.pumpPendingBuilds(mapLookup(files), M87MaterialMapsHarness::decodeImageIO, source);
            DrawMaterialContext context = profiledContext(mob, 4.0f);
            // Simple normals keep the source policy but clamp to LOD zero.
            var normals = owner.resolveDrawMaterial(context, "normals");
            // Simple labPBR specular filters NEAREST and clamps to LOD zero.
            var specular = owner.resolveDrawMaterial(context, "specular");
            assertEquals(2, samplers.requests.size(), "simple maps did not derive two samplers");
            FakeSamplers.Request normalRequest = samplers.requests.get(0);
            assertEquals(LINEAR, normalRequest.minFilter(), "simple normals min filter");
            assertEquals(LINEAR, normalRequest.magFilter(), "simple normals mag filter");
            assertEquals(REPEAT, normalRequest.addressU(), "simple normals address");
            assertEquals(0.0f, normalRequest.maxLod(), "simple normals LOD not clamped");
            FakeSamplers.Request specularRequest = samplers.requests.get(1);
            assertEquals(NEAREST, specularRequest.minFilter(), "simple specular min filter");
            assertEquals(NEAREST, specularRequest.magFilter(), "simple specular mag filter");
            assertEquals(0.0f, specularRequest.maxLod(), "simple specular LOD not clamped");
            assertTrue(normals.samplerOverride() != specular.samplerOverride(),
                    "simple kinds shared one sampler");
        } finally {
            owner.close();
        }
        assertTrue(samplers.allClosed(), "shutdown closed simple samplers");
    }

    /**
     * The first-draw race, directed: an entity draw marks slot 0 with its
     * own sampler, then the very first terrain transaction must still bind
     * all three terrain resources with the chunk profile, because terrain
     * resolves from its captured context and never re-queries slot state.
     */
    private static void verifyFirstTerrainDrawWins() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        FakeFactory factory = new FakeFactory();
        FakeSamplers samplers = new FakeSamplers();
        MaterialMapOwner owner =
                MaterialMapOwner.withImages(markerImage(), markerImage(), factory, samplers);
        VulkanImage atlasBase = markerImage();
        VulkanImage entityBase = markerImage();
        try {
            owner.noteAtlasUpload(layout(), atlasBase);
            owner.pumpPendingBuilds(mapLookup(files), M87MaterialMapsHarness::decodeImageIO);
            // Entity batch first: slot 0 now names a foreign image.
            ChimeraTextureBindingState.markPackBinding(0, entityBase, 0x5555L);
            DrawMaterialContext terrain = new DrawMaterialContext(atlasBase,
                    new SamplerProfile(0x7777L,
                            com.mojang.blaze3d.textures.AddressMode.REPEAT,
                            com.mojang.blaze3d.textures.AddressMode.REPEAT,
                            com.mojang.blaze3d.textures.FilterMode.LINEAR,
                            com.mojang.blaze3d.textures.FilterMode.LINEAR, 4, 4.0f));
            var normals = owner.resolveDrawMaterial(terrain, "normals");
            var specular = owner.resolveDrawMaterial(terrain, "specular");
            assertTrue(normals.image() == factory.set("minecraft:blocks",
                    MaterialMapOwner.MaterialMapKind.NORMALS).image,
                    "first terrain draw missed its normals");
            assertTrue(specular.image() == factory.set("minecraft:blocks",
                    MaterialMapOwner.MaterialMapKind.SPECULAR).image,
                    "first terrain draw missed its specular");
            assertEquals(0x7777L, normals.samplerOverride(), "first draw lost the chunk sampler");
            assertEquals(0x7777L, specular.samplerOverride(), "linear specular lost chunk sampler");
            assertTrue(ChimeraTextureBindingState.capture(0, atlasBase).samplerOverride() == null,
                    "slot state leaked into the verdict");
        } finally {
            ChimeraTextureBindingState.reset();
            owner.close();
        }
    }

    // ------------------------------------------------------------------
    // Transaction rollback around the resolver
    // ------------------------------------------------------------------

    private static void verifyTransactionRollback() {
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner =
                MaterialMapOwner.withImages(markerImage(), markerImage(), factory);
        VulkanImage base = markerImage();
        var prior = new ChimeraTextureBindingState.Snapshot(markerImage(), 123L);
        Map<Integer, ChimeraTextureBindingState.Snapshot> slots = new java.util.HashMap<>();
        slots.put(25, prior);
        List<String> events = new ArrayList<>();
        ProgramImageBindingTransaction.Store<ChimeraTextureBindingState.Snapshot> store =
                new ProgramImageBindingTransaction.Store<>() {
                    public ChimeraTextureBindingState.Snapshot capture(int slot) {
                        events.add("capture:" + slot);
                        return slots.get(slot);
                    }
                    public boolean available(ChimeraTextureBindingState.Snapshot value) {
                        return value != null && value.image() != null;
                    }
                    public void bind(int slot, ChimeraTextureBindingState.Snapshot value) {
                        events.add("bind:" + slot);
                        slots.put(slot, value);
                    }
                    public void restore(int slot, ChimeraTextureBindingState.Snapshot value) {
                        events.add("restore:" + slot);
                        if (value == null) slots.remove(slot);
                        else slots.put(slot, value);
                    }
                };
        var manifest = new net.chimera.shaderpack.ProgramImageBindingManifest(List.of(
                new net.chimera.shaderpack.ProgramImageBindingManifest.Entry(
                        "normals", 25, 1, 63,
                        net.chimera.shaderpack.ProgramImageBindingManifest.Kind.MATERIAL_MAP,
                        "normals", 0, 9, "normals"),
                new net.chimera.shaderpack.ProgramImageBindingManifest.Entry(
                        "broken", 26, 1, 63,
                        net.chimera.shaderpack.ProgramImageBindingManifest.Kind.MATERIAL_MAP,
                        "bogus", 0, 10, "bogus")));
        boolean failed = false;
        try {
            ProgramImageBindingTransaction.bind("slice-c-test", manifest, store,
                    entry -> owner.resolveDrawMaterial(contextFor(base, 0L), entry.resourceKey()));
        } catch (RuntimeException failure) {
            failed = failure.getMessage() != null
                    && failure.getMessage().startsWith("RESOURCE_BINDING_UNAVAILABLE:slice-c-test:");
        }
        assertTrue(failed, "resolver failure did not fail the transaction loudly");
        assertTrue(slots.get(25) == prior, "rollback did not restore slot 25");
        assertTrue(!slots.containsKey(26), "rollback left slot 26 bound");
        assertTrue(events.contains("restore:25") && events.contains("restore:26"),
                "rollback did not restore every slot: " + events);
        owner.close();
    }

    // ------------------------------------------------------------------
    // Simple textures: first frame flat, next frame bound
    // ------------------------------------------------------------------

    private static final class CountingLookup implements MaterialMapOwner.ResourceLookup {
        final MaterialMapOwner.ResourceLookup inner;
        int opens;
        int siblingOpens;

        CountingLookup(MaterialMapOwner.ResourceLookup inner) {
            this.inner = inner;
        }

        public Optional<InputStream> open(String namespace, String path) {
            opens++;
            if (!path.equals("optifine/texture.properties")) siblingOpens++;
            return inner.open(namespace, path);
        }
    }

    private static void verifySimpleTextures() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        CountingLookup lookup = new CountingLookup(mapLookup(files));
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner =
                MaterialMapOwner.withImages(markerImage(), markerImage(), factory);
        VulkanImage mob = markerImage();
        VulkanImage stranger = markerImage();
        MaterialMapOwner.SimpleTextureSource source = () -> List.of(
                new MaterialMapOwner.SimpleEntry(mob, "minecraft",
                        SimpleTextureIndex.relativeSpritePath("textures/entity/m87_mob.png")));
        assertEquals("entity/creeper",
                SimpleTextureIndex.relativeSpritePath("textures/entity/creeper.png"),
                "simple texture extension was not removed");
        assertEquals("entity/m87_mob",
                SimpleTextureIndex.relativeSpritePath("textures/entity/m87_mob"),
                "simple path is not atlas-relative");
        assertEquals("optifine/cit/ruby_sword",
                SimpleTextureIndex.relativeSpritePath("optifine/cit/ruby_sword"),
                "CIT path was rewritten");
        try {
            // First frame answers flat and queues the unknown image.
            assertTrue(owner.resolveDrawMaterial(contextFor(mob, 0L), "normals").image() != null,
                    "first frame has no fallback");
            assertTrue(owner.companionFor(mob, "normals") == null, "unknown image resolved early");

            owner.pumpPendingBuilds(lookup, M87MaterialMapsHarness::decodeImageIO, source);
            // One properties probe plus exactly the two sibling maps.
            assertEquals(3, lookup.opens, "simple decode did not read exactly three files");
            FakeSet normals = factory.set("minecraft:entity/m87_mob",
                    MaterialMapOwner.MaterialMapKind.NORMALS);
            assertEquals(1, normals.levels.size(), "simple companion is not single-level");
            assertEquals(8, normals.levels.get(0).width(), "simple companion width");
            assertEquals(pack(255, 250, 130, 120), normals.levels.get(0).pixels()[0],
                    "simple companion is not the checked-in entity map");

            // Next frame binds the entity's own map, not the block atlas.
            var resolved = owner.resolveDrawMaterial(contextFor(mob, 0L), "normals");
            assertTrue(resolved.image() == normals.image, "second frame missed its simple map");

            // Unknown images stay flat and are never re-scanned.
            assertTrue(owner.resolveDrawMaterial(contextFor(stranger, 0L), "normals").image() != null,
                    "stranger has no fallback");
            owner.pumpPendingBuilds(lookup, M87MaterialMapsHarness::decodeImageIO, source);
            int afterNegative = lookup.siblingOpens;
            owner.resolveDrawMaterial(contextFor(mob, 0L), "specular");
            owner.resolveDrawMaterial(contextFor(stranger, 0L), "specular");
            owner.pumpPendingBuilds(lookup, M87MaterialMapsHarness::decodeImageIO, source);
            assertEquals(afterNegative, lookup.siblingOpens, "negative cache did not hold");
        } finally {
            owner.close();
        }
        assertTrue(factory.allClosed(), "shutdown closed every simple companion");
    }

    // ------------------------------------------------------------------
    // Reload generations
    // ------------------------------------------------------------------

    private static void verifyGenerations() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        CountingLookup lookup = new CountingLookup(mapLookup(files));
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner =
                MaterialMapOwner.withImages(markerImage(), markerImage(), factory);
        VulkanImage baseA = markerImage();
        VulkanImage baseB = markerImage();
        VulkanImage mob = markerImage();
        MaterialMapOwner.SimpleTextureSource source = () -> List.of(
                new MaterialMapOwner.SimpleEntry(mob, "minecraft",
                        SimpleTextureIndex.relativeSpritePath("textures/entity/m87_mob.png")));
        try {
            owner.noteAtlasUpload(layout(), baseA);
            owner.resolveDrawMaterial(contextFor(mob, 0L), "normals");
            owner.pumpPendingBuilds(lookup, M87MaterialMapsHarness::decodeImageIO, source);
            assertTrue(owner.companionFor(baseA, "normals") != null, "generation A did not resolve");
            assertTrue(owner.companionFor(mob, "normals") != null, "simple map did not resolve");
            FakeSet setA = factory.set("minecraft:blocks",
                    MaterialMapOwner.MaterialMapKind.NORMALS);
            FakeSet setMob = factory.set("minecraft:entity/m87_mob",
                    MaterialMapOwner.MaterialMapKind.NORMALS);

            var listener = new ChimeraMaterialReloadListener(() -> owner);
            listener.apply(0, null, null);
            assertEquals(1, owner.generation(), "first bump is not generation 1");
            // Atlas companions survive the bump: the reload's re-stitch
            // displaces them, and the bump lands after fresh builds.
            assertTrue(owner.companionFor(baseA, "normals") != null, "bump retired atlas A");
            assertTrue(!setA.closed, "bump closed atlas A");
            // Simple identities die with the TextureManager swap.
            assertTrue(owner.companionFor(mob, "normals") == null, "bump kept simple resolving");
            owner.pumpPendingBuilds(lookup, M87MaterialMapsHarness::decodeImageIO, source);
            assertTrue(setMob.closed, "retired simple set was not closed");
            assertTrue(!setA.closed, "pump closed surviving atlas A");

            // A fresh upload for the same location displaces the old base.
            owner.noteAtlasUpload(layout(), baseB);
            owner.pumpPendingBuilds(lookup, M87MaterialMapsHarness::decodeImageIO, source);
            assertTrue(setA.closed, "displacement did not close atlas A");
            assertTrue(owner.companionFor(baseA, "normals") == null, "old base still resolves");
            assertTrue(owner.companionFor(baseB, "normals") != null, "new base does not resolve");
            listener.apply(0, null, null);
            assertEquals(2, owner.generation(), "second bump is not generation 2");

            // The live listener tolerates chimera being idle.
            ChimeraMaterialReloadListener.live().apply(0, null, null);
        } finally {
            owner.close();
        }
        assertTrue(factory.allClosed(), "shutdown closed every generation");
    }

    // ------------------------------------------------------------------
    // Padded topology: content at slot + padding, borders replicate
    // ------------------------------------------------------------------

    private static void verifyPixelPadding() {
        int[] logical = {
                pack(255, 0, 0, 1), pack(255, 0, 0, 2),
                pack(255, 0, 0, 3), pack(255, 0, 0, 4),
        };
        int[] padded = MaterialMapPixels.padReplicate(logical, 2, 2, 1);
        assertEquals(16, padded.length, "padded size");
        assertEquals(logical[0], padded[0], "padded corner replicates");
        assertEquals(logical[1], padded[3], "padded far corner replicates");
        assertEquals(logical[3], padded[15], "padded bottom corner replicates");
        assertEquals(logical[0], padded[5], "padded content origin");
        assertEquals(logical[3], padded[10], "padded content far texel");
        int[] same = MaterialMapPixels.padReplicate(logical, 2, 2, 0);
        assertTrue(same != logical && java.util.Arrays.equals(same, logical), "zero pad copies");

        assertEquals(2, MaterialMapPixels.recoverPad(10.0f / 64.0f, 64, 8), "padding recovery");
        assertEquals(2, MaterialMapPixels.recoverPad(0.5f, 64, 30), "padding recovery halfway");
        assertEquals(0, MaterialMapPixels.recoverPad(0.0f, 64, 0), "no padding");
        assertEquals(0, MaterialMapPixels.recoverPad(0.0f, 64, 5), "negative padding clamps");
    }

    private static void verifyPaddedTopology() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        CountingLookup lookup = new CountingLookup(mapLookup(files));
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner =
                MaterialMapOwner.withImages(markerImage(), markerImage(), factory);
        try {
            owner.noteAtlasUpload(layoutPadded(), markerImage());
            owner.pumpPendingBuilds(lookup, M87MaterialMapsHarness::decodeImageIO);
            FakeSet normals = factory.set("minecraft:padded",
                    MaterialMapOwner.MaterialMapKind.NORMALS);
            int[] n0 = normals.levels.get(0).pixels();

            // Brick content begins at slot + padding, borders replicate edges.
            assertEquals(pack(255, 200, 100, 0), n0[10 * 64 + 10], "padded content origin");
            assertEquals(n0[10 * 64 + 10], n0[8 * 64 + 8], "padded corner replicates");
            assertEquals(pack(255, 200, 115, 240), n0[10 * 64 + 27], "padded far border replicates");
            // Adjacent padded slots meet without overwriting each other.
            assertEquals(pack(255, 255, 255, 255), n0[10 * 64 + 28], "adjacent slot keeps its border");
            // Missing sprites leave the whole padded slot at fallback.
            assertEquals(FALLBACK_NORMALS, n0[10 * 64 + 50], "missing content stays fallback");
            assertEquals(FALLBACK_NORMALS, n0[8 * 64 + 48], "missing border stays fallback");
            assertEquals(FALLBACK_NORMALS, n0[19 * 64 + 59], "missing far corner stays fallback");
            // Nothing writes outside padded rectangles.
            assertEquals(FALLBACK_NORMALS, n0[0], "canvas corner stays fallback");
            assertEquals(FALLBACK_NORMALS, n0[63 * 64 + 63], "canvas far corner stays fallback");
            assertEquals(FALLBACK_NORMALS, n0[7 * 64 + 10], "row above slot stays fallback");

            // Padding shrinks through the mip chain: pad 1 at level 1, pad 0 at level 2.
            int[] n1 = normals.levels.get(1).pixels();
            assertEquals(32, normals.levels.get(1).width(), "padded level 1 width");
            assertEquals(pack(255, 199, 100, 8), n1[5 * 32 + 5], "shrunk content origin");
            assertEquals(n1[5 * 32 + 5], n1[4 * 32 + 4], "shrunk border replicates");
            int[] n2 = normals.levels.get(2).pixels();
            assertEquals(pack(255, 198, 101, 25), n2[2 * 16 + 2], "unpaded level 2 content");

            // Lab specular content rides its 1-texel border on its own canvas.
            FakeSet specular = factory.set("minecraft:padded",
                    MaterialMapOwner.MaterialMapKind.SPECULAR);
            int[] s0 = specular.levels.get(0).pixels();
            assertEquals(pack(255, 10, 200, 10), s0[33 * 64 + 9], "lab content origin");
            assertEquals(s0[33 * 64 + 9], s0[32 * 64 + 8], "lab border replicates");
            assertEquals(FALLBACK_SPECULAR, s0[32 * 64 + 14], "outside lab slot stays fallback");
        } finally {
            owner.close();
        }
    }

    private static void verifyAsymmetricPaddingIsolated() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        CountingLookup lookup = new CountingLookup(mapLookup(files));
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner =
                MaterialMapOwner.withImages(markerImage(), markerImage(), factory);
        try {
            int siblingBefore = lookup.siblingOpens;
            owner.noteAtlasUpload(new MaterialMapOwner.AtlasUpload("minecraft:skewed", 64, 64, 1,
                    List.of(sprite("minecraft:block/m87_brick", 0, 0, 16, 16, 2, 3))), markerImage());
            owner.pumpPendingBuilds(lookup, M87MaterialMapsHarness::decodeImageIO);
            assertEquals(siblingBefore, lookup.siblingOpens, "asymmetric sprite read files");
            assertTrue(factory.buildsFor("minecraft:skewed") == 0, "asymmetric sprite built");
        } finally {
            owner.close();
        }
    }

    // ------------------------------------------------------------------
    // Animated material maps
    // ------------------------------------------------------------------

    /**
     * Animated sprites, each map carrying its own table: anim_n cycles
     * 0 (2 ticks) -> 2 (3 ticks) -> 1 (1 tick) without interpolation;
     * blend_s interpolates two frames of 4 ticks; single_n names one frame
     * and so stays static; badmeta_n names a frame outside its strip and
     * baddims_n cannot be cut into frames, so both stay flat alone. Brick
     * is a static neighbour whose slot animation must never touch.
     */
    private static MaterialMapOwner.AtlasUpload layoutAnimated(String location) {
        return new MaterialMapOwner.AtlasUpload(location, 32, 32, 3, List.of(
                sprite("minecraft:block/m87_anim", 0, 0, 4, 4, 1, 1),
                sprite("minecraft:block/m87_blend", 8, 0, 4, 4, 1, 1),
                sprite("minecraft:block/m87_single", 16, 0, 4, 4, 0, 0),
                sprite("minecraft:block/m87_badmeta", 0, 8, 4, 4, 0, 0),
                sprite("minecraft:block/m87_baddims", 8, 8, 4, 4, 0, 0),
                sprite("minecraft:block/m87_brick", 16, 16, 16, 16, 0, 0)));
    }

    private static void verifyAnimationSchedules() throws Exception {
        String table = "{\"animation\":{\"frametime\":1,\"frames\":[{\"index\":0,\"time\":2},"
                + "{\"index\":2,\"time\":3},1]}}";
        MaterialAnimationSchedule schedule = MaterialAnimationSchedule.parse(table, 4, 12);
        assertEquals(List.of(new MaterialAnimation.Frame(0, 2), new MaterialAnimation.Frame(2, 3),
                new MaterialAnimation.Frame(1, 1)), schedule.frames(), "explicit table order and times");
        assertTrue(!schedule.interpolate() && schedule.animated(), "explicit table flags");
        assertEquals(4, schedule.frameHeight(), "square frame from the width");

        MaterialAnimationSchedule implicit = MaterialAnimationSchedule.parse(
                "{\"animation\":{\"interpolate\":true,\"frametime\":4}}", 4, 8);
        assertEquals(List.of(new MaterialAnimation.Frame(0, 4), new MaterialAnimation.Frame(1, 4)),
                implicit.frames(), "implicit table is every frame at frametime");
        assertTrue(implicit.interpolate(), "interpolate flag lost");

        MaterialAnimationSchedule wide = MaterialAnimationSchedule.parse(
                "{\"animation\":{\"width\":2,\"height\":4}}", 4, 8);
        assertEquals(4, wide.frameCount(8), "explicit frame size counts a grid");
        assertEquals(2, wide.columns(), "explicit frame size keeps two columns");

        assertTrue(MaterialAnimationSchedule.parse("{\"texture\":{\"blur\":true}}", 4, 8) == null,
                "a file without animation is not a schedule");
        assertTrue(!MaterialAnimationSchedule.parse("{\"animation\":{\"frames\":[1]}}", 4, 8).animated(),
                "a single entry is not an animation");
        expectRejected("{\"animation\":{\"frames\":[0,7]}}", 4, 8, "frame index outside the strip");
        expectRejected("{\"animation\":{\"frames\":[{\"index\":0,\"time\":0},1]}}", 4, 8, "zero duration");
        expectRejected("{\"animation\":{}}", 4, 10, "frame size that does not divide the image");
    }

    private static void expectRejected(String mcmeta, int width, int height, String what) {
        try {
            MaterialAnimationSchedule.parse(mcmeta, width, height);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("accepted " + what);
    }

    private static void verifyAnimatedCompanions() throws Exception {
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner = MaterialMapOwner.withImages(markerImage(), markerImage(), factory);
        MaterialMapOwner.ResourceLookup lookup = mapLookup(fixtureFiles());
        VulkanImage base = markerImage();
        try {
            owner.noteAtlasUpload(layoutAnimated("minecraft:animated"), base);
            owner.pumpPendingBuilds(lookup, M87MaterialMapsHarness::decodeImageIO);
            FakeSet normals = factory.set("minecraft:animated", MaterialMapOwner.MaterialMapKind.NORMALS);
            VulkanImage bound = owner.companionFor(base, "normals").image();
            int[] frames = {pack(255, 30, 20, 10), pack(255, 60, 50, 40), pack(255, 90, 80, 70)};

            int[] n0 = normals.levels.get(0).pixels();
            assertEquals(frames[0], n0[32 + 1], "animation starts on its first entry");
            assertEquals(frames[0], n0[0], "first frame fills the replicated padding");
            assertEquals(pack(255, 3, 2, 1), n0[16], "single-entry table shows the strip's first frame");
            assertEquals(FALLBACK_NORMALS, n0[8 * 32], "invalid frame index left its slot flat");
            assertEquals(FALLBACK_NORMALS, n0[8 * 32 + 8], "undividable strip left its slot flat");
            int brick = n0[16 * 32 + 16];

            // Shown frame after each tick: 0 holds 2, then 2 holds 3, then 1 holds 1.
            // Tick 5 first reaches the last entry: from then on only mip 0 is
            // redrawn (Iris + Sodium), so mip 1 keeps frame 2.
            int[] expected = {0, 2, 2, 2, 1, 0, 0, 2};
            int lastEntryTick = 4;
            int writes = 0;
            for (int tick = 0; tick < expected.length; tick++) {
                owner.tickAtlas("minecraft:animated");
                owner.pumpPendingBuilds(lookup, M87MaterialMapsHarness::decodeImageIO);
                boolean changed = tick == 0 ? false : expected[tick] != expected[tick - 1];
                if (changed) writes += tick < lastEntryTick ? 3 : 1;
                assertEquals(writes, normals.writes.size(), "tick " + (tick + 1) + " redraw count");
                int shown = frames[expected[tick]];
                int smaller = frames[expected[Math.min(tick, lastEntryTick - 1)]];
                assertEquals(shown, normals.levels.get(0).pixels()[32 + 1], "tick " + (tick + 1) + " frame");
                assertEquals(shown, normals.levels.get(0).pixels()[0], "tick " + (tick + 1) + " padding");
                assertEquals(smaller, normals.levels.get(1).pixels()[0], "tick " + (tick + 1) + " mip 1");
            }
            assertTrue(owner.companionFor(base, "normals").image() == bound,
                    "animation changed the descriptor identity");
            assertEquals(1, factory.buildsFor("minecraft:animated"), "animation rebuilt the companion");
            assertEquals(brick, normals.levels.get(0).pixels()[16 * 32 + 16], "animation touched a neighbour");
            for (int[] write : normals.writes) {
                assertTrue(write[1] + write[3] <= (8 >> write[0]) && write[2] + write[4] <= (8 >> write[0]),
                        "write left the animated slot: " + java.util.Arrays.toString(write));
            }

            // A tick for another atlas never advances this one.
            int before = normals.writes.size();
            owner.tickAtlas("minecraft:blocks");
            owner.pumpPendingBuilds(lookup, M87MaterialMapsHarness::decodeImageIO);
            assertEquals(before, normals.writes.size(), "a foreign atlas tick advanced the map");

            // A re-stitch restarts the schedule on its first entry.
            owner.noteAtlasUpload(layoutAnimated("minecraft:animated"), markerImage());
            owner.pumpPendingBuilds(lookup, M87MaterialMapsHarness::decodeImageIO);
            FakeSet rebuilt = factory.set("minecraft:animated", MaterialMapOwner.MaterialMapKind.NORMALS);
            assertEquals(frames[0], rebuilt.levels.get(0).pixels()[32 + 1], "re-stitch kept an old frame");
            assertTrue(normals.closed, "re-stitch did not close the old companion");
        } finally {
            owner.close();
        }
        assertTrue(factory.allClosed(), "shutdown closed every animated companion");
    }

    private static void verifyInterpolatedCompanion() throws Exception {
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner = MaterialMapOwner.withImages(markerImage(), markerImage(), factory);
        MaterialMapOwner.ResourceLookup lookup = mapLookup(fixtureFiles());
        try {
            owner.noteAtlasUpload(layoutAnimated("minecraft:animated"), markerImage());
            owner.pumpPendingBuilds(lookup, M87MaterialMapsHarness::decodeImageIO);
            FakeSet specular = factory.set("minecraft:animated", MaterialMapOwner.MaterialMapKind.SPECULAR);
            int content = 32 + 9;
            assertEquals(pack(40, 200, 100, 0), specular.levels.get(0).pixels()[content],
                    "interpolation starts on frame 0");
            // Four ticks per frame: progress 0.25, 0.5, 0.75, then frame 1 exactly.
            int[][] shown = {
                    {90, 150, 100, 50},
                    {140, 100, 100, 100},
                    {190, 50, 100, 150},
                    {240, 0, 100, 200},
                    {190, 50, 100, 150}};
            for (int tick = 0; tick < shown.length; tick++) {
                owner.tickAtlas("minecraft:animated");
                owner.pumpPendingBuilds(lookup, M87MaterialMapsHarness::decodeImageIO);
                int[] value = shown[tick];
                assertEquals(pack(value[0], value[1], value[2], value[3]),
                        specular.levels.get(0).pixels()[content], "interpolated tick " + (tick + 1));
                assertEquals(pack(value[0], value[1], value[2], value[3]),
                        specular.levels.get(0).pixels()[8], "interpolated padding tick " + (tick + 1));
            }
            // Every tick redraws; tick 4 reaches the last entry, after which only mip 0 is written.
            assertEquals(3 * 3 + (shown.length - 3), specular.writes.size(), "interpolation redraws every tick");
        } finally {
            owner.close();
        }
    }

    /** Ticks that land between the atlas upload and the build still count. */
    private static void verifyAnimationLockstep() throws Exception {
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner = MaterialMapOwner.withImages(markerImage(), markerImage(), factory);
        MaterialMapOwner.ResourceLookup lookup = mapLookup(fixtureFiles());
        try {
            owner.noteAtlasUpload(layoutAnimated("minecraft:animated"), markerImage());
            owner.tickAtlas("minecraft:animated");
            owner.tickAtlas("minecraft:animated");
            owner.pumpPendingBuilds(lookup, M87MaterialMapsHarness::decodeImageIO);
            FakeSet normals = factory.set("minecraft:animated", MaterialMapOwner.MaterialMapKind.NORMALS);
            assertEquals(pack(255, 90, 80, 70), normals.levels.get(0).pixels()[32 + 1],
                    "the build did not catch up with the albedo's ticks");
        } finally {
            owner.close();
        }
    }
    // ------------------------------------------------------------------
    // Fakes and helpers
    // ------------------------------------------------------------------

    private static final class FakeSet implements MaterialMapOwner.CompanionSet {
        final List<MaterialMapOwner.LevelPixels> levels;
        final VulkanImage image;
        boolean closed;

        FakeSet(List<MaterialMapOwner.LevelPixels> levels, VulkanImage image) {
            this.levels = levels;
            this.image = image;
        }

        /** Region writes, applied to the recorded levels like a GPU copy. */
        final List<int[]> writes = new ArrayList<>();

        @Override
        public VulkanImage image() {
            return image;
        }

        @Override
        public void writeRegion(int level, int x, int y, int width, int height, int[] pixels) {
            MaterialMapOwner.LevelPixels canvas = levels.get(level);
            MaterialMapPixels.placeRect(canvas.pixels(), canvas.width(), canvas.height(),
                    x, y, pixels, width, height);
            writes.add(new int[] {level, x, y, width, height});
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakeSamplers implements MaterialMapOwner.MaterialSamplerFactory {
        record Request(com.mojang.blaze3d.textures.AddressMode addressU,
                com.mojang.blaze3d.textures.AddressMode addressV,
                com.mojang.blaze3d.textures.FilterMode minFilter,
                com.mojang.blaze3d.textures.FilterMode magFilter,
                int anisotropy, float maxLod) {}
        final List<Request> requests = new ArrayList<>();
        final List<FakeOwnedSampler> live = new ArrayList<>();
        long nextId = 0x9E450L;

        @Override
        public MaterialMapOwner.OwnedMaterialSampler create(
                com.mojang.blaze3d.textures.AddressMode addressU,
                com.mojang.blaze3d.textures.AddressMode addressV,
                com.mojang.blaze3d.textures.FilterMode minFilter,
                com.mojang.blaze3d.textures.FilterMode magFilter,
                int anisotropy, float maxLod) {
            requests.add(new Request(addressU, addressV, minFilter, magFilter, anisotropy, maxLod));
            FakeOwnedSampler sampler = new FakeOwnedSampler(nextId++);
            live.add(sampler);
            return sampler;
        }

        boolean allClosed() {
            return live.stream().allMatch(sampler -> sampler.closed);
        }
    }

    private static final class FakeOwnedSampler
            implements MaterialMapOwner.OwnedMaterialSampler {
        final long samplerId;
        boolean closed;

        FakeOwnedSampler(long samplerId) {
            this.samplerId = samplerId;
        }

        @Override
        public long id() {
            return samplerId;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakeFactory implements MaterialMapOwner.CompanionFactory {
        final Map<String, Map<MaterialMapOwner.MaterialMapKind, FakeSet>> built = new LinkedHashMap<>();
        final Map<String, Integer> builds = new LinkedHashMap<>();

        @Override
        public MaterialMapOwner.CompanionSet build(String location,
                MaterialMapOwner.MaterialMapKind kind, int width, int height,
                List<MaterialMapOwner.LevelPixels> levels) {
            FakeSet set = new FakeSet(levels, markerImage());
            built.computeIfAbsent(location, key -> new LinkedHashMap<>()).put(kind, set);
            builds.merge(location + "/" + kind.sampler(), 1, Integer::sum);
            return set;
        }

        FakeSet set(String location, MaterialMapOwner.MaterialMapKind kind) {
            return built.get(location).get(kind);
        }

        int buildsFor(String location) {
            int total = 0;
            for (var entry : builds.entrySet()) {
                if (entry.getKey().startsWith(location + "/")) total += entry.getValue();
            }
            return total / MaterialMapOwner.MaterialMapKind.values().length;
        }

        boolean allClosed() {
            return built.values().stream().flatMap(map -> map.values().stream()).allMatch(set -> set.closed);
        }
    }

    private static MaterialMapOwner.DecodedImage decodeImageIO(InputStream in) throws IOException {
        BufferedImage image = ImageIO.read(in);
        if (image == null) {
            throw new IOException("MATERIAL_MAP_UNDECODABLE");
        }
        int width = image.getWidth();
        int height = image.getHeight();
        MaterialMapPixels.checkSize(width, height, "fixture");
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        int[] abgr = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int pixel = argb[i];
            abgr[i] = MaterialMapPixels.pack(pixel >>> 24, pixel & 0xFF,
                    (pixel >> 8) & 0xFF, (pixel >> 16) & 0xFF);
        }
        return new MaterialMapOwner.DecodedImage(abgr, width, height);
    }

    private static int[] readFixtureAbgr(String relative) throws IOException {
        try (InputStream in = Files.newInputStream(FIXTURE.resolve(relative))) {
            MaterialMapOwner.DecodedImage decoded = decodeImageIO(in);
            return decoded.pixels();
        }
    }

    private static VulkanImage markerImage() {
        try {
            var constructor = VulkanImage.class.getDeclaredConstructor(VulkanImage.Builder.class);
            constructor.setAccessible(true);
            return constructor.newInstance(VulkanImage.builder(1, 1));
        } catch (ReflectiveOperationException failure) {
            throw new RuntimeException(failure);
        }
    }

    private static int pack(int alpha, int blue, int green, int red) {
        return MaterialMapPixels.pack(alpha, blue, green, red);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
