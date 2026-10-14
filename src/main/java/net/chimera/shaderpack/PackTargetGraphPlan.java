package net.chimera.shaderpack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
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
    /** Targets a world (gbuffers) program samples; the world draws before any post step. */
    private final Set<Integer> geometryReads;
    private final String snapshot;
    private final String fingerprint;

    private PackTargetGraphPlan(
            List<TargetSpec> targets,
            List<TargetStep> steps,
            DepthGraphPlan depth,
            List<String> deviations,
            int maxAttachments,
            Set<Integer> geometryReads
    ) {
        this.targets = targets.stream().sorted(Comparator.comparingInt(TargetSpec::index)).toList();
        this.steps = steps.stream().toList();
        this.geometryReads = Set.copyOf(geometryReads);
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
        GraphInputs inputs = new GraphInputs(
                programs == null ? List.of() : programs.stream().filter(value -> value != null).toList(),
                config, resources == null ? PackResourcePlan.empty() : resources,
                Math.max(1, windowWidth), Math.max(1, windowHeight), deviceMaxColorAttachments,
                deviceMaxImageDimension <= 0 ? Integer.MAX_VALUE : deviceMaxImageDimension,
                unavailableFormatTargets(config));
        List<String> deviations = new ArrayList<>();
        Set<Integer> geometryReads = new TreeSet<>();
        Map<Integer, TargetSpec> preliminary = preliminaryTargets(inputs, usedTargets(inputs, geometryReads),
                deviations);
        List<PackProgramPlan> postPrograms = inputs.programs().stream()
                .filter(value -> value.targetPlan() != null)
                .sorted(Comparator.comparing(PackProgramPlan::name, PostTargetPlan.programComparator()))
                .toList();
        Map<Integer, Boolean> doubleTargets = new TreeMap<>();
        Set<Integer> mipmappedTargets = new TreeSet<>();
        List<TargetStep> steps = new ArrayList<>();
        for (PackProgramPlan program : postPrograms) {
            TargetStep step = step(inputs, program, preliminary, doubleTargets, mipmappedTargets);
            steps.add(step);
            deviations.addAll(step.deviations());
        }
        DepthGraphPlan depth = depthGraph(inputs.programs(), postPrograms);
        deviations.addAll(depth.deviations());
        return new PackTargetGraphPlan(finalTargets(preliminary, doubleTargets, mipmappedTargets), steps, depth,
                deviations, Math.max(1, deviceMaxColorAttachments), geometryReads);
    }

    /** What every phase of the graph build reads. */
    private record GraphInputs(
            List<PackProgramPlan> programs,
            PackConfig.PackConfigData config,
            PackResourcePlan resources,
            int windowWidth,
            int windowHeight,
            int deviceMaxColorAttachments,
            int maxDimension,
            Set<Integer> unavailableFormatTargets
    ) {
        Map<Integer, PackConfig.TargetSettings> settings() {
            return config == null ? Map.of() : config.targetSettings();
        }

        Map<Integer, Integer> formats() {
            return config == null ? Map.of() : config.colortexFormats();
        }

        boolean allocatable(Integer target) {
            return target != null && !unavailableFormatTargets.contains(target);
        }
    }

    /**
     * Every target an executable post or world program writes or reads, in range. The targets a
     * world program reads are also collected into {@code geometryReads}.
     */
    private static Set<Integer> usedTargets(GraphInputs inputs, Set<Integer> geometryReads) {
        Set<Integer> used = new TreeSet<>();
        used.add(0);
        for (PackProgramPlan program : inputs.programs()) {
            if (program.targetPlan() == null || !program.executable() || !program.targetPlan().executable()) {
                continue;
            }
            program.targetPlan().targetSlots().stream().filter(inputs::allocatable).forEach(used::add);
            for (String sampler : samplerNames(program)) {
                Integer target = colorTarget(program, sampler, inputs.resources());
                if (inputs.allocatable(target)) used.add(target);
            }
        }
        for (PackProgramPlan program : inputs.programs()) {
            if (program.geometryOutputPlan() == null || !program.executable()
                    || !program.geometryOutputPlan().executable()) {
                continue;
            }
            program.geometryOutputPlan().targetSlots().stream().filter(inputs::allocatable).forEach(used::add);
            for (String sampler : geometrySamplerNames(program)) {
                Integer target = colorTarget(program, sampler, inputs.resources());
                if (inputs.allocatable(target)) {
                    geometryReads.add(target);
                    used.add(target);
                }
            }
        }
        // Resource identity resolution can recognize targets beyond this backend's
        // capability. Such references remain diagnostic inputs, never allocations.
        used.removeIf(target -> target < 0 || target > MAX_TARGET);
        return used;
    }

    /** Each used target's declared size, format and clear policy, clamped to what the device allows. */
    private static Map<Integer, TargetSpec> preliminaryTargets(
            GraphInputs inputs, Set<Integer> used, List<String> deviations) {
        Map<Integer, TargetSpec> preliminary = new TreeMap<>();
        for (int target : used) {
            PackConfig.TargetSettings setting = inputs.settings()
                    .getOrDefault(target, PackConfig.TargetSettings.defaults());
            Size size = resolveSize(setting.sizeExpression(), inputs.config(), inputs.windowWidth(),
                    inputs.windowHeight(), target, deviations);
            int width = size.width();
            int height = size.height();
            if (width > inputs.maxDimension() || height > inputs.maxDimension()) {
                deviations.add("POST_TARGET_SIZE_UNSUPPORTED:" + target);
                width = Math.min(width, inputs.maxDimension());
                height = Math.min(height, inputs.maxDimension());
            }
            int format = inputs.formats().getOrDefault(target, PostTargetPlan.DEFAULT_FORMAT);
            if (!isSupportedFormat(format)) {
                deviations.add("POST_TARGET_FORMAT_DEVICE_UNSUPPORTED:" + target);
                format = PostTargetPlan.DEFAULT_FORMAT;
            }
            // Target 0 is the HDR image, cleared each frame to its fog-colour default (ChimeraMainPass).
            float[] clearColor = target == 0 ? setting.clearColorCopy() : setting.irisClearColor(target, null);
            preliminary.put(target, new TargetSpec(target, format, width, height,
                    setting.clear(), clearColor, !setting.clear(), false, false,
                    setting.deviations()));
            deviations.addAll(setting.deviations());
        }
        return preliminary;
    }

    /**
     * One post program's step: what it reads and writes, at which extent, and on which side of
     * each double-buffered target. Targets it both reads and writes, or that a flip names, become
     * double-buffered in {@code doubleTargets}.
     */
    private static TargetStep step(
            GraphInputs inputs,
            PackProgramPlan program,
            Map<Integer, TargetSpec> preliminary,
            Map<Integer, Boolean> doubleTargets,
            Set<Integer> mipmappedTargets
    ) {
        PostTargetPlan post = program.targetPlan();
        List<Integer> outputs = new ArrayList<>(post.targetSlots());
        if (outputs.isEmpty()) outputs.add(0);
        List<Integer> writes = outputTargets(post);
        List<Integer> reads = readTargets(inputs, program);
        List<String> stepDeviations = new ArrayList<>(post.deviations());
        if (!program.executable()) {
            addProgramFailures(program, stepDeviations);
        }
        boolean executable = program.executable() && post.executable();
        Set<Integer> referencedTargets = new TreeSet<>(outputs);
        referencedTargets.addAll(reads);
        executable &= targetsSupported(inputs, referencedTargets, stepDeviations);
        // Iris generates mips for every directive whose target exists,
        // read by this program or not; an unallocated target has nothing to mip.
        List<Integer> mipmapTargets = post.mipmappedTargets().stream().filter(preliminary::containsKey).toList();
        if (executable) mipmappedTargets.addAll(mipmapTargets);
        if (outputs.size() > Math.min(LOGICAL_ATTACHMENT_LIMIT, Math.max(1, inputs.deviceMaxColorAttachments()))) {
            stepDeviations.add("POST_TARGET_ATTACHMENT_LIMIT:" + program.name());
            executable = false;
        }
        if (post.isFinal() && writes.stream().anyMatch(value -> value != 0)) {
            stepDeviations.add("FINAL_MRT_UNSUPPORTED");
            executable = false;
        }
        StepExtent extent = StepExtent.of(inputs, post, outputs, preliminary);
        if (extent.conflict()) {
            stepDeviations.add("POST_TARGET_SIZE_CONFLICT:" + program.name());
            executable = false;
        }
        for (int target : reads) {
            if (writes.contains(target)) {
                doubleTargets.put(target, true);
            }
        }
        StepFlips flips = applyFlips(inputs.config(), post, preliminary, doubleTargets, stepDeviations);
        Map<Integer, Integer> readSides = new TreeMap<>();
        Map<Integer, Integer> writeSides = new TreeMap<>();
        for (int target : reads) readSides.put(target, 0);
        for (int target : writes) writeSides.put(target, doubleTargets.getOrDefault(target, false) ? 1 : 0);
        return new TargetStep(post.programName(), reads, writes, post.outputFormats(), readSides, writeSides,
                mipmapTargets, extent.width(), extent.height(), post.isFinal(), executable, stepDeviations,
                flips.before(), flips.after());
    }

    /** The color targets a post program samples, ascending. */
    private static List<Integer> readTargets(GraphInputs inputs, PackProgramPlan program) {
        List<Integer> reads = new ArrayList<>();
        for (String sampler : samplerNames(program)) {
            Integer target = colorTarget(program, sampler, inputs.resources());
            if (target != null && !reads.contains(target)) reads.add(target);
        }
        reads.sort(Integer::compareTo);
        return reads;
    }

    /** A program that cannot execute keeps its own deviations, and names each unmapped target past the range. */
    private static void addProgramFailures(PackProgramPlan program, List<String> stepDeviations) {
        stepDeviations.addAll(program.deviations());
        for (String deviation : program.deviations()) {
            if (!deviation.startsWith("SAMPLER_NOT_MAPPED:")) continue;
            Integer target = PackResourcePlan.targetIndex(deviation.substring("SAMPLER_NOT_MAPPED:".length()));
            if (target != null && target > MAX_TARGET) {
                stepDeviations.add("POST_TARGET_INDEX_UNSUPPORTED:" + target);
            }
        }
    }

    /** Whether every target a step references is in range and in a format the device supports. */
    private static boolean targetsSupported(GraphInputs inputs, Set<Integer> referencedTargets,
                                            List<String> stepDeviations) {
        boolean supported = true;
        for (int target : referencedTargets) {
            if (target < 0 || target > MAX_TARGET) {
                stepDeviations.add("POST_TARGET_INDEX_UNSUPPORTED:" + target);
                supported = false;
            }
            if (inputs.unavailableFormatTargets().contains(target)) {
                stepDeviations.add("POST_TARGET_FORMAT_DEVICE_UNSUPPORTED:" + target);
                supported = false;
            }
        }
        return supported;
    }

    /**
     * The extent a step draws at, its first output's size or the window's, and whether another
     * output, or a final pass against the window, disagrees with it.
     */
    private record StepExtent(int width, int height, boolean conflict) {
        static StepExtent of(GraphInputs inputs, PostTargetPlan post, List<Integer> outputs,
                             Map<Integer, TargetSpec> preliminary) {
            TargetSpec first = preliminary.get(outputs.get(0));
            int stepWidth = first == null ? inputs.windowWidth() : first.width();
            int stepHeight = first == null ? inputs.windowHeight() : first.height();
            boolean sizeConflict = outputs.stream().map(preliminary::get).anyMatch(spec ->
                    spec == null || spec.width() != stepWidth || spec.height() != stepHeight)
                    || post.isFinal() && (stepWidth != inputs.windowWidth() || stepHeight != inputs.windowHeight());
            return new StepExtent(stepWidth, stepHeight, sizeConflict);
        }
    }

    /** The allocated targets a step's pre-flip and flip directives flip before and after it runs. */
    private record StepFlips(List<Integer> before, List<Integer> after) {}

    /** Double-buffers each target a pre-flip or flip directive names for this pass, recording each. */
    private static StepFlips applyFlips(PackConfig.PackConfigData config, PostTargetPlan post,
                                        Map<Integer, TargetSpec> preliminary, Map<Integer, Boolean> doubleTargets,
                                        List<String> stepDeviations) {
        Map<String, List<Integer>> preFlips = config == null ? Map.of() : config.preFlips();
        Map<String, List<Integer>> flips = config == null ? Map.of() : config.flips();
        List<Integer> before = preFlips.getOrDefault(post.isFinal() ? "final_pre" : post.programName() + "_pre",
                List.of()).stream().filter(preliminary::containsKey).toList();
        List<Integer> after = flips.getOrDefault(post.programName(), List.of()).stream()
                .filter(preliminary::containsKey).toList();
        for (int target : before) {
            doubleTargets.put(target, true);
            stepDeviations.add("POST_TARGET_FLIP_PRE_APPLIED:" + post.programName() + ":" + target);
        }
        for (int target : after) {
            doubleTargets.put(target, true);
            stepDeviations.add("POST_TARGET_FLIP_APPLIED:" + post.programName() + ":" + target);
        }
        return new StepFlips(before, after);
    }

    /**
     * The allocated targets with their schedule-derived flags. Persistence is declaration-driven:
     * a target persists across frames only when the pack says it is not cleared every frame
     * (colortexNClear=false). A read-before-write shape alone never implies persistence; declared
     * persistent feedback targets still receive their one-time initial seed through
     * requiresInitialSeed.
     */
    private static List<TargetSpec> finalTargets(
            Map<Integer, TargetSpec> preliminary,
            Map<Integer, Boolean> doubleTargets,
            Set<Integer> mipmappedTargets
    ) {
        List<TargetSpec> targets = new ArrayList<>();
        for (TargetSpec source : preliminary.values()) {
            // Target 0 is reseeded from the HDR identity source every frame and reinitialized by
            // geometry, so graph-level persistence must not apply to it.
            boolean persistent = source.index() != 0 && source.persistent();
            targets.add(new TargetSpec(source.index(), source.format(), source.width(), source.height(),
                    source.clear(), source.clearColorCopy(), persistent,
                    doubleTargets.getOrDefault(source.index(), false),
                    mipmappedTargets.contains(source.index()), source.deviations()));
        }
        return targets;
    }

    /**
     * The depth snapshots executable programs read. A world program that reads depth needs the
     * same snapshot a post pass would: gbuffers_water reads opaque depth as depthtex1.
     */
    private static DepthGraphPlan depthGraph(List<PackProgramPlan> programs, List<PackProgramPlan> postPrograms) {
        Set<String> depthReads = new TreeSet<>();
        for (PackProgramPlan program : postPrograms) {
            if (program.executable() && program.targetPlan().executable()) {
                depthReads.addAll(samplerNames(program));
            }
        }
        for (PackProgramPlan program : programs) {
            if (program.geometryOutputPlan() != null && program.executable()) {
                depthReads.addAll(geometrySamplerNames(program));
            }
        }
        boolean depth0 = depthReads.contains("depthtex0");
        boolean depth1 = depthReads.contains("depthtex1");
        boolean depth2 = depthReads.contains("depthtex2");
        List<String> depthDeviations = new ArrayList<>();
        if (depth0 || depth1 || depth2) {
            depthDeviations.add("DEPTH_COPY_GRAPH_APPLIED");
            depthDeviations.add("DEPTH_COPY_FORWARD_Z_PRESERVED");
            if (depth2) depthDeviations.add("DEPTH2_PREHAND_AT_LEVEL_END");
        }
        return new DepthGraphPlan(depth0, depth1, depth2, DepthGraphPlan.R32_SFLOAT, depthDeviations);
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
        return PackConfig.FMT_TO_VK.containsValue(format);
    }

    private static List<String> samplerNames(PackProgramPlan program) {
        if (program.interfacePlan() == null) return List.of();
        return program.interfacePlan().effective(UniformRegistry.Stage.POST).samplers().stream()
                .map(UniformRegistry.SamplerBinding::name).toList();
    }

    /** Samplers a world program reads, projected through its own stage table. */
    private static List<String> geometrySamplerNames(PackProgramPlan program) {
        if (program.interfacePlan() == null) return List.of();
        return program.interfacePlan().effective(FamilyAdapterRegistry.stageFor(program.name()))
                .samplers().stream().map(UniformRegistry.SamplerBinding::name).toList();
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
        return requiresInitialSeed(target(target), steps, geometryReads);
    }

    /** Targets a world program samples. */
    public Set<Integer> geometryReads() {
        return geometryReads;
    }

    /** Pure form used by the target-owner tests. */
    public static boolean requiresInitialSeed(TargetSpec spec, List<TargetStep> steps) {
        return requiresInitialSeed(spec, steps, Set.of());
    }

    /**
     * A persistent target needs a defined first frame when a post program reads
     * it before any writes it, or when a world program reads it at all: the
     * world draws before every post step (MakeUp's terrain reads last frame's
     * fog from gaux4).
     */
    public static boolean requiresInitialSeed(TargetSpec spec, List<TargetStep> steps, Set<Integer> geometryReads) {
        if (spec == null || !spec.persistent()) {
            return false;
        }
        return geometryReads.contains(spec.index()) || hasReadBeforeWrite(spec.index(), steps, Set.of());
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
            if (target.mipmapped()) result.append(":mips=").append(target.mipLevels());
            result.append(';');
        }
        result.append("steps=");
        for (TargetStep step : steps) {
            result.append(step.programName()).append(':').append(step.readTargets()).append("->")
                    .append(step.outputTargets()).append(':').append(step.width()).append('x')
                    .append(step.height()).append(':').append(step.executable());
            if (!step.mipmapTargets().isEmpty()) result.append(":mipmap=").append(step.mipmapTargets());
            result.append(';');
        }
        if (!geometryReads.isEmpty()) result.append("geometryReads=").append(new TreeSet<>(geometryReads)).append(';');
        result.append("depth=").append(depth.names()).append(':').append(depth.format())
                .append(";deviations=").append(deviations);
        return result.toString();
    }
}
