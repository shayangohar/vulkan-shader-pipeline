package net.chimera.render;

import net.chimera.shaderpack.PackAdvancedResourcePlan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.HashMap;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdFillBuffer;

/** Owns the bounded read/write storage buffers for one pack session. */
public final class PackStorageBufferOwner implements AutoCloseable {
    private final PackAdvancedResourcePlan plan;
    private final Map<Integer, Buffer> buffers = new TreeMap<>();
    private final Map<Integer, String> failures = new TreeMap<>();
    private final Map<String, String> programFailures = new HashMap<>();
    private final Set<String> attachedPrograms = new TreeSet<>();
    private final Set<String> installedPrograms = new TreeSet<>();
    private final Set<String> verifiedLayouts = new TreeSet<>();
    private final long plannedBytes;
    private boolean initialized;
    private boolean closed;
    private final Map<Integer, String> drawnWriters = new HashMap<>();
    private final Set<Integer> synchronizedWrites = new TreeSet<>();
    private final Set<String> reportedReaders = new TreeSet<>();
    private int dispatchCount;

    public void beginExecutionFrame() {
        drawnWriters.clear();
        synchronizedWrites.clear();
        dispatchCount = 0;
    }

    public void recordDispatch() { dispatchCount++; }

    /** Called only after a native draw command was recorded, never on descriptor upload. */
    public void recordDraw(String program, int stage) {
        for (var binding : plan.storageBuffers(program)) {
            if (!binding.supported() || !buffers.containsKey(binding.logicalIndex())
                    || (binding.stageMask() & stage) == 0) continue;
            int index = binding.logicalIndex();
            if (stage == VK_SHADER_STAGE_VERTEX_BIT) {
                drawnWriters.put(index, program);
                synchronizedWrites.remove(index);
            } else if (stage == org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT
                    && drawnWriters.containsKey(index) && synchronizedWrites.contains(index)
                    && reportedReaders.add(program + ":" + index)) {
                net.chimera.ChimeraMod.LOGGER.info("[chimera] storage execution: writer={},reader={},offset=0,range={},writerDrawn=true,barrierRecorded=true,readerDrawn=true,dispatchCount={}",
                        drawnWriters.get(index), program, binding.size(), dispatchCount);
            }
        }
    }

    public PackStorageBufferOwner(PackAdvancedResourcePlan plan) {
        this.plan = plan == null ? PackAdvancedResourcePlan.empty() : plan;
        this.plannedBytes = this.plan.storageBuffers().entrySet().stream()
                .filter(entry -> this.plan.storageEligiblePrograms().contains(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .filter(PackAdvancedResourcePlan.StorageBufferBinding::supported)
                .map(PackAdvancedResourcePlan.StorageBufferBinding::logicalIndex)
                .distinct()
                .map(this.plan.buffers()::get)
                .filter(java.util.Objects::nonNull)
                .mapToLong(PackAdvancedResourcePlan.BufferSpec::size)
                .sum();
    }

    public static PackStorageBufferOwner load(PackAdvancedResourcePlan plan) {
        PackStorageBufferOwner owner = new PackStorageBufferOwner(plan);
        long maxRange = DeviceManager.deviceProperties == null
                ? Long.MAX_VALUE
                : unsignedStorageBufferRange(
                DeviceManager.deviceProperties.limits().maxStorageBufferRange());
        Set<Integer> used = new TreeSet<>();
        owner.plan.storageBuffers().values().forEach(values -> values.stream()
                .filter(PackAdvancedResourcePlan.StorageBufferBinding::supported)
                .filter(value -> owner.plan.storageEligiblePrograms().contains(value.program()))
                .map(PackAdvancedResourcePlan.StorageBufferBinding::logicalIndex)
                .forEach(used::add));
        for (Integer index : used) {
            PackAdvancedResourcePlan.BufferSpec spec = owner.plan.buffers().get(index);
            if (spec == null || !spec.supported()) continue;
            if (spec.size() > maxRange) {
                owner.failures.put(index, "STORAGE_BUFFER_DEVICE_RANGE_UNSUPPORTED:" + index);
                continue;
            }
            try {
                Buffer buffer = new Buffer("ChimeraStorageBuffer" + index,
                        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        MemoryTypes.GPU_MEM);
                buffer.createBuffer(spec.size());
                owner.buffers.put(index, buffer);
            } catch (RuntimeException failure) {
                owner.failures.put(index, failure.getMessage() == null
                        ? "STORAGE_BUFFER_ALLOCATION_FAILED:" + index
                        : "STORAGE_BUFFER_ALLOCATION_FAILED:" + index);
            }
        }
        return owner;
    }

    /** Vulkan exposes this limit as an unsigned 32-bit value. */
    public static long unsignedStorageBufferRange(int rawValue) {
        return Integer.toUnsignedLong(rawValue);
    }

    public boolean hasBuffers() {
        return !buffers.isEmpty();
    }

    /** Returns the session-owned buffer for an admitted logical index. */
    public Buffer buffer(int logicalIndex) {
        return buffers.get(logicalIndex);
    }

    /**
     * Resolves a program block by its authored block or instance name. The
     * immutable plan remains the authority for the logical index; this method
     * only exposes the already-created session buffer.
     */
    public Buffer buffer(String program, String blockName, String instanceName) {
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
            long descriptorCount = bindings.stream()
                    .map(PackAdvancedResourcePlan.StorageBufferBinding::logicalIndex)
                    .distinct().count();
            if (descriptorCount > limits.maxDescriptorSetStorageBuffersDynamic()) {
                programFailures.put(program, "STORAGE_BUFFER_DESCRIPTOR_LIMIT:" + program);
                return false;
            }
            long fragmentCount = bindings.stream()
                    .filter(value -> (value.stageMask() & VK_SHADER_STAGE_FRAGMENT_BIT) != 0)
                    .map(PackAdvancedResourcePlan.StorageBufferBinding::logicalIndex)
                    .distinct().count();
            long vertexCount = bindings.stream()
                    .filter(value -> (value.stageMask() & VK_SHADER_STAGE_VERTEX_BIT) != 0)
                    .map(PackAdvancedResourcePlan.StorageBufferBinding::logicalIndex)
                    .distinct().count();
            int perStageLimit = limits.maxPerStageDescriptorStorageBuffers();
            if (fragmentCount > perStageLimit || vertexCount > perStageLimit) {
                programFailures.put(program, "STORAGE_BUFFER_STAGE_LIMIT:" + program);
                return false;
            }
        }
        return true;
    }

    public Map<Integer, String> failures() {
        return Map.copyOf(failures);
    }

    public long allocatedBytes() {
        return buffers.values().stream().mapToLong(Buffer::getBufferSize).sum();
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
                .filter(java.util.Objects::nonNull)
                .mapToLong(PackAdvancedResourcePlan.BufferSpec::size)
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
        for (Map.Entry<Integer, Buffer> entry : buffers.entrySet()) {
            PackAdvancedResourcePlan.BufferSpec spec = plan.buffers().get(entry.getKey());
            if (spec == null) return false;
            vkCmdFillBuffer(commandBuffer, entry.getValue().getId(), 0L, spec.size(), 0);
        }
        PackVulkanBarriers.afterStorageBufferTransfer(commandBuffer);
        initialized = true;
        return true;
    }


    /** Records shader/layout agreement before native pipeline creation. */
    public String verifyLayout(String program, String vertex, String fragment,
            java.util.List<PackAdvancedResourcePlan.DescriptorBinding> descriptors) {
        String mismatch = PackAdvancedResourcePlan.layoutMismatch(
                plan.bindingLayout(program), vertex, fragment, descriptors);
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
            Buffer storage = buffers.get(binding.logicalIndex());
            if (storage == null) return false;
            UBO descriptor = pipeline.getUBO(binding.rewrittenBinding());
            if (!(descriptor instanceof PackStorageBufferDescriptor)) return false;
            descriptor.getBufferSlice().set(storage, 0L, (int) binding.size());
            descriptor.setUseGlobalBuffer(false);
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
        String missing = ready ? "none"
                : programFailures.containsKey(writer) ? programFailures.get(writer)
                : programFailures.containsKey(reader) ? programFailures.get(reader)
                : !layoutVerified(writer) ? "DESCRIPTOR_LAYOUT_UNVERIFIED:" + writer
                : !layoutVerified(reader) ? "DESCRIPTOR_LAYOUT_UNVERIFIED:" + reader
                : !planned ? "MISSING_DECLARATION"
                : !allocated ? "MISSING_ALLOCATION"
                : !initialized ? "MISSING_INITIALIZATION"
                : !descriptorAttached(writer) ? "MISSING_WRITER_DESCRIPTOR"
                : !descriptorAttached(reader) ? "MISSING_READER:" + reader
                : !pipelineInstalled(writer) ? "MISSING_WRITER_PIPELINE"
                : "MISSING_READER:" + reader;
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
        buffers.values().forEach(Buffer::scheduleFree);
        buffers.clear();
        failures.clear();
        programFailures.clear();
        attachedPrograms.clear();
        installedPrograms.clear();
        verifiedLayouts.clear();
        initialized = false;
    }
}
