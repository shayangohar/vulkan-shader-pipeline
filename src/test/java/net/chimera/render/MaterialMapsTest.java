package net.chimera.render;

import net.chimera.shaderpack.PackProbe;
import net.chimera.shaderpack.PackProgramPlan;
import net.chimera.shaderpack.PackResourceBinding;
import net.chimera.shaderpack.PackResourceKind;
import net.chimera.shaderpack.PackResourceStatus;
import net.chimera.shaderpack.UniformRegistry;
import net.chimera.testing.TestPacks;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static net.chimera.render.MaterialMapFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Material maps from the checked-in mini resource pack: pixel
 * policies, exact layout and padding, sibling mapping, labPBR detection,
 * per-sprite failure isolation, descriptor resolution and rollback, simple
 * entity textures, reload generations, and animated maps (schedule,
 * interpolation, lockstep ticking, mip and padding rewrites). Everything
 * runs without natives or a GPU: PNGs decode through ImageIO and
 * companions land in a recording fake factory.
 */
final class MaterialMapsTest {
    // ------------------------------------------------------------------
    // Entity fixture: a supported gbuffers_entities program plans material maps
    // ------------------------------------------------------------------

    @Test
    void entityFixturePlan() throws Exception {
        Path root = TestPacks.fixtures();
        PackProbe.Analysis analysis =
                PackProbe.analyze(root.resolve("m87_entity"));
        PackProgramPlan entities =
                analysis.plan().program("gbuffers_entities");
        assertTrue(entities != null, "entity fixture program is missing");
        Map<String, Integer> slots = new TreeMap<>();
        entities.interfacePlan()
                .effective(UniformRegistry.Stage.ENTITY).samplers()
                .forEach(value -> slots.put(value.name(), value.slot()));
        assertEquals(25, slots.get("normals"), "entity fixture normals slot");
        assertEquals(26, slots.get("specular"), "entity fixture specular slot");

        List<PackResourceBinding> bindings =
                analysis.plan().resources().bindings("gbuffers_entities");
        for (String sampler : List.of("normals", "specular")) {
            var binding = bindings.stream().filter(value -> value.sampler().equals(sampler))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("missing entity binding " + sampler));
            assertTrue(binding.kind() == PackResourceKind.MATERIAL_MAP
                    && binding.status() == PackResourceStatus.MATERIAL_MAP
                    && binding.available(), "entity binding is not servable: " + sampler);
        }
        assertTrue(entities.deviations().stream().noneMatch(value ->
                        value.contains("MATERIAL_MAP_DEFERRED") || value.contains("NOT_MAPPED")),
                "entity material deviations: " + entities.deviations());
    }

    // ------------------------------------------------------------------
    // Pixel policies
    // ------------------------------------------------------------------

    @Test
    void labPbrReduction() throws Exception {
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

        int[] level2 = MaterialMapPixels.reduceLabPbr(level1, 2, 2, 1, 1);
        assertEquals(pack(255, 37, 143, 52), level2[0], "lab quad final");
    }

    @Test
    void averagedReduction() {
        // Asymmetric reduction averages the whole footprint: a 4x4 source
        // with distinct texels per 2x2 quad must not select top-left texels.
        int[] blocks = new int[16];
        for (int i = 0; i < 16; i++) {
            blocks[i] = pack(255, i, i, i);
        }
        int[] small = MaterialMapPixels.scale(blocks, 4, 4, 2, 2);
        assertEquals(pack(255, 2, 2, 2), small[0], "weighted reduction averages");
        assertEquals(pack(255, 12, 12, 12), small[3], "weighted reduction far quad");

        int[] quad = {
                pack(30, 20, 10, 0), pack(70, 60, 50, 40),
                pack(110, 100, 90, 80), pack(150, 140, 130, 120),
        };
        int[] mean = MaterialMapPixels.reduceLinear(quad, 2, 2, 1, 1);
        assertEquals(pack(90, 80, 70, 60), mean[0], "linear mean");
    }

    @Test
    void scaling() throws Exception {
        int[] brickN = readFixtureAbgr("assets/minecraft/textures/block/m87_brick_n.png");
        int[] down = MaterialMapPixels.scale(brickN, 32, 32, 16, 16);
        assertEquals(pack(255, 200, 100, 0), down[0], "point downscale origin");
        assertEquals(pack(255, 193, 105, 87), down[7 * 16 + 5], "point downscale interior");

        int[] brickS = readFixtureAbgr("assets/minecraft/textures/block/m87_brick_s.png");
        int[] up = MaterialMapPixels.scale(brickS, 8, 8, 16, 16);
        assertEquals(pack(255, 128, 0, 0), up[0], "point upscale origin");
        assertEquals(pack(255, 128, 32, 32), up[2 * 16 + 2], "point upscale interior");
        assertEquals(pack(255, 128, 32, 32), up[3 * 16 + 3], "point upscale replication");

        int[] tiny = {pack(255, 0, 0, 10), pack(255, 0, 0, 20),
                pack(255, 0, 0, 30), pack(255, 0, 0, 40)};
        int[] grown = MaterialMapPixels.scale(tiny, 2, 2, 3, 3);
        assertEquals(9, grown.length, "box upscale size");
        assertEquals(tiny[0], grown[0], "box upscale corner");
        assertEquals(tiny[3], grown[8], "box upscale far corner");
        assertEquals(tiny[0], grown[4], "box upscale center");

        int[] identical = MaterialMapPixels.scale(tiny, 2, 2, 2, 2);
        assertTrue(identical != tiny && Arrays.equals(identical, tiny), "identical copy");
    }

    @Test
    void levelSizesAndLimits() {
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

    @Test
    void siblingMapping() {
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

    @Test
    void labPbrDetection() throws Exception {
        try (InputStream fixture = Files.newInputStream(
                FIXTURE.resolve("assets/minecraft/optifine/texture.properties"))) {
            assertTrue(!MaterialMapPixels.detectLabPbr(fixture), "fixture is linear mode");
        }
        assertTrue(MaterialMapPixels.detectLabPbr(stream("format=lab-pbr\n")), "lab-pbr flag");
        assertTrue(MaterialMapPixels.detectLabPbr(stream("format=lab-pbr/1.1\n")),
                "lab-pbr versioned");
        assertTrue(!MaterialMapPixels.detectLabPbr(stream("format=old-pbr\n")), "other format");
        assertTrue(!MaterialMapPixels.detectLabPbr(stream("# empty\n")), "missing key");
    }

    // ------------------------------------------------------------------
    // Owner build: linear mode
    // ------------------------------------------------------------------

    @Test
    void ownerBuildLinear() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner = MaterialMapOwner.withImages(markerImage(),
                markerImage(), factory);
        VulkanImage base = markerImage();
        try {
            owner.noteAtlasUpload(layout(), base);
            owner.pumpPendingBuilds(mapLookup(files), MaterialMapFixtures::decodeImageIO);

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

            // Descriptors still answer flat fallbacks.
            assertTrue(owner.snapshot("normals").image() != null, "flat dispatch alive");
            // The companion lookup answers by exact albedo identity.
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

    @Test
    void ownerBuildLabPbr() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        files.put("minecraft:optifine/texture.properties", "format=lab-pbr\n".getBytes(StandardCharsets.UTF_8));
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner = MaterialMapOwner.withImages(markerImage(),
                markerImage(), factory);
        try {
            owner.noteAtlasUpload(layout(), markerImage());
            owner.pumpPendingBuilds(mapLookup(files), MaterialMapFixtures::decodeImageIO);
            FakeSet specular = factory.set("minecraft:blocks",
                    MaterialMapOwner.MaterialMapKind.SPECULAR);
            int[] s1 = specular.levels.get(1).pixels();
            assertEquals(pack(255, 10, 200, 25), s1[24], "lab quad keeps its class");
            assertEquals(pack(255, 70, 0, 100), s1[25], "lab quad majority class");
            assertEquals(pack(254, 64, 229, 77), s1[32 + 24], "lab quad uniform class");
            assertEquals(pack(255, 110, 230, 6), s1[32 + 25],
                    "lab quad split metals stay discrete");
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

    @Test
    void failureIsolation() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        files.put("minecraft:textures/block/m87_edge_n.png", "not a png".getBytes(StandardCharsets.UTF_8));
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner = MaterialMapOwner.withImages(markerImage(),
                markerImage(), factory);
        try {
            owner.noteAtlasUpload(layout(), markerImage());
            owner.pumpPendingBuilds(mapLookup(files), MaterialMapFixtures::decodeImageIO);
            FakeSet normals = factory.set("minecraft:blocks",
                    MaterialMapOwner.MaterialMapKind.NORMALS);
            int[] n0 = normals.levels.get(0).pixels();
            assertEquals(FALLBACK_NORMALS, n0[8 * 64 + 24], "bad sprite stays fallback");
            assertEquals(pack(255, 200, 100, 0), n0[0], "good sprite survives a bad sibling");
        } finally {
            owner.close();
        }
    }

    @Test
    void replacementAndLifecycle() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner = MaterialMapOwner.withImages(markerImage(),
                markerImage(), factory);
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
            owner.pumpPendingBuilds(mapLookup(files), MaterialMapFixtures::decodeImageIO);
            assertEquals(1, factory.buildsFor("minecraft:blocks"), "latest upload wins");
            assertTrue(factory.set("minecraft:blocks",
                    MaterialMapOwner.MaterialMapKind.NORMALS) != null, "first build installed");
            FakeSet first = factory.set("minecraft:blocks",
                    MaterialMapOwner.MaterialMapKind.NORMALS);

            // A new base image retires the old companions.
            owner.noteAtlasUpload(layout(), baseB);
            owner.pumpPendingBuilds(mapLookup(files), MaterialMapFixtures::decodeImageIO);
            assertTrue(first.closed, "replacement closed the old companions");
            assertTrue(owner.companionFor(baseA, "normals") == null, "old base no longer resolves");
            assertTrue(owner.companionFor(baseB, "normals") != null, "new base resolves");

            // Post-close pumps and notes are silent no-ops.
            owner.close();
            owner.close();
            owner.noteAtlasUpload(layout(), markerImage());
            owner.pumpPendingBuilds(mapLookup(files), MaterialMapFixtures::decodeImageIO);
            assertEquals(2, factory.buildsFor("minecraft:blocks"), "no build after close");
        } finally {
            owner.close();
        }
        assertTrue(factory.allClosed(), "shutdown closed every companion");
    }

    // ------------------------------------------------------------------
    // Simple textures: first frame flat, next frame bound
    // ------------------------------------------------------------------

    @Test
    void simpleTextures() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        CountingLookup lookup = new CountingLookup(
                mapLookup(files));
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner =
                MaterialMapOwner.withImages(markerImage(), markerImage(),
                        factory);
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

            owner.pumpPendingBuilds(lookup, MaterialMapFixtures::decodeImageIO, source);
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
            assertTrue(owner.resolveDrawMaterial(contextFor(stranger, 0L), "normals")
                    .image() != null,
                    "stranger has no fallback");
            owner.pumpPendingBuilds(lookup, MaterialMapFixtures::decodeImageIO, source);
            int afterNegative = lookup.siblingOpens;
            owner.resolveDrawMaterial(contextFor(mob, 0L), "specular");
            owner.resolveDrawMaterial(contextFor(stranger, 0L), "specular");
            owner.pumpPendingBuilds(lookup, MaterialMapFixtures::decodeImageIO, source);
            assertEquals(afterNegative, lookup.siblingOpens, "negative cache did not hold");
        } finally {
            owner.close();
        }
        assertTrue(factory.allClosed(), "shutdown closed every simple companion");
    }

    // ------------------------------------------------------------------
    // Reload generations
    // ------------------------------------------------------------------

    @Test
    void generations() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        CountingLookup lookup = new CountingLookup(
                mapLookup(files));
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner =
                MaterialMapOwner.withImages(markerImage(), markerImage(),
                        factory);
        VulkanImage baseA = markerImage();
        VulkanImage baseB = markerImage();
        VulkanImage mob = markerImage();
        MaterialMapOwner.SimpleTextureSource source = () -> List.of(
                new MaterialMapOwner.SimpleEntry(mob, "minecraft",
                        SimpleTextureIndex.relativeSpritePath("textures/entity/m87_mob.png")));
        try {
            owner.noteAtlasUpload(layout(), baseA);
            owner.resolveDrawMaterial(contextFor(mob, 0L), "normals");
            owner.pumpPendingBuilds(lookup, MaterialMapFixtures::decodeImageIO, source);
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
            owner.pumpPendingBuilds(lookup, MaterialMapFixtures::decodeImageIO, source);
            assertTrue(setMob.closed, "retired simple set was not closed");
            assertTrue(!setA.closed, "pump closed surviving atlas A");

            // A fresh upload for the same location displaces the old base.
            owner.noteAtlasUpload(layout(), baseB);
            owner.pumpPendingBuilds(lookup, MaterialMapFixtures::decodeImageIO, source);
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

    @Test
    void pixelPadding() {
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
        assertTrue(same != logical && Arrays.equals(same, logical), "zero pad copies");

        assertEquals(2, MaterialMapPixels.recoverPad(10.0f / 64.0f, 64, 8), "padding recovery");
        assertEquals(2, MaterialMapPixels.recoverPad(0.5f, 64, 30), "padding recovery halfway");
        assertEquals(0, MaterialMapPixels.recoverPad(0.0f, 64, 0), "no padding");
        assertEquals(0, MaterialMapPixels.recoverPad(0.0f, 64, 5), "negative padding clamps");
    }

    @Test
    void paddedTopology() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        CountingLookup lookup = new CountingLookup(
                mapLookup(files));
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner =
                MaterialMapOwner.withImages(markerImage(), markerImage(),
                        factory);
        try {
            owner.noteAtlasUpload(layoutPadded(), markerImage());
            owner.pumpPendingBuilds(lookup, MaterialMapFixtures::decodeImageIO);
            FakeSet normals = factory.set("minecraft:padded",
                    MaterialMapOwner.MaterialMapKind.NORMALS);
            int[] n0 = normals.levels.get(0).pixels();

            // Brick content begins at slot + padding, borders replicate edges.
            assertEquals(pack(255, 200, 100, 0), n0[10 * 64 + 10], "padded content origin");
            assertEquals(n0[10 * 64 + 10], n0[8 * 64 + 8], "padded corner replicates");
            assertEquals(pack(255, 200, 115, 240), n0[10 * 64 + 27],
                    "padded far border replicates");
            // Adjacent padded slots meet without overwriting each other.
            assertEquals(pack(255, 255, 255, 255), n0[10 * 64 + 28],
                    "adjacent slot keeps its border");
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

    @Test
    void asymmetricPaddingIsolated() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        CountingLookup lookup = new CountingLookup(
                mapLookup(files));
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner =
                MaterialMapOwner.withImages(markerImage(), markerImage(),
                        factory);
        try {
            int siblingBefore = lookup.siblingOpens;
            owner.noteAtlasUpload(new MaterialMapOwner.AtlasUpload("minecraft:skewed", 64, 64, 1,
                    List.of(sprite("minecraft:block/m87_brick", 0, 0, 16, 16, 2, 3))),
                            markerImage());
            owner.pumpPendingBuilds(lookup, MaterialMapFixtures::decodeImageIO);
            assertEquals(siblingBefore, lookup.siblingOpens, "asymmetric sprite read files");
            assertTrue(factory.buildsFor("minecraft:skewed") == 0, "asymmetric sprite built");
        } finally {
            owner.close();
        }
    }

}
