package net.chimera.shaderpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.chimera.render.PackStorageBufferOwner;
import net.chimera.render.shader.ChimeraShaderLoader;
import net.chimera.shaderpack.PackAdvancedResourcePlan.ProgramBindingLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_ALL_GRAPHICS;
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
            return new OrdinaryDescriptorContract(PackDescriptorLayout.EMPTY);
        }
        UniformRegistry.Stage stage = stageForProgram(plan.name());
        var iface = plan.interfacePlan().effective(stage);
        int[] slots = ordinarySamplerSlots(iface, stage, advancedSamplerNames);
        for (int slot : slots) PackPipelines.requireSelector(slot);
        PackDescriptorLayout layout = PIPELINE_JSON_STAGES.contains(stage)
                ? pipelineJsonLayout(stage, iface, slots) : familyLayout(plan, stage, iface, slots);
        return new OrdinaryDescriptorContract(layout, ordinaryResources(layout, iface));
    }

    /** A shadow, terrain, water or post layout: its pipeline JSON's blocks, the pack's uniforms, then samplers. */
    private static PackDescriptorLayout pipelineJsonLayout(UniformRegistry.Stage stage,
                                                           UniformRegistry.ProgramInterface iface, int[] slots) {
        PackDescriptorLayout layout;
        if (stage == UniformRegistry.Stage.POST) {
            // A post program's only block is the pack's uniforms, at binding 0, and it pushes nothing.
            layout = new PackDescriptorLayout(packUniformBlock(iface, 0, VK_SHADER_STAGE_ALL_GRAPHICS),
                    List.of(), PackDescriptorLayout.EMPTY_PUSH_CONSTANTS);
        } else {
            PackDescriptorLayout host = PackDescriptorLayout.fromJson(ChimeraShaderLoader.loadJson(
                    stage == UniformRegistry.Stage.SHADOW ? "chimera_shadow.json" : "chimera_terrain.json"));
            List<PackDescriptorLayout.UniformBlock> blocks = new ArrayList<>(host.uniformBlocks());
            blocks.addAll(packUniformBlock(iface, host.bindingAfterBlocks(), VK_SHADER_STAGE_ALL_GRAPHICS));
            layout = new PackDescriptorLayout(blocks, List.of(), host.pushConstants());
        }
        return layout.withImages(samplerImages(layout.bindingAfterBlocks(), slots));
    }

    /**
     * The pack's uniform block at {@code binding}, empty when the program has no uniforms. A GLSL
     * bool occupies an int word in std140, and the host's layout builder accepts int, not bool.
     */
    static List<PackDescriptorLayout.UniformBlock> packUniformBlock(UniformRegistry.ProgramInterface iface,
                                                                    int binding, int stages) {
        if (iface.executableUniforms().isEmpty()) return List.of();
        return List.of(new PackDescriptorLayout.UniformBlock(binding, stages, 0, iface.executableUniforms().stream()
                .map(uniform -> new PackDescriptorLayout.Field(
                        uniform.glslType().equals("bool") ? "int" : uniform.glslType(), uniform.name()))
                .toList()));
    }

    /** One sampler2D descriptor per slot, named Sampler&lt;slot&gt;, on consecutive bindings from {@code base}. */
    static List<PackDescriptorLayout.ImageSlot> samplerImages(int base, int[] slots) {
        List<PackDescriptorLayout.ImageSlot> images = new ArrayList<>();
        for (int index = 0; index < slots.length; index++) images.add(new PackDescriptorLayout.ImageSlot(
                base + index, "sampler2D", "Sampler" + slots[index], slots[index]));
        return images;
    }

    /** A family layout: the host transform blocks, the pack's uniform block, then samplers. */
    private static PackDescriptorLayout familyLayout(PackProgramPlan plan, UniformRegistry.Stage stage,
                                                     UniformRegistry.ProgramInterface iface, int[] slots) {
        List<PackDescriptorLayout.UniformBlock> blocks = new ArrayList<>(List.of(
                new PackDescriptorLayout.UniformBlock(UniformRegistry.DYNAMIC_TRANSFORMS_BINDING,
                        VK_SHADER_STAGE_VERTEX_BIT, 0, List.of(
                        new PackDescriptorLayout.Field("mat4", "ModelViewMat"),
                        new PackDescriptorLayout.Field("vec4", "ColorModulator"),
                        new PackDescriptorLayout.Field("vec3", "ModelOffset"),
                        new PackDescriptorLayout.Field("mat4", "TextureMat"))),
                new PackDescriptorLayout.UniformBlock(UniformRegistry.PROJECTION_BINDING,
                        VK_SHADER_STAGE_VERTEX_BIT, 0, List.of(new PackDescriptorLayout.Field("mat4", "ProjMat")))));
        boolean sky = stage == UniformRegistry.Stage.SKY || stage == UniformRegistry.Stage.CLOUD;
        if (!iface.executableUniforms().isEmpty()) {
            // Entity-family vertex shaders may declare the same pack
            // uniform block the fragment uses (live cameraPosition; a
            // shadow vertex reads its shadow matrices). The buffer is
            // shared, so the binding must be visible to both stages
            // whenever the converted vertex actually declares it.
            boolean vertexUniforms = plan.convertedVertex() != null && plan.convertedVertex()
                    .contains("layout(binding = 2) uniform ");
            blocks.addAll(packUniformBlock(iface, 2, vertexUniforms
                    ? VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT
                    : VK_SHADER_STAGE_FRAGMENT_BIT));
        } else if (sky) {
            blocks.add(new PackDescriptorLayout.UniformBlock(2, VK_SHADER_STAGE_FRAGMENT_BIT, 16, List.of()));
        }
        int base = sky || !iface.executableUniforms().isEmpty() ? 3 : 2;
        return new PackDescriptorLayout(blocks, samplerImages(base, slots), null);
    }

    static ProgramImageBindingManifest legacyImageBindings(PackDescriptorLayout layout,
            UniformRegistry.ProgramInterface iface) {
        return ProgramImageBindingManifest.from(null,
                new OrdinaryDescriptorContract(layout, ordinaryResources(layout, iface)));
    }

    static List<PackResourceBinding> ordinaryResources(PackDescriptorLayout layout,
            UniformRegistry.ProgramInterface iface) {
        List<PackResourceBinding> resources = new ArrayList<>();
        for (var image : layout.images()) {
            String name = iface.samplers().stream().filter(value -> value.slot() == image.textureSlot())
                    .map(UniformRegistry.SamplerBinding::name).findFirst().orElse(null);
            if (name == null && iface.stage() != UniformRegistry.Stage.POST) {
                name = image.textureSlot() == 0 ? "texture" : image.textureSlot() == 2 ? "lightmap" : null;
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
            resources.add(new PackResourceBinding("", name, key, kind, "", image.textureSlot(),
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

    /**
     * A program's whole descriptor set: its ordinary layout, then the storage buffers, storage
     * images and advanced samplers its binding layout places. An advanced sampler reads a pack
     * storage image, which stays in the general layout because a graphics or compute stage may
     * also write it.
     */
    static PackDescriptorLayout withAdvancedDescriptors(PackDescriptorLayout ordinary, String program,
                                                        PackAdvancedResourcePlan advancedResources) {
        if (advancedResources == null) return ordinary;
        ProgramBindingLayout layout = advancedResources.bindingLayout(program);
        List<PackDescriptorLayout.StorageBuffer> buffers = new ArrayList<>();
        for (PackAdvancedResourcePlan.StorageBufferBinding binding : layout.storageBuffers()) {
            if (!binding.supported() || binding.rewrittenBinding() < 0
                    || binding.size() <= 0 || binding.size() > Integer.MAX_VALUE) {
                continue;
            }
            buffers.add(new PackDescriptorLayout.StorageBuffer(binding.rewrittenBinding(), binding.stageMask(),
                    (int) binding.size(), binding.logicalIndex()));
        }
        List<PackDescriptorLayout.ImageSlot> images = new ArrayList<>();
        for (PackAdvancedResourcePlan.GraphicsImageBinding binding : layout.storageImages()) {
            images.add(new PackDescriptorLayout.ImageSlot(layout.imageBase() + images.size(), "image3D",
                    "Sampler" + binding.selectorSlot(), binding.selectorSlot(),
                    PackDescriptorLayout.ImageAccess.STORAGE));
        }
        int samplerIndex = 0;
        for (PackAdvancedResourcePlan.GraphicsImageBinding binding : layout.advancedSamplers()) {
            images.add(new PackDescriptorLayout.ImageSlot(layout.samplerBase() + samplerIndex++,
                    advancedSamplerType(advancedResources, binding), "Sampler" + binding.selectorSlot(),
                    binding.selectorSlot(), PackDescriptorLayout.ImageAccess.SAMPLED_GENERAL));
        }
        return ordinary.withAdvanced(buffers, images);
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

    /** Checks the shaders and the program's binding plan against the whole descriptor set. */
    static void verifyAdvancedLayout(PackDescriptorLayout layout, String program,
            PackAdvancedResourcePlan resources, String vertex, String fragment,
            PackStorageBufferOwner owner) {
        List<PackAdvancedResourcePlan.DescriptorBinding> descriptors = layout.descriptorBindings();
        String mismatch = owner == null ? PackAdvancedResourcePlan.layoutMismatch(
                resources.bindingLayout(program), vertex, fragment, descriptors)
                : owner.verifyLayout(program, vertex, fragment, descriptors);
        if (mismatch != null) throw new PackPipelines.PreparationFailure("descriptor-contract", mismatch);
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
