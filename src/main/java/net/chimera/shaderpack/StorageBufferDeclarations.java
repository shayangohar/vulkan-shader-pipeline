package net.chimera.shaderpack;

import org.lwjgl.vulkan.VK10;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reads a pack's storage buffers: the bufferObject.N declarations, the storage blocks each program
 * declares, which declaration each block binds, and the budget the declarations must fit.
 */
final class StorageBufferDeclarations {
    /** Deviations that keep a block from binding even when its bufferObject declaration is supported. */
    private static final List<String> UNBINDABLE_PREFIXES = List.of(
            "STORAGE_BUFFER_LAYOUT_UNSUPPORTED:", "STORAGE_BUFFER_BLOCK_UNMAPPED:",
            "STORAGE_BUFFER_BLOCK_AMBIGUOUS:", "STORAGE_BUFFER_BLOCK_DUPLICATE:", "STORAGE_BUFFER_SIZE_");

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
                    // A size below 1 is the pack switching the buffer off: Iris creates none.
                    if (!spec.deviations().contains(DISABLED + index)) result.put(index, spec);
                    deviations.addAll(spec.deviations());
                });
        return result;
    }

    /** ShaderProperties skips a buffer whose size is below 1: "the shader dev intended to disable the buffer". */
    static final String DISABLED = "STORAGE_BUFFER_DISABLED:";

    static PackAdvancedResourcePlan.BufferSpec parseBuffer(int index, String raw) {
        List<String> deviations = new ArrayList<>();
        if (index < 0 || index > PackAdvancedResourcePlan.MAX_BUFFER_INDEX) {
            deviations.add("STORAGE_BUFFER_INDEX_UNSUPPORTED:" + index);
            return new PackAdvancedResourcePlan.BufferSpec(index, 0L, "", PackAdvancedResourcePlan.Status.UNSUPPORTED,
                    deviations);
        }
        // ShaderProperties splits the value on single spaces: "size [name]", or "size relative scaleX
        // scaleY" read from its first four values.
        String[] values = raw == null ? new String[0] : raw.stripLeading().split(" ");
        if (values.length == 0 || values[0].isEmpty() || values.length == 3) {
            deviations.add("STORAGE_BUFFER_PROPERTY_MALFORMED:" + index);
            return new PackAdvancedResourcePlan.BufferSpec(index, 0L, "", PackAdvancedResourcePlan.Status.UNSUPPORTED,
                    deviations);
        }
        if (values.length > 3) return relativeBuffer(index, values, deviations);
        long size;
        try {
            size = Long.parseLong(values[0]);
        } catch (RuntimeException failure) {
            deviations.add("STORAGE_BUFFER_SIZE_UNSUPPORTED:" + index);
            return new PackAdvancedResourcePlan.BufferSpec(index, 0L, values.length == 2 ? values[1] : "",
                    PackAdvancedResourcePlan.Status.UNSUPPORTED, deviations);
        }
        checkSize(index, size, deviations);
        return new PackAdvancedResourcePlan.BufferSpec(index, size, values.length == 2 ? values[1] : "",
                status(deviations), deviations);
    }

    /** The long form, which has no name; with relative false it is a fixed buffer of {@code size}. */
    private static PackAdvancedResourcePlan.BufferSpec relativeBuffer(int index, String[] values,
                                                                      List<String> deviations) {
        long size;
        float scaleX;
        float scaleY;
        try {
            size = Long.parseLong(values[0]);
            scaleX = Float.parseFloat(values[2]);
            scaleY = Float.parseFloat(values[3]);
        } catch (NumberFormatException failure) {
            deviations.add("STORAGE_BUFFER_PROPERTY_MALFORMED:" + index);
            return new PackAdvancedResourcePlan.BufferSpec(index, 0L, "", PackAdvancedResourcePlan.Status.UNSUPPORTED,
                    deviations);
        }
        checkSize(index, size, deviations);
        boolean relative = Boolean.parseBoolean(values[1]);
        boolean emptyShare = !(scaleX > 0F && scaleY > 0F && Float.isFinite(scaleX) && Float.isFinite(scaleY));
        if (size >= 1L && relative && emptyShare) {
            // Iris sizes such a buffer at zero bytes or fewer, which GL refuses to create.
            deviations.add("STORAGE_BUFFER_SIZE_UNSUPPORTED:" + index);
        }
        return new PackAdvancedResourcePlan.BufferSpec(index, size, "", relative,
                relative ? scaleX : 0f, relative ? scaleY : 0f, status(deviations), deviations);
    }

    /**
     * A size below 1 switches the buffer off, as in Iris (DISABLED). Beyond that, Chimera's one limit:
     * one buffer's budget. Any byte count binds, as Iris's buffers take any size: the buffer is
     * filled whole (VK_WHOLE_SIZE).
     */
    private static void checkSize(int index, long size, List<String> deviations) {
        if (size < 1L) {
            deviations.add(DISABLED + index);
        } else if (size > PackAdvancedResourcePlan.MAX_STORAGE_BUFFER_BYTES) {
            deviations.add("STORAGE_BUFFER_SIZE_UNSUPPORTED:" + index);
        }
    }

    private static PackAdvancedResourcePlan.Status status(List<String> deviations) {
        return deviations.isEmpty() ? PackAdvancedResourcePlan.Status.SUPPORTED
                : PackAdvancedResourcePlan.Status.UNSUPPORTED;
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
            List<StorageBlock> blocks = programBlocks(program, deviations);
            if (blocks.isEmpty()) continue;
            dependentPrograms.add(program.name());
            // A structurally valid block is a producer/consumer candidate even
            // while the first immutable plan is still waiting for its final
            // storage bindings. Final eligibility is decided after descriptor
            // construction and shader conversion.
            eligiblePrograms.add(program.name());
            ProgramBlocks merged = ProgramBlocks.merge(blocks);
            int bindingBase = bindingBases.getOrDefault(program.name(), 0);
            result.put(program.name(), bindBlocks(program.name(), merged, specs, bindingBase, deviations));
        }
        for (PackAdvancedResourcePlan.BufferSpec spec : specs.values()) {
            boolean used = result.values().stream().flatMap(List::stream)
                    .filter(value -> eligiblePrograms.contains(value.program()))
                    .anyMatch(value -> value.logicalIndex() == spec.logicalIndex());
            if (!used) {
                // No program binds it; the session allocates it only for a pass compute that does.
                deviations.add("STORAGE_BUFFER_DEFERRED:bufferObject." + spec.logicalIndex());
            }
        }
        return result;
    }

    /** The blocks a program's vertex and fragment stages declare; a stage that fails to parse adds a marker. */
    private static List<StorageBlock> programBlocks(PackProgramPlan program, List<String> deviations) {
        List<StorageBlock> blocks = new ArrayList<>();
        for (Map.Entry<String, PreparedShaderSource> stage : program.stages().entrySet()) {
            if (stage.getValue() == null || stage.getValue().source() == null) continue;
            int stageMask = stageMask(stage.getKey());
            if (stageMask == 0) continue;
            try {
                for (StorageBlock block : scanStorageBlocks(stage.getValue().source())) {
                    blocks.add(new StorageBlock(block.blockName(), block.instanceName(),
                            block.originalBinding(), block.laidOut(), stageMask));
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
        return blocks;
    }

    /** A program's blocks merged across stages by block and instance name, and those one stage declares twice. */
    private record ProgramBlocks(Map<String, StorageBlock> blocks, Set<String> duplicates) {
        static ProgramBlocks merge(List<StorageBlock> stageBlocks) {
            Map<String, StorageBlock> merged = new TreeMap<>();
            Set<String> duplicateBlocks = new TreeSet<>();
            for (StorageBlock block : stageBlocks) {
                String key = blockKey(block);
                StorageBlock previous = merged.get(key);
                if (previous == null) {
                    merged.put(key, block);
                } else {
                    if ((previous.stageMask() & block.stageMask()) != 0) {
                        duplicateBlocks.add(key);
                    }
                    merged.put(key, new StorageBlock(previous.blockName(), previous.instanceName(),
                            previous.originalBinding(), previous.laidOut() && block.laidOut(),
                            previous.stageMask() | block.stageMask()));
                }
            }
            return new ProgramBlocks(merged, duplicateBlocks);
        }
    }

    private static String blockKey(StorageBlock block) {
        return block.blockName() + "\\u0000" + block.instanceName();
    }

    /**
     * Binds each merged block to the bufferObject declaration it names. Supported blocks of one
     * declaration share one rewritten binding, numbered on from the program's binding base.
     */
    private static List<PackAdvancedResourcePlan.StorageBufferBinding> bindBlocks(
            String program, ProgramBlocks merged, Map<Integer, PackAdvancedResourcePlan.BufferSpec> specs,
            int bindingBase, List<String> deviations) {
        Map<Integer, Integer> logicalBindings = new TreeMap<>();
        List<PackAdvancedResourcePlan.StorageBufferBinding> bindings = new ArrayList<>();
        for (StorageBlock block : merged.blocks().values()) {
            BlockMatch match = match(program, block, merged.duplicates(), specs);
            PackAdvancedResourcePlan.BufferSpec spec = match.spec();
            List<String> local = match.deviations();
            PackAdvancedResourcePlan.Status status = match.bindable()
                    ? PackAdvancedResourcePlan.Status.SUPPORTED : PackAdvancedResourcePlan.Status.UNSUPPORTED;
            int logicalIndex = spec == null ? -1 : spec.logicalIndex();
            long size = spec == null ? 0L : spec.size();
            int rewritten = status != PackAdvancedResourcePlan.Status.SUPPORTED ? -1
                    : logicalBindings.computeIfAbsent(
                    logicalIndex, ignored -> bindingBase + logicalBindings.size());
            PackAdvancedResourcePlan.StorageBufferBinding binding =
                    new PackAdvancedResourcePlan.StorageBufferBinding(
                    program, block.blockName(), block.instanceName(),
                    block.originalBinding(), logicalIndex, rewritten, block.stageMask(),
                    size, status, local);
            bindings.add(binding);
            deviations.addAll(local);
            if (!binding.supported()) {
                deviations.add("STORAGE_BUFFER_PROGRAM_FALLBACK:" + program);
            }
        }
        return bindings;
    }

    /** The declaration a block names (null unless exactly one), whether it binds, and the block's deviations. */
    record BlockMatch(PackAdvancedResourcePlan.BufferSpec spec, boolean bindable, List<String> deviations) {}

    /**
     * How a program's or pass compute's block meets the pack's bufferObject declarations: it binds
     * when it names exactly one supported declaration and nothing in its own deviations
     * (UNBINDABLE_PREFIXES) stops it.
     */
    static BlockMatch match(String program, StorageBlock block, Set<String> duplicateBlocks,
                            Map<Integer, PackAdvancedResourcePlan.BufferSpec> specs) {
        List<PackAdvancedResourcePlan.BufferSpec> matches = matchingBufferSpecs(block, specs);
        PackAdvancedResourcePlan.BufferSpec spec = matches.size() == 1 ? matches.get(0) : null;
        List<String> local = blockDeviations(program, block, duplicateBlocks, matches);
        boolean bindable = spec != null && spec.supported()
                && local.stream().noneMatch(value -> UNBINDABLE_PREFIXES.stream().anyMatch(value::startsWith));
        return new BlockMatch(spec, bindable, local);
    }

    /** A block's own deviations: its layout, a duplicate declaration, and how it matched a declaration. */
    private static List<String> blockDeviations(String program, StorageBlock block, Set<String> duplicateBlocks,
                                                List<PackAdvancedResourcePlan.BufferSpec> matches) {
        List<String> local = new ArrayList<>();
        if (!block.laidOut()) {
            local.add("STORAGE_BUFFER_LAYOUT_UNSUPPORTED:" + block.blockName());
        }
        if (duplicateBlocks.contains(blockKey(block))) {
            local.add("STORAGE_BUFFER_BLOCK_DUPLICATE:" + program + ":"
                    + block.blockName());
        }
        if (matches.size() > 1) {
            local.add("STORAGE_BUFFER_BLOCK_AMBIGUOUS:" + program + ":"
                    + block.blockName());
        } else if (matches.isEmpty()) {
            local.add("STORAGE_BUFFER_BLOCK_UNMAPPED:" + program + ":"
                    + block.blockName());
        } else {
            local.addAll(matches.get(0).deviations());
        }
        return local;
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
        long remaining = Math.max(0L, PackAdvancedResourcePlan.MAX_TOTAL_BYTES - imageBytes(images));
        boolean rejected = false;
        for (Integer index : programBuffers(storageBuffers, eligiblePrograms)) {
            PackAdvancedResourcePlan.BufferSpec spec = result.get(index);
            // A relative buffer follows the screen; allocation checks it against the device's range.
            if (spec == null || !spec.supported() || spec.relative()) continue;
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

    /** The bytes the supported images take from the session budget. */
    static long imageBytes(Map<String, PackAdvancedResourcePlan.ImageSpec> images) {
        return images.values().stream()
                .filter(PackAdvancedResourcePlan.ImageSpec::supported)
                .mapToLong(PackAdvancedResourcePlan.ImageSpec::estimatedBytes)
                .sum();
    }

    /** The bufferObject indexes storage-eligible programs bind through a supported block. */
    static Set<Integer> programBuffers(Map<String, List<PackAdvancedResourcePlan.StorageBufferBinding>> storageBuffers,
                                       List<String> eligiblePrograms) {
        TreeSet<Integer> used = new TreeSet<>();
        storageBuffers.entrySet().stream()
                .filter(entry -> eligiblePrograms.contains(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .filter(PackAdvancedResourcePlan.StorageBufferBinding::supported)
                .map(PackAdvancedResourcePlan.StorageBufferBinding::logicalIndex)
                .forEach(used::add);
        return used;
    }

    record StorageBlock(
            String blockName,
            String instanceName,
            int originalBinding,
            boolean laidOut,
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
                    block.originalBinding(), block.laidOut(), 0));
        }
        return result;
    }
}
