package net.chimera.render;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static net.chimera.render.MaterialMapFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Animated material maps: schedules read from the .mcmeta files, interpolated frames, and companion
 * frames that tick in lockstep with their albedo atlas.
 */
final class MaterialMapAnimationTest {
    @Test
    void animationSchedules() throws Exception {
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

    static void expectRejected(String mcmeta, int width, int height, String what) {
        try {
            MaterialAnimationSchedule.parse(mcmeta, width, height);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("accepted " + what);
    }

    @Test
    void animatedCompanions() throws Exception {
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner = MaterialMapOwner.withImages(markerImage(),
                markerImage(), factory);
        MaterialMapOwner.ResourceLookup lookup = mapLookup(fixtureFiles());
        VulkanImage base = markerImage();
        try {
            owner.noteAtlasUpload(layoutAnimated("minecraft:animated"), base);
            owner.pumpPendingBuilds(lookup, MaterialMapFixtures::decodeImageIO);
            FakeSet normals = factory.set("minecraft:animated",
                    MaterialMapOwner.MaterialMapKind.NORMALS);
            VulkanImage bound = owner.companionFor(base, "normals").image();
            int[] frames = {pack(255, 30, 20, 10), pack(255, 60, 50, 40),
                    pack(255, 90, 80, 70)};

            int[] n0 = normals.levels.get(0).pixels();
            assertEquals(frames[0], n0[32 + 1], "animation starts on its first entry");
            assertEquals(frames[0], n0[0], "first frame fills the replicated padding");
            assertEquals(pack(255, 3, 2, 1), n0[16],
                    "single-entry table shows the strip's first frame");
            assertEquals(FALLBACK_NORMALS, n0[8 * 32], "invalid frame index left its slot flat");
            assertEquals(FALLBACK_NORMALS, n0[8 * 32 + 8], "undividable strip left its slot flat");
            int brick = n0[16 * 32 + 16];

            // Shown frame after each tick: 0 holds 2, then 2 holds 3, then 1 holds 1. Every
            // redraw writes all three mip levels, as 1.21.1 uploads a frame.
            int[] expected = {0, 2, 2, 2, 1, 0, 0, 2};
            int writes = 0;
            for (int tick = 0; tick < expected.length; tick++) {
                owner.tickAtlas("minecraft:animated");
                owner.pumpPendingBuilds(lookup, MaterialMapFixtures::decodeImageIO);
                boolean changed = tick == 0 ? false : expected[tick] != expected[tick - 1];
                if (changed) writes += 3;
                assertEquals(writes, normals.writes.size(), "tick " + (tick + 1) + " redraw count");
                int shown = frames[expected[tick]];
                assertEquals(shown, normals.levels.get(0).pixels()[32 + 1], "tick " + (tick + 1) + " frame");
                assertEquals(shown, normals.levels.get(0).pixels()[0], "tick " + (tick + 1) + " padding");
                assertEquals(shown, normals.levels.get(1).pixels()[0], "tick " + (tick + 1) + " mip 1");
            }
            assertTrue(owner.companionFor(base, "normals").image() == bound,
                    "animation changed the descriptor identity");
            assertEquals(1, factory.buildsFor("minecraft:animated"), "animation rebuilt the companion");
            assertEquals(brick, normals.levels.get(0).pixels()[16 * 32 + 16], "animation touched a neighbour");
            for (int[] write : normals.writes) {
                assertTrue(write[1] + write[3] <= (8 >> write[0]) && write[2] + write[4] <= (8 >> write[0]),
                        "write left the animated slot: " + Arrays.toString(write));
            }

            // A tick for another atlas never advances this one.
            int before = normals.writes.size();
            owner.tickAtlas("minecraft:blocks");
            owner.pumpPendingBuilds(lookup, MaterialMapFixtures::decodeImageIO);
            assertEquals(before, normals.writes.size(), "a foreign atlas tick advanced the map");

            // A re-stitch restarts the schedule on its first entry.
            owner.noteAtlasUpload(layoutAnimated("minecraft:animated"),
                    markerImage());
            owner.pumpPendingBuilds(lookup, MaterialMapFixtures::decodeImageIO);
            FakeSet rebuilt = factory.set("minecraft:animated",
                    MaterialMapOwner.MaterialMapKind.NORMALS);
            assertEquals(frames[0], rebuilt.levels.get(0).pixels()[32 + 1], "re-stitch kept an old frame");
            assertTrue(normals.closed, "re-stitch did not close the old companion");
        } finally {
            owner.close();
        }
        assertTrue(factory.allClosed(), "shutdown closed every animated companion");
    }

    @Test
    void interpolatedCompanion() throws Exception {
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner = MaterialMapOwner.withImages(markerImage(),
                markerImage(), factory);
        MaterialMapOwner.ResourceLookup lookup = mapLookup(fixtureFiles());
        try {
            owner.noteAtlasUpload(layoutAnimated("minecraft:animated"),
                    markerImage());
            owner.pumpPendingBuilds(lookup, MaterialMapFixtures::decodeImageIO);
            FakeSet specular = factory.set("minecraft:animated",
                    MaterialMapOwner.MaterialMapKind.SPECULAR);
            int content = 32 + 9;
            assertEquals(pack(40, 200, 100, 0), specular.levels.get(0).pixels()[content],
                    "interpolation starts on frame 0");
            // Four ticks per frame. Sodium weighs the current frame by 191, 127, then 63 of 255,
            // mixes the colour channels and keeps the current frame's alpha; then frame 1 shows
            // exactly and mixes towards frame 0.
            int[][] shown = {
                    {40, 150, 100, 50},
                    {40, 100, 100, 100},
                    {40, 50, 100, 150},
                    {240, 0, 100, 200},
                    {240, 50, 100, 150}};
            for (int tick = 0; tick < shown.length; tick++) {
                owner.tickAtlas("minecraft:animated");
                owner.pumpPendingBuilds(lookup, MaterialMapFixtures::decodeImageIO);
                int[] value = shown[tick];
                assertEquals(pack(value[0], value[1], value[2], value[3]),
                        specular.levels.get(0).pixels()[content], "interpolated tick " + (tick + 1));
                assertEquals(pack(value[0], value[1], value[2], value[3]),
                        specular.levels.get(0).pixels()[8], "interpolated padding tick " + (tick + 1));
            }
            // Every tick redraws all three mip levels.
            assertEquals(3 * shown.length, specular.writes.size(), "interpolation redraws every tick");
        } finally {
            owner.close();
        }
    }

    /** Ticks that land between the atlas upload and the build still count. */
    @Test
    void animationLockstep() throws Exception {
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner = MaterialMapOwner.withImages(markerImage(),
                markerImage(), factory);
        MaterialMapOwner.ResourceLookup lookup = mapLookup(fixtureFiles());
        try {
            owner.noteAtlasUpload(layoutAnimated("minecraft:animated"),
                    markerImage());
            owner.tickAtlas("minecraft:animated");
            owner.tickAtlas("minecraft:animated");
            owner.pumpPendingBuilds(lookup, MaterialMapFixtures::decodeImageIO);
            FakeSet normals = factory.set("minecraft:animated",
                    MaterialMapOwner.MaterialMapKind.NORMALS);
            assertEquals(pack(255, 90, 80, 70), normals.levels.get(0).pixels()[32 + 1],
                    "the build did not catch up with the albedo's ticks");
        } finally {
            owner.close();
        }
    }
}
