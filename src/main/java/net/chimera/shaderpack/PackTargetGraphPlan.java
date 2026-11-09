package net.chimera.shaderpack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    /** A declared format the device cannot render keeps its range as RGBA16F, not the 8-bit default. */
    private static final int DEVICE_FALLBACK_FORMAT = 97;

    private final List<TargetSpec> targets;
    private final List<TargetStep> steps;
    private final DepthGraphPlan depth;
    private final List<String> deviations;
    private final int maxAttachments;
    /** Targets a world (gbuffers) program samples; the world draws before any post step. */
    private final Set<Integer> geometryReads;
    /** Which side every pass, the world and final read and write each target from (PackFlipPlan). */
    private final PackFlipPlan flips;
    private final String snapshot;
    private final String fingerprint;

    private PackTargetGraphPlan(
            List<TargetSpec> targets,
            List<TargetStep> steps,
            DepthGraphPlan depth,
            List<String> deviations,
            int maxAttachments,
            Set<Integer> geometryReads,
            PackFlipPlan flips
    ) {
        this.targets = targets.stream().sorted(Comparator.comparingInt(TargetSpec::index)).toList();
        this.steps = steps.stream().toList();
        this.flips = flips == null ? PackFlipPlan.empty() : flips;
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
        return build(programs, config, resources, new Limits(windowWidth, windowHeight, deviceMaxColorAttachments,
                deviceMaxImageDimension), ComputeUse.NONE);
    }

    /** The window's size and the device's attachment and image-dimension limits. */
    public record Limits(int windowWidth, int windowHeight, int deviceMaxColorAttachments,
                         int deviceMaxImageDimension) {}

    /**
     * What the session's pass computes bind: the colour targets they read or write and the depth
     * snapshots they read. Iris creates a target a compute names (IrisImages.addRenderTargetImages:
     * createIfUnsure) and gives computes the composite depth samplers (CompositeRenderer.createComputes).
     */
    public record ComputeUse(Set<Integer> targets, Set<String> depthReads) {
        public static final ComputeUse NONE = new ComputeUse(Set.of(), Set.of());
    }

    /** The graph for these programs and the targets and depth snapshots the session's computes use. */
    public static PackTargetGraphPlan build(List<PackProgramPlan> programs, PackConfig.PackConfigData config,
                                            PackResourcePlan resources, Limits limits, ComputeUse computes) {
        int deviceMaxColorAttachments = limits.deviceMaxColorAttachments();
        // A shadowcomp pass routes over the shadowcolor targets (PackShadowComposites), not these.
        GraphInputs inputs = new GraphInputs(
                programs == null ? List.of() : programs.stream()
                        .filter(value -> value != null && !PassComputePlan.isShadowcomp(value.name())).toList(),
                config, resources == null ? PackResourcePlan.empty() : resources,
                Math.max(1, limits.windowWidth()), Math.max(1, limits.windowHeight()), deviceMaxColorAttachments,
                limits.deviceMaxImageDimension() <= 0 ? Integer.MAX_VALUE : limits.deviceMaxImageDimension(),
                unavailableFormatTargets(config), computes);
        List<String> deviations = new ArrayList<>();
        Set<Integer> geometryReads = new TreeSet<>();
        Map<Integer, TargetSpec> preliminary = preliminaryTargets(inputs, usedTargets(inputs, geometryReads),
                deviations);
        List<PackProgramPlan> postPrograms = inputs.programs().stream()
                .filter(value -> value.targetPlan() != null)
                .sorted(Comparator.comparing(PackProgramPlan::name, PostTargetPlan.programComparator()))
                .toList();
        PackFlipPlan flips = PackFlipPlan.of(inputs.programs(), config);
        recordPreFlips(config, preliminary, deviations);
        Set<Integer> mipmappedTargets = new TreeSet<>();
        List<TargetStep> steps = new ArrayList<>();
        for (PackProgramPlan program : postPrograms) {
            TargetStep step = step(inputs, program, preliminary, mipmappedTargets);
            steps.add(step);
            deviations.addAll(step.deviations());
        }
        DepthGraphPlan depth = depthGraph(inputs, postPrograms);
        deviations.addAll(depth.deviations());
        return new PackTargetGraphPlan(finalTargets(preliminary, flips, mipmappedTargets), steps, depth,
                deviations, Math.max(1, deviceMaxColorAttachments), geometryReads, flips);
    }

    /** The pass groups' {@code flip.<group>_pre} keys. A flip.final_pre has no renderer in Iris and flips nothing. */
    private static final List<String> PRE_FLIP_KEYS = List.of("begin_pre", "prepare_pre", "deferred_pre",
            "composite_pre");

    /** Records each allocated target a pass group's {@code flip.<group>_pre} flips as the group opens. */
    private static void recordPreFlips(PackConfig.PackConfigData config, Map<Integer, TargetSpec> preliminary,
                                       List<String> deviations) {
        if (config == null) return;
        for (String key : PRE_FLIP_KEYS) {
            for (int target : config.preFlips().getOrDefault(key, List.of())) {
                if (!preliminary.containsKey(target)) continue;
                deviations.add("POST_TARGET_FLIP_PRE_APPLIED:" + key + ":" + target);
            }
        }
    }

    /** Which side every pass, the world and final read and write each target from. */
    public PackFlipPlan flips() {
        return flips;
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
            Set<Integer> unavailableFormatTargets,
            ComputeUse computes
    ) {
        Map<Integer, PackConfig.TargetSettings> settings() {
            return config == null ? Map.of() : config.targetSettings();
        }

        Map<Integer, Integer> formats() {
            return config == null ? Map.of() : config.colortexFormats();
        }

        Set<Integer> paddedAlpha() {
            return config == null ? Set.of() : config.paddedAlphaTargets();
        }

        boolean allocatable(Integer target) {
            return target != null && !unavailableFormatTargets.contains(target);
        }
    }

    /**
     * Every target an executable post or world program or a pass compute writes or reads, in range.
     * The targets a world program reads are also collected into {@code geometryReads}.
     */
    private static Set<Integer> usedTargets(GraphInputs inputs, Set<Integer> geometryReads) {
        Set<Integer> used = new TreeSet<>();
        used.add(0);
        inputs.computes().targets().stream().filter(inputs::allocatable).forEach(used::add);
        for (PackProgramPlan program : inputs.programs()) {
            if (program.targetPlan() == null || !program.executable() || !program.targetPlan().executable()) {
                continue;
            }
            program.targetPlan().targetSlots().stream().filter(inputs::allocatable).forEach(used::add);
            TargetImages.referenced(program).stream().filter(inputs::allocatable).forEach(used::add);
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
            for (int target : geometryReadTargets(inputs, program)) {
                geometryReads.add(target);
                used.add(target);
            }
        }
        // Resource identity resolution can recognize targets beyond this backend's
        // capability. Such references remain diagnostic inputs, never allocations.
        used.removeIf(target -> target < 0 || target > MAX_TARGET);
        return used;
    }

    /**
     * The allocatable targets a world program reads: those its samplers read, and a hand program's
     * colorimgN, the image world programs read as colortexN (TargetImages).
     */
    private static Set<Integer> geometryReadTargets(GraphInputs inputs, PackProgramPlan program) {
        Set<Integer> reads = new TreeSet<>(TargetImages.referenced(program));
        for (String sampler : geometrySamplerNames(program)) {
            Integer target = colorTarget(program, sampler, inputs.resources());
            if (target != null) reads.add(target);
        }
        reads.removeIf(target -> !inputs.allocatable(target));
        return reads;
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
                format = DEVICE_FALLBACK_FORMAT;
            }
            // Target 0 is the HDR image, cleared each frame to its fog-colour default (ChimeraMainPass).
            float[] clearColor = target == 0 ? setting.clearColorCopy() : setting.irisClearColor(target, null);
            preliminary.put(target, new TargetSpec(target, format, width, height,
                    setting.clear(), clearColor, !setting.clear(), false, false,
                    inputs.paddedAlpha().contains(target), setting.deviations()));
            deviations.addAll(setting.deviations());
        }
        return preliminary;
    }

    /**
     * One post program's step: what it reads and writes, and at which extent. Which side of each
     * target it reads and writes is the flip plan's.
     */
    private static TargetStep step(
            GraphInputs inputs,
            PackProgramPlan program,
            Map<Integer, TargetSpec> preliminary,
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
        List<Integer> unwritten = unwrittenTargets(post, writes, stepDeviations);
        List<Integer> stepWrites = new ArrayList<>(writes);
        stepWrites.addAll(unwritten);
        recordFlips(inputs.config(), post, preliminary, stepDeviations);
        return new TargetStep(post.programName(), reads, stepWrites, post.outputFormats(), mipmapTargets,
                extent.width(), extent.height(), post.isFinal(), executable, stepDeviations, unwritten);
    }

    /**
     * The targets a post program's directive names but its shader never writes. Iris flips every
     * draw buffer of a composite pass (CompositeRenderer), written or not, so the pass leaves such a
     * target with its other side's content. Bloop's composite names colortex13 and writes it only
     * with Distant Horizons; the flip is what drops the frame's water refraction.
     */
    private static List<Integer> unwrittenTargets(PostTargetPlan post, List<Integer> writes,
                                                  List<String> stepDeviations) {
        if (post.isFinal()) return List.of();
        List<Integer> unwritten = post.targetSlots().stream().distinct()
                .filter(target -> !writes.contains(target)).toList();
        for (int target : unwritten) {
            stepDeviations.add("POST_TARGET_UNWRITTEN_FLIPPED:" + post.programName() + ":" + target);
        }
        return unwritten;
    }

    /**
     * The color targets a post program samples, and those it binds as colorimgN, ascending: Iris
     * binds that the image the pass samples (IrisImages.addRenderTargetImages).
     */
    private static List<Integer> readTargets(GraphInputs inputs, PackProgramPlan program) {
        List<Integer> reads = new ArrayList<>(TargetImages.referenced(program));
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

    /** Records each allocated target a {@code flip.<program>} directive flips after this pass. */
    private static void recordFlips(PackConfig.PackConfigData config, PostTargetPlan post,
                                    Map<Integer, TargetSpec> preliminary, List<String> stepDeviations) {
        Map<String, List<Integer>> flips = config == null ? Map.of() : config.flips();
        for (int target : flips.getOrDefault(post.programName(), List.of())) {
            if (preliminary.containsKey(target)) {
                stepDeviations.add("POST_TARGET_FLIP_APPLIED:" + post.programName() + ":" + target);
            }
        }
    }

    /**
     * The allocated targets with their schedule-derived flags. Persistence is declaration-driven:
     * a target persists across frames only when the pack says it is not cleared every frame
     * (colortexNClear=false); every target is cleared on the first frame (PostTargetSides). A target
     * has an alt side when the flip plan flips or writes it.
     */
    private static List<TargetSpec> finalTargets(
            Map<Integer, TargetSpec> preliminary,
            PackFlipPlan flips,
            Set<Integer> mipmappedTargets
    ) {
        List<TargetSpec> targets = new ArrayList<>();
        for (TargetSpec source : preliminary.values()) {
            // Target 0 is reseeded from the HDR identity source every frame and reinitialized by
            // geometry, so graph-level persistence must not apply to it.
            boolean persistent = source.index() != 0 && source.persistent();
            targets.add(new TargetSpec(source.index(), source.format(), source.width(), source.height(),
                    source.clear(), source.clearColorCopy(), persistent,
                    flips.sided(source.index()),
                    mipmappedTargets.contains(source.index()), source.paddedAlpha(), source.deviations()));
        }
        return targets;
    }

    /**
     * The depth snapshots executable programs read. A world program that reads depth needs the
     * same snapshot a post pass would: gbuffers_water reads opaque depth as depthtex1. A depth name
     * a stage texture serves reads no snapshot.
     */
    private static DepthGraphPlan depthGraph(GraphInputs inputs, List<PackProgramPlan> postPrograms) {
        Set<String> depthReads = new TreeSet<>(inputs.computes().depthReads());
        for (PackProgramPlan program : postPrograms) {
            if (program.executable() && program.targetPlan().executable()) {
                depthReads.addAll(frameReads(program, samplerNames(program), inputs.resources()));
            }
        }
        for (PackProgramPlan program : inputs.programs()) {
            if (program.geometryOutputPlan() != null && program.executable()) {
                depthReads.addAll(frameReads(program, geometrySamplerNames(program), inputs.resources()));
            }
        }
        return DepthGraphPlan.of(depthReads);
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
        return PackResourcePlan.targetIndex(resources.frameResource(program.name(), sampler));
    }

    /** The frame resources {@code samplers} read in {@code program}; a name the pack serves reads none. */
    private static List<String> frameReads(PackProgramPlan program, List<String> samplers, PackResourcePlan resources) {
        return samplers.stream().map(sampler -> resources.frameResource(program.name(), sampler))
                .filter(Objects::nonNull).toList();
    }

    /**
     * The targets a post program's shader writes. One that certainly writes nothing leaves all its
     * directive targets unwritten, so they flip with no content change, as in Iris, whose clear of
     * both sides makes the flip neutral: Rethinking Voxels' prepare1 and prepare2 name colortex3
     * and only discard. Read as written, the flip handed prepare3 last frame's colour there.
     */
    private static List<Integer> outputTargets(PostTargetPlan plan) {
        if (plan.writesNoOutput() && !plan.isFinal() && !plan.targetSlots().isEmpty()) return List.of();
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

    /**
     * One size.buffer dimension: an absolute size, or a relative one as Iris's TextureScaleOverride
     * makes it, {@code (int) (window * factor)} with the factor a float. A factor past 16 is refused,
     * a guard Iris lacks.
     */
    static int parseDimension(String token, int window) {
        if (token.contains(".")) {
            float factor = Float.parseFloat(token);
            if (!Float.isFinite(factor) || factor <= 0.0F || factor > 16.0F) {
                throw new NumberFormatException();
            }
            return Math.max(1, (int) (window * factor));
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
     * Targets a world program reads: those it samples, and those a hand program stores to as
     * colorimgN, which bind the image world programs read (TargetImages). Iris reads both unordered.
     */
    public Set<Integer> geometryReads() {
        return geometryReads;
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
            if (target.paddedAlpha()) result.append(":paddedAlpha");
            result.append(';');
        }
        result.append("steps=");
        for (TargetStep step : steps) {
            result.append(step.programName()).append(':').append(step.readTargets()).append("->")
                    .append(step.outputTargets()).append(':').append(step.width()).append('x')
                    .append(step.height()).append(':').append(step.executable());
            if (!step.mipmapTargets().isEmpty()) result.append(":mipmap=").append(step.mipmapTargets());
            if (!step.unwrittenTargets().isEmpty()) result.append(":unwritten=").append(step.unwrittenTargets());
            result.append(';');
        }
        if (!geometryReads.isEmpty()) result.append("geometryReads=").append(new TreeSet<>(geometryReads)).append(';');
        result.append("flips=").append(flips.snapshot());
        result.append("depth=").append(depth.names()).append(':').append(depth.format())
                .append(";deviations=").append(deviations);
        return result.toString();
    }
}
