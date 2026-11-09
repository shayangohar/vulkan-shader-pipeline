package net.chimera.render;

import net.chimera.ChimeraMod;
import net.chimera.shaderpack.BetweenStages;
import net.chimera.shaderpack.PackAdvancedResourcePlan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntConsumer;

import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE;
import static org.lwjgl.vulkan.VK10.vkCmdFillBuffer;

/** Owns the bounded read/write storage buffers for one pack session. */
public final class PackStorageBufferOwner implements AutoCloseable {
    private final PackAdvancedResourcePlan plan;
    private final Map<Integer, PackStorageBuffer> buffers = new TreeMap<>();
    private final Map<Integer, String> failures = new TreeMap<>();
    private final Map<String, String> programFailures = new HashMap<>();
    private final Set<String> attachedPrograms = new TreeSet<>();
    private final Set<String> installedPrograms = new TreeSet<>();
    private final Set<String> verifiedLayouts = new TreeSet<>();
    private final long plannedBytes;
    private int screenWidth;
    private int screenHeight;
    private boolean initialized;
    private boolean closed;
    private final Map<Integer, String> drawnWriters = new HashMap<>();
    private final Set<Integer> synchronizedWrites = new TreeSet<>();
    private final Set<String> reportedReaders = new TreeSet<>();

    public void beginExecutionFrame() {
        drawnWriters.clear();
        synchronizedWrites.clear();
    }

    /** Called only after a native draw command was recorded, never on descriptor upload. */
    public void recordDraw(String program, int stage) {
        for (var binding : plan.storageBuffers(program)) {
            if (!binding.supported() || !buffers.containsKey(binding.logicalIndex())
                    || (binding.stageMask() & stage) == 0) continue;
            int index = binding.logicalIndex();
            if (stage == VK_SHADER_STAGE_VERTEX_BIT) {
                drawnWriters.put(index, program);
                synchronizedWrites.remove(index);
            } else if (stage == VK10.VK_SHADER_STAGE_FRAGMENT_BIT
                    && drawnWriters.containsKey(index) && synchronizedWrites.contains(index)
                    && reportedReaders.add(program + ":" + index)) {
                ChimeraMod.LOGGER.info("[chimera] storage execution: writer={},reader={},offset=0,range={},"
                        + "writerDrawn=true,barrierRecorded=true,readerDrawn=true",
                        drawnWriters.get(index), program, binding.size());
            }
        }
    }

    public PackStorageBufferOwner(PackAdvancedResourcePlan plan) {
        this(plan, 1, 1, Set.of());
    }

    private PackStorageBufferOwner(PackAdvancedResourcePlan plan, int screenWidth, int screenHeight,
                                   Set<Integer> computeBuffers) {
        this.plan = plan == null ? PackAdvancedResourcePlan.empty() : plan;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.plannedBytes = used(this.plan, computeBuffers).stream()
                .map(this.plan.buffers()::get)
                .filter(Objects::nonNull)
                .mapToLong(spec -> spec.bytes(screenWidth, screenHeight))
                .sum();
    }

    /** The buffers a program or a pass compute binds. */
    private static Set<Integer> used(PackAdvancedResourcePlan plan, Set<Integer> computeBuffers) {
        Set<Integer> used = new TreeSet<>(plan.programBuffers());
        used.addAll(computeBuffers);
        return used;
    }

    /**
     * The session's buffers: those a program or a pass compute ({@code computeBuffers}) binds, a
     * relative one sized for a screen of this size (Iris sizes it at creation). As in Iris, only the
     * device bounds a buffer: its storage range, then the allocation.
     */
    public static PackStorageBufferOwner load(PackAdvancedResourcePlan plan, int screenWidth, int screenHeight,
                                              Set<Integer> computeBuffers) {
        PackStorageBufferOwner owner = new PackStorageBufferOwner(plan, screenWidth, screenHeight, computeBuffers);
        long maxRange = maxRange();
        for (Integer index : used(owner.plan, computeBuffers)) {
            PackAdvancedResourcePlan.BufferSpec spec = owner.plan.buffers().get(index);
            if (spec == null || !spec.supported()) continue;
            long bytes = spec.bytes(screenWidth, screenHeight);
            // The host sizes buffers with an int.
            if (bytes > maxRange) {
                owner.failures.put(index, "STORAGE_BUFFER_DEVICE_RANGE_UNSUPPORTED:" + index);
                continue;
            }
            try {
                owner.buffers.put(index, new PackStorageBuffer((int) bytes));
            } catch (RuntimeException failure) {
                owner.failures.put(index, "STORAGE_BUFFER_ALLOCATION_FAILED:" + index);
            }
        }
        return owner;
    }

    /** The largest buffer the device binds and the host can size, which sizes buffers with an int. */
    private static long maxRange() {
        long device = DeviceManager.deviceProperties == null ? Long.MAX_VALUE
                : unsignedStorageBufferRange(DeviceManager.deviceProperties.limits().maxStorageBufferRange());
        return Math.min(device, Integer.MAX_VALUE);
    }

    /** Vulkan exposes this limit as an unsigned 32-bit value. */
    public static long unsignedStorageBufferRange(int rawValue) {
        return Integer.toUnsignedLong(rawValue);
    }

    /**
     * Recreates each relative buffer for the main target's new size and zero-fills it, as Iris's
     * ShaderStorageBufferHolder.hasResizedScreen does: contents do not survive a resize. Recorded
     * outside a render pass. A buffer that cannot take the new size keeps its old one ({@link #refit}).
     */
    public void fitRelativeBuffers(VkCommandBuffer commandBuffer, int width, int height) {
        if (commandBuffer == null || closed || (width == screenWidth && height == screenHeight)) return;
        screenWidth = width;
        screenHeight = height;
        boolean filled = false;
        for (Map.Entry<Integer, PackStorageBuffer> entry : buffers.entrySet()) {
            PackAdvancedResourcePlan.BufferSpec spec = plan.buffers().get(entry.getKey());
            if (spec == null || !spec.relative()) continue;
            String size = width + "x" + height;
            if (!refit(entry.getKey(), spec.bytes(width, height), maxRange(), size, entry.getValue()::recreate)) {
                continue;
            }
            // Before the session's first fill (initialize), that fill covers the new buffer.
            if (initialized) {
                vkCmdFillBuffer(commandBuffer, entry.getValue().getId(), 0L, VK_WHOLE_SIZE, 0);
                filled = true;
            }
        }
        if (filled) PackVulkanBarriers.afterStorageBufferTransfer(commandBuffer);
    }

    /**
     * Recreates one relative buffer at {@code bytes} through {@code recreate}, and says whether it
     * did. A size past the device's range, or one the device cannot allocate, keeps the old buffer
     * and is logged: the pack never reads a freed buffer. After a failed grow it indexes at the new
     * size, past the old buffer's end; with no robustBufferAccess those accesses are undefined, as
     * for a size past the range. The alternative was a crash; a graceful fallback is TASK-922's.
     */
    static boolean refit(int index, long bytes, long maxRange, String size, IntConsumer recreate) {
        if (bytes > maxRange) {
            ChimeraMod.LOGGER.warn("[chimera] storage buffer {} needs {} bytes at {}, past the device range",
                    index, bytes, size);
            return false;
        }
        try {
            recreate.accept((int) bytes);
            return true;
        } catch (RuntimeException failure) {
            ChimeraMod.LOGGER.warn("[chimera] storage buffer {} could not be recreated at {} ({} bytes): {}",
                    index, size, bytes, failure.toString());
            return false;
        }
    }

    /** Returns the session-owned buffer for an admitted logical index. */
    public PackStorageBuffer buffer(int logicalIndex) {
        return buffers.get(logicalIndex);
    }

    /**
     * Resolves a program block by its authored block or instance name. The
     * immutable plan remains the authority for the logical index; this method
     * only exposes the already-created session buffer.
     */
    public PackStorageBuffer buffer(String program, String blockName, String instanceName) {
        if (program == null) return null;
        for (PackAdvancedResourcePlan.StorageBufferBinding binding
                : plan.storageBuffers(program)) {
            boolean blockMatches = blockName != null && blockName.equals(binding.blockName());
            boolean instanceMatches = instanceName != null && !instanceName.isBlank()
                    && instanceName.equals(binding.instanceName());
            if (blockMatches || instanceMatches) {
                return buffers.get(binding.logicalIndex());
            }
        }
        return null;
    }

    /** Why {@code bufferObject.<index>} was not allocated, or null. */
    public String failure(int index) {
        return failures.get(index);
    }

    public boolean programAvailable(String program) {
        if (program == null || plan.storageBuffers(program).isEmpty()) return true;
        var bindings = plan.storageBuffers(program);
        if (bindings.stream()
                .anyMatch(value -> !value.supported() || !buffers.containsKey(value.logicalIndex()))) {
            bindings.stream()
                    .filter(value -> value.supported() && !buffers.containsKey(value.logicalIndex()))
                    .findFirst()
                    .ifPresent(value -> programFailures.put(program,
                            "STORAGE_BUFFER_UNAVAILABLE:" + value.logicalIndex()));
            return false;
        }
        String failure = bindings.stream()
                .map(value -> failures.get(value.logicalIndex()))
                .filter(value -> value != null)
                .findFirst().orElse(null);
        if (failure != null) {
            programFailures.put(program, failure);
            return false;
        }
        if (DeviceManager.deviceProperties != null) {
            var limits = DeviceManager.deviceProperties.limits();
            String limit = limitFailure(program, bindings, limits.maxDescriptorSetStorageBuffersDynamic(),
                    limits.maxPerStageDescriptorStorageBuffers());
            if (limit != null) {
                programFailures.put(program, limit);
                return false;
            }
        }
        return true;
    }

    /**
     * Why the device's descriptor limits refuse {@code program}'s storage buffers, or null. Vulkan
     * reports each limit as an unsigned 32-bit count: AMD's driver gives the per-stage one as
     * 0xFFFFFFFF, no limit, which Java reads as -1, and every program binding a buffer, RenderPearl's
     * terrain, water and hand among them, was refused on the Radeon.
     */
    static String limitFailure(String program, List<PackAdvancedResourcePlan.StorageBufferBinding> bindings,
                               int setDynamicLimit, int perStageLimit) {
        long all = bindings.stream().map(PackAdvancedResourcePlan.StorageBufferBinding::logicalIndex)
                .distinct().count();
        if (all > Integer.toUnsignedLong(setDynamicLimit)) {
            return "STORAGE_BUFFER_DESCRIPTOR_LIMIT:" + program;
        }
        long stageLimit = Integer.toUnsignedLong(perStageLimit);
        if (descriptors(bindings, VK_SHADER_STAGE_FRAGMENT_BIT) > stageLimit
                || descriptors(bindings, VK_SHADER_STAGE_VERTEX_BIT) > stageLimit) {
            return "STORAGE_BUFFER_STAGE_LIMIT:" + program;
        }
        return null;
    }

    /** The distinct buffers {@code bindings} give the stages in {@code stages}. */
    private static long descriptors(List<PackAdvancedResourcePlan.StorageBufferBinding> bindings, int stages) {
        return bindings.stream().filter(value -> (value.stageMask() & stages) != 0)
                .map(PackAdvancedResourcePlan.StorageBufferBinding::logicalIndex).distinct().count();
    }

    public Map<Integer, String> failures() {
        return Map.copyOf(failures);
    }

    public long allocatedBytes() {
        return buffers.values().stream().mapToLong(PackStorageBuffer::getBufferSize).sum();
    }

    public long plannedBytes() {
        return plannedBytes;
    }

    public long initializedBytes() {
        return initialized ? allocatedBytes() : 0L;
    }

    public long failedBytes() {
        return failures.keySet().stream()
                .map(plan.buffers()::get)
                .filter(Objects::nonNull)
                .mapToLong(spec -> spec.bytes(screenWidth, screenHeight))
                .sum();
    }

    public String failureReason(String program) {
        if (program == null || plan.storageBuffers(program).isEmpty()) {
            return "STORAGE_BUFFER_OWNER_UNAVAILABLE";
        }
        String programFailure = programFailures.get(program);
        if (programFailure != null) return programFailure;
        for (PackAdvancedResourcePlan.StorageBufferBinding binding : plan.storageBuffers(program)) {
            if (!binding.supported()) {
                return binding.deviations().stream().findFirst()
                        .orElse("STORAGE_BUFFER_PROGRAM_UNSUPPORTED:" + program);
            }
            String failure = failures.get(binding.logicalIndex());
            if (failure != null) return failure;
        }
        return "STORAGE_BUFFER_DESCRIPTOR_LIMIT:" + program;
    }

    /** Adds one zero-fill command for each buffer, once per pack session. */
    public boolean initialize(VkCommandBuffer commandBuffer) {
        if (commandBuffer == null || closed || initialized || buffers.isEmpty()) return false;
        for (Map.Entry<Integer, PackStorageBuffer> entry : buffers.entrySet()) {
            if (!plan.buffers().containsKey(entry.getKey())) return false;
            vkCmdFillBuffer(commandBuffer, entry.getValue().getId(), 0L, VK_WHOLE_SIZE, 0);
        }
        PackVulkanBarriers.afterStorageBufferTransfer(commandBuffer);
        initialized = true;
        return true;
    }

    /** Records shader/layout agreement before native pipeline creation. */
    public String verifyLayout(String program, String vertex, String fragment,
            List<PackAdvancedResourcePlan.DescriptorBinding> descriptors) {
        return verifyLayout(program, vertex, BetweenStages.NONE, fragment, descriptors);
    }

    /** The same over every stage the program runs, the stages {@code between} its vertex and fragment too. */
    public String verifyLayout(String program, String vertex, BetweenStages between, String fragment,
            List<PackAdvancedResourcePlan.DescriptorBinding> descriptors) {
        String mismatch = PackAdvancedResourcePlan.layoutMismatch(
                plan.bindingLayout(program), vertex, between, fragment, descriptors);
        if (mismatch == null) {
            verifiedLayouts.add(program);
            programFailures.remove(program);
        } else {
            verifiedLayouts.remove(program);
            attachedPrograms.remove(program);
            installedPrograms.remove(program);
            programFailures.put(program, mismatch);
        }
        return mismatch;
    }

    public boolean layoutVerified(String program) {
        return verifiedLayouts.contains(program);
    }
    /** Makes writes from an earlier guarded pack stage visible to this stage. */
    public void prepareForUse(VkCommandBuffer commandBuffer) {
        if (commandBuffer == null || closed || !initialized || buffers.isEmpty()) return;
        PackVulkanBarriers.beforeStorageBufferUse(commandBuffer, buffers.values());
        synchronizedWrites.addAll(drawnWriters.keySet());
    }

    /** Connects the planned descriptors to their session-owned buffers. */
    public boolean attachPipeline(GraphicsPipeline pipeline, String program) {
        if (pipeline == null || !programAvailable(program)) return false;
        if (!layoutVerified(program)) {
            programFailures.putIfAbsent(program, "DESCRIPTOR_LAYOUT_UNVERIFIED:" + program);
            return false;
        }
        for (PackAdvancedResourcePlan.StorageBufferBinding binding : plan.storageBuffers(program)) {
            PackStorageBuffer storage = buffers.get(binding.logicalIndex());
            if (storage == null) return false;
            PackStorageBufferDescriptor descriptor = pipeline.getBuffers().stream()
                    .filter(buffer -> buffer.getBinding() == binding.rewrittenBinding())
                    .filter(PackStorageBufferDescriptor.class::isInstance)
                    .map(PackStorageBufferDescriptor.class::cast)
                    .findFirst().orElse(null);
            if (descriptor == null) return false;
            descriptor.setUniformBuffer(storage);
        }
        attachedPrograms.add(program);
        return true;
    }

    public boolean attached(String program) {
        return plan.storageBuffers(program).isEmpty() || attachedPrograms.contains(program);
    }

    /** Records that the runtime installed the pipeline after descriptor attachment. */
    public void markPipelineInstalled(String program) {
        if (program != null && attached(program) && layoutVerified(program)) {
            installedPrograms.add(program);
        }
    }

    public boolean descriptorAttached(String program) {
        return plan.storageBuffers(program).isEmpty() || attachedPrograms.contains(program);
    }

    public boolean pipelineInstalled(String program) {
        return plan.storageBuffers(program).isEmpty() || installedPrograms.contains(program);
    }

    /** Summarizes the exact writer-reader chain for one logical storage path. */
    public String storagePathStatus(String writer, String reader) {
        Set<Integer> writerBuffers = logicalBuffers(writer);
        Set<Integer> readerBuffers = logicalBuffers(reader);
        Set<Integer> shared = new TreeSet<>(writerBuffers);
        shared.retainAll(readerBuffers);
        boolean planned = !shared.isEmpty();
        boolean allocated = planned && shared.stream().allMatch(buffers::containsKey);
        boolean ready = allocated && initialized
                && descriptorAttached(writer) && descriptorAttached(reader)
                && pipelineInstalled(writer) && pipelineInstalled(reader)
                && layoutVerified(writer) && layoutVerified(reader);
        String missing = ready ? "none" : missingLink(writer, reader, planned, allocated);
        return "writer=" + writer + ",reader=" + reader
                + ",planned=" + planned
                + ",allocated=" + allocated
                + ",initialized=" + initialized
                + ",writerDescriptor=" + descriptorAttached(writer)
                + ",readerDescriptor=" + descriptorAttached(reader)
                + ",writerPipeline=" + pipelineInstalled(writer)
                + ",readerPipeline=" + pipelineInstalled(reader)
                + ",writerLayout=" + layoutVerified(writer) + ",readerLayout=" + layoutVerified(reader)
                + ",ready=" + ready + ",missing=" + missing;
    }

    /** The first unready link of a storage path, a program's recorded failure first. */
    private String missingLink(String writer, String reader, boolean planned, boolean allocated) {
        if (programFailures.containsKey(writer)) return programFailures.get(writer);
        if (programFailures.containsKey(reader)) return programFailures.get(reader);
        if (!layoutVerified(writer)) return "DESCRIPTOR_LAYOUT_UNVERIFIED:" + writer;
        if (!layoutVerified(reader)) return "DESCRIPTOR_LAYOUT_UNVERIFIED:" + reader;
        if (!planned) return "MISSING_DECLARATION";
        if (!allocated) return "MISSING_ALLOCATION";
        if (!initialized) return "MISSING_INITIALIZATION";
        if (!descriptorAttached(writer)) return "MISSING_WRITER_DESCRIPTOR";
        if (!descriptorAttached(reader)) return "MISSING_READER:" + reader;
        if (!pipelineInstalled(writer)) return "MISSING_WRITER_PIPELINE";
        return "MISSING_READER:" + reader;
    }

    public boolean storagePathReady(String writer, String reader) {
        Set<Integer> shared = logicalBuffers(writer);
        shared.retainAll(logicalBuffers(reader));
        return !shared.isEmpty() && shared.stream().allMatch(buffers::containsKey)
                && initialized && descriptorAttached(writer) && descriptorAttached(reader)
                && pipelineInstalled(writer) && pipelineInstalled(reader)
                && layoutVerified(writer) && layoutVerified(reader);
    }

    private Set<Integer> logicalBuffers(String program) {
        Set<Integer> result = new TreeSet<>();
        for (PackAdvancedResourcePlan.StorageBufferBinding binding : plan.storageBuffers(program)) {
            if (binding.supported() && binding.logicalIndex() >= 0) {
                result.add(binding.logicalIndex());
            }
        }
        return result;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        buffers.values().forEach(PackStorageBuffer::freeBuffer);
        buffers.clear();
        failures.clear();
        programFailures.clear();
        attachedPrograms.clear();
        installedPrograms.clear();
        verifiedLayouts.clear();
        initialized = false;
    }
}
