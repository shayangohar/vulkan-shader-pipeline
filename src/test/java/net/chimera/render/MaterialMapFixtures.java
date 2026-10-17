package net.chimera.render;

import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VulkanImage;

import javax.imageio.ImageIO;
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

import static org.lwjgl.opengl.GL11.GL_LEQUAL;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NONE;
import static org.lwjgl.opengl.GL11.GL_REPEAT;

/** The mini resource pack, sprite layouts and recording fakes the material-map tests share. */
final class MaterialMapFixtures {
    private MaterialMapFixtures() {}

    static final Path FIXTURE = Path.of("src/test/resources/m87_material_pack");

    static final int FALLBACK_NORMALS = 0xFFFF7F7F;

    static final int FALLBACK_SPECULAR = 0x00000000;

    static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    static MaterialMapOwner.AtlasUpload layout() {
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
    static MaterialMapOwner.AtlasUpload layoutPadded() {
        return new MaterialMapOwner.AtlasUpload("minecraft:padded", 64, 64, 4, List.of(
                sprite("minecraft:block/m87_brick", 8, 8, 16, 16, 2, 2),
                sprite("minecraft:block/m87_edge", 28, 8, 16, 16, 2, 2),
                sprite("minecraft:block/m87_plain", 48, 8, 8, 8, 2, 2),
                sprite("minecraft:block/m87_lab", 8, 32, 4, 4, 1, 1)));
    }

    static MaterialMapOwner.AtlasSprite sprite(String id, int x, int y, int w, int h) {
        return sprite(id, x, y, w, h, 0, 0);
    }

    static MaterialMapOwner.AtlasSprite sprite(String id, int x, int y, int w, int h,
            int padX, int padY) {
        int separator = id.indexOf(':');
        return new MaterialMapOwner.AtlasSprite(id, id.substring(0, separator),
                id.substring(separator + 1), x, y, w, h, padX, padY);
    }

    static Map<String, byte[]> fixtureFiles() throws IOException {
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

    static MaterialMapOwner.ResourceLookup mapLookup(Map<String, byte[]> files) {
        return (namespace, path) -> Optional.ofNullable(files.get(namespace + ":" + path))
                .map(content -> (InputStream) new ByteArrayInputStream(content));
    }

    static DrawMaterialContext contextFor(VulkanImage base, long samplerId) {
        return new DrawMaterialContext(base, SamplerProfile.ofId(samplerId));
    }

    /**
     * A repeating, trilinear key with mip ceiling {@code maxLod}, as VulkanMod builds a mipmapped
     * texture's sampler (biased by -0.5).
     */
    static SamplerManager.SamplerKey trilinearKey(float maxLod) {
        return new SamplerManager.SamplerKey(GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR, GL_REPEAT, GL_REPEAT, GL_REPEAT,
                0.0F, maxLod, -0.5F, GL_NONE, GL_LEQUAL);
    }

    static DrawMaterialContext profiledContext(VulkanImage base, float maxLod) {
        return new DrawMaterialContext(base, new SamplerProfile(0x7777L, trilinearKey(maxLod)));
    }

    static final class CountingLookup implements MaterialMapOwner.ResourceLookup {
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

    /**
     * Animated sprites, each map carrying its own table: anim_n cycles
     * 0 (2 ticks) -> 2 (3 ticks) -> 1 (1 tick) without interpolation;
     * blend_s interpolates two frames of 4 ticks; single_n names one frame
     * and so stays static; badmeta_n names a frame outside its strip and
     * baddims_n cannot be cut into frames, so both stay flat alone. Brick
     * is a static neighbour whose slot animation must never touch.
     */
    static MaterialMapOwner.AtlasUpload layoutAnimated(String location) {
        return new MaterialMapOwner.AtlasUpload(location, 32, 32, 3, List.of(
                sprite("minecraft:block/m87_anim", 0, 0, 4, 4, 1, 1),
                sprite("minecraft:block/m87_blend", 8, 0, 4, 4, 1, 1),
                sprite("minecraft:block/m87_single", 16, 0, 4, 4, 0, 0),
                sprite("minecraft:block/m87_badmeta", 0, 8, 4, 4, 0, 0),
                sprite("minecraft:block/m87_baddims", 8, 8, 4, 4, 0, 0),
                sprite("minecraft:block/m87_brick", 16, 16, 16, 16, 0, 0)));
    }

    static final class FakeSet implements MaterialMapOwner.CompanionSet {
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

    /** SamplerManager's sampler cache: one sampler per distinct key, recorded in creation order. */
    static final class FakeSamplers implements MaterialMapOwner.MaterialSamplerFactory {
        final List<SamplerManager.SamplerKey> requests = new ArrayList<>();
        private final Map<SamplerManager.SamplerKey, Long> samplers = new LinkedHashMap<>();
        long nextId = 0x9E450L;

        @Override
        public long sampler(SamplerManager.SamplerKey key) {
            return samplers.computeIfAbsent(key, created -> {
                requests.add(created);
                return nextId++;
            });
        }
    }

    static final class FakeFactory implements MaterialMapOwner.CompanionFactory {
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

    static MaterialMapOwner.DecodedImage decodeImageIO(InputStream in) throws IOException {
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

    static int[] readFixtureAbgr(String relative) throws IOException {
        try (InputStream in = Files.newInputStream(FIXTURE.resolve(relative))) {
            MaterialMapOwner.DecodedImage decoded = decodeImageIO(in);
            return decoded.pixels();
        }
    }

    static VulkanImage markerImage() {
        try {
            var constructor = VulkanImage.class.getDeclaredConstructor(VulkanImage.Builder.class);
            constructor.setAccessible(true);
            return constructor.newInstance(VulkanImage.builder(1, 1));
        } catch (ReflectiveOperationException failure) {
            throw new RuntimeException(failure);
        }
    }

    static int pack(int alpha, int blue, int green, int red) {
        return MaterialMapPixels.pack(alpha, blue, green, red);
    }
}
