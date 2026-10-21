package net.chimera.render;

import net.chimera.shaderpack.PackResourceBinding;
import net.chimera.shaderpack.PackResourceKind;
import net.chimera.shaderpack.PackResourcePlan;
import net.chimera.shaderpack.PackResourceStatus;
import net.chimera.shaderpack.RawTextureSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Owns immutable pack-sampled images for one pack session. */
public final class PackResourceOwner implements AutoCloseable {
    /** One loaded pack image: a PNG or game texture, or a raw texture. */
    interface LoadedTexture extends AutoCloseable {
        VulkanImage image();

        @Override
        void close();
    }

    /** The statuses whose image this owner makes: a pack file, a game resource or the generated noise. */
    private static final Set<PackResourceStatus> LOADED_STATUSES = EnumSet.of(
            PackResourceStatus.PACK_FILE, PackResourceStatus.GAME_RESOURCE, PackResourceStatus.GENERATED);

    private final PackResourcePlan plan;
    private final Path shadersDir;
    private final Map<String, LoadedTexture> textures = new HashMap<>();
    private final Map<String, String> failures = new HashMap<>();
    private boolean closed;

    private PackResourceOwner(PackResourcePlan plan, Path shadersDir) {
        this.plan = plan == null ? PackResourcePlan.empty() : plan;
        this.shadersDir = shadersDir;
    }

    public static PackResourceOwner load(PackResourcePlan plan, Path shadersDir) {
        PackResourceOwner owner = new PackResourceOwner(plan, shadersDir);
        for (PackResourceBinding binding : plan.bindings().values().stream()
                .flatMap(Collection::stream)
                .sorted(Comparator.comparing(PackResourceBinding::resourceKey))
                .toList()) {
            owner.loadOnce(binding);
        }
        return owner;
    }

    /** Makes the binding's image unless it is not this owner's to make or a try already made or failed it. */
    private void loadOnce(PackResourceBinding binding) {
        String key = binding.resourceKey();
        if (closed || !LOADED_STATUSES.contains(binding.status())
                || textures.containsKey(key) || failures.containsKey(key)) {
            return;
        }
        try {
            LoadedTexture texture = switch (binding.status()) {
                case PACK_FILE -> loadPackFile(binding, shadersDir);
                case GENERATED -> PackNoiseTexture.generated(
                        PackResourcePlan.generatedNoiseSize(binding.source()), "chimera_pack_" + key);
                default -> loadGameResource(binding);
            };
            if (texture == null) {
                failures.put(key, "resource unavailable");
            } else {
                textures.put(key, texture);
            }
        } catch (Exception failure) {
            failures.put(key, failure.getMessage());
        }
    }

    /**
     * The image of a binding no program planned, such as a pass compute's texture: made on first
     * use and owned with the rest. Null when it cannot be made; {@link #failure} says why.
     */
    public VulkanImage image(PackResourceBinding binding) {
        loadOnce(binding);
        return image(binding.resourceKey());
    }

    private static LoadedTexture loadPackFile(PackResourceBinding binding, Path shadersDir)
            throws IOException {
        String name = "chimera_pack_" + binding.resourceKey();
        if (binding.kind() == PackResourceKind.RAW_TEXTURE) {
            RawTextureSpec spec = RawTextureSpec.parse(binding.source());
            Path path = spec == null ? null : PackResourcePlan.resolvePath(shadersDir, spec.file());
            return path == null ? null : PackRawTexture.load(path, spec, name, binding.filter(), binding.wrap());
        }
        Path path = PackResourcePlan.resolvePath(shadersDir, binding.source());
        if (path == null) return null;
        return PackNoiseTexture.load(path, name, binding.filter(), binding.wrap());
    }

    private static PackNoiseTexture loadGameResource(PackResourceBinding binding) throws IOException {
        String source = binding.source();
        ResourceLocation location;
        try {
            location = ResourceLocation.parse(source);
        } catch (RuntimeException failure) {
            return null;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null) return null;
        Optional<Resource> resource = client.getResourceManager().getResource(location);
        if (resource.isEmpty()) return null;
        try (InputStream input = resource.get().open()) {
            return PackNoiseTexture.load(input, "chimera_pack_" + binding.resourceKey(),
                    binding.filter(), binding.wrap());
        }
    }

    public boolean programAvailable(String program) {
        for (PackResourceBinding binding : plan.bindings(program)) {
            if (!binding.available()) continue;
            if (binding.kind() == PackResourceKind.TARGET
                    || binding.kind() == PackResourceKind.DEPTH
                    || binding.kind() == PackResourceKind.SHADOW_DEPTH
                    || binding.kind() == PackResourceKind.SHADOW_COLOR
                    || binding.kind() == PackResourceKind.ADVANCED_IMAGE
                    || binding.kind() == PackResourceKind.MATERIAL_MAP) {
                continue;
            }
            if (!textures.containsKey(binding.resourceKey())) return false;
        }
        return true;
    }

    public VulkanImage image(String resourceKey) {
        LoadedTexture texture = textures.get(resourceKey);
        return texture == null ? null : texture.image();
    }

    public PackResourceBinding binding(String program, String sampler) {
        return plan.binding(program, sampler);
    }

    public String failure(String resourceKey) {
        return failures.get(resourceKey);
    }

    /** Returns the first deterministic runtime-load deviation for a program. */
    public String failureDeviationForProgram(String program) {
        return plan.bindings(program).stream()
                .filter(binding -> LOADED_STATUSES.contains(binding.status()))
                .filter(binding -> !textures.containsKey(binding.resourceKey()))
                .map(binding -> "PACK_TEXTURE_LOAD_FAILED:" + binding.resourceKey())
                .sorted()
                .findFirst()
                .orElse("PACK_RESOURCE_LOAD_FAILED");
    }

    public PackResourcePlan plan() {
        return plan;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (LoadedTexture texture : textures.values()) {
            texture.close();
        }
        textures.clear();
        failures.clear();
    }
}
