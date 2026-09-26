package net.chimera.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.vulkanmod.render.engine.VkSampler;
import net.vulkanmod.render.engine.VkTextureView;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Owns resource-pack material images for one renderer lifetime.
 *
 * <p>Fallback core first: two permanent 1x1 images with the contractual
 * flat texels, so material-capable programs always bind something valid.
 * Static atlas companions join here: the upload mixin reports stitched
 * layouts, the pump builds exact-layout companions at a safe render-thread
 * seam, and descriptor resolution keeps answering flat fallbacks until
 * Slice C joins the transaction. Simple-texture companions, reload
 * generations, and animation uploads arrive in later slices.</p>
 */
public final class MaterialMapOwner implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(MaterialMapOwner.class);

    /** Material sampler name, resource-pack suffix, and canonical flat texel. */
    public enum MaterialMapKind {
        NORMALS("normals", "_n", 0xFFFF7F7F),
        SPECULAR("specular", "_s", 0x00000000);

        private final String sampler;
        private final String suffix;
        private final int fallbackAbgr;

        MaterialMapKind(String sampler, String suffix, int fallbackAbgr) {
            this.sampler = sampler;
            this.suffix = suffix;
            this.fallbackAbgr = fallbackAbgr;
        }

        public String sampler() { return sampler; }
        public String suffix() { return suffix; }
        public int fallbackAbgr() { return fallbackAbgr; }

        public static MaterialMapKind forSampler(String sampler) {
            for (MaterialMapKind kind : values()) {
                if (kind.sampler.equals(sampler)) return kind;
            }
            return null;
        }
    }

    private final DynamicTexture normalsTexture;
    private final DynamicTexture specularTexture;
    private final VulkanImage normalsImage;
    private final VulkanImage specularImage;
    private final CompanionFactory companionFactory;
    private final MaterialSamplerFactory materialSamplerFactory;
    /** Cached owned material samplers by full effective profile; closed with the owner. */
    private final Map<MaterialSamplerKey, OwnedMaterialSampler> ownedSamplers = new LinkedHashMap<>();
    private final Object companionsLock = new Object();
    /** Latest pending upload per atlas location; the pump drains this on the render thread. */
    private final Map<String, PendingUpload> pendingUploads = new LinkedHashMap<>();
    /** Built companions per atlas location, keyed by exact base-image identity. */
    private final Map<String, BuiltAtlas> builtAtlases = new LinkedHashMap<>();
    /** Draw-observed non-atlas albedo images awaiting pump-time identification. */
    private final Set<VulkanImage> wantedSimple =
            Collections.newSetFromMap(new IdentityHashMap<>());
    /** Resolved simple-texture companions by exact albedo identity. */
    private final Map<VulkanImage, SimpleCompanion> simpleCompanions =
            new IdentityHashMap<>();
    /** Albedo images proven to have no maps; never re-scanned within a generation. */
    private final Set<VulkanImage> simpleMisses =
            Collections.newSetFromMap(new IdentityHashMap<>());
    /** Companion sets retired by a generation bump, closed at the next pump. */
    private final List<AutoCloseable> retiredCompanions = new ArrayList<>();
    /** Resource-reload generation; invalidates built companions and identity caches. */
    private int generation;
    /** Cap on queued unknowns per pump so one frame cannot grow the queue without bound. */
    private static final int MAX_SIMPLE_WANTED = 256;
    private boolean closed;

    /**
     * One stitched sprite, copied out of the upload preparations. The
     * origin is the padded slot origin while width/height are the logical
     * content dimensions; the paddings recover the replicated border the
     * stitcher placed around the content. Only square borders are
     * supported: X and Y padding must agree.
     */
    public record AtlasSprite(String id, String namespace, String path, int x, int y, int width,
            int height, int padX, int padY) {}

    /** Immutable snapshot of one {@code TextureAtlas.upload} call. */
    public record AtlasUpload(String location, int width, int height, int mipLevels,
            List<AtlasSprite> sprites) {}

    /**
     * Opens one resource-pack file for reading. Returns empty when the file
     * is absent (a missing map is a fallback, not an error). The owner
     * closes every stream it receives.
     */
    public interface ResourceLookup {
        Optional<InputStream> open(String namespace, String path);
    }

    /** Decoded material file: ABGR pixels matching {@link MaterialMapPixels}. */
    public record DecodedImage(int[] pixels, int width, int height) {}

    /** Converts a PNG stream to ABGR pixels. Production uses NativeImage; tests use ImageIO. */
    public interface MapImageDecoder {
        DecodedImage decode(InputStream in) throws IOException;
    }

    /** One resource-backed simple texture: its albedo image plus its resource identity. */
    public record SimpleEntry(VulkanImage image, String namespace, String path) {}

    /**
     * Live resource-backed simple textures for pump-time identity matching.
     * Production enumerates {@code TextureManager} through the narrow
     * accessor; tests substitute a map-backed fake.
     */
    public interface SimpleTextureSource {
        List<SimpleEntry> entries();
    }

    /** One mip chain of ABGR pixels for a single companion texture. */
    public record LevelPixels(int[] pixels, int width, int height) {}

    /** GPU backing for one kind's companion. Tests substitute a recording fake. */
    public interface CompanionSet extends AutoCloseable {
        VulkanImage image();
        @Override void close();
    }

    /** Allocates GPU storage for one kind's companion and uploads its levels. */
    public interface CompanionFactory {
        CompanionSet build(String location, MaterialMapKind kind, int width, int height,
                List<LevelPixels> levels);
    }

    private record PendingUpload(AtlasUpload upload, VulkanImage baseImage) {}

    private record BuiltAtlas(VulkanImage baseImage, Map<MaterialMapKind, CompanionSet> sets,
            boolean labPbr) implements AutoCloseable {
        @Override
        public void close() {
            for (CompanionSet set : sets.values()) {
                try {
                    set.close();
                } catch (RuntimeException failure) {
                    LOGGER.warn("[chimera] material maps: companion close failed", failure);
                }
            }
        }
    }

    private record SimpleCompanion(Map<MaterialMapKind, CompanionSet> sets, boolean labPbr)
            implements AutoCloseable {
        @Override
        public void close() {
            for (CompanionSet set : sets.values()) {
                try {
                    set.close();
                } catch (RuntimeException failure) {
                    LOGGER.warn("[chimera] material maps: simple close failed", failure);
                }
            }
        }
    }

    /** One resolved companion image plus the labPBR mode it was built under. */
    private record CompanionMatch(VulkanImage image, boolean labPbr, boolean simple) {}

    /**
     * Cache key for one owned material sampler: every Vulkan sampler field
     * Chimera controls. Two profiles that differ in any field, including
     * max LOD, own different samplers.
     */
    private record MaterialSamplerKey(AddressMode addressU, AddressMode addressV,
            FilterMode minFilter, FilterMode magFilter, int anisotropy, float maxLod) {}

    /** One cached material sampler owned by this owner. */
    public interface OwnedMaterialSampler extends AutoCloseable {
        long id();
        @Override void close();
    }

    /**
     * Allocates owned material samplers. Production wraps VkSampler;
     * tests record the requested fields.
     */
    public interface MaterialSamplerFactory {
        OwnedMaterialSampler create(AddressMode addressU, AddressMode addressV,
                FilterMode minFilter, FilterMode magFilter, int anisotropy, float maxLod);
    }

    private MaterialMapOwner(DynamicTexture normalsTexture, VulkanImage normalsImage,
            DynamicTexture specularTexture, VulkanImage specularImage,
            CompanionFactory companionFactory, MaterialSamplerFactory materialSamplerFactory) {
        this.normalsTexture = normalsTexture;
        this.normalsImage = normalsImage;
        this.specularTexture = specularTexture;
        this.specularImage = specularImage;
        this.companionFactory = companionFactory;
        this.materialSamplerFactory = materialSamplerFactory;
    }

    /** Test and forward seam: an owner over prebuilt images without GPU allocation. */
    public static MaterialMapOwner withImages(VulkanImage normalsImage, VulkanImage specularImage) {
        return withImages(normalsImage, specularImage, (location, kind, width, height, levels) -> {
            throw new UnsupportedOperationException("MATERIAL_MAP_NO_GPU:test owner");
        });
    }

    /** Test seam: prebuilt fallbacks plus a caller-supplied companion factory. */
    public static MaterialMapOwner withImages(VulkanImage normalsImage, VulkanImage specularImage,
            CompanionFactory companionFactory) {
        return withImages(normalsImage, specularImage, companionFactory,
                (addressU, addressV, minFilter, magFilter, anisotropy, maxLod) -> {
                    throw new UnsupportedOperationException("MATERIAL_MAP_NO_GPU:test owner");
                });
    }

    /** Test seam: prebuilt fallbacks plus caller-supplied companion and sampler factories. */
    public static MaterialMapOwner withImages(VulkanImage normalsImage, VulkanImage specularImage,
            CompanionFactory companionFactory, MaterialSamplerFactory materialSamplerFactory) {
        return new MaterialMapOwner(null, normalsImage, null, specularImage, companionFactory,
                materialSamplerFactory);
    }

    /**
     * Allocates the two permanent flat fallbacks on the calling thread.
     * Call only where GPU uploads are legal (pack install on the render
     * thread); never during a draw.
     */
    public static MaterialMapOwner create() {
        FlatImage normals = flat(MaterialMapKind.NORMALS);
        FlatImage specular;
        try {
            specular = flat(MaterialMapKind.SPECULAR);
        } catch (RuntimeException | Error failure) {
            normals.texture().close();
            throw failure;
        }
        LOGGER.info("[chimera] material maps: flat fallbacks ready normals={} specular={}",
                normals.image().getId(), specular.image().getId());
        return new MaterialMapOwner(normals.texture(), normals.image(),
                specular.texture(), specular.image(), MaterialMapOwner::buildGpuCompanions,
                MaterialMapOwner::buildMaterialSampler);
    }

    private static OwnedMaterialSampler buildMaterialSampler(
            AddressMode addressU, AddressMode addressV,
            FilterMode minFilter, FilterMode magFilter, int anisotropy, float maxLod) {
        java.util.OptionalDouble lod =
                maxLod >= 1000.0f ? java.util.OptionalDouble.empty() : java.util.OptionalDouble.of(maxLod);
        VkSampler sampler = new VkSampler(addressU, addressV, minFilter, magFilter, anisotropy, lod);
        LOGGER.info("[chimera] material maps: owned sampler {} min={} mag={} address={}/{} "
                        + "anisotropy={} maxLod={}",
                sampler.getId(), minFilter, magFilter, addressU, addressV, anisotropy, maxLod);
        return new OwnedMaterialSampler() {
            @Override
            public long id() {
                return sampler.getId();
            }

            @Override
            public void close() {
                sampler.close();
            }
        };
    }

    private record FlatImage(DynamicTexture texture, VulkanImage image) {}

    private static FlatImage flat(MaterialMapKind kind) {
        NativeImage pixels = new NativeImage(1, 1, false);
        boolean owned = true;
        try {
            pixels.setPixelABGR(0, 0, kind.fallbackAbgr());
            DynamicTexture texture = new DynamicTexture(
                    () -> "chimera_material_flat_" + kind.sampler(), pixels);
            owned = false;
            texture.upload();
            GpuTextureView view = texture.getTextureView();
            if (!(view instanceof VkTextureView vulkanView)) {
                texture.close();
                throw new IllegalStateException("material fallback has no Vulkan view: " + kind.sampler());
            }
            VulkanImage image = vulkanView.texture().getVulkanImage();
            if (image == null) {
                texture.close();
                throw new IllegalStateException("material fallback has no Vulkan image: " + kind.sampler());
            }
            return new FlatImage(texture, image);
        } finally {
            if (owned) pixels.close();
        }
    }

    /**
     * Resolves one material sampler to its current image. Atlas companions
     * build beside this path, but descriptors keep answering the flat
     * fallbacks until Slice C joins the transaction on exact albedo
     * identity (see {@link #companionFor}). Unknown names fail loudly so
     * a bad manifest entry can never silently bind the wrong map.
     */
    public VulkanImage image(String sampler) {
        return snapshot(sampler).image();
    }

    /** Full binding answer including the exact sampler override. */
    public ChimeraTextureBindingState.Snapshot snapshot(String sampler) {
        MaterialMapKind kind = MaterialMapKind.forSampler(sampler);
        if (kind == null) {
            throw new IllegalArgumentException("MATERIAL_MAP_UNKNOWN:" + sampler);
        }
        VulkanImage image = kind == MaterialMapKind.NORMALS ? normalsImage : specularImage;
        return new ChimeraTextureBindingState.Snapshot(image,
                image == null ? null : image.getSampler());
    }

    /**
     * Queues one stitched atlas for companion building. Keeps only immutable
     * metadata, so the upload path never blocks on file or GPU work. The
     * latest upload per location wins.
     */
    public void noteAtlasUpload(AtlasUpload upload, VulkanImage baseImage) {
        if (upload == null || baseImage == null || upload.sprites().isEmpty()) return;
        synchronized (companionsLock) {
            if (closed) return;
            pendingUploads.put(upload.location(), new PendingUpload(upload, baseImage));
        }
    }

    /**
     * Builds every queued atlas companion. Runs on the render thread at a
     * safe seam: no render pass is open and no draw can observe a half-built
     * companion. A failed atlas build keeps the previous companions and
     * warns; it never throws out of the pump.
     */
    public void pumpPendingBuilds(ResourceLookup lookup, MapImageDecoder decoder) {
        pumpPendingBuilds(lookup, decoder, null);
    }

    /**
     * Full pump: atlas companions, then simple-texture identification, then
     * retirement of the previous generation. A null source skips the simple
     * step and leaves the wanted queue for the next pump.
     */
    public void pumpPendingBuilds(ResourceLookup lookup, MapImageDecoder decoder,
            SimpleTextureSource source) {
        List<PendingUpload> due;
        synchronized (companionsLock) {
            if (closed) return;
            closeRetiredLocked();
            due = new ArrayList<>(pendingUploads.values());
            pendingUploads.clear();
        }
        boolean labPbr = detectLabPbr(lookup);
        for (PendingUpload pending : due) {
            try {
                buildAtlas(pending.upload(), pending.baseImage(), lookup, decoder, labPbr);
            } catch (RuntimeException failure) {
                LOGGER.warn("[chimera] material maps: atlas build failed, keeping previous: {}",
                        pending.upload().location(), failure);
            }
        }
        pumpSimpleWanted(lookup, decoder, source, labPbr);
        synchronized (companionsLock) {
            closeRetiredLocked();
        }
    }

    /** Current resource-reload generation; zero before the first reload. */
    public int generation() {
        synchronized (companionsLock) {
            return generation;
        }
    }
    /**
     * Advances the resource-reload generation. TextureManager re-registers
     * every texture on reload, so simple identity caches retire here: their
     * GPU sets close at the next pump and misses re-queue from live draws.
     * Built atlas companions and pending uploads stay: the reload's own
     * re-stitch displaces stale atlas entries, and retiring here would kill
     * companions the pump built earlier in the same reload (the listener
     * applies after texture work, so a bump always lands after fresh
     * builds). Stale atlas entries are unreachable by construction: draws
     * bind the new base image, which resolves only to its own companions
     * or to flat fallbacks. Returns the new generation.
     */
    public int bumpGeneration() {
        synchronized (companionsLock) {
            generation++;
            for (SimpleCompanion simple : simpleCompanions.values()) {
                retiredCompanions.add(simple);
            }
            simpleCompanions.clear();
            simpleMisses.clear();
            wantedSimple.clear();
            LOGGER.info("[chimera] material maps: generation {} started, {} simple sets retired",
                    generation, retiredCompanions.size());
            return generation;
        }
    }

    private void closeRetiredLocked() {
        if (retiredCompanions.isEmpty()) return;
        List<AutoCloseable> due = new ArrayList<>(retiredCompanions);
        retiredCompanions.clear();
        for (AutoCloseable retired : due) {
            try {
                retired.close();
            } catch (Exception failure) {
                LOGGER.warn("[chimera] material maps: retired companion close failed", failure);
            }
        }
    }

    /**
     * Notes one draw-time albedo image for simple-texture identification.
     * Atlas bases and cached identities return immediately; anything else
     * queues once for the next pump. Metadata only, safe during a draw.
     */
    public void noteDrawAlbedo(VulkanImage base) {
        synchronized (companionsLock) {
            if (closed || base == null) return;
            if (isKnownBaseLocked(base) || simpleMisses.contains(base)
                    || wantedSimple.contains(base)) return;
            if (wantedSimple.size() >= MAX_SIMPLE_WANTED) return;
            wantedSimple.add(base);
        }
    }

    /**
     * Draw-time material resolution from an immutable captured draw
     * context. Atlas and simple companions answer with the context's
     * sampler policy; anything else falls back to the flat maps. The
     * transaction still owns rollback around this call. The context is
     * captured once per draw before the transaction, so this method never
     * re-queries mutable global slot state.
     */
    public ChimeraTextureBindingState.Snapshot resolveDrawMaterial(
            DrawMaterialContext context, String sampler) {
        MaterialMapKind kind = MaterialMapKind.forSampler(sampler);
        if (kind == null) {
            throw new IllegalArgumentException("MATERIAL_MAP_UNKNOWN:" + sampler);
        }
        VulkanImage base = context.albedo();
        if (base != null) noteDrawAlbedo(base);
        CompanionMatch match = base == null ? null : findCompanion(base, kind);
        if (match == null || match.image() == null) {
            // Flat fallbacks stand in for the maps, so they sample with the
            // draw's own policy exactly like companions do.
            ChimeraTextureBindingState.Snapshot flat = snapshot(sampler);
            SamplerProfile profile = context.profile();
            long id = profile != null && profile.id() != 0
                    ? profile.id()
                    : flat.image().getSampler();
            return new ChimeraTextureBindingState.Snapshot(flat.image(), id);
        }
        long id = resolveSamplerId(context.profile(), base, match, kind);
        return new ChimeraTextureBindingState.Snapshot(match.image(), id);
    }

    private long resolveSamplerId(SamplerProfile profile, VulkanImage base,
            CompanionMatch match, MaterialMapKind kind) {
        boolean labSpecular = kind == MaterialMapKind.SPECULAR && match.labPbr();
        if (!match.simple() && !labSpecular) {
            // Atlas normals and non-labPBR specular reuse the exact albedo sampler.
            if (profile != null && profile.id() != 0) return profile.id();
            return fallbackSampler(base, match);
        }
        if (profile == null) {
            return fallbackSampler(base, match);
        }
        // Owned samplers: atlas labPBR specular filters NEAREST inside its
        // mip levels with the source ceiling; every simple map keeps the
        // source min/mag/address/anisotropy policy but clamps to LOD zero
        // (NEAREST pair for labPBR specular).
        FilterMode minFilter = labSpecular ? FilterMode.NEAREST : profile.minFilter();
        FilterMode magFilter = labSpecular ? FilterMode.NEAREST : profile.magFilter();
        float maxLod = match.simple() ? 0.0f : profile.maxLod();
        if (profile == null) {
            return fallbackSampler(base, match);
        }
        if (profile.addressU() == null || profile.addressV() == null
                || minFilter == null || magFilter == null) {
            // The draw sampler ID is still meaningful; only derivation is impossible.
            if (profile.id() != 0) return profile.id();
            return fallbackSampler(base, match);
        }
        Long owned = ownedSamplerId(profile.addressU(), profile.addressV(),
                minFilter, magFilter, profile.anisotropy(), maxLod);
        if (owned != null) return owned;
        return fallbackSampler(base, match);
    }

    private static long fallbackSampler(VulkanImage base, CompanionMatch match) {
        if (base.getSampler() != 0) return base.getSampler();
        return match.image().getSampler();
    }

    /**
     * Cached owned sampler for one effective profile. Returns null when the
     * owner is closed mid-draw; the caller falls back to the base image ID.
     */
    private Long ownedSamplerId(AddressMode addressU, AddressMode addressV,
            FilterMode minFilter, FilterMode magFilter, int anisotropy, float maxLod) {
        MaterialSamplerKey key = new MaterialSamplerKey(
                addressU, addressV, minFilter, magFilter, anisotropy, maxLod);
        synchronized (companionsLock) {
            if (closed) return null;
            OwnedMaterialSampler existing = ownedSamplers.get(key);
            if (existing != null) return existing.id();
            OwnedMaterialSampler created = materialSamplerFactory.create(
                    addressU, addressV, minFilter, magFilter, anisotropy, maxLod);
            ownedSamplers.put(key, created);
            return created.id();
        }
    }

    private CompanionMatch findCompanion(VulkanImage base, MaterialMapKind kind) {
        synchronized (companionsLock) {
            for (BuiltAtlas built : builtAtlases.values()) {
                if (built.baseImage() == base) {
                    CompanionSet set = built.sets().get(kind);
                    return new CompanionMatch(
                            set == null ? null : set.image(), built.labPbr(), false);
                }
            }
            SimpleCompanion simple = simpleCompanions.get(base);
            if (simple != null) {
                CompanionSet set = simple.sets().get(kind);
                return new CompanionMatch(
                        set == null ? null : set.image(), simple.labPbr(), true);
            }
            return new CompanionMatch(null, false, false);
        }
    }

    private boolean isKnownBaseLocked(VulkanImage base) {
        for (BuiltAtlas built : builtAtlases.values()) {
            if (built.baseImage() == base) return true;
        }
        return simpleCompanions.containsKey(base);
    }

    /** Slice C seam: the companion for an exact albedo image, or null when it has none. */
    public ChimeraTextureBindingState.Snapshot companionFor(VulkanImage baseImage, String sampler) {
        MaterialMapKind kind = MaterialMapKind.forSampler(sampler);
        if (kind == null) {
            throw new IllegalArgumentException("MATERIAL_MAP_UNKNOWN:" + sampler);
        }
        CompanionMatch match = baseImage == null
                ? new CompanionMatch(null, false, false)
                : findCompanion(baseImage, kind);
        if (match.image() == null) return null;
        return new ChimeraTextureBindingState.Snapshot(match.image(),
                match.image().getSampler());
    }

    private void pumpSimpleWanted(ResourceLookup lookup, MapImageDecoder decoder,
            SimpleTextureSource source, boolean labPbr) {
        List<VulkanImage> due;
        synchronized (companionsLock) {
            if (closed || wantedSimple.isEmpty() || source == null) return;
            due = new ArrayList<>(wantedSimple);
            wantedSimple.clear();
        }
        for (VulkanImage albedo : due) {
            try {
                buildSimple(albedo, lookup, decoder, source, labPbr);
            } catch (RuntimeException failure) {
                LOGGER.warn("[chimera] material maps: simple build failed, staying fallback", failure);
                synchronized (companionsLock) {
                    simpleMisses.add(albedo);
                }
            }
        }
    }

    private void buildSimple(VulkanImage albedo, ResourceLookup lookup, MapImageDecoder decoder,
            SimpleTextureSource source, boolean labPbr) {
        SimpleEntry match = null;
        for (SimpleEntry entry : source.entries()) {
            if (entry.image() == albedo) {
                match = entry;
                break;
            }
        }
        synchronized (companionsLock) {
            if (closed) return;
            if (isKnownBaseLocked(albedo)) return;
            if (match == null) {
                simpleMisses.add(albedo);
                return;
            }
        }
        String matchId = match.namespace() + ":" + match.path();
        Map<MaterialMapKind, CompanionSet> sets = new EnumMap<>(MaterialMapKind.class);
        Map<MaterialMapKind, String> dims = new EnumMap<>(MaterialMapKind.class);
        try {
            for (MaterialMapKind kind : MaterialMapKind.values()) {
                String resource = MaterialMapPixels.siblingResource(
                        match.namespace(), match.path(), kind.suffix());
                int separator = resource.indexOf(':');
                try (InputStream in = lookup.open(
                        resource.substring(0, separator), resource.substring(separator + 1))
                        .orElse(null)) {
                    if (in == null) continue;
                    DecodedImage decoded = decoder.decode(in);
                    MaterialMapPixels.checkSize(decoded.width(), decoded.height(), resource);
                    List<LevelPixels> levels = List.of(
                            new LevelPixels(decoded.pixels(), decoded.width(), decoded.height()));
                    sets.put(kind, companionFactory.build(
                            matchId, kind, decoded.width(), decoded.height(), levels));
                    dims.put(kind, decoded.width() + "x" + decoded.height());
                } catch (IOException | IllegalArgumentException failure) {
                    LOGGER.warn("[chimera] material maps: skipping {} for {}: {}",
                            resource, matchId, failure.toString());
                }
            }
        } catch (RuntimeException | Error failure) {
            for (CompanionSet set : sets.values()) {
                try {
                    set.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
        synchronized (companionsLock) {
            if (closed || isKnownBaseLocked(albedo)) {
                for (CompanionSet set : sets.values()) {
                    try {
                        set.close();
                    } catch (RuntimeException failure) {
                        LOGGER.warn("[chimera] material maps: late simple close failed", failure);
                    }
                }
                return;
            }
            if (sets.isEmpty()) {
                simpleMisses.add(albedo);
                return;
            }
            simpleCompanions.put(albedo, new SimpleCompanion(sets, labPbr));
        }
        LOGGER.info("[chimera] material maps: simple {} kinds={} dims={} labpbr={} generation={}",
                matchId, sets.keySet(), dims.values(), labPbr, generation);
    }

    private void buildAtlas(AtlasUpload upload, VulkanImage baseImage,
            ResourceLookup lookup, MapImageDecoder decoder, boolean labPbr) {
        long started = System.nanoTime();
        Map<MaterialMapKind, CompanionSet> sets = new EnumMap<>(MaterialMapKind.class);
        int mappedSprites = 0;
        try {
            List<AtlasSprite> sprites = upload.sprites().stream()
                    .sorted(Comparator.comparing(AtlasSprite::id))
                    .toList();
            for (MaterialMapKind kind : MaterialMapKind.values()) {
                List<PlacedSprite> placed = decodeKind(sprites, kind, lookup, decoder);
                if (placed.isEmpty()) continue;
                mappedSprites = Math.max(mappedSprites, placed.size());
                List<LevelPixels> levels = buildLevels(upload, kind, placed, labPbr);
                sets.put(kind, companionFactory.build(
                        upload.location(), kind, upload.width(), upload.height(), levels));
            }
        } catch (RuntimeException | Error failure) {
            for (CompanionSet set : sets.values()) {
                try {
                    set.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
        synchronized (companionsLock) {
            if (closed) {
                for (CompanionSet set : sets.values()) {
                    try {
                        set.close();
                    } catch (RuntimeException failure) {
                        LOGGER.warn("[chimera] material maps: late companion close failed", failure);
                    }
                }
                return;
            }
            BuiltAtlas previous = builtAtlases.put(upload.location(),
                    new BuiltAtlas(baseImage, sets, labPbr));
            if (previous != null) previous.close();
        }
        long millis = (System.nanoTime() - started) / 1_000_000L;
        if (sets.isEmpty()) {
            LOGGER.info("[chimera] material maps: atlas {} has no material sprites, "
                    + "fallbacks stay bound ({}x{}, {} mips)",
                    upload.location(), upload.width(), upload.height(), upload.mipLevels());
        } else {
            LOGGER.info("[chimera] material maps: atlas {} kinds={} sprites={}/{} {}x{} {} mips "
                    + "labpbr={} base={} in {}ms",
                    upload.location(), sets.keySet(), mappedSprites, upload.sprites().size(),
                    upload.width(), upload.height(), upload.mipLevels(), labPbr,
                    baseImage.getId(), millis);
        }
    }

    private static boolean detectLabPbr(ResourceLookup lookup) {
        try (InputStream in = lookup.open("minecraft", "optifine/texture.properties")
                .orElse(null)) {
            if (in == null) return false;
            return MaterialMapPixels.detectLabPbr(in);
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("[chimera] material maps: texture.properties unreadable, "
                    + "assuming linear mips", failure);
            return false;
        }
    }

    /** One sprite whose material file decoded cleanly, scaled to its logical slot size. */
    private record PlacedSprite(AtlasSprite sprite, int[] levelZero, int width, int height) {}

    private static List<PlacedSprite> decodeKind(List<AtlasSprite> sprites, MaterialMapKind kind,
            ResourceLookup lookup, MapImageDecoder decoder) {
        List<PlacedSprite> placed = new ArrayList<>();
        for (AtlasSprite sprite : sprites) {
            if (sprite.padX() != sprite.padY()) {
                LOGGER.warn("[chimera] material maps: skipping {} with asymmetric padding {}x{}",
                        sprite.id(), sprite.padX(), sprite.padY());
                continue;
            }
            if (sprite.padX() < 0) {
                LOGGER.warn("[chimera] material maps: skipping {} with negative padding {}",
                        sprite.id(), sprite.padX());
                continue;
            }
            String resource = MaterialMapPixels.siblingResource(
                    sprite.namespace(), sprite.path(), kind.suffix());
            int separator = resource.indexOf(':');
            DecodedImage decoded;
            try (InputStream in = lookup.open(
                    resource.substring(0, separator), resource.substring(separator + 1))
                    .orElse(null)) {
                if (in == null) continue;
                decoded = decoder.decode(in);
                MaterialMapPixels.checkSize(decoded.width(), decoded.height(), resource);
            } catch (IOException | IllegalArgumentException failure) {
                LOGGER.warn("[chimera] material maps: skipping {} for {}: {}",
                        resource, sprite.id(), failure.toString());
                continue;
            }
            if (sprite.width() <= 0 || sprite.height() <= 0) {
                LOGGER.warn("[chimera] material maps: skipping {} with empty slot {}x{}",
                        sprite.id(), sprite.width(), sprite.height());
                continue;
            }
            int[] scaled = MaterialMapPixels.scale(
                    decoded.pixels(), decoded.width(), decoded.height(),
                    sprite.width(), sprite.height());
            placed.add(new PlacedSprite(sprite, scaled, sprite.width(), sprite.height()));
        }
        return placed;
    }

    /**
     * Builds one exact-layout mip chain per kind. Each sprite contributes
     * its own padded image: the logical chain reduces sprite-locally, each
     * level grows a replicated border of {@code pad >> level}, and the
     * complete padded image lands at the slot origin shifted by level. The
     * canvas starts at fallback, so missing sprites leave their whole
     * padded slot untouched. Sprites never negotiate shared pixels, so no
     * occupancy map or ring algorithm exists.
     */
    private static List<LevelPixels> buildLevels(AtlasUpload upload, MaterialMapKind kind,
            List<PlacedSprite> placed, boolean labPbr) {
        MaterialMapPixels.checkSize(upload.width(), upload.height(), upload.location());
        if (upload.mipLevels() < 1) {
            throw new IllegalArgumentException("MATERIAL_MAP_MIPS:" + upload.location());
        }
        List<LevelPixels> levels = new ArrayList<>(upload.mipLevels());
        List<int[]> current = new ArrayList<>(placed.size());
        for (PlacedSprite sprite : placed) {
            current.add(sprite.levelZero());
        }
        for (int level = 0; level < upload.mipLevels(); level++) {
            int canvasWidth = MaterialMapPixels.levelSize(upload.width(), level);
            int canvasHeight = MaterialMapPixels.levelSize(upload.height(), level);
            int[] canvas = new int[canvasWidth * canvasHeight];
            Arrays.fill(canvas, kind.fallbackAbgr());
            for (int i = 0; i < placed.size(); i++) {
                PlacedSprite sprite = placed.get(i);
                int pad = sprite.sprite().padX() >> level;
                int logicalWidth = MaterialMapPixels.levelSize(sprite.width(), level);
                int logicalHeight = MaterialMapPixels.levelSize(sprite.height(), level);
                int[] padded = MaterialMapPixels.padReplicate(
                        current.get(i), logicalWidth, logicalHeight, pad);
                MaterialMapPixels.placeRect(canvas, canvasWidth, canvasHeight,
                        sprite.sprite().x() >> level, sprite.sprite().y() >> level,
                        padded, logicalWidth + pad * 2, logicalHeight + pad * 2);
            }
            levels.add(new LevelPixels(canvas, canvasWidth, canvasHeight));
            if (level + 1 < upload.mipLevels()) {
                List<int[]> next = new ArrayList<>(placed.size());
                for (int i = 0; i < placed.size(); i++) {
                    int w = MaterialMapPixels.levelSize(placed.get(i).width(), level + 1);
                    int h = MaterialMapPixels.levelSize(placed.get(i).height(), level + 1);
                    next.add(reduceFor(kind, labPbr, current.get(i),
                            MaterialMapPixels.levelSize(placed.get(i).width(), level),
                            MaterialMapPixels.levelSize(placed.get(i).height(), level), w, h));
                }
                current = next;
            }
        }
        return levels;
    }

    private static int[] reduceFor(MaterialMapKind kind, boolean labPbr, int[] src,
            int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        if (kind == MaterialMapKind.SPECULAR && labPbr) {
            return MaterialMapPixels.reduceLabPbr(src, srcWidth, srcHeight, dstWidth, dstHeight);
        }
        return MaterialMapPixels.reduceLinear(src, srcWidth, srcHeight, dstWidth, dstHeight);
    }

    /** Production decoder over {@code NativeImage}; the caller closes the stream. */
    public static DecodedImage decodeNative(InputStream in) throws IOException {
        NativeImage image = NativeImage.read(in);
        try {
            MaterialMapPixels.checkSize(image.getWidth(), image.getHeight(), "decoded");
            return new DecodedImage(image.getPixelsABGR(), image.getWidth(), image.getHeight());
        } finally {
            image.close();
        }
    }

    private static CompanionSet buildGpuCompanions(String location, MaterialMapKind kind,
            int width, int height, List<LevelPixels> levels) {
        GpuTexture texture = RenderSystem.getDevice().createTexture(
                () -> "chimera_material_" + kind.sampler() + "_" + location,
                GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST
                        | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                TextureFormat.RGBA8, width, height, 1, levels.size());
        boolean installed = false;
        try {
            List<NativeImage> retained = new ArrayList<>(levels.size());
            for (int level = 0; level < levels.size(); level++) {
                LevelPixels pixels = levels.get(level);
                NativeImage nativeLevel = toNative(pixels.pixels(), pixels.width(), pixels.height());
                retained.add(nativeLevel);
                RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, nativeLevel,
                        level, 0, 0, 0, pixels.width(), pixels.height(), 0, 0);
            }
            GpuTextureView view = RenderSystem.getDevice().createTextureView(texture);
            if (!(view instanceof VkTextureView vulkanView)
                    || vulkanView.texture().getVulkanImage() == null) {
                view.close();
                throw new IllegalStateException("material companion has no Vulkan image: "
                        + location + "/" + kind.sampler());
            }
            installed = true;
            return new GpuCompanionSet(texture, view,
                    vulkanView.texture().getVulkanImage(), retained);
        } finally {
            if (!installed) texture.close();
        }
    }

    private record GpuCompanionSet(GpuTexture texture, GpuTextureView view, VulkanImage image,
            List<NativeImage> retainedLevels) implements CompanionSet {
        @Override
        public void close() {
            for (NativeImage level : retainedLevels) {
                try {
                    level.close();
                } catch (RuntimeException failure) {
                    LOGGER.warn("[chimera] material maps: level close failed", failure);
                }
            }
            try {
                view.close();
            } finally {
                texture.close();
            }
        }
    }

    private static NativeImage toNative(int[] pixels, int width, int height) {
        NativeImage image = new NativeImage(width, height, false);
        boolean filled = false;
        try {
            for (int i = 0; i < pixels.length; i++) {
                image.setPixelABGR(i % width, i / width, pixels[i]);
            }
            filled = true;
            return image;
        } finally {
            if (!filled) image.close();
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        synchronized (companionsLock) {
            pendingUploads.clear();
            wantedSimple.clear();
            for (BuiltAtlas built : builtAtlases.values()) {
                try {
                    built.close();
                } catch (RuntimeException failure) {
                    LOGGER.warn("[chimera] material maps: atlas close failed", failure);
                }
            }
            builtAtlases.clear();
            for (SimpleCompanion simple : simpleCompanions.values()) {
                try {
                    simple.close();
                } catch (RuntimeException failure) {
                    LOGGER.warn("[chimera] material maps: simple close failed", failure);
                }
            }
            simpleCompanions.clear();
            simpleMisses.clear();
            for (OwnedMaterialSampler owned : ownedSamplers.values()) {
                try {
                    owned.close();
                } catch (RuntimeException failure) {
                    LOGGER.warn("[chimera] material maps: owned sampler close failed", failure);
                }
            }
            ownedSamplers.clear();
            closeRetiredLocked();
        }
        if (normalsTexture != null) normalsTexture.close();
        if (specularTexture != null && specularTexture != normalsTexture) specularTexture.close();
    }
}
