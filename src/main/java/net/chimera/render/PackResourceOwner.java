package net.chimera.render;

import net.chimera.shaderpack.PackResourceBinding;
import net.chimera.shaderpack.PackResourceKind;
import net.chimera.shaderpack.PackResourcePlan;
import net.chimera.shaderpack.PackResourceStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Owns immutable pack-sampled images for one pack session. */
public final class PackResourceOwner implements AutoCloseable {
    private final PackResourcePlan plan;
    private final Map<String, PackNoiseTexture> textures = new HashMap<>();
    private final Map<String, String> failures = new HashMap<>();
    private boolean closed;

    private PackResourceOwner(PackResourcePlan plan) {
        this.plan = plan == null ? PackResourcePlan.empty() : plan;
    }

    public static PackResourceOwner load(PackResourcePlan plan, Path shadersDir) {
        PackResourceOwner owner = new PackResourceOwner(plan);
        for (PackResourceBinding binding : plan.bindings().values().stream()
                .flatMap(java.util.Collection::stream)
                .sorted(java.util.Comparator.comparing(PackResourceBinding::resourceKey))
                .toList()) {
            if (binding.status() != PackResourceStatus.PACK_FILE
                    && binding.status() != PackResourceStatus.GAME_RESOURCE) {
                continue;
            }
            if (owner.textures.containsKey(binding.resourceKey())
                    || owner.failures.containsKey(binding.resourceKey())) {
                continue;
            }
            try {
                PackNoiseTexture texture = binding.status() == PackResourceStatus.PACK_FILE
                        ? loadPackFile(binding, shadersDir)
                        : loadGameResource(binding);
                if (texture == null) {
                    owner.failures.put(binding.resourceKey(), "resource unavailable");
                } else {
                    owner.textures.put(binding.resourceKey(), texture);
                }
            } catch (Exception failure) {
                owner.failures.put(binding.resourceKey(), failure.getMessage());
            }
        }
        return owner;
    }

    private static PackNoiseTexture loadPackFile(PackResourceBinding binding, Path shadersDir)
            throws IOException {
        Path path = PackResourcePlan.resolvePath(shadersDir, binding.source());
        if (path == null) return null;
        return PackNoiseTexture.load(path, "chimera_pack_" + binding.resourceKey(),
                binding.filter(), binding.wrap());
    }

    private static PackNoiseTexture loadGameResource(PackResourceBinding binding) throws IOException {
        String source = binding.source();
        Identifier location;
        try {
            location = Identifier.parse(source);
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
        PackNoiseTexture texture = textures.get(resourceKey);
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
                .filter(binding -> binding.status() == PackResourceStatus.PACK_FILE
                        || binding.status() == PackResourceStatus.GAME_RESOURCE)
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
        for (PackNoiseTexture texture : textures.values()) {
            texture.close();
        }
        textures.clear();
        failures.clear();
    }
}
