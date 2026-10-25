package net.chimera.render;

import net.chimera.shaderpack.PackResourceBinding;
import net.chimera.shaderpack.PackResourceKind;
import net.chimera.shaderpack.PackResourcePlan;
import net.chimera.shaderpack.PackResourceStatus;
import net.chimera.shaderpack.RawTextureSpec;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Owns immutable pack-sampled images for one pack session. */
public final class PackResourceOwner implements AutoCloseable {
    /** One loaded pack image: a PNG or game texture, or a raw texture. */
    interface LoadedTexture extends AutoCloseable {
        VulkanImage image();

        @Override
        void close();
    }

    /** The statuses whose image this owner makes: a pack file or a generated texture. */
    private static final Set<PackResourceStatus> LOADED_STATUSES = EnumSet.of(
            PackResourceStatus.PACK_FILE, PackResourceStatus.GENERATED);

    private final PackResourcePlan plan;
    private final Path shadersDir;
    private final Supplier<MaterialMapOwner> materials;
    private final Map<String, LoadedTexture> textures = new HashMap<>();
    /** Game textures by resource key, looked up at every bind (PackGameTexture); the game owns them. */
    private final Map<String, PackGameTexture.Source> gameTextures = new HashMap<>();
    private final Map<String, String> failures = new HashMap<>();
    private boolean closed;

    private PackResourceOwner(PackResourcePlan plan, Path shadersDir, Supplier<MaterialMapOwner> materials) {
        this.plan = plan == null ? PackResourcePlan.empty() : plan;
        this.shadersDir = shadersDir;
        this.materials = materials;
    }

    /** Loads the plan's pack images; {@code materials} serves the PBR maps a game texture's name asks for. */
    public static PackResourceOwner load(PackResourcePlan plan, Path shadersDir, Supplier<MaterialMapOwner> materials) {
        PackResourceOwner owner = new PackResourceOwner(plan, shadersDir, materials);
        for (PackResourceBinding binding : plan.bindings().values().stream()
                .flatMap(Collection::stream)
                .sorted(Comparator.comparing(PackResourceBinding::resourceKey))
                .toList()) {
            owner.loadOnce(binding);
        }
        return owner;
    }

    /**
     * Makes the binding's image unless it is not this owner's to make or a try already made or failed
     * it. A game texture is only named here; {@link #image(String)} looks it up at each bind.
     */
    private void loadOnce(PackResourceBinding binding) {
        String key = binding.resourceKey();
        if (!closed && binding.status() == PackResourceStatus.GAME_RESOURCE && !gameTextures.containsKey(key)) {
            PackGameTexture.Source source = PackGameTexture.Source.parse(binding.source());
            if (source != null) gameTextures.put(key, source);
            else failures.put(key, "not a namespaced location: " + binding.source());
        }
        if (closed || !LOADED_STATUSES.contains(binding.status())
                || textures.containsKey(key) || failures.containsKey(key)) {
            return;
        }
        try {
            LoadedTexture texture = binding.status() == PackResourceStatus.PACK_FILE
                    ? loadPackFile(binding, shadersDir)
                    : PackResourcePlan.GENERATED_UNBOUND.equals(binding.source())
                    ? PackNoiseTexture.unbound("chimera_pack_" + key)
                    : PackNoiseTexture.generated(
                            PackResourcePlan.generatedNoiseSize(binding.source()), "chimera_pack_" + key);
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

    public boolean programAvailable(String program) {
        for (PackResourceBinding binding : plan.bindings(program)) {
            if (!binding.available()) continue;
            if (binding.kind() == PackResourceKind.TARGET
                    || binding.kind() == PackResourceKind.DEPTH
                    || binding.kind() == PackResourceKind.SHADOW_DEPTH
                    || binding.kind() == PackResourceKind.SHADOW_COLOR
                    || binding.kind() == PackResourceKind.ADVANCED_IMAGE
                    || binding.kind() == PackResourceKind.MATERIAL_MAP
                    || binding.kind() == PackResourceKind.CENTER_DEPTH) {
                continue;
            }
            String key = binding.resourceKey();
            if (!textures.containsKey(key) && !gameTextures.containsKey(key)) return false;
        }
        return true;
    }

    /** The image bound for {@code resourceKey} now: a loaded pack image, or a game texture looked up afresh. */
    public VulkanImage image(String resourceKey) {
        PackGameTexture.Source game = gameTextures.get(resourceKey);
        if (game != null) return closed ? null : PackGameTexture.image(game, materials.get());
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
        gameTextures.clear();
        failures.clear();
    }
}
