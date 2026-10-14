package net.chimera.shaderpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.chimera.mixin.ChimeraPipelineBuilderAccessor;
import net.chimera.render.PackStorageBufferDescriptor;
import net.chimera.render.PackStorageBufferOwner;
import net.chimera.render.shader.ChimeraShaderLoader;
import net.chimera.shaderpack.PackAdvancedResourcePlan.ProgramBindingLayout;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.UBO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;

/**
 * The descriptor contract of a pack pipeline: which bindings its ordinary samplers, storage buffers,
 * storage images and advanced samplers take, and the proof that the built pipeline matches it.
 */
final class PackDescriptorContracts {
    /** Stages whose layout comes from a pipeline JSON; every other stage has a guarded family contract. */
    private static final Set<UniformRegistry.Stage> PIPELINE_JSON_STAGES = Set.of(UniformRegistry.Stage.SHADOW,
            UniformRegistry.Stage.GEOMETRY, UniformRegistry.Stage.TRANSLUCENT, UniformRegistry.Stage.POST);

    private PackDescriptorContracts() {}

    static int nextBinding(JsonObject json, int samplerCount) {
        int next = 0;
        JsonArray ubos = json == null ? null : json.getAsJsonArray("UBOs");
        if (ubos != null) {
            for (var element : ubos) {
                if (!element.isJsonObject()) continue;
                next = Math.max(next, element.getAsJsonObject().get("binding").getAsInt() + 1);
            }
        }
        return next + samplerCount;
    }

    /** Descriptor binding immediately after a program's ordinary samplers. */
    static int storageBufferBindingBase(PackProgramPlan plan) {
        return storageBufferBindingBase(plan, Set.of());
    }

    /** Descriptor binding immediately after the filtered ordinary descriptors. */
    static int storageBufferBindingBase(PackProgramPlan plan, Set<String> advancedSamplerNames) {
        return ordinaryDescriptorContract(plan, advancedSamplerNames).nextBinding();
    }

    static OrdinaryDescriptorContract ordinaryDescriptorContract(
            PackProgramPlan plan, Set<String> advancedSamplerNames
    ) {
        if (plan == null || plan.interfacePlan() == null) {
            return new OrdinaryDescriptorContract(PipelineConfig.builder().build());
        }
        UniformRegistry.Stage stage = stageForProgram(plan.name());
        var iface = plan.interfacePlan().effective(stage);
        int[] slots = ordinarySamplerSlots(iface, stage, advancedSamplerNames);
        for (int slot : slots) PackPipelines.requireSelector(slot);
        PipelineConfig config = PIPELINE_JSON_STAGES.contains(stage)
                ? pipelineJsonConfig(plan.name(), stage, iface, slots) : familyConfig(plan, stage, iface, slots);
        // A guarded family contract owns the host transform blocks at bindings
        // 0 and 1, so those two blocks must carry the host semantic names for
        // the bridge's name-based lookups.
        boolean familyContract = !PIPELINE_JSON_STAGES.contains(stage);
        return new OrdinaryDescriptorContract(config, ordinaryResources(config, iface), familyContract);
    }

    /** A shadow, terrain, water or post layout: its pipeline JSON's blocks, the pack's uniforms, then samplers. */
    private static PipelineConfig pipelineJsonConfig(String program, UniformRegistry.Stage stage,
                                                     UniformRegistry.ProgramInterface iface, int[] slots) {
        JsonObject json = stage == UniformRegistry.Stage.SHADOW ? PackPipelines.shadowPipelineJson()
                : stage == UniformRegistry.Stage.POST ? new JsonObject()
                : ChimeraShaderLoader.loadJson("chimera_terrain.json").deepCopy();
        json.addProperty("fragment", "pack_" + program);
        json.add("samplers", new JsonArray());
        if (stage == UniformRegistry.Stage.POST) {
            json.add("UBOs", PackPipelines.uniformUboArray(iface, 0, "all"));
            json.add("PushConstants", new JsonArray());
        } else {
            json.getAsJsonArray("UBOs").addAll(PackPipelines.uniformUboArray(iface, 3, "all"));
        }
        PipelineConfig parsed = PipelineConfig.fromJson("pack_" + program, json);
        int base = parsed.ubs.stream().mapToInt(ub -> ub.binding + 1).max().orElse(0);
        List<PipelineConfig.ImageDescriptorInfo> images = new ArrayList<>();
        for (int index = 0; index < slots.length; index++) images.add(
                new PipelineConfig.ImageDescriptorInfo(base + index, "sampler2D",
                        "Sampler" + slots[index], slots[index]));
        return new PipelineConfig(parsed.shaderPaths, parsed.ubs, images, parsed.pushConstantsInfo);
    }

    /** A family layout: the host transform blocks, the pack's uniform block, then samplers. */
    private static PipelineConfig familyConfig(PackProgramPlan plan, UniformRegistry.Stage stage,
                                               UniformRegistry.ProgramInterface iface, int[] slots) {
        PipelineConfig.Builder builder = PipelineConfig.builder()
                .addUB(PipelineConfig.UB.builder(UniformRegistry.DYNAMIC_TRANSFORMS_BINDING,
                                VK_SHADER_STAGE_VERTEX_BIT)
                        .addUniform("mat4", "ModelViewMat").addUniform("vec4", "ColorModulator")
                        .addUniform("vec3", "ModelOffset").addUniform("mat4", "TextureMat").build())
                .addUB(PipelineConfig.UB.builder(UniformRegistry.PROJECTION_BINDING,
                        VK_SHADER_STAGE_VERTEX_BIT)
                        .addUniform("mat4", "ProjMat").build());
        boolean sky = stage == UniformRegistry.Stage.SKY || stage == UniformRegistry.Stage.CLOUD;
        if (!iface.executableUniforms().isEmpty()) {
            // Entity-family vertex shaders may declare the same pack
            // uniform block the fragment uses (live cameraPosition; a
            // shadow vertex reads its shadow matrices). The buffer is
            // shared, so the binding must be visible to both stages
            // whenever the converted vertex actually declares it.
            boolean vertexUniforms = plan.convertedVertex() != null && plan.convertedVertex()
                    .contains("layout(binding = 2) uniform ");
            int uniformStages = vertexUniforms
                    ? VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT
                    : VK_SHADER_STAGE_FRAGMENT_BIT;
            var uniforms = PipelineConfig.UB.builder(2, uniformStages);
            // std140 bool occupies an integer word; VulkanMod's native
            // layout builder accepts int, not GLSL bool. Keep GLSL unchanged.
            for (var uniform : iface.executableUniforms()) uniforms.addUniform(
                    uniform.glslType().equals("bool") ? "int" : uniform.glslType(), uniform.name());
            builder.addUB(uniforms.build());
        } else if (sky) {
            builder.addUB(PipelineConfig.UB.builder(2, VK_SHADER_STAGE_FRAGMENT_BIT).setSize(16).build());
        }
        int base = sky || !iface.executableUniforms().isEmpty() ? 3 : 2;
        for (int index = 0; index < slots.length; index++) builder.addImageDescriptor(
                base + index, "sampler2D", "Sampler" + slots[index], slots[index]);
        return builder.build();
    }

    static ProgramImageBindingManifest legacyImageBindings(PipelineConfig config,
            UniformRegistry.ProgramInterface iface) {
        return ProgramImageBindingManifest.from(null,
                new OrdinaryDescriptorContract(config, ordinaryResources(config, iface)));
    }

    static List<PackResourceBinding> ordinaryResources(PipelineConfig config,
            UniformRegistry.ProgramInterface iface) {
        List<PackResourceBinding> resources = new ArrayList<>();
        for (var image : config.imageDescriptors) {
            String name = iface.samplers().stream().filter(value -> value.slot() == image.imageIdx())
                    .map(UniformRegistry.SamplerBinding::name).findFirst().orElse(null);
            if (name == null && iface.stage() != UniformRegistry.Stage.POST) {
                name = image.imageIdx() == 0 ? "texture" : image.imageIdx() == 2 ? "lightmap" : null;
            }
            if (name == null) continue;
            String key = PackResourcePlan.canonicalResource(name);
            PackResourceKind kind = key.startsWith("colortex") || PackResourcePlan.isHostTexture(key)
                    ? PackResourceKind.TARGET : key.startsWith("depthtex") ? PackResourceKind.DEPTH
                    : key.startsWith("shadowtex") ? PackResourceKind.SHADOW_DEPTH
                    : key.startsWith("shadowcolor") ? PackResourceKind.SHADOW_COLOR
                    : key.equals("noisetex") ? PackResourceKind.NOISE
                    : key.equals("normals") || key.equals("specular") ? PackResourceKind.MATERIAL_MAP
                    : PackResourceKind.UNSERVED;
            resources.add(new PackResourceBinding("", name, key, kind, "", image.imageIdx(),
                    "nearest", "repeat", PackResourceStatus.HOST_ALIAS, List.of()));
        }
        return List.copyOf(resources);
    }

    static int[] ordinarySamplerSlots(
            UniformRegistry.ProgramInterface interfacePlan,
            UniformRegistry.Stage stage,
            Set<String> advancedSamplerNames
    ) {
        if (interfacePlan == null) return new int[0];
        int[] declared = interfacePlan.samplers().stream()
                .filter(value -> advancedSamplerNames == null
                        || !advancedSamplerNames.contains(value.name()))
                .mapToInt(UniformRegistry.SamplerBinding::slot)
                // Multiple Iris names can intentionally alias one host
                // selector. Keep the source aliases, but give the Vulkan
                // layout one descriptor for the shared resource identity.
                .distinct()
                .toArray();
        if (stage == UniformRegistry.Stage.POST) return declared;
        if (stage.shadowPass()) return PackPipelines.shadowSamplerSlots(declared);
        if (stage == UniformRegistry.Stage.GEOMETRY
                || stage == UniformRegistry.Stage.TRANSLUCENT) {
            return PackPipelines.interleaveLightmap(declared);
        }
        return PackPipelines.entitySamplerSlots(declared);
    }

    static UniformRegistry.Stage stageForProgram(String name) {
        if (name == null) return UniformRegistry.Stage.ENTITY;
        if (PostTargetPlan.isPostProgramName(name)) return UniformRegistry.Stage.POST;
        if (name.equals("shadow")) return UniformRegistry.Stage.SHADOW;
        // shadow_entities and the host-layout shadow lanes share the entity-layout shadow descriptors.
        if (FamilyAdapterRegistry.stageFor(name) == UniformRegistry.Stage.SHADOW_ENTITY) {
            return UniformRegistry.Stage.SHADOW_ENTITY;
        }
        if (name.equals("gbuffers_terrain")) return UniformRegistry.Stage.GEOMETRY;
        if (name.equals("gbuffers_water")) return UniformRegistry.Stage.TRANSLUCENT;
        if (FamilyAdapterRegistry.isParticleLike(name)) return UniformRegistry.Stage.PARTICLE;
        if (FamilyAdapterRegistry.isSkyFamily(name)) return UniformRegistry.Stage.SKY;
        if (FamilyAdapterRegistry.isCloudFamily(name)) return UniformRegistry.Stage.CLOUD;
        if (FamilyAdapterRegistry.isBlockFamily(name)) return UniformRegistry.Stage.BLOCK;
        if (FamilyAdapterRegistry.isHandFamily(name)) return UniformRegistry.Stage.HAND;
        return UniformRegistry.Stage.ENTITY;
    }

    static void addStorageBufferDescriptors(
            Pipeline.Builder builder,
            String program,
            PackAdvancedResourcePlan advancedResources
    ) {
        if (builder == null || advancedResources == null) return;
        for (PackAdvancedResourcePlan.StorageBufferBinding binding
                : advancedResources.bindingLayout(program).storageBuffers()) {
            if (!binding.supported() || binding.rewrittenBinding() < 0
                    || binding.size() <= 0 || binding.size() > Integer.MAX_VALUE) {
                continue;
            }
            builder.addUBO(new PackStorageBufferDescriptor(
                    "PackStorageBuffer" + binding.logicalIndex(),
                    binding.rewrittenBinding(), binding.stageMask(), (int) binding.size()));
        }
    }

    static void addStorageImageDescriptors(
            Pipeline.Builder builder,
            String program,
            PackAdvancedResourcePlan advancedResources
    ) {
        if (builder == null || advancedResources == null) return;
        int index = 0;
        for (PackAdvancedResourcePlan.GraphicsImageBinding binding
                : advancedResources.bindingLayout(program).storageImages()) {
            builder.addImageDescriptor(new ImageDescriptor(
                    advancedResources.bindingLayout(program).imageBase() + index++, "image3D",
                            "Sampler" + binding.selectorSlot(),
                    binding.selectorSlot(), VK_DESCRIPTOR_TYPE_STORAGE_IMAGE));
        }
    }

    static void addAdvancedSamplerDescriptors(
            Pipeline.Builder builder,
            String program,
            PackAdvancedResourcePlan advancedResources
    ) {
        if (builder == null || advancedResources == null) return;
        int index = 0;
        for (PackAdvancedResourcePlan.GraphicsImageBinding binding
                : advancedResources.bindingLayout(program).advancedSamplers()) {
            ImageDescriptor descriptor = new ImageDescriptor(
                    advancedResources.bindingLayout(program).samplerBase() + index++,
                    advancedSamplerType(advancedResources, binding),
                    "Sampler" + binding.selectorSlot(), binding.selectorSlot(),
                    VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            // PackStorageImage deliberately stays in GENERAL because the same
            // image may also be written by a graphics or compute stage.
            descriptor.setLayout(VK_IMAGE_LAYOUT_GENERAL);
            builder.addImageDescriptor(descriptor);
        }
    }

    static String bindStorageImages(String source, String program,
            PackAdvancedResourcePlan advancedResources, ProgramBindingLayout layout) {
        return bindStorageImages(source, program, advancedResources, layout, 0);
    }

    static String bindStorageImages(String source, String program,
            PackAdvancedResourcePlan advancedResources, ProgramBindingLayout layout,
            int sourceStage) {
        if (source == null) throw new PackPipelines.PreparationFailure("source-projection", "STAGE_SOURCE_MISSING");
        if (layout == null) throw new PackPipelines.PreparationFailure("descriptor-contract",
                "DESCRIPTOR_LAYOUT_MISSING");
        List<PackAdvancedResourcePlan.GraphicsImageBinding> bindings = layout.storageImages();
        List<PackAdvancedResourcePlan.GraphicsImageBinding> readers = layout.advancedSamplers();
        if (bindings.isEmpty() && readers.isEmpty()) {
            if (PackPipelines.STORAGE_IMAGE_DECLARATION.matcher(source).find()) {
                throw new PackPipelines.PreparationFailure("resource-rewrite", "STORAGE_IMAGE_UNPLANNED");
            }
            return source;
        }
        Set<String> emitted = new TreeSet<>();
        String rewritten = bindImageDeclarations(source, advancedResources, layout, sourceStage, emitted);
        Set<String> emittedSamplers = new TreeSet<>();
        rewritten = bindSamplerDeclarations(rewritten, advancedResources, layout, sourceStage, emittedSamplers);
        StringBuilder generated = new StringBuilder();
        for (int index = 0; index < bindings.size(); index++) {
            PackAdvancedResourcePlan.GraphicsImageBinding binding = bindings.get(index);
            if (!visibleTo(binding, sourceStage) || emitted.contains(binding.symbol())) continue;
            PackAdvancedResourcePlan.ImageSpec spec = supportedImage(advancedResources, binding);
            generated.append(imageDeclaration(spec, binding, layout.imageBase() + index)).append('\n');
        }
        for (int index = 0; index < readers.size(); index++) {
            PackAdvancedResourcePlan.GraphicsImageBinding binding = readers.get(index);
            if (!visibleTo(binding, sourceStage) || emittedSamplers.contains(binding.sampler())) continue;
            generated.append(samplerDeclaration(advancedResources, binding, layout.samplerBase() + index))
                    .append('\n');
        }
        if (generated.isEmpty()) return rewritten;
        int versionEnd = rewritten.indexOf('\n');
        int insertAt = versionEnd < 0 ? 0 : versionEnd + 1;
        return rewritten.substring(0, insertAt) + generated + rewritten.substring(insertAt);
    }

    /** Rewrites each storage image declaration to its planned binding, collecting the symbols it bound. */
    private static String bindImageDeclarations(String source, PackAdvancedResourcePlan advancedResources,
                                                ProgramBindingLayout layout, int sourceStage, Set<String> emitted) {
        List<PackAdvancedResourcePlan.GraphicsImageBinding> bindings = layout.storageImages();
        Map<String, PackAdvancedResourcePlan.GraphicsImageBinding> bySymbol = new TreeMap<>();
        for (PackAdvancedResourcePlan.GraphicsImageBinding binding : bindings) {
            bySymbol.put(binding.symbol(), binding);
        }
        Matcher matcher = PackPipelines.STORAGE_IMAGE_DECLARATION.matcher(source);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            PackAdvancedResourcePlan.GraphicsImageBinding binding = bySymbol.get(matcher.group(2));
            if (binding == null) {
                throw new PackPipelines.PreparationFailure("resource-rewrite",
                        "STORAGE_IMAGE_UNPLANNED:" + matcher.group(2));
            }
            if (!visibleTo(binding, sourceStage)) {
                throw new PackPipelines.PreparationFailure("resource-rewrite",
                        "RESOURCE_STAGE_MISMATCH:" + binding.symbol());
            }
            PackAdvancedResourcePlan.ImageSpec spec = supportedImage(advancedResources, binding);
            String replacement = imageDeclaration(spec, binding, layout.imageBase() + bindings.indexOf(binding));
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            emitted.add(binding.symbol());
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** Rewrites each advanced sampler declaration to its planned binding, collecting the samplers it bound. */
    private static String bindSamplerDeclarations(String source, PackAdvancedResourcePlan advancedResources,
                                                  ProgramBindingLayout layout, int sourceStage,
                                                  Set<String> emittedSamplers) {
        List<PackAdvancedResourcePlan.GraphicsImageBinding> readers = layout.advancedSamplers();
        Map<String, PackAdvancedResourcePlan.GraphicsImageBinding> readerBySampler = new TreeMap<>();
        for (PackAdvancedResourcePlan.GraphicsImageBinding binding : readers) {
            readerBySampler.put(binding.sampler(), binding);
        }
        Matcher samplerMatcher = PackPipelines.STORAGE_SAMPLER_DECLARATION.matcher(source);
        StringBuffer samplerResult = new StringBuffer();
        while (samplerMatcher.find()) {
            PackAdvancedResourcePlan.GraphicsImageBinding binding =
                    readerBySampler.get(samplerMatcher.group(2));
            if (binding == null) {
                samplerMatcher.appendReplacement(samplerResult,
                        Matcher.quoteReplacement(samplerMatcher.group()));
                continue;
            }
            if (!visibleTo(binding, sourceStage)) {
                throw new PackPipelines.PreparationFailure("resource-rewrite",
                        "RESOURCE_STAGE_MISMATCH:" + binding.sampler());
            }
            String replacement = samplerDeclaration(advancedResources, binding,
                    layout.samplerBase() + readers.indexOf(binding));
            samplerMatcher.appendReplacement(samplerResult, Matcher.quoteReplacement(replacement));
            emittedSamplers.add(binding.sampler());
        }
        samplerMatcher.appendTail(samplerResult);
        return samplerResult.toString();
    }

    /** Whether a binding serves the stage being bound; stage 0 binds them all. */
    private static boolean visibleTo(PackAdvancedResourcePlan.GraphicsImageBinding binding, int sourceStage) {
        return sourceStage == 0 || (binding.stageMask() & sourceStage) == sourceStage;
    }

    /** The admitted image a storage binding writes; an unsupported one fails the rewrite. */
    private static PackAdvancedResourcePlan.ImageSpec supportedImage(
            PackAdvancedResourcePlan advancedResources, PackAdvancedResourcePlan.GraphicsImageBinding binding) {
        PackAdvancedResourcePlan.ImageSpec spec = advancedResources.images()
                .get(binding.imageName());
        if (spec == null || !spec.supported()) throw new PackPipelines.PreparationFailure(
                "resource-rewrite", "STORAGE_IMAGE_UNSUPPORTED:" + binding.imageName());
        return spec;
    }

    private static String imageDeclaration(PackAdvancedResourcePlan.ImageSpec spec,
                                           PackAdvancedResourcePlan.GraphicsImageBinding binding, int descriptor) {
        return "layout(" + spec.internalFormat() + ", binding = " + descriptor + ") uniform " + binding.glslType()
                + " " + binding.symbol() + ";";
    }

    private static String samplerDeclaration(PackAdvancedResourcePlan advancedResources,
                                             PackAdvancedResourcePlan.GraphicsImageBinding binding, int descriptor) {
        return "layout(binding = " + descriptor + ") uniform " + advancedSamplerType(advancedResources, binding)
                + " " + binding.sampler() + ";";
    }

    static String bindPackShader(
            String source,
            String program,
            PackAdvancedResourcePlan advancedResources,
            ProgramBindingLayout layout,
            UniformRegistry.ProgramInterface interfacePlan,
            UniformRegistry.Stage stage,
            int sourceStage
    ) {
        PackPipelines.validateSelectorLayout(layout);
        for (var sampler : interfacePlan.samplers()) PackPipelines.requireSelector(sampler.slot());
        String rewritten = bindStorageImages(source, program, advancedResources, layout, sourceStage);
        Set<String> advancedNames = advancedSamplerNames(advancedResources, program);
        for (UniformRegistry.SamplerBinding sampler : interfacePlan.samplers()) {
            if (advancedNames.contains(sampler.name())) continue;
            String sourceName = sourceSamplerName(stage, sampler.name());
            PackAdvancedResourcePlan.DescriptorBinding descriptor = layout.ordinaryDescriptors().stream()
                    .filter(value -> value.identity().equals("resource:" + sampler.slot() + ":1"))
                    .findFirst().orElse(null);
            if (descriptor == null || !hasSamplerDeclaration(rewritten, sourceName)) continue;
            rewritten = rewriteSamplerDeclaration(rewritten, sourceName, descriptor.binding());
        }
        return rewritten;
    }

    static String sourceSamplerName(UniformRegistry.Stage stage, String name) {
        return stage != UniformRegistry.Stage.POST && "texture".equals(name)
                ? "chimeraTexture" : name;
    }

    static boolean hasSamplerDeclaration(String source, String name) {
        Matcher matcher = PackPipelines.SAMPLER_DECLARATION.matcher(source == null ? "" : source);
        while (matcher.find()) {
            if (matcher.group(2).equals(name)) return true;
        }
        return false;
    }

    static String rewriteSamplerDeclaration(String source, String name, int binding) {
        Matcher matcher = PackPipelines.SAMPLER_DECLARATION.matcher(source == null ? "" : source);
        while (matcher.find()) {
            if (!matcher.group(2).equals(name)) continue;
            String replacement = "layout(binding = " + binding + ") uniform "
                    + matcher.group(1) + " " + name + ";";
            return source.substring(0, matcher.start()) + replacement + source.substring(matcher.end());
        }
        return source;
    }

    static Set<String> advancedSamplerNames(
            PackAdvancedResourcePlan advancedResources, String program
    ) {
        if (advancedResources == null) return Set.of();
        return advancedResources.bindingLayout(program).advancedSamplers().stream()
                .map(PackAdvancedResourcePlan.GraphicsImageBinding::sampler)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    static void verifyAdvancedLayout(Pipeline.Builder builder, String program,
            PackAdvancedResourcePlan resources, String vertex, String fragment,
            PackStorageBufferOwner owner) {
        List<PackAdvancedResourcePlan.DescriptorBinding> descriptors = builderDescriptors(builder);
        String mismatch = owner == null ? PackAdvancedResourcePlan.layoutMismatch(
                resources.bindingLayout(program), vertex, fragment, descriptors)
                : owner.verifyLayout(program, vertex, fragment, descriptors);
        if (mismatch != null) throw new PackPipelines.PreparationFailure("descriptor-contract", mismatch);
    }

    static List<PackAdvancedResourcePlan.DescriptorBinding> builderDescriptors(Pipeline.Builder builder) {
        List<PackAdvancedResourcePlan.DescriptorBinding> descriptors = new ArrayList<>();
        for (UBO descriptor : builder.getUBOs()) descriptors.add(new PackAdvancedResourcePlan.DescriptorBinding(
                descriptor.name, 0, descriptor.getBinding(), descriptor.getType(), descriptor.getStages(),
                nativeDescriptorIdentity(descriptor.name, descriptor.getType(), descriptor.getBinding())));
        for (ImageDescriptor descriptor : ((ChimeraPipelineBuilderAccessor) (Object) builder)
                .chimera$imageDescriptors()) descriptors.add(new PackAdvancedResourcePlan.DescriptorBinding(
                descriptor.name, 0, descriptor.getBinding(), descriptor.getType(), descriptor.getStages(),
                nativeDescriptorIdentity(descriptor.name, descriptor.getType(), descriptor.getBinding())));
        return List.copyOf(descriptors);
    }

    static String nativeDescriptorIdentity(String name, int type, int binding) {
        Matcher sampler = Pattern.compile("Sampler(\\d+)").matcher(name == null ? "" : name);
        if (sampler.matches() && (type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                || type == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)) {
            return "resource:" + sampler.group(1) + ":" + type;
        }
        if (type == 9 && name != null && name.startsWith("PackStorageBuffer")) {
            return "storage:" + name.substring("PackStorageBuffer".length());
        }
        if (type == 8) return "ubo:" + binding;
        return name == null ? "" : name;
    }

    static String advancedSamplerType(
            PackAdvancedResourcePlan advancedResources,
            PackAdvancedResourcePlan.GraphicsImageBinding binding
    ) {
        PackAdvancedResourcePlan.ImageSpec spec = advancedResources.images()
                .get(binding.imageName());
        if (spec != null && (spec.internalFormat().equalsIgnoreCase("r8ui")
                || spec.internalFormat().equalsIgnoreCase("r16ui"))) {
            return "usampler3D";
        }
        return "sampler3D";
    }
}
