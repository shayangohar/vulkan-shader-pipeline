package net.chimera.render;

import com.mojang.blaze3d.textures.GpuSampler;
import net.chimera.ChimeraMod;
import net.vulkanmod.render.engine.VkSampler;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps pack sampler overrides separate from VulkanMod image identity.
 *
 * <p>The host render path owns a texture-view and sampler pair. Pack draws
 * therefore use this short-lived selector-side override instead of changing
 * the shared image. The terrain sampler comes from Minecraft's
 * {@code LevelRenderer.chunkLayerSampler}; it is never inferred from another
 * image binding.</p>
 */
public final class ChimeraTextureBindingState {
    private static final BindingStore<VulkanImage> STORE = new BindingStore<>();
    private static final ThreadLocal<DescriptorBinding> DESCRIPTOR = new ThreadLocal<>();

    private static TerrainSamplerInfo terrainSamplerInfo;
    private static SamplerProfile terrainSamplerProfile;
    private static final Map<Integer, SamplerProfile> samplerProfiles = new HashMap<>();
    private static long loggedTerrainImage = Long.MIN_VALUE;
    private static long loggedTerrainSampler = Long.MIN_VALUE;

    private ChimeraTextureBindingState() {}

    /** Captures the exact sampler selected by LevelRenderer for chunk terrain. */
    public static void captureChunkSampler(GpuSampler sampler) {
        if (!(sampler instanceof VkSampler vkSampler) || vkSampler.getId() == 0L) {
            synchronized (ChimeraTextureBindingState.class) {
                STORE.clearTerrainSampler();
                terrainSamplerInfo = null;
                terrainSamplerProfile = null;
            }
            return;
        }
        TerrainSamplerInfo info = new TerrainSamplerInfo(
                vkSampler.getId(),
                String.valueOf(vkSampler.getMinFilter()),
                String.valueOf(vkSampler.getMagFilter()),
                String.valueOf(vkSampler.getAddressModeU()),
                String.valueOf(vkSampler.getAddressModeV()),
                vkSampler.getMaxAnisotropy());
        SamplerProfile profile = SamplerProfile.of(sampler, vkSampler.getId());
        synchronized (ChimeraTextureBindingState.class) {
            STORE.setTerrainSampler(info.id());
            terrainSamplerInfo = info;
            terrainSamplerProfile = profile;
        }
    }

    /** Authoritative chunk sampler profile, or null when never captured. */
    public static SamplerProfile terrainSamplerProfile() {
        synchronized (ChimeraTextureBindingState.class) {
            return terrainSamplerProfile;
        }
    }

    /** Recorded profile for one selector slot, or null when never marked. */
    public static SamplerProfile samplerProfile(int slot) {
        synchronized (ChimeraTextureBindingState.class) {
            return samplerProfiles.get(slot);
        }
    }

    /** Binds selector slot 0 to the authoritative terrain sampler pair. */
    public static void bindTerrainAtlas(VulkanImage image) {
        if (image == null) {
            clearPackBinding(0);
            return;
        }
        BindingStore.Entry<VulkanImage> entry;
        TerrainSamplerInfo info;
        synchronized (ChimeraTextureBindingState.class) {
            entry = STORE.bindTerrainAtlas(image, image.getSampler());
            info = terrainSamplerInfo;
            long imageId = image.getId();
            if (imageId != loggedTerrainImage || entry.sampler() != loggedTerrainSampler) {
                loggedTerrainImage = imageId;
                loggedTerrainSampler = entry.sampler();
                if (info == null || info.id() != entry.sampler()) {
                    LOGGER.warn("[chimera] pack terrain binding: image={} sampler={} "
                                    + "authoritative chunk sampler unavailable; using image sampler; atlasMipLevels={}",
                            imageId, entry.sampler(), image.mipLevels);
                } else {
                    LOGGER.info("[chimera] pack terrain binding: image={} sampler={} minFilter={} "
                                    + "magFilter={} addressU={} addressV={} anisotropy={} atlasMipLevels={}",
                            imageId, entry.sampler(), info.minFilter(), info.magFilter(),
                            info.addressU(), info.addressV(), info.anisotropy(), image.mipLevels);
                }
            }
        }
    }

    /** Records the normal image sampler for a pack selector slot. */
    public static void markPackBinding(int slot, VulkanImage image) {
        if (image == null) {
            clearPackBinding(slot);
            return;
        }
        synchronized (ChimeraTextureBindingState.class) {
            STORE.bind(slot, image, image.getSampler());
            samplerProfiles.put(slot, SamplerProfile.ofId(image.getSampler()));
        }
    }

    /** Records a host texture pair redirected during an entity or sky draw. */
    public static void markPackBinding(int slot, VulkanImage image, long sampler) {
        if (image == null || sampler == 0L) {
            clearPackBinding(slot);
            return;
        }
        synchronized (ChimeraTextureBindingState.class) {
            STORE.bindExact(slot, image, sampler);
            samplerProfiles.put(slot, SamplerProfile.ofId(sampler));
        }
    }

    /** Records a host texture pair with its exact sampler profile. */
    public static void markPackBinding(int slot, VulkanImage image, GpuSampler sampler) {
        if (image == null || sampler == null) {
            clearPackBinding(slot);
            return;
        }
        if (!(sampler instanceof VkSampler vkSampler) || vkSampler.getId() == 0L) {
            markPackBinding(slot, image, image.getSampler());
            return;
        }
        synchronized (ChimeraTextureBindingState.class) {
            STORE.bindExact(slot, image, vkSampler.getId());
            samplerProfiles.put(slot, SamplerProfile.of(sampler, vkSampler.getId()));
        }
    }

    public record Snapshot(VulkanImage image, Long samplerOverride) {}

    public static synchronized Snapshot capture(int slot, VulkanImage image) {
        var entry = STORE.binding(slot);
        return new Snapshot(image, entry != null && entry.image() == image ? entry.sampler() : null);
    }

    public static synchronized void restore(int slot, Snapshot snapshot) {
        if (snapshot.samplerOverride() == null) STORE.clear(slot);
        else STORE.bindExact(slot, snapshot.image(), snapshot.samplerOverride());
    }

    public static void clearPackBinding(int slot) {
        synchronized (ChimeraTextureBindingState.class) {
            STORE.clear(slot);
            samplerProfiles.remove(slot);
        }
    }

    public static void beginDescriptor(int slot, VulkanImage image) {
        DESCRIPTOR.set(new DescriptorBinding(slot, image));
    }

    /** Consumed by the descriptor-set sampler read immediately after getImage(). */
    public static long samplerForDescriptor(VulkanImage image, long fallback) {
        DescriptorBinding descriptor = DESCRIPTOR.get();
        DESCRIPTOR.remove();
        if (descriptor == null || image == null || descriptor.image != image) {
            return fallback;
        }
        synchronized (ChimeraTextureBindingState.class) {
            BindingStore.Entry<VulkanImage> binding = STORE.binding(descriptor.slot);
            return binding != null && binding.image() == image ? binding.sampler() : fallback;
        }
    }

    /** Clears thread-local and session selector state at pack cleanup boundaries. */
    public static void reset() {
        synchronized (ChimeraTextureBindingState.class) {
            STORE.reset();
            samplerProfiles.clear();
            terrainSamplerInfo = null;
            terrainSamplerProfile = null;
            loggedTerrainImage = Long.MIN_VALUE;
            loggedTerrainSampler = Long.MIN_VALUE;
        }
        DESCRIPTOR.remove();
    }

    /** Small identity-based state machine used by runtime code and behavioral tests. */
    static final class BindingStore<I> {
        private final Map<Integer, Entry<I>> bindings = new HashMap<>();
        private long terrainSampler;

        void setTerrainSampler(long sampler) {
            this.terrainSampler = sampler;
        }

        void clearTerrainSampler() {
            this.terrainSampler = 0L;
        }

        Entry<I> bindTerrainAtlas(I image, long fallbackSampler) {
            long sampler = terrainSampler == 0L ? fallbackSampler : terrainSampler;
            Entry<I> entry = new Entry<>(image, sampler);
            bindings.put(0, entry);
            return entry;
        }

        void bind(int slot, I image, long sampler) {
            bindings.put(slot, new Entry<>(image, sampler));
        }

        void bindExact(int slot, I image, long sampler) {
            bindings.put(slot, new Entry<>(image, sampler));
        }

        Entry<I> binding(int slot) {
            return bindings.get(slot);
        }

        Map<Integer, Entry<I>> snapshot() {
            return new HashMap<>(bindings);
        }

        void restore(Map<Integer, Entry<I>> snapshot) {
            bindings.clear();
            if (snapshot != null) {
                bindings.putAll(snapshot);
            }
        }

        void clear(int slot) {
            bindings.remove(slot);
        }

        void reset() {
            bindings.clear();
            terrainSampler = 0L;
        }

        static record Entry<I>(I image, long sampler) {}
    }

    private record DescriptorBinding(int slot, VulkanImage image) {}

    private record TerrainSamplerInfo(
            long id,
            String minFilter,
            String magFilter,
            String addressU,
            String addressV,
            int anisotropy
    ) {}

    private static final org.slf4j.Logger LOGGER = ChimeraMod.LOGGER;
}
