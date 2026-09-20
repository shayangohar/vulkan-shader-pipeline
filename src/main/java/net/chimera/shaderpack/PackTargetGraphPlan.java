package net.chimera.shaderpack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Immutable load-time target and depth graph. It is the only place where a
 * post program's logical reads, writes, extents, formats, and side schedule
 * are interpreted together.
 */
public final class PackTargetGraphPlan {
    /** Keep the graph range identical to the post target parser. */
    public static final int MAX_TARGET = PostTargetPlan.MAX_TARGET;
    public static final int LOGICAL_ATTACHMENT_LIMIT = 8;

    private final List<TargetSpec> targets;
    private final List<TargetStep> steps;
    private final DepthGraphPlan depth;
    private final List<String> deviations;
    private final int maxAttachments;
    private final String snapshot;
    private final String fingerprint;

    private PackTargetGraphPlan(
            List<TargetSpec> targets,
            List<TargetStep> steps,
            DepthGraphPlan depth,
            List<String> deviations,
            int maxAttachments
    ) {
        this.targets = targets.stream().sorted(Comparator.comparingInt(TargetSpec::index)).toList();
        this.steps = steps.stream().toList();
        this.depth = depth == null ? DepthGraphPlan.empty() : depth;
        this.deviations = deviations == null ? List.of()
                : deviations.stream().filter(value -> value != null && !value.isBlank())
                .distinct().sorted().toList();
        this.maxAttachments = Math.max(1, Math.min(LOGICAL_ATTACHMENT_LIMIT, maxAttachments));
        this.snapshot = buildSnapshot();
        this.fingerprint = ConformanceReport.sha256(snapshot.getBytes(StandardCharsets.UTF_8));
    }

    public static PackTargetGraphPlan build(
            List<PackProgramPlan> programs,
            PackConfig.PackConfigData config,
            int windowWidth,
            int windowHeight,
            int deviceMaxColorAttachments,
            int deviceMaxImageDimension
    ) {
        return build(programs, config, PackResourcePlan.empty(), windowWidth, windowHeight,
                deviceMaxColorAttachments, deviceMaxImageDimension);
    }

    /**
     * Builds the graph with the already-resolved resource plan. Standard
     * target aliases must allocate the same logical target that the resource
     * plan exposes; pack-texture overrides must not allocate an unused target.
     */
    public static PackTargetGraphPlan build(
            List<PackProgramPlan> programs,
            PackConfig.PackConfigData config,
            PackResourcePlan resources,
            int windowWidth,
            int windowHeight,
            int deviceMaxColorAttachments,
            int deviceMaxImageDimension
    ) {
        Map<Integer, Integer> formats = config == null ? Map.of() : config.colortexFormats();
        Map<Integer, PackConfig.TargetSettings> settings = config == null
                ? Map.of() : config.targetSettings();
        Map<String, List<Integer>> flips = config == null ? Map.of() : config.flips();
        Map<String, List<Integer>> preFlips = config == null ? Map.of() : config.preFlips();
        PackResourcePlan resourcePlan = resources == null ? PackResourcePlan.empty() : resources;
        int safeWindowWidth = Math.max(1, windowWidth);
        int safeWindowHeight = Math.max(1, windowHeight);
        int safeDimension = deviceMaxImageDimension <= 0 ? Integer.MAX_VALUE : deviceMaxImageDimension;
        Set<Integer> unavailableFormatTargets = unavailableFormatTargets(config);

        Set<Integer> used = new TreeSet<>();
        used.add(0);
        if (programs != null) {
            for (PackProgramPlan program : programs) {
                if (program == null || program.targetPlan() == null
                        || !program.executable() || !program.targetPlan().executable()) continue;
                for (int target : program.targetPlan().targetSlots()) {
                    if (!unavailableFormatTargets.contains(target)) {
                        used.add(target);
                    }
                }
                for (String sampler : samplerNames(program)) {
                    Integer target = colorTarget(program, sampler, resourcePlan);
                    if (target != null && !unavailableFormatTargets.contains(target)) {
                        used.add(target);
                    }
                }
            }
            for (PackProgramPlan program : programs) {
                if (program == null || program.geometryOutputPlan() == null
                        || !program.executable() || !program.geometryOutputPlan().executable()) {
                    continue;
                }
                for (int target : program.geometryOutputPlan().targetSlots()) {
                    if (!unavailableFormatTargets.contains(target)) {
                        used.add(target);
                    }
                }
            }
        }

        Map<Integer, Boolean> doubleTargets = new TreeMap<>();
        List<TargetStep> steps = new ArrayList<>();
        List<String> deviations = new ArrayList<>();
        // Resource identity resolution can recognize targets beyond this backend's
        // capability. Such references remain diagnostic inputs, never allocations.
        used.removeIf(target -> target < 0 || target > MAX_TARGET);
        List<PackProgramPlan> sortedPrograms = programs == null ? List.of() : programs.stream()
                .filter(value -> value != null && value.targetPlan() != null)
                .sorted(Comparator.comparing(PackProgramPlan::name, PostTargetPlan.programComparator()))
                .toList();

        Map<Integer, TargetSpec> preliminary = new TreeMap<>();
        for (int target : used) {
            PackConfig.TargetSettings setting = settings.getOrDefault(target, PackConfig.TargetSettings.defaults());
            Size size = resolveSize(setting.sizeExpression(), config, safeWindowWidth, safeWindowHeight,
                    target, deviations);
            int width = size.width();
            int height = size.height();
            if (width > safeDimension || height > safeDimension) {
                deviations.add("POST_TARGET_SIZE_UNSUPPORTED:" + target);
                width = Math.min(width, safeDimension);
                height = Math.min(height, safeDimension);
            }
            int format = formats.getOrDefault(target, PostTargetPlan.DEFAULT_FORMAT);
            if (!PackConfig.FMT_TO_VK.containsValue(format) && format != 37 && format != 97 && format != 109) {
                deviations.add("POST_TARGET_FORMAT_DEVICE_UNSUPPORTED:" + target);
                format = PostTargetPlan.DEFAULT_FORMAT;
            }
            preliminary.put(target, new TargetSpec(target, format, width, height,
                    setting.clear(), setting.clearColorCopy(), !setting.clear(), false,
                    setting.deviations()));
            deviations.addAll(setting.deviations());
            if (setting.mipmapped()) {
                deviations.add("POST_TARGET_MIPMAP_UNSUPPORTED:" + target);
            }
        }

        for (PackProgramPlan program : sortedPrograms) {
            PostTargetPlan post = program.targetPlan();
            List<Integer> outputs = new ArrayList<>(post.targetSlots());
            if (outputs.isEmpty()) outputs.add(0);
            List<Integer> writes = outputTargets(post);
            List<Integer> reads = new ArrayList<>();
            for (String sampler : samplerNames(program)) {
                Integer target = colorTarget(program, sampler, resourcePlan);
                if (target != null && !reads.contains(target)) reads.add(target);
            }
            reads.sort(Integer::compareTo);
            List<String> stepDeviations = new ArrayList<>(post.deviations());
            if (!program.executable()) {
                stepDeviations.addAll(program.deviations());
                for (String deviation : program.deviations()) {
                    if (!deviation.startsWith("SAMPLER_NOT_MAPPED:")) continue;
                    Integer target = PackResourcePlan.targetIndex(
                            deviation.substring("SAMPLER_NOT_MAPPED:".length()));
                    if (target != null && target > MAX_TARGET) {
                        stepDeviations.add("POST_TARGET_INDEX_UNSUPPORTED:" + target);
                    }
                }
            }
            boolean executable = program.executable() && post.executable();
            Set<Integer> referencedTargets = new TreeSet<>(outputs);
            referencedTargets.addAll(reads);
            for (int target : referencedTargets) {
                if (target < 0 || target > MAX_TARGET) {
                    stepDeviations.add("POST_TARGET_INDEX_UNSUPPORTED:" + target);
                    executable = false;
                }
                if (unavailableFormatTargets.contains(target)) {
                    stepDeviations.add("POST_TARGET_FORMAT_DEVICE_UNSUPPORTED:" + target);
                    executable = false;
                }
                PackConfig.TargetSettings setting = settings.get(target);
                if (setting != null && setting.mipmapped()) {
                    // The resource owner currently has one mip level. Keep the
                    // pass valid with an explicit base-level sampling fallback.
                    stepDeviations.add("POST_TARGET_MIPMAP_UNSUPPORTED:" + target);
                    stepDeviations.add("POST_TARGET_MIPMAP_BASE_LEVEL_FALLBACK:" + target);
                }
            }
            if (outputs.size() > Math.min(LOGICAL_ATTACHMENT_LIMIT, Math.max(1, deviceMaxColorAttachments))) {
                stepDeviations.add("POST_TARGET_ATTACHMENT_LIMIT:" + program.name());
                executable = false;
            }
            if (post.isFinal() && writes.stream().anyMatch(value -> value != 0)) {
                stepDeviations.add("FINAL_MRT_UNSUPPORTED");
                executable = false;
            }
            TargetSpec first = preliminary.get(outputs.get(0));
            int stepWidth = first == null ? safeWindowWidth : first.width();
            int stepHeight = first == null ? safeWindowHeight : first.height();
            for (int target : outputs) {
                TargetSpec spec = preliminary.get(target);
                if (spec == null || spec.width() != stepWidth || spec.height() != stepHeight) {
                    stepDeviations.add("POST_TARGET_SIZE_CONFLICT:" + program.name());
                    executable = false;
                }
            }
            if (post.isFinal() && (stepWidth != safeWindowWidth || stepHeight != safeWindowHeight)) {
                stepDeviations.add("POST_TARGET_SIZE_CONFLICT:" + program.name());
                executable = false;
            }
            for (int target : reads) {
                if (writes.contains(target)) {
                    doubleTargets.put(target, true);
                }
            }
            List<Integer> preFlipTargets = preFlips.getOrDefault(
                    post.isFinal() ? "final_pre" : post.programName() + "_pre", List.of());
            if (!preFlipTargets.isEmpty()) {
                for (int target : preFlipTargets) {
                    doubleTargets.put(target, true);
                    stepDeviations.add("POST_TARGET_FLIP_PRE_APPLIED:" + post.programName()
                            + ":" + target);
                }
            }
            for (int target : flips.getOrDefault(post.programName(), List.of())) {
                doubleTargets.put(target, true);
                stepDeviations.add("POST_TARGET_FLIP_APPLIED:" + post.programName() + ":" + target);
            }
            Map<Integer, Integer> readSides = new TreeMap<>();
            Map<Integer, Integer> writeSides = new TreeMap<>();
            for (int target : reads) readSides.put(target, 0);
            for (int target : writes) writeSides.put(target, doubleTargets.getOrDefault(target, false) ? 1 : 0);
            steps.add(new TargetStep(post.programName(), reads, writes,
                    post.outputFormats(), readSides, writeSides, stepWidth, stepHeight,
                    post.isFinal(), executable, stepDeviations));
            deviations.addAll(stepDeviations);
        }

        List<TargetSpec> finalTargets = new ArrayList<>();
        // Geometry outputs always precede post in frame order, so a post
        // read of one is never a cross-frame dependency.
        Set<Integer> geometryWritten = new TreeSet<>();
        if (programs != null) {
            for (PackProgramPlan program : programs) {
                if (program == null || program.geometryOutputPlan() == null
                        || !program.executable() || !program.geometryOutputPlan().executable()) {
                    continue;
                }
                geometryWritten.addAll(program.geometryOutputPlan().targetSlots());
            }
        }
        for (Map.Entry<Integer, TargetSpec> entry : preliminary.entrySet()) {
            TargetSpec source = entry.getValue();
            // Target 0 is reseeded from the HDR identity source every frame
            // and reinitialized by geometry, so graph-level persistence must
            // not apply to it. Every other read-before-write target carries
            // cross-frame state: the per-frame clear skips it and the install
            // seed covers the first frame instead.
            boolean persistent = source.persistent()
                    || (source.index() != 0
                    && hasReadBeforeWrite(source.index(), steps, geometryWritten));
            finalTargets.add(new TargetSpec(source.index(), source.format(), source.width(), source.height(),
                    source.clear(), source.clearColorCopy(), persistent,
                    doubleTargets.getOrDefault(source.index(), false), source.deviations()));
        }
        boolean depth0 = false;
        boolean depth1 = false;
        boolean depth2 = false;
        for (PackProgramPlan program : sortedPrograms) {
            if (!program.executable() || !program.targetPlan().executable()) {
                continue;
            }
            for (String sampler : samplerNames(program)) {
                depth0 |= sampler.equals("depthtex0");
                depth1 |= sampler.equals("depthtex1");
                depth2 |= sampler.equals("depthtex2");
            }
        }
        List<String> depthDeviations = new ArrayList<>();
        if (depth0 || depth1 || depth2) {
            depthDeviations.add("DEPTH_COPY_GRAPH_APPLIED");
            depthDeviations.add("DEPTH_COPY_REVERSED_Z_CONVERTED");
            if (depth2) depthDeviations.add("DEPTH2_PREHAND_AT_LEVEL_END");
        }
        deviations.addAll(depthDeviations);
        return new PackTargetGraphPlan(finalTargets, steps,
                new DepthGraphPlan(depth0, depth1, depth2, DepthGraphPlan.R32_SFLOAT, depthDeviations),
                deviations, Math.max(1, deviceMaxColorAttachments));
    }

    private static Set<Integer> unavailableFormatTargets(PackConfig.PackConfigData config) {
        Set<Integer> result = new TreeSet<>();
        if (config == null) {
            return result;
        }
        for (Map.Entry<Integer, Integer> entry : config.colortexFormats().entrySet()) {
            if (!isSupportedFormat(entry.getValue())) {
                result.add(entry.getKey());
            }
        }
        for (String deviation : config.deviations()) {
            if (!deviation.startsWith("POST_TARGET_FORMAT_UNSUPPORTED:")) {
                continue;
            }
            int marker = deviation.indexOf("colortex") + "colortex".length();
            int end = marker;
            while (end < deviation.length() && Character.isDigit(deviation.charAt(end))) {
                end++;
            }
            if (end > marker) {
                try {
                    result.add(Integer.parseInt(deviation.substring(marker, end)));
                } catch (NumberFormatException ignored) {
                    // The parser already reported the malformed declaration.
                }
            }
        }
        return result;
    }

    private static boolean isSupportedFormat(int format) {
        return PackConfig.FMT_TO_VK.containsValue(format)
                || format == 37 || format == 97 || format == 109;
    }

    private static List<String> samplerNames(PackProgramPlan program) {
        if (program.interfacePlan() == null) return List.of();
        return program.interfacePlan().effective(UniformRegistry.Stage.POST).samplers().stream()
                .map(UniformRegistry.SamplerBinding::name).toList();
    }

    private static Integer colorTarget(
            PackProgramPlan program,
            String sampler,
            PackResourcePlan resources
    ) {
        PackResourceBinding binding = resources.binding(program.name(), sampler);
        if (binding != null && binding.kind() != PackResourceKind.TARGET) {
            return null;
        }
        return PackResourcePlan.targetIndex(sampler);
    }

    private static List<Integer> outputTargets(PostTargetPlan plan) {
        if (plan.outputLocations().isEmpty()) {
            return plan.targetSlots().isEmpty() ? List.of(0) : List.of(plan.targetSlots().get(0));
        }
        List<Integer> result = new ArrayList<>();
        for (int location : plan.outputLocations()) {
            if (location >= 0 && location < plan.targetSlots().size()) {
                int target = plan.targetForOutput(location);
                if (!result.contains(target)) result.add(target);
            }
        }
        return result.isEmpty() ? List.of(0) : List.copyOf(result);
    }

    private static Size resolveSize(
            String expression,
            PackConfig.PackConfigData config,
            int windowWidth,
            int windowHeight,
            int target,
            List<String> deviations
    ) {
        if (expression == null || expression.isBlank()) return new Size(windowWidth, windowHeight);
        String value = expression.trim();
        if (config != null && config.settings() != null) {
            for (Map.Entry<String, String> entry : config.settings().defaults().entrySet()) {
                value = value.replace("${" + entry.getKey() + "}", entry.getValue());
            }
        }
        String[] parts = value.split("[x,\\s]+");
        if (parts.length != 2) {
            deviations.add("POST_TARGET_SIZE_UNSUPPORTED:" + target);
            return new Size(windowWidth, windowHeight);
        }
        try {
            int width = parseDimension(parts[0], windowWidth);
            int height = parseDimension(parts[1], windowHeight);
            if (width < 1 || height < 1) throw new NumberFormatException();
            deviations.add("POST_TARGET_SIZE_APPLIED:" + target);
            return new Size(width, height);
        } catch (NumberFormatException failure) {
            deviations.add("POST_TARGET_SIZE_UNSUPPORTED:" + target);
            return new Size(windowWidth, windowHeight);
        }
    }

    private static int parseDimension(String token, int window) {
        if (token.contains(".")) {
            double factor = Double.parseDouble(token);
            if (!Double.isFinite(factor) || factor <= 0.0 || factor > 16.0) {
                throw new NumberFormatException();
            }
            return Math.max(1, (int) Math.round(window * factor));
        }
        return Integer.parseInt(token);
    }

    private record Size(int width, int height) {}

    public List<TargetSpec> targets() { return targets; }
    public List<TargetStep> steps() { return steps; }
    public DepthGraphPlan depth() { return depth; }
    public List<String> deviations() { return deviations; }
    public int maxAttachments() { return maxAttachments; }
    public String snapshot() { return snapshot; }
    public String fingerprint() { return fingerprint; }

    public TargetSpec target(int index) {
        return targets.stream().filter(value -> value.index() == index).findFirst().orElse(null);
    }

    /**
     * Returns true when a persistent target must be seeded before the first
     * successful producer can run. This covers feedback passes that read a
     * clear=false target before they write it, while leaving unrelated
     * uninitialized targets unavailable.
     */
    public boolean requiresInitialSeed(int target) {
        return requiresInitialSeed(target(target), steps);
    }

    /** Pure form used by the target-owner tests. */
    public static boolean requiresInitialSeed(TargetSpec spec, List<TargetStep> steps) {
        if (spec == null || !spec.persistent()) {
            return false;
        }
        return hasReadBeforeWrite(spec.index(), steps, Set.of());
    }

    /**
     * True when a program reads the target before any program writes it in
     * frame order. Such targets carry cross-frame state by construction:
     * clearing them every frame destroys what their first reader expects.
     *
     * @param preProduced targets already produced before the steps run,
     *                    such as geometry outputs that always precede post
     */
    static boolean hasReadBeforeWrite(int index, List<TargetStep> steps, Set<Integer> preProduced) {
        boolean producerSeen = preProduced != null && preProduced.contains(index);
        boolean readBeforeProducer = false;
        for (TargetStep step : steps == null ? List.<TargetStep>of() : steps) {
            if (step == null) {
                continue;
            }
            if (!step.executable()) {
                continue;
            }
            if (!producerSeen && step.reads(index)) {
                readBeforeProducer = true;
            }
            if (step.writes(index)) {
                if (readBeforeProducer) {
                    return true;
                }
                producerSeen = true;
            }
        }
        return false;
    }

    public TargetStep step(String name) {
        return steps.stream().filter(value -> value.programName().equals(name)).findFirst().orElse(null);
    }

    public boolean executable(String name) {
        TargetStep step = step(name);
        return step != null && step.executable();
    }

    private String buildSnapshot() {
        StringBuilder result = new StringBuilder();
        result.append("targets=");
        for (TargetSpec target : targets) {
            result.append(target.index()).append(':').append(target.format()).append('@')
                    .append(target.width()).append('x').append(target.height()).append(':')
                    .append(target.clear()).append(':').append(target.persistent()).append(':')
                    .append(target.doubled()).append(':');
            for (float value : target.clearColor()) result.append(value).append(',');
            result.append(';');
        }
        result.append("steps=");
        for (TargetStep step : steps) {
            result.append(step.programName()).append(':').append(step.readTargets()).append("->")
                    .append(step.outputTargets()).append(':').append(step.width()).append('x')
                    .append(step.height()).append(':').append(step.executable()).append(';');
        }
        result.append("depth=").append(depth.names()).append(':').append(depth.format())
                .append(";deviations=").append(deviations);
        return result.toString();
    }
}
