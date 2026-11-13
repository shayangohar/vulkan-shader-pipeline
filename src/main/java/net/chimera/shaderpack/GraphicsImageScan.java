package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One pass over every program's prepared stages for the pack images they use: the programs that
 * depend on an advanced resource, the images each stage writes through a storage image declaration
 * and reads through its sampler, and the stages guarded by the custom-image feature.
 */
final class GraphicsImageScan {
    static final Pattern GRAPHICS_IMAGE_DECLARATION = Pattern.compile(
            "(?:layout\\s*\\(([^)]*)\\)\\s*)?"
                    + "(?:(?:uniform|writeonly|readonly|coherent|volatile|restrict)\\s+)*"
                    + "\\b(u?i?image(?:1D|2D|3D)(?:Array)?)\\s+([A-Za-z_]\\w*)");

    final List<String> dependent = new ArrayList<>();
    final List<String> customImageFeaturePrograms = new ArrayList<>();
    final Map<String, List<PackAdvancedResourcePlan.GraphicsImageBinding>> writers = new TreeMap<>();
    final Map<String, List<PackAdvancedResourcePlan.GraphicsImageBinding>> readers = new TreeMap<>();
    private final PackPlan plan;
    private final Map<String, PackAdvancedResourcePlan.ImageSpec> images;
    private final List<String> deviations;
    private final Set<String> imageSamplerNames;
    /** The selector slots the program being scanned samples through (addTargetWriter). */
    private Set<Integer> sampledSlots = Set.of();
    /** The colour targets the world program being scanned draws to (addTargetWriter). */
    private Set<Integer> drawnTargets = Set.of();

    private GraphicsImageScan(PackPlan plan, Map<String, PackAdvancedResourcePlan.ImageSpec> images,
                              List<String> deviations) {
        this.plan = plan;
        this.images = images;
        this.deviations = deviations;
        this.imageSamplerNames = images.values().stream()
                .map(PackAdvancedResourcePlan.ImageSpec::sampler)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    static GraphicsImageScan scan(PackPlan plan, Map<String, PackAdvancedResourcePlan.ImageSpec> images,
                                  List<String> deviations) {
        GraphicsImageScan scan = new GraphicsImageScan(plan, images, deviations);
        List<PackProgramPlan> programs = plan.programs().stream()
                .filter(program -> program != null && !program.name().isBlank()).toList();
        // A program's bindings and deviations are its own: scanned apart and merged in program
        // order, they are what one scan of every program records.
        for (GraphicsImageScan part : PackWorkers.map(programs, program -> {
            GraphicsImageScan own = new GraphicsImageScan(plan, images, new ArrayList<>());
            own.scanProgram(program);
            return own;
        })) {
            scan.merge(part);
        }
        return scan;
    }

    private void merge(GraphicsImageScan part) {
        dependent.addAll(part.dependent);
        customImageFeaturePrograms.addAll(part.customImageFeaturePrograms);
        part.writers.forEach((program, bindings) ->
                writers.computeIfAbsent(program, ignored -> new ArrayList<>()).addAll(bindings));
        part.readers.forEach((program, bindings) ->
                readers.computeIfAbsent(program, ignored -> new ArrayList<>()).addAll(bindings));
        deviations.addAll(part.deviations);
    }

    private void scanProgram(PackProgramPlan program) {
        // Photon's scattering and worley volumes are raw 3D textures, not images shadowcomp produces.
        Map<String, PackResourceDeclaration> declarations = plan.settings().resourceDeclarations();
        Predicate<String> packVolume = sampler ->
                PackResourcePlan.rawTextureServes(program.name(), sampler, "sampler3D", declarations);
        boolean usesAdvanced = program.stages().values().stream().anyMatch(value ->
                AdvancedImageDeclarations.usesAdvancedResource(value.source(), images, packVolume));
        if (usesAdvanced) {
            dependent.add(program.name());
        }
        sampledSlots = program.interfacePlan() == null ? Set.of()
                : program.interfacePlan().effective(UniformRegistry.Stage.POST).samplers().stream()
                        .map(UniformRegistry.SamplerBinding::slot).collect(Collectors.toSet());
        drawnTargets = !TargetImages.world(program.name()) || program.geometryOutputPlan() == null ? Set.of()
                : Set.copyOf(program.geometryOutputPlan().targetSlots());
        for (Map.Entry<String, PreparedShaderSource> stageEntry : program.stages().entrySet()) {
            PreparedShaderSource stage = stageEntry.getValue();
            if (stage == null || stage.source() == null) continue;
            scanStage(program.name(), stageEntry.getKey(), stage.source());
        }
    }

    private void scanStage(String program, String stage, String source) {
        GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(source, imageSamplerNames);
        deviations.addAll(usage.deviations());
        // Vulkan GLSL needs a binding for every opaque declaration, even one nothing reads (Solas
        // declares voxelSampler in terrain but only the shadow pass writes voxel_img). An image
        // declaration nothing reachable reads is already gone, with any dead code that read it
        // (PackPlanBuilder.withoutInactiveImages); samplers stay. Give every declared pack
        // image and every declared or referenced pack image sampler a descriptor. Runtime
        // eligibility remains based on the live uses in usesAdvancedResource.
        // The pattern opens with optional parts, so it is tried at every character; a stage
        // without an image declaration (most of them) cannot match at all.
        Matcher image = GRAPHICS_IMAGE_DECLARATION.matcher(source.contains("image") ? source : "");
        while (image.find()) {
            String symbol = image.group(3);
            // A match the parser does not see as a declaration declares nothing: Shrimple's composite18
            // keeps `//layout(r32f) uniform readonly image2D imgDepthNear;` in a comment.
            if (usage.successful() && !usage.declaredImages().contains(symbol)) continue;
            String format = AdvancedImageDeclarations.declaredFormat(image.group(1));
            addWriter(program, stage, new Declared(image.group(2), symbol, format),
                    GlslTokenRewriter.identifierCount(source, symbol) > 1);
        }
        Set<String> imageSamplers = new TreeSet<>(usage.referencedSamplers());
        imageSamplers.addAll(UniformRegistry.scanDeclaredSamplerNames(source));
        for (String sampler : imageSamplers) {
            addReader(program, stage, sampler, usage.referencedSamplers().contains(sampler)
                    || GlslTokenRewriter.identifierCount(source, sampler) > 1,
                    !usage.successful() || usage.liveSamplers().contains(sampler));
        }
        if (source.contains("IRIS_FEATURE_CUSTOM_IMAGES")
                && source.contains("OPTIFINE_ACT_ERROR")) {
            customImageFeaturePrograms.add(program);
        }
    }

    /** A storage image declaration: its GLSL type, its name, and its format qualifier, or none. */
    private record Declared(String glslType, String symbol, String format) {}

    /**
     * A storage image declaration of a pack image, bound to its sampler's selector. An image
     * Chimera does not create refuses the program only where the stage references it.
     */
    private void addWriter(String program, String stage, Declared declared, boolean referenced) {
        String symbol = declared.symbol();
        PackAdvancedResourcePlan.ImageSpec spec = PackAdvancedResourcePlan.imageBySymbol(images, symbol);
        if (spec == null) {
            int target = TargetImages.target(symbol);
            if (target >= 0 && TargetImages.servedIn(program)) {
                if (referenced) addTargetWriter(program, stage, declared, target);
                return;
            }
            // Not a pack image: Iris's shadowcolorimgN, and colorimgN outside the post passes and
            // the hand programs, which no draw here binds.
            if (referenced) deviations.add(PackAdvancedResourcePlan.UNSERVED_IMAGE + program + ":" + symbol);
            return;
        }
        if (!spec.supported() && !referenced) return;
        String viewFormat = spec.supported() ? viewFormat(program, spec, declared.format()) : "";
        if (viewFormat == null || formatConflict(program, spec, symbol, viewFormat)) return;
        int selectorSlot = servedSlot(program, spec);
        if (selectorSlot < 0) return;
        writers.computeIfAbsent(program, ignored -> new ArrayList<>())
                .add(new PackAdvancedResourcePlan.GraphicsImageBinding(program, symbol,
                        spec.name(), spec.sampler(), declared.glslType(), viewFormat, selectorSlot,
                        StorageBufferDeclarations.stageMask(stage)));
    }

    /**
     * colorimgN in a post pass or a hand program: target N as a storage image at the slot the
     * program would sample it through, in the target's format or a same-size view the declaration
     * names, as for a pass compute (StorageForm). A format of another size has no such view, and a
     * program that also samples the target would read one image in two layouts: either refuses the
     * program. So does a world program that also draws to the target, which would hold it as an
     * attachment and a storage image at once; colortex0 counts as drawn, since the HDR image world
     * programs render into stands in for it.
     */
    private void addTargetWriter(String program, String stage, Declared declared, int target) {
        String symbol = declared.symbol();
        int slot = SelectorNamespace.colorTargetSlot(target);
        Map<Integer, Integer> formats = plan.config() == null ? Map.of() : plan.config().colortexFormats();
        StorageForm form = StorageForm.of(formats.getOrDefault(target, PostTargetPlan.DEFAULT_FORMAT),
                declared.format());
        if (form == null) deviations.add("ADVANCED_IMAGE_FORMAT_INCOMPATIBLE:" + symbol + ":" + declared.format());
        if (sampledSlots.contains(slot)) {
            deviations.add("TARGET_IMAGE_ALSO_SAMPLED:" + program + ":" + TargetImages.resource(target));
        }
        boolean drawn = TargetImages.world(program) && (target == 0 || drawnTargets.contains(target));
        if (drawn) deviations.add("TARGET_IMAGE_ALSO_DRAWN:" + program + ":" + TargetImages.resource(target));
        if (form == null || sampledSlots.contains(slot) || drawn) {
            deviations.add(PackAdvancedResourcePlan.UNSERVED_IMAGE + program + ":" + symbol);
            return;
        }
        writers.computeIfAbsent(program, ignored -> new ArrayList<>())
                .add(new PackAdvancedResourcePlan.GraphicsImageBinding(program, symbol,
                        TargetImages.resource(target), "", form.type(), form.format(), slot,
                        StorageBufferDeclarations.stageMask(stage)));
    }

    /**
     * Whether another stage of the program declares the image in another format. The program binds
     * one descriptor for it, so the stages cannot read it through two views: it is refused.
     */
    private boolean formatConflict(String program, PackAdvancedResourcePlan.ImageSpec spec, String symbol,
                                   String viewFormat) {
        boolean conflict = writers.getOrDefault(program, List.of()).stream()
                .anyMatch(binding -> binding.symbol().equals(symbol) && !binding.viewFormat().equals(viewFormat));
        if (conflict) {
            deviations.add("ADVANCED_IMAGE_FORMAT_CONFLICT:" + program + ":" + spec.name());
            deviations.add(PackAdvancedResourcePlan.UNSERVED_IMAGE + program + ":" + spec.name());
        }
        return conflict;
    }

    /**
     * The format a stage reads an image through (AdvancedImageDeclarations.viewFormat). Another
     * size has no such reading: the program is refused, and null returned.
     */
    private String viewFormat(String program, PackAdvancedResourcePlan.ImageSpec spec, String declared) {
        String view = AdvancedImageDeclarations.viewFormat(spec, declared);
        if (view == null) {
            deviations.add("ADVANCED_IMAGE_FORMAT_INCOMPATIBLE:" + spec.name() + ":" + declared);
            deviations.add(PackAdvancedResourcePlan.UNSERVED_IMAGE + program + ":" + spec.name());
        }
        return view;
    }

    /**
     * A sampler of a pack image, bound to its selector as the image's storage type. As for a storage
     * declaration, an unread sampler of an image Chimera does not create is inactive, not a refusal.
     * So is one no stage reads that got no slot: Glimmer's header declares multipleScatteringLUTTex
     * in every program, and only its compute passes read it. The planner strips that declaration
     * (InterfacePlanner.bindSamplers).
     */
    private void addReader(String program, String stage, String sampler, boolean referenced, boolean live) {
        PackAdvancedResourcePlan.ImageSpec spec = PackAdvancedResourcePlan.imageBySampler(images, sampler);
        if (spec == null || !spec.supported() && !referenced) return;
        if (!live && !plan.settings().customSamplerSlots().containsKey(spec.slotKey())) return;
        int selectorSlot = servedSlot(program, spec);
        if (selectorSlot < 0) return;
        readers.computeIfAbsent(program, ignored -> new ArrayList<>())
                .add(new PackAdvancedResourcePlan.GraphicsImageBinding(program, spec.name(), spec.name(),
                        spec.sampler(), AdvancedImageDeclarations.storageImageType(spec), "", selectorSlot,
                        StorageBufferDeclarations.stageMask(stage)));
    }

    /**
     * The selector slot {@code program} binds a supported image at, or -1 when the image is
     * unsupported or has no slot; the program is then recorded as using an image Chimera cannot
     * serve (PackAdvancedResourcePlan.imagesServable).
     */
    private int servedSlot(String program, PackAdvancedResourcePlan.ImageSpec spec) {
        int slot = spec.supported() ? selectorSlot(spec) : -1;
        if (slot < 0) deviations.add(PackAdvancedResourcePlan.UNSERVED_IMAGE + program + ":" + spec.name());
        return slot;
    }

    /** The selector slot of an image, or -1 with the slot limit recorded. */
    private int selectorSlot(PackAdvancedResourcePlan.ImageSpec spec) {
        int selectorSlot = plan.settings().customSamplerSlots().getOrDefault(spec.slotKey(), -1);
        if (selectorSlot < 0) {
            deviations.add("PACK_RESOURCE_SLOT_LIMIT:" + spec.slotKey());
        }
        return selectorSlot;
    }
}
