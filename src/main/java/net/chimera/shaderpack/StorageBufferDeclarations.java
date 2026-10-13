package net.chimera.shaderpack;

import org.lwjgl.vulkan.VK10;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reads a pack's storage buffers: the bufferObject.N declarations, the std430 blocks each program
 * declares, which declaration each block binds, and the budget the declarations must fit.
 */
final class StorageBufferDeclarations {
    private StorageBufferDeclarations() {}

    static Map<Integer, PackAdvancedResourcePlan.BufferSpec> parseBuffers(
            PackSettingsPlan settings,
            List<String> deviations
    ) {
        Map<Integer, PackAdvancedResourcePlan.BufferSpec> result = new TreeMap<>();
        if (settings == null) {
            return result;
        }
        settings.propertyValues().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String key = entry.getKey();
                    if (key.startsWith("ssbo.") || key.startsWith("storage.")) {
                        deviations.add("STORAGE_BUFFER_UNSUPPORTED_PROPERTY:" + key);
                        deviations.add("STORAGE_BUFFER_DEFERRED:" + key);
                        return;
                    }
                    if (!key.startsWith("bufferObject.")) {
                        return;
                    }
                    String suffix = key.substring("bufferObject.".length());
                    int index;
                    try {
                        index = Integer.parseInt(suffix);
                    } catch (RuntimeException failure) {
                        deviations.add("STORAGE_BUFFER_INDEX_UNSUPPORTED:" + suffix);
                        return;
                    }
                    PackAdvancedResourcePlan.BufferSpec spec = parseBuffer(index, entry.getValue());
                    result.put(index, spec);
                    deviations.addAll(spec.deviations());
                });
        return result;
    }

    static PackAdvancedResourcePlan.BufferSpec parseBuffer(int index, String raw) {
        List<String> deviations = new ArrayList<>();
        if (index < 0 || index > PackAdvancedResourcePlan.MAX_BUFFER_INDEX) {
            deviations.add("STORAGE_BUFFER_INDEX_UNSUPPORTED:" + index);
            return new PackAdvancedResourcePlan.BufferSpec(index, 0L, "", PackAdvancedResourcePlan.Status.UNSUPPORTED,
                    deviations);
        }
        String[] values = raw == null ? new String[0] : raw.trim().split("\\s+");
        if (values.length == 0 || values.length > 2) {
            deviations.add("STORAGE_BUFFER_PROPERTY_MALFORMED:" + index);
            return new PackAdvancedResourcePlan.BufferSpec(index, 0L, "", PackAdvancedResourcePlan.Status.UNSUPPORTED,
                    deviations);
        }
        long size;
        try {
            size = Long.parseLong(values[0]);
        } catch (RuntimeException failure) {
            deviations.add("STORAGE_BUFFER_SIZE_UNSUPPORTED:" + index);
            return new PackAdvancedResourcePlan.BufferSpec(index, 0L, values.length == 2 ? values[1] : "",
                    PackAdvancedResourcePlan.Status.UNSUPPORTED, deviations);
        }
        if (size <= 0L || size > PackAdvancedResourcePlan.MAX_STORAGE_BUFFER_BYTES) {
            deviations.add("STORAGE_BUFFER_SIZE_UNSUPPORTED:" + index);
        } else if ((size & 3L) != 0L) {
            deviations.add("STORAGE_BUFFER_ALIGNMENT_UNSUPPORTED:" + index);
        }
        if (raw != null && raw.toLowerCase(Locale.ROOT).contains("relative")) {
            deviations.add("STORAGE_BUFFER_RELATIVE_UNSUPPORTED:" + index);
        }
        return new PackAdvancedResourcePlan.BufferSpec(index, size, values.length == 2 ? values[1] : "",
                deviations.isEmpty() ? PackAdvancedResourcePlan.Status.SUPPORTED
                        : PackAdvancedResourcePlan.Status.UNSUPPORTED, deviations);
    }

    static Map<String, List<PackAdvancedResourcePlan.StorageBufferBinding>> scanStorageBuffers(
            PackPlan plan,
            Map<Integer, PackAdvancedResourcePlan.BufferSpec> specs,
            List<String> dependentPrograms,
            List<String> eligiblePrograms,
            List<String> deviations,
            Map<String, Integer> bindingBases
    ) {
        Map<String, List<PackAdvancedResourcePlan.StorageBufferBinding>> result = new TreeMap<>();
        if (plan == null) return result;
        for (PackProgramPlan program : plan.programs()) {
            if (program == null || program.name().isBlank()) continue;
            List<StorageBlock> blocks = new ArrayList<>();
            for (Map.Entry<String, PreparedShaderSource> stage : program.stages().entrySet()) {
                if (stage.getValue() == null || stage.getValue().source() == null) continue;
                int stageMask = stage.getKey().equals("vertex")
                        ? VK10.VK_SHADER_STAGE_VERTEX_BIT
                        : stage.getKey().equals("fragment")
                        ? VK10.VK_SHADER_STAGE_FRAGMENT_BIT : 0;
                if (stageMask == 0) continue;
                try {
                    for (StorageBlock block : scanStorageBlocks(stage.getValue().source())) {
                        blocks.add(new StorageBlock(block.blockName(), block.instanceName(),
                                block.originalBinding(), block.std430(), stageMask));
                    }
                } catch (RuntimeException failure) {
                    // Keep malformed storage declarations local to this
                    // program. They must not become an invented executable
                    // binding, and they must not reject unrelated programs.
                    String marker = "__parse_error__" + stage.getKey();
                    blocks.add(new StorageBlock(marker, "", -1, false, stageMask));
                    deviations.add("STORAGE_BUFFER_PARSE_UNSUPPORTED:" + program.name()
                            + ":" + stage.getKey());
                }
            }
            if (blocks.isEmpty()) continue;
            dependentPrograms.add(program.name());
            // A structurally valid block is a producer/consumer candidate even
            // while the first immutable plan is still waiting for its final
            // storage bindings. Final eligibility is decided after descriptor
            // construction and shader conversion.
            eligiblePrograms.add(program.name());
            Map<String, StorageBlock> merged = new TreeMap<>();
            Set<String> duplicateBlocks = new TreeSet<>();
            for (StorageBlock block : blocks) {
                String key = block.blockName() + "\\u0000" + block.instanceName();
                StorageBlock previous = merged.get(key);
                if (previous == null) {
                    merged.put(key, block);
                } else {
                    if ((previous.stageMask() & block.stageMask()) != 0) {
                        duplicateBlocks.add(key);
                    }
                    merged.put(key, new StorageBlock(previous.blockName(), previous.instanceName(),
                            previous.originalBinding(), previous.std430() && block.std430(),
                            previous.stageMask() | block.stageMask()));
                }
            }
            Map<Integer, Integer> logicalBindings = new TreeMap<>();
            List<PackAdvancedResourcePlan.StorageBufferBinding> bindings = new ArrayList<>();
            for (StorageBlock block : merged.values()) {
                String blockKey = block.blockName() + "\\u0000" + block.instanceName();
                List<PackAdvancedResourcePlan.BufferSpec> matches = matchingBufferSpecs(block, specs);
                PackAdvancedResourcePlan.BufferSpec spec = matches.size() == 1 ? matches.get(0) : null;
                List<String> local = new ArrayList<>();
                if (!block.std430()) {
                    local.add("STORAGE_BUFFER_LAYOUT_UNSUPPORTED:" + block.blockName());
                }
                if (duplicateBlocks.contains(blockKey)) {
                    local.add("STORAGE_BUFFER_BLOCK_DUPLICATE:" + program.name() + ":"
                            + block.blockName());
                }
                if (matches.size() > 1) {
                    local.add("STORAGE_BUFFER_BLOCK_AMBIGUOUS:" + program.name() + ":"
                            + block.blockName());
                } else if (spec == null) {
                    local.add("STORAGE_BUFFER_BLOCK_UNMAPPED:" + program.name() + ":"
                            + block.blockName());
                } else {
                    local.addAll(spec.deviations());
                }
                PackAdvancedResourcePlan.Status status = spec != null && spec.supported() && local.stream().noneMatch(
                        value -> value.startsWith("STORAGE_BUFFER_LAYOUT_UNSUPPORTED:")
                                || value.startsWith("STORAGE_BUFFER_BLOCK_UNMAPPED:")
                                || value.startsWith("STORAGE_BUFFER_BLOCK_AMBIGUOUS:")
                                || value.startsWith("STORAGE_BUFFER_BLOCK_DUPLICATE:")
                                || value.startsWith("STORAGE_BUFFER_SIZE_")
                                || value.startsWith("STORAGE_BUFFER_ALIGNMENT_")
                                || value.startsWith("STORAGE_BUFFER_RELATIVE_"))
                        ? PackAdvancedResourcePlan.Status.SUPPORTED : PackAdvancedResourcePlan.Status.UNSUPPORTED;
                int logicalIndex = spec == null ? -1 : spec.logicalIndex();
                long size = spec == null ? 0L : spec.size();
                int rewritten = status != PackAdvancedResourcePlan.Status.SUPPORTED ? -1
                        : logicalBindings.computeIfAbsent(
                        logicalIndex, ignored -> bindingBases.getOrDefault(program.name(), 0)
                                + logicalBindings.size());
                PackAdvancedResourcePlan.StorageBufferBinding binding =
                        new PackAdvancedResourcePlan.StorageBufferBinding(
                        program.name(), block.blockName(), block.instanceName(),
                        block.originalBinding(), logicalIndex, rewritten, block.stageMask(),
                        size, status, local);
                bindings.add(binding);
                deviations.addAll(local);
                if (!binding.supported()) {
                    deviations.add("STORAGE_BUFFER_PROGRAM_FALLBACK:" + program.name());
                }
            }
            result.put(program.name(), bindings);
        }
        for (PackAdvancedResourcePlan.BufferSpec spec : specs.values()) {
            boolean used = result.values().stream().flatMap(List::stream)
                    .filter(value -> eligiblePrograms.contains(value.program()))
                    .anyMatch(value -> value.logicalIndex() == spec.logicalIndex());
            if (!used) {
                deviations.add("STORAGE_BUFFER_DEFERRED:bufferObject." + spec.logicalIndex());
            }
        }
        return result;
    }

    static List<PackAdvancedResourcePlan.BufferSpec> matchingBufferSpecs(StorageBlock block,
                                                        Map<Integer, PackAdvancedResourcePlan.BufferSpec> specs) {
        List<PackAdvancedResourcePlan.BufferSpec> matches = specs.values().stream()
                .filter(spec -> !spec.requestedName().isBlank()
                        && (spec.requestedName().equals(block.blockName())
                        || spec.requestedName().equals(block.instanceName())))
                .toList();
        if (matches.isEmpty() && block.originalBinding() >= 0) {
            matches = specs.values().stream()
                    .filter(spec -> spec.requestedName().isBlank()
                            && spec.logicalIndex() == block.originalBinding())
                    .toList();
        }
        return matches;
    }

    static Map<Integer, PackAdvancedResourcePlan.BufferSpec> applyStorageBudget(
            Map<Integer, PackAdvancedResourcePlan.BufferSpec> buffers,
            Map<String, List<PackAdvancedResourcePlan.StorageBufferBinding>> storageBuffers,
            List<String> eligiblePrograms,
            Map<String, PackAdvancedResourcePlan.ImageSpec> images,
            List<String> deviations
    ) {
        Map<Integer, PackAdvancedResourcePlan.BufferSpec> result = new TreeMap<>(buffers);
        long imageBytes = images.values().stream()
                .filter(PackAdvancedResourcePlan.ImageSpec::supported)
                .mapToLong(PackAdvancedResourcePlan.ImageSpec::estimatedBytes)
                .sum();
        long remaining = Math.max(0L, PackAdvancedResourcePlan.MAX_TOTAL_BYTES - imageBytes);
        TreeSet<Integer> used = new TreeSet<>();
        storageBuffers.entrySet().stream()
                .filter(entry -> eligiblePrograms.contains(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .filter(PackAdvancedResourcePlan.StorageBufferBinding::supported)
                .map(PackAdvancedResourcePlan.StorageBufferBinding::logicalIndex)
                .forEach(used::add);
        boolean rejected = false;
        for (Integer index : used) {
            PackAdvancedResourcePlan.BufferSpec spec = result.get(index);
            if (spec == null || !spec.supported()) continue;
            if (spec.size() > remaining) {
                List<String> next = new ArrayList<>(spec.deviations());
                next.add("STORAGE_BUFFER_BUDGET_EXCEEDED:" + index);
                result.put(index, new PackAdvancedResourcePlan.BufferSpec(spec.logicalIndex(), spec.size(),
                        spec.requestedName(), PackAdvancedResourcePlan.Status.UNSUPPORTED, next));
                deviations.add("STORAGE_BUFFER_BUDGET_EXCEEDED:" + index);
                rejected = true;
            } else {
                remaining -= spec.size();
            }
        }
        if (rejected) deviations.add("STORAGE_BUFFER_BUDGET_EXCEEDED");
        return result;
    }

    record StorageBlock(
            String blockName,
            String instanceName,
            int originalBinding,
            boolean std430,
            int stageMask
    ) {}

    static int stageMask(String stage) {
        return switch (stage == null ? "" : stage) {
            case "vertex" -> VK10.VK_SHADER_STAGE_VERTEX_BIT;
            case "fragment" -> VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
            default -> 0;
        };
    }

    static List<StorageBlock> scanStorageBlocks(String source) {
        List<StorageBlock> result = new ArrayList<>();
        for (GlslStorageBufferParser.Block block : GlslStorageBufferParser.scan(source)) {
            result.add(new StorageBlock(block.blockName(), block.instanceName(),
                    block.originalBinding(), block.std430(), 0));
        }
        return result;
    }
}
