package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One pass over every program's prepared stages for the pack images they use: the programs that
 * depend on an advanced resource, the images each stage writes through a storage image declaration
 * and reads through its sampler, and the stages guarded by the custom-image feature.
 */
final class GraphicsImageScan {
    private static final Pattern GRAPHICS_IMAGE_DECLARATION = Pattern.compile(
            "(?:layout\\s*\\([^)]*\\)\\s*)?"
                    + "(?:(?:uniform|writeonly|readonly|coherent|volatile|restrict)\\s+)*"
                    + "\\b(u?i?image3D)\\s+([A-Za-z_]\\w*)");

    final List<String> dependent = new ArrayList<>();
    final List<String> customImageFeaturePrograms = new ArrayList<>();
    final Map<String, List<PackAdvancedResourcePlan.GraphicsImageBinding>> writers = new TreeMap<>();
    final Map<String, List<PackAdvancedResourcePlan.GraphicsImageBinding>> readers = new TreeMap<>();
    final Set<String> producedImages = new TreeSet<>();
    private final PackPlan plan;
    private final Map<String, PackAdvancedResourcePlan.ImageSpec> images;
    private final List<String> deviations;
    private final Set<String> imageSamplerNames;

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
        for (PackProgramPlan program : plan.programs()) {
            if (program == null || program.name().isBlank()) continue;
            scan.scanProgram(program);
        }
        return scan;
    }

    private void scanProgram(PackProgramPlan program) {
        boolean usesAdvanced = program.stages().values().stream().anyMatch(value ->
                AdvancedImageDeclarations.usesAdvancedResource(value.source(), images));
        if (usesAdvanced) {
            dependent.add(program.name());
        }
        for (Map.Entry<String, PreparedShaderSource> stageEntry : program.stages().entrySet()) {
            PreparedShaderSource stage = stageEntry.getValue();
            if (stage == null || stage.source() == null) continue;
            scanStage(program.name(), stageEntry.getKey(), stage.source());
        }
    }

    private void scanStage(String program, String stage, String source) {
        GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(source, imageSamplerNames);
        deviations.addAll(usage.deviations());
        Matcher image = GRAPHICS_IMAGE_DECLARATION.matcher(source);
        while (image.find()) {
            if (usage.liveImages().contains(image.group(2))) {
                addWriter(program, stage, image.group(1), image.group(2));
            }
        }
        // Vulkan GLSL needs a binding for every opaque declaration,
        // even one in an unreachable helper or one never referenced
        // (Solas declares voxelSampler in terrain but only the shadow
        // pass writes voxel_img).  Give every declared or referenced
        // pack image sampler a descriptor.  Runtime eligibility
        // remains based on liveSamplers() in usesAdvancedResource;
        // this does not create a fake alias or make an unrelated
        // sampler a dependency.
        Set<String> imageSamplers = new TreeSet<>(usage.referencedSamplers());
        imageSamplers.addAll(UniformRegistry.scanDeclaredSamplerNames(source));
        for (String sampler : imageSamplers) {
            addReader(program, stage, sampler);
        }
        if (source.contains("IRIS_FEATURE_CUSTOM_IMAGES")
                && source.contains("OPTIFINE_ACT_ERROR")) {
            customImageFeaturePrograms.add(program);
        }
    }

    /** A live storage image declaration of a supported pack image, bound to its sampler's selector. */
    private void addWriter(String program, String stage, String glslType, String symbol) {
        PackAdvancedResourcePlan.ImageSpec spec = PackAdvancedResourcePlan.imageBySymbol(images, symbol);
        if (spec == null || !spec.supported()) return;
        int selectorSlot = selectorSlot(spec);
        if (selectorSlot < 0) return;
        writers.computeIfAbsent(program, ignored -> new ArrayList<>())
                .add(new PackAdvancedResourcePlan.GraphicsImageBinding(program, symbol,
                        spec.name(), spec.sampler(), glslType, selectorSlot,
                        StorageBufferDeclarations.stageMask(stage)));
        producedImages.add(spec.name());
    }

    /** A sampler of a supported pack image, bound to its selector as the image's storage type. */
    private void addReader(String program, String stage, String sampler) {
        PackAdvancedResourcePlan.ImageSpec spec = PackAdvancedResourcePlan.imageBySampler(images, sampler);
        if (spec == null || !spec.supported()) return;
        int selectorSlot = selectorSlot(spec);
        if (selectorSlot < 0) return;
        readers.computeIfAbsent(program, ignored -> new ArrayList<>())
                .add(new PackAdvancedResourcePlan.GraphicsImageBinding(program, spec.name(), spec.name(),
                        spec.sampler(), AdvancedImageDeclarations.storageImageType(spec), selectorSlot,
                        StorageBufferDeclarations.stageMask(stage)));
    }

    /** The selector slot of an image's sampler, or -1 with the slot limit recorded. */
    private int selectorSlot(PackAdvancedResourcePlan.ImageSpec spec) {
        int selectorSlot = plan.settings().customSamplerSlots().getOrDefault(spec.sampler(), -1);
        if (selectorSlot < 0) {
            deviations.add("PACK_RESOURCE_SLOT_LIMIT:" + spec.sampler());
        }
        return selectorSlot;
    }
}
