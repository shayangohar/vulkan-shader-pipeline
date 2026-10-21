package net.chimera.render;

import net.chimera.shaderpack.PackAdvancedResourcePlan;
import net.chimera.shaderpack.ProgramImageBindingManifest;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Map;
import java.util.TreeMap;


/** Owns advanced writable images for one pack session. */
public final class PackAdvancedImageOwner implements AutoCloseable {
    private final PackAdvancedResourcePlan plan;
    private final Map<String, PackStorageImage> images = new TreeMap<>();
    private final Map<String, String> failures = new TreeMap<>();
    private boolean closed;

    /** Pure policy used by the owner and deterministic conformance tests. */
    public static boolean shouldInitializeImage(boolean clearEachFrame, boolean initialized) {
        return clearEachFrame || !initialized;
    }

    private PackAdvancedImageOwner(PackAdvancedResourcePlan plan) {
        this.plan = plan == null ? PackAdvancedResourcePlan.empty() : plan;
    }

    public static PackAdvancedImageOwner load(PackAdvancedResourcePlan plan) {
        PackAdvancedImageOwner owner = new PackAdvancedImageOwner(plan);
        for (PackAdvancedResourcePlan.ImageSpec spec : owner.plan.images().values()) {
            if (!spec.supported()) continue;
            try {
                owner.images.put(spec.name(), PackStorageImage.create(spec));
            } catch (RuntimeException failure) {
                owner.failures.put(spec.name(), failure.getMessage() == null
                        ? "advanced image allocation failed" : failure.getMessage());
            }
        }
        return owner;
    }

    /**
     * Whether every image {@code program} binds exists. Iris runs each program on its own: a
     * consumer runs whether or not the program that writes its images does, and reads what the
     * frame's clear left.
     */
    public boolean programImagesAvailable(String program) {
        for (PackAdvancedResourcePlan.GraphicsImageBinding binding : plan.imageBindings(program)) {
            if (imageForSymbol(binding.symbol()) == null) return false;
        }
        return true;
    }

    /** Whether every image the shadowcomp binds exists, so it can dispatch. */
    public boolean computeImagesAvailable() {
        return requiredImagesAvailable();
    }

    /**
     * Makes every earlier shader write to the images visible to later shader reads and writes, as
     * Iris's glMemoryBarrier before each program does. Recorded outside a render pass.
     */
    public void afterImageWrites(VkCommandBuffer commandBuffer) {
        if (commandBuffer == null || closed || images.isEmpty()) return;
        PackVulkanBarriers.afterShaderImageWrites(commandBuffer);
    }

    public boolean needsGraphicsTransition(ProgramImageBindingManifest manifest) {
        for (var entry : manifest.entries()) {
            if (entry.kind() != ProgramImageBindingManifest.Kind.ADVANCED_IMAGE) continue;
            PackStorageImage image = images.get(entry.resourceKey());
            if (image != null && image.getCurrentLayout() != VK10.VK_IMAGE_LAYOUT_GENERAL) return true;
        }
        return false;
    }

    public void prepareGraphicsImages(VkCommandBuffer commandBuffer,
            ProgramImageBindingManifest manifest) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (var entry : manifest.entries()) {
                if (entry.kind() != ProgramImageBindingManifest.Kind.ADVANCED_IMAGE) continue;
                PackStorageImage image = images.get(entry.resourceKey());
                if (image != null) image.transitionToGeneral(stack, commandBuffer);
            }
        }
    }

    /**
     * Applies the pack clear policy at the start of each world frame, before any program runs
     * (Iris clears its clear images at beginLevelRendering).
     *
     * A non-clear image is persistent after its first frame, but Vulkan does
     * not initialize newly allocated image memory. Seed every new image once
     * before a producer or consumer can read it. This is required for packs
     * such as BSL, whose persistent light volumes are read and written by the
     * same compute stage. Later frames preserve those contents unless the
     * pack explicitly requested a clear.
     */
    public void prepareFrame(VkCommandBuffer commandBuffer) {
        if (commandBuffer == null || closed || images.isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean hasClear = plan.images().values().stream().anyMatch(
                    spec -> {
                        PackStorageImage image = images.get(spec.name());
                        return image != null
                                && shouldInitializeImage(spec.clear(), image.initialized());
                    });
            if (hasClear) {
                PackVulkanBarriers.beforeTransfer(commandBuffer, stack);
            }
            for (PackAdvancedResourcePlan.ImageSpec spec : plan.images().values()) {
                PackStorageImage image = images.get(spec.name());
                if (image == null || !shouldInitializeImage(spec.clear(), image.initialized())) {
                    continue;
                }
                image.clearToZero(stack, commandBuffer);
                image.markInitialized();
            }
            if (hasClear) {
                PackVulkanBarriers.afterTransfer(commandBuffer, stack);
            }
        }
    }

    public PackAdvancedResourcePlan plan() { return plan; }
    public Map<String, String> failures() { return Map.copyOf(failures); }

    public int plannedImageCount() {
        return (int) plan.images().values().stream().filter(PackAdvancedResourcePlan.ImageSpec::supported).count();
    }

    public int allocatedImageCount() {
        return images.size();
    }

    public int initializedImageCount() {
        return (int) images.values().stream().filter(PackStorageImage::initialized).count();
    }

    public VulkanImage image(String name) {
        return images.get(name);
    }

    PackStorageImage imageForSymbol(String symbol) {
        PackAdvancedResourcePlan.ImageSpec spec = plan.imageForSymbol(symbol);
        return spec == null ? null : images.get(spec.name());
    }

    public boolean available(String name) {
        return images.containsKey(name) && failures.get(name) == null;
    }

    private boolean requiredImagesAvailable() {
        for (PackAdvancedResourcePlan.ComputeSpec compute : plan.computeStages().values()) {
            if (!compute.supported()) continue;
            for (String name : compute.imageNames()) {
                if (imageForSymbol(name) == null) return false;
            }
            for (String name : compute.samplerNames()) {
                if (imageForSymbol(name) == null) return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        images.values().forEach(PackStorageImage::free);
        images.clear();
        failures.clear();
    }
}
