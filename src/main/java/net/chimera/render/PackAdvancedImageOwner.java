package net.chimera.render;

import net.chimera.shaderpack.PackAdvancedResourcePlan;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;


/** Owns advanced writable images for one pack session. */
public final class PackAdvancedImageOwner implements AutoCloseable {
    private final PackAdvancedResourcePlan plan;
    private final Map<String, PackStorageImage> images = new TreeMap<>();
    private final Map<String, String> failures = new TreeMap<>();
    private boolean closed;
    private boolean computeInstalled;
    private final Set<String> candidateGraphicsPrograms = new TreeSet<>();
    private final Set<String> installedGraphicsPrograms = new TreeSet<>();

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

    /** Capability remains false until the compute pipeline has been installed. */
    public boolean capabilityEnabled() {
        return computeInstalled && resourcesAvailable()
                && plan.capabilityPossible()
                && (!plan.requiresGraphicsProducer()
                || producerCoverage(installedGraphicsPrograms));
    }

    /**
     * True while the planned graphics producers are compiled and their images
     * are available. This admits dependent pipeline construction only; runtime
     * dispatch still requires the committed producer set from capabilityEnabled.
     */
    public boolean producerCandidatesReady() {
        return computeInstalled && resourcesAvailable() && plan.capabilityPossible()
                && (!plan.requiresGraphicsProducer()
                || producerCoverage(candidateGraphicsPrograms));
    }

    /** Records a compiled producer before dependent consumer pipelines build. */
    public void markGraphicsProducerCandidate(String program) {
        if (program != null && !program.isBlank()
                && plan.graphicsImages().containsKey(program)
                && requiredGraphicsImagesAvailable()) {
            candidateGraphicsPrograms.add(program);
        }
    }

    /** Commits a candidate only after a compatible consumer is installed. */
    public void commitGraphicsProducer(String program) {
        if (candidateGraphicsPrograms.contains(program)) {
            installedGraphicsPrograms.add(program);
        }
    }

    /** True when every image needed by the planned graphics or compute path exists. */
    public boolean resourcesAvailable() {
        return requiredImagesAvailable() && requiredGraphicsImagesAvailable();
    }

    public void markComputeInstalled() {
        if (plan.hasSupportedCompute() && resourcesAvailable()) computeInstalled = true;
    }

    public boolean needsGraphicsTransition(net.chimera.shaderpack.ProgramImageBindingManifest manifest) {
        for (var entry : manifest.entries()) {
            if (entry.kind() != net.chimera.shaderpack.ProgramImageBindingManifest.Kind.ADVANCED_IMAGE) continue;
            PackStorageImage image = images.get(entry.resourceKey());
            if (image != null && image.getCurrentLayout() != org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL) return true;
        }
        return false;
    }

    public void prepareGraphicsImages(VkCommandBuffer commandBuffer,
            net.chimera.shaderpack.ProgramImageBindingManifest manifest) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (var entry : manifest.entries()) {
                if (entry.kind() != net.chimera.shaderpack.ProgramImageBindingManifest.Kind.ADVANCED_IMAGE) continue;
                PackStorageImage image = images.get(entry.resourceKey());
                if (image != null) image.transitionToGeneral(stack, commandBuffer);
            }
        }
    }

    /** Transitions the images written by one guarded graphics program to GENERAL. */
    public void prepareGraphicsWrites(VkCommandBuffer commandBuffer, String program) {
        if (commandBuffer == null || !installedGraphicsPrograms.contains(program)) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (PackAdvancedResourcePlan.GraphicsImageBinding binding : plan.graphicsImages(program)) {
                PackStorageImage image = imageForSymbol(binding.symbol());
                if (image != null) image.transitionToGeneral(stack, commandBuffer);
            }
        }
    }

    /** True when this program will transition pack-owned images for a graphics write. */
    public boolean hasGraphicsWrites(String program) {
        return program != null && installedGraphicsPrograms.contains(program)
                && !plan.graphicsImages(program).isEmpty();
    }

    /**
     * Applies the pack clear policy at the start of each shadow frame.
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
        PackStorageImage direct = images.get(symbol);
        if (direct != null) return direct;
        for (PackAdvancedResourcePlan.ImageSpec spec : plan.images().values()) {
            if (spec.sampler().equals(symbol)) return images.get(spec.name());
        }
        return null;
    }

    PackAdvancedResourcePlan.ImageSpec specForSymbol(String symbol) {
        PackAdvancedResourcePlan.ImageSpec direct = plan.images().get(symbol);
        if (direct != null) return direct;
        return plan.images().values().stream()
                .filter(value -> value.sampler().equals(symbol))
                .findFirst().orElse(null);
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

    private boolean requiredGraphicsImagesAvailable() {
        for (String program : plan.graphicsImageReaders().keySet()) {
            for (PackAdvancedResourcePlan.GraphicsImageBinding binding : plan.imageBindings(program)) {
                if (imageForSymbol(binding.symbol()) == null) return false;
            }
        }
        for (String program : plan.graphicsImages().keySet()) {
            for (PackAdvancedResourcePlan.GraphicsImageBinding binding : plan.imageBindings(program)) {
                if (imageForSymbol(binding.symbol()) == null) return false;
            }
        }
        return true;
    }

    private boolean producerCoverage(Set<String> programs) {
        return plan.graphicsImageNames(programs).containsAll(plan.requiredGraphicsImageNames());
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        images.values().forEach(PackStorageImage::free);
        images.clear();
        failures.clear();
        computeInstalled = false;
        candidateGraphicsPrograms.clear();
        installedGraphicsPrograms.clear();
    }
}
