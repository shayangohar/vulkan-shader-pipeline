package net.chimera.render;

import net.chimera.shaderpack.ProgramImageBindingManifest;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.chimera.render.MaterialMapFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NEAREST_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_REPEAT;

/**
 * Draw-time material-map resolution: which companion a draw binds, the sampler it samples with, and
 * the binding transaction that rolls back when one resolution fails.
 */
final class MaterialMapDrawTest {
    @Test
    void resolveDrawMaterial() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        FakeFactory factory = new FakeFactory();
        FakeSamplers samplers = new FakeSamplers();
        VulkanImage flatNormals = markerImage();
        VulkanImage flatSpecular = markerImage();
        MaterialMapOwner owner =
                MaterialMapOwner.withImages(flatNormals, flatSpecular, factory, samplers);
        VulkanImage base = markerImage();
        try {
            // Pre-pump draws answer flat fallbacks, exactly like descriptors.
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
            owner.pumpPendingBuilds(mapLookup(files), MaterialMapFixtures::decodeImageIO);
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
    @Test
    void twoAtlasDispatch() throws Exception {
        FakeFactory factory = new FakeFactory();
        VulkanImage flatNormals = markerImage();
        MaterialMapOwner owner = MaterialMapOwner.withImages(flatNormals, markerImage(), factory);
        MaterialMapOwner.ResourceLookup lookup = mapLookup(fixtureFiles());
        VulkanImage blocks = markerImage();
        VulkanImage animated = markerImage();
        try {
            owner.noteAtlasUpload(layout(), blocks);
            owner.noteAtlasUpload(layoutAnimated("minecraft:animated"), animated);
            owner.pumpPendingBuilds(lookup, MaterialMapFixtures::decodeImageIO);
            for (MaterialMapOwner.MaterialMapKind kind : MaterialMapOwner.MaterialMapKind.values()) {
                assertTrue(owner.resolveDrawMaterial(contextFor(blocks, 0L), kind.sampler()).image()
                                == factory.set("minecraft:blocks", kind).image,
                        "blocks draw missed its own " + kind.sampler());
                assertTrue(owner.resolveDrawMaterial(contextFor(animated, 0L), kind.sampler())
                        .image()
                                == factory.set("minecraft:animated", kind).image,
                        "animated draw missed its own " + kind.sampler());
            }
            assertTrue(owner.resolveDrawMaterial(contextFor(markerImage(), 0L),
                    "normals").image()
                    == flatNormals, "an unowned image did not stay flat");
        } finally {
            owner.close();
        }
    }

    @Test
    void ownedMaterialSamplers() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        files.put("minecraft:optifine/texture.properties", "format=lab-pbr\n".getBytes(
                StandardCharsets.UTF_8));
        FakeFactory factory = new FakeFactory();
        FakeSamplers samplers = new FakeSamplers();
        MaterialMapOwner owner =
                MaterialMapOwner.withImages(markerImage(), markerImage(),
                        factory, samplers);
        VulkanImage base = markerImage();
        try {
            owner.noteAtlasUpload(layout(), base);
            owner.pumpPendingBuilds(mapLookup(files), MaterialMapFixtures::decodeImageIO);
            DrawMaterialContext lod4 = profiledContext(base, 4.0f);
            DrawMaterialContext lod8 = profiledContext(base, 8.0f);

            // Normals keep the exact albedo sampler ID and own nothing.
            var normals = owner.resolveDrawMaterial(lod4, "normals");
            assertEquals(0x7777L, normals.samplerOverride(), "normals left the albedo sampler");
            assertTrue(samplers.requests.isEmpty(), "normals allocated a sampler");

            // Atlas labPBR specular filters NEAREST inside the source's mip mode, with its ceiling.
            var specular4 = owner.resolveDrawMaterial(lod4, "specular");
            var specular8 = owner.resolveDrawMaterial(lod8, "specular");
            assertEquals(2, samplers.requests.size(), "max-LOD variants aliased in the cache");
            SamplerManager.SamplerKey first = samplers.requests.get(0);
            assertEquals(GL_NEAREST_MIPMAP_LINEAR, first.minFilter(), "labPBR min filter");
            assertEquals(GL_NEAREST, first.magFilter(), "labPBR mag filter");
            assertEquals(GL_REPEAT, first.wrapS(), "labPBR wrap S");
            assertEquals(GL_REPEAT, first.wrapT(), "labPBR wrap T");
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
    }

    @Test
    void simpleSamplerClamp() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        files.put("minecraft:optifine/texture.properties", "format=lab-pbr\n".getBytes(
                StandardCharsets.UTF_8));
        byte[] mobN = Files.readAllBytes(
                FIXTURE.resolve("assets/minecraft/textures/block/m87_brick_n.png"));
        byte[] mobS = Files.readAllBytes(
                FIXTURE.resolve("assets/minecraft/textures/block/m87_brick_s.png"));
        files.put("minecraft:textures/entity/m87_mob_n.png", mobN);
        files.put("minecraft:textures/entity/m87_mob_s.png", mobS);
        FakeFactory factory = new FakeFactory();
        FakeSamplers samplers = new FakeSamplers();
        MaterialMapOwner owner =
                MaterialMapOwner.withImages(markerImage(), markerImage(),
                        factory, samplers);
        VulkanImage mob = markerImage();
        MaterialMapOwner.SimpleTextureSource source = () -> List.of(
                new MaterialMapOwner.SimpleEntry(mob, "minecraft",
                        SimpleTextureIndex.relativeSpritePath("textures/entity/m87_mob.png")));
        try {
            owner.resolveDrawMaterial(contextFor(mob, 0L), "normals");
            owner.pumpPendingBuilds(mapLookup(files), MaterialMapFixtures::decodeImageIO, source);
            DrawMaterialContext context = profiledContext(mob, 4.0f);
            // Simple normals keep the source policy but clamp to LOD zero.
            var normals = owner.resolveDrawMaterial(context, "normals");
            // Simple labPBR specular filters NEAREST and clamps to LOD zero.
            var specular = owner.resolveDrawMaterial(context, "specular");
            assertEquals(2, samplers.requests.size(), "simple maps did not derive two samplers");
            SamplerManager.SamplerKey normalRequest = samplers.requests.get(0);
            assertEquals(GL_LINEAR_MIPMAP_LINEAR, normalRequest.minFilter(), "simple normals min filter");
            assertEquals(GL_LINEAR, normalRequest.magFilter(), "simple normals mag filter");
            assertEquals(GL_REPEAT, normalRequest.wrapS(), "simple normals wrap");
            assertEquals(0.0f, normalRequest.maxLod(), "simple normals LOD not clamped");
            SamplerManager.SamplerKey specularRequest = samplers.requests.get(1);
            assertEquals(GL_NEAREST_MIPMAP_LINEAR, specularRequest.minFilter(), "simple specular min filter");
            assertEquals(GL_NEAREST, specularRequest.magFilter(), "simple specular mag filter");
            assertEquals(0.0f, specularRequest.maxLod(), "simple specular LOD not clamped");
            assertTrue(normals.samplerOverride() != specular.samplerOverride(),
                    "simple kinds shared one sampler");
        } finally {
            owner.close();
        }
    }

    /**
     * The first-draw race, directed: an entity draw marks slot 0 with its
     * own sampler, then the very first terrain transaction must still bind
     * all three terrain resources with the chunk profile, because terrain
     * resolves from its captured context and never re-queries slot state.
     */
    @Test
    void firstTerrainDrawWins() throws Exception {
        Map<String, byte[]> files = fixtureFiles();
        FakeFactory factory = new FakeFactory();
        FakeSamplers samplers = new FakeSamplers();
        MaterialMapOwner owner =
                MaterialMapOwner.withImages(markerImage(), markerImage(),
                        factory, samplers);
        VulkanImage atlasBase = markerImage();
        VulkanImage entityBase = markerImage();
        try {
            owner.noteAtlasUpload(layout(), atlasBase);
            owner.pumpPendingBuilds(mapLookup(files), MaterialMapFixtures::decodeImageIO);
            // Entity batch first: slot 0 now names a foreign image.
            ChimeraTextureBindingState.markPackBinding(0, entityBase, 0x5555L);
            DrawMaterialContext terrain = profiledContext(atlasBase, 4.0f);
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

    @Test
    void transactionRollback() {
        FakeFactory factory = new FakeFactory();
        MaterialMapOwner owner =
                MaterialMapOwner.withImages(markerImage(), markerImage(),
                        factory);
        VulkanImage base = markerImage();
        var prior = new ChimeraTextureBindingState.Snapshot(markerImage(), 123L);
        Map<Integer, ChimeraTextureBindingState.Snapshot> slots = new HashMap<>();
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
        var manifest = new ProgramImageBindingManifest(List.of(
                new ProgramImageBindingManifest.Entry(
                        "normals", 25, 1, 63,
                        ProgramImageBindingManifest.Kind.MATERIAL_MAP,
                        "normals", 0, 9, "normals"),
                new ProgramImageBindingManifest.Entry(
                        "broken", 26, 1, 63,
                        ProgramImageBindingManifest.Kind.MATERIAL_MAP,
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
}
