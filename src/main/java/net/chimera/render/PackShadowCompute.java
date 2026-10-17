package net.chimera.render;

import net.chimera.render.shader.PackUniformProvider;
import net.chimera.shaderpack.ComputeValueBlock;
import net.chimera.shaderpack.GlslTokenRewriter;
import net.chimera.shaderpack.PackAdvancedResourcePlan;
import net.chimera.shaderpack.PackEngineDefines;
import net.chimera.shaderpack.ShaderSourcePreprocessor;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;

/** Native compute bridge for the bounded shadowcomp image contract. */
public final class PackShadowCompute implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private static final Pattern IMAGE_DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?"
                    + "(?:(?:uniform|writeonly|readonly|coherent|volatile|restrict)\\s+)*"
                    + "(u?i?image3D)\\s+([A-Za-z_]\\w*)\\s*;");
    private static final Pattern SAMPLER_DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+"
                    + "(u?sampler3D|sampler2D|isampler2D|usampler2D|sampler2DShadow)\\s+"
                    + "([A-Za-z_]\\w*)\\s*;");
    private static final Pattern SAMPLER_3D_PARAMETER = Pattern.compile(
            "\\b(?:u|i)?sampler3D\\s+([A-Za-z_]\\w*)");
    private static final Pattern VALUE_UNIFORM_DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)[ \\t]*)?uniform[ \\t]+"
                    + "(bool|int|uint|float|vec[234]|ivec[234]|uvec[234]|bvec[234]|mat[234])"
                    + "[ \\t]+([^;\\r\\n]+);");
    private static final Pattern ANY_UNIFORM_DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)[ \\t]*)?"
                    + "(?:(?:uniform|writeonly|readonly|coherent|volatile|restrict)[ \\t]+)+"
                    + "([A-Za-z_]\\w*)[ \\t]+([^;\\r\\n]+);");

    private final PackAdvancedResourcePlan.ComputeSpec spec;
    private final PackAdvancedImageOwner owner;
    private final PackResourceOwner sampledResourceOwner;
    private final List<String> imageNames;
    private final List<String> samplerNames;
    private final List<String> sampled2dNames;
    private final List<ComputeValueBlock.Value> computeUniforms;
    private ComputeKernel kernel;
    private boolean failed;
    private boolean dispatchObserved;

    private PackShadowCompute(
            PackAdvancedResourcePlan.ComputeSpec spec,
            PackAdvancedImageOwner owner,
            PackResourceOwner sampledResourceOwner,
            List<String> imageNames,
            List<String> samplerNames,
            List<String> sampled2dNames,
            List<ComputeValueBlock.Value> computeUniforms
    ) {
        this.spec = spec;
        this.owner = owner;
        this.sampledResourceOwner = sampledResourceOwner;
        this.imageNames = imageNames;
        this.samplerNames = samplerNames;
        this.sampled2dNames = sampled2dNames;
        this.computeUniforms = computeUniforms == null ? List.of() : List.copyOf(computeUniforms);
    }

    /** A translated shadowcomp module and the resources it binds, in binding order. */
    public record Module(
            String source,
            List<String> imageNames,
            List<String> samplerNames,
            List<String> sampled2dNames,
            List<ComputeValueBlock.Value> uniforms
    ) {}


    public static PackShadowCompute load(
            PackAdvancedResourcePlan plan,
            PackAdvancedImageOwner owner,
            PackResourceOwner sampledResourceOwner,
            Path shadersDir,
            Map<String, String> initialMacros,
            Set<String> lockedMacros
    ) {
        if (plan == null || owner == null || !plan.capabilityPossible()) return null;
        PackAdvancedResourcePlan.ComputeSpec spec = plan.computeStages().get("shadowcomp");
        if (spec == null || !spec.supported()) return null;
        Module module;
        try {
            module = module(plan, spec, symbol -> owner.imageForSymbol(symbol) != null,
                    name -> sampledResourceOwner != null && sampledResourceOwner.image(name) != null,
                    shadersDir, initialMacros, lockedMacros);
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("[chimera] shadowcomp translation failed: {}", failure.getMessage());
            return null;
        }
        try {
            PackShadowCompute compute = new PackShadowCompute(
                    spec, owner, sampledResourceOwner, module.imageNames(), module.samplerNames(),
                    module.sampled2dNames(), module.uniforms());
            compute.create(module.source());
            return compute.kernel == null ? null : compute;
        } catch (RuntimeException failure) {
            LOGGER.warn("[chimera] shadowcomp load failed: {}", failure.getMessage(), failure);
            return null;
        }
    }

    /**
     * The shadowcomp module exactly as {@link #load} builds it, given an owner for every image
     * the plan supports; null when the plan has no supported shadowcomp. The pack compile check
     * compiles its source offline and checks the uniform offsets against the compiled block.
     * Throws with the reason when the stage does not translate.
     */
    public static Module translatedModule(
            PackAdvancedResourcePlan plan,
            Path shadersDir,
            Map<String, String> initialMacros,
            Set<String> lockedMacros
    ) throws IOException {
        if (plan == null || !plan.capabilityPossible()) return null;
        PackAdvancedResourcePlan.ComputeSpec spec = plan.computeStages().get("shadowcomp");
        if (spec == null || !spec.supported()) return null;
        Predicate<String> owned = symbol -> {
            PackAdvancedResourcePlan.ImageSpec image = plan.imageForSymbol(symbol);
            return image != null && image.supported();
        };
        // Offline there is no owner; the pack's noise texture is taken as loaded, as it is in game.
        return module(plan, spec, owned, name -> true, shadersDir, initialMacros, lockedMacros);
    }

    private static Module module(
            PackAdvancedResourcePlan plan,
            PackAdvancedResourcePlan.ComputeSpec spec,
            Predicate<String> owned,
            Predicate<String> sampledAvailable,
            Path shadersDir,
            Map<String, String> initialMacros,
            Set<String> lockedMacros
    ) throws IOException {
        Path sourcePath = shadersDir.resolve(spec.relativeSource()).normalize();
        String source = Files.readString(sourcePath);
        Map<String, String> macros = PackEngineDefines.forCompute(
                initialMacros, plan.capabilityPossible());
        ShaderSourcePreprocessor.Result prepared = ShaderSourcePreprocessor.prepareForRuntime(
                shadersDir, sourcePath, source,
                macros,
                PackEngineDefines.lockedNames(
                        lockedMacros, plan.capabilityPossible(), true));
        List<String> blocking = prepared.deviations().stream()
                .filter(value -> value == null || !ShaderSourcePreprocessor.isWarning(value))
                .toList();
        if (!blocking.isEmpty()) {
            throw new IllegalArgumentException("preprocessing deviations " + blocking);
        }
        List<String> unowned = new ArrayList<>();
        spec.imageNames().stream().filter(owned.negate()).forEach(unowned::add);
        spec.samplerNames().stream().filter(owned.negate()).forEach(unowned::add);
        if (!unowned.isEmpty()) {
            throw new IllegalArgumentException("no image owner for " + unowned);
        }
        List<String> sampled2d = standardSampledResources(prepared.source(), sampledAvailable);
        List<ComputeValueBlock.Value> uniforms = computeUniforms(prepared.source());
        int uniformBinding = spec.imageNames().size() + spec.samplerNames().size() + sampled2d.size();
        String translated = translate(prepared.source(), plan, spec.imageNames(), spec.samplerNames(),
                sampled2d, uniforms, uniformBinding);
        return new Module(translated, spec.imageNames(), spec.samplerNames(), sampled2d, uniforms);
    }


    public boolean isInstalled() { return kernel != null && !failed; }

    public boolean dispatch(VkCommandBuffer commandBuffer) {
        if (!isInstalled() || commandBuffer == null) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!this.dispatchObserved) {
                this.dispatchObserved = true;
                LOGGER.info("[chimera] shadowcomp dispatch recording");
            }
            PackVulkanBarriers.beforeCompute(commandBuffer, stack);
            Set<String> transitioned = new HashSet<>();
            for (String name : imageNames) {
                if (!transitioned.add(name)) continue;
                PackStorageImage image = owner.imageForSymbol(name);
                if (image != null) image.transitionToGeneral(stack, commandBuffer);
            }
            for (String name : samplerNames) {
                if (!transitioned.add(name)) continue;
                PackStorageImage image = owner.imageForSymbol(name);
                if (image != null) image.transitionToGeneral(stack, commandBuffer);
            }
            kernel.bind(commandBuffer, stack, kernel.currentFrame(), computeUniforms);
            int x = groups(imageNames.get(0), spec.localSizeX(), true);
            int y = groups(imageNames.get(0), spec.localSizeY(), false);
            int z = Math.max(1, (owner.imageForSymbol(imageNames.get(0)).depth()
                    + spec.localSizeZ() - 1) / spec.localSizeZ());
            vkCmdDispatch(commandBuffer, x, y, z);
            // Advanced images stay in GENERAL. Their sampler aliases and
            // storage-image descriptors can name the same physical image,
            // so moving them to read-only layout here would make the next
            // graphics producer or storage descriptor disagree with Vulkan's
            // tracked layout. The memory barrier below orders this dispatch
            // without changing that shared layout contract.
            PackVulkanBarriers.afterCompute(commandBuffer, stack);
            return true;
        } catch (RuntimeException failure) {
            failed = true;
            LOGGER.warn("[chimera] shadowcomp dispatch error: {}", failure.getMessage(), failure);
            return false;
        }
    }

    private int groups(String name, int local, boolean width) {
        PackStorageImage image = owner.imageForSymbol(name);
        int extent = width ? image.width : image.height;
        return Math.max(1, (extent + local - 1) / local);
    }

    /** Builds the kernel: storage images, then 3D samplers, then 2D samplers, then the uniform block. */
    private void create(String source) {
        int[] types = new int[imageNames.size() + samplerNames.size() + sampled2dNames.size()];
        Arrays.fill(types, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        Arrays.fill(types, 0, imageNames.size(), VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        kernel = ComputeKernel.create("chimera_shadowcomp.csh", source, types,
                ComputeValueBlock.bufferSize(computeUniforms));
        if (kernel != null && !writeDescriptors()) {
            kernel.close();
            kernel = null;
        }
    }

    /** Points every frame slot's set at the shared images and samplers. False when a sampled resource has no owner. */
    private boolean writeDescriptors() {
        for (int index = 0; index < kernel.frameCount(); index++) {
            ComputeKernel.FrameSlot frame = kernel.frame(index);
            for (int binding = 0; binding < imageNames.size(); binding++) {
                PackStorageImage image = owner.imageForSymbol(imageNames.get(binding));
                kernel.writeImage(frame, binding, image.getImageView(), 0L, VK_IMAGE_LAYOUT_GENERAL);
            }
            for (int sampler = 0; sampler < samplerNames.size(); sampler++) {
                PackStorageImage image = owner.imageForSymbol(samplerNames.get(sampler));
                kernel.writeImage(frame, imageNames.size() + sampler, image.getImageView(), image.getSampler(),
                        VK_IMAGE_LAYOUT_GENERAL);
            }
            for (int sampler = 0; sampler < sampled2dNames.size(); sampler++) {
                VulkanImage image = sampledResourceOwner == null
                        ? null : sampledResourceOwner.image(sampled2dNames.get(sampler));
                if (image == null) return false;
                kernel.writeImage(frame, imageNames.size() + samplerNames.size() + sampler, image.getImageView(),
                        image.getSampler(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
        }
        return true;
    }

    /** Throws IllegalArgumentException naming the first declaration the module cannot bind. */
    private static String translate(
            String source,
            PackAdvancedResourcePlan plan,
            List<String> imageNames,
            List<String> samplerNames,
            List<String> sampled2dNames,
            List<ComputeValueBlock.Value> computeUniforms,
            int uniformBinding
    ) {
        String body = normalizeVersion(source);
        body = removeLegacyExtensions(body);
        body = GlslTokenRewriter.splitUniformDeclarators(body);
        body = GlslTokenRewriter.rewriteTextureCalls(body);
        body = boundTexelFetches(body, samplerNames);
        ResourceDeclarations resources = new ResourceDeclarations(plan, imageNames, samplerNames, sampled2dNames);
        body = resources.bindSamplers(body);
        body = resources.bindImages(body);
        body = removeValueUniforms(body, computeUniforms);
        body = resources.removeUnsupportedUniforms(body);
        body = addDispatchBoundsGuard(body, imageNames);
        String generated = resources.undeclaredSamplers(body);
        return "#version 450\n" + ComputeValueBlock.declaration("ChimeraShadowComputeValues", computeUniforms,
                uniformBinding) + generated + body;
    }

    /** Bounds texelFetch on the bound 3D samplers and on every sampler3D function parameter. */
    private static String boundTexelFetches(String body, List<String> samplerNames) {
        Set<String> boundedSamplers = new TreeSet<>(samplerNames);
        Matcher samplerParameter = SAMPLER_3D_PARAMETER.matcher(body);
        while (samplerParameter.find()) {
            boundedSamplers.add(samplerParameter.group(1));
        }
        return GlslTokenRewriter.rewriteTexelFetchBounds(body, boundedSamplers);
    }

    /**
     * Drops every value uniform declaration; the generated block declares the live ones. Throws on
     * one the block cannot serve that the source still uses.
     */
    private static String removeValueUniforms(String body, List<ComputeValueBlock.Value> computeUniforms) {
        Matcher valueUniform = VALUE_UNIFORM_DECLARATION.matcher(body);
        StringBuffer uniformResult = new StringBuffer();
        while (valueUniform.find()) {
            String declarators = valueUniform.group(2).trim();
            for (String declarator : declarators.split(",")) {
                String name = declarator.trim();
                int initializer = name.indexOf('=');
                if (initializer >= 0) name = name.substring(0, initializer).trim();
                String uniformName = name;
                boolean live = computeUniforms.stream().anyMatch(value -> value.name().equals(uniformName));
                if (!live && GlslTokenRewriter.identifierCount(body, name) > 1) {
                    throw new IllegalArgumentException("unsupported compute uniform: " + name);
                }
            }
            valueUniform.appendReplacement(uniformResult, "");
        }
        valueUniform.appendTail(uniformResult);
        return uniformResult.toString();
    }


    /**
     * The module's image and sampler declarations: binds the ones it owns in binding order (storage
     * images, 3D samplers, 2D samplers), drops unused ones, and declares any the source uses
     * without declaring. Throws on a used declaration it cannot bind.
     */
    private static final class ResourceDeclarations {
        private final PackAdvancedResourcePlan plan;
        private final List<String> imageNames;
        private final List<String> samplerNames;
        private final List<String> sampled2dNames;
        private final Set<String> declaredSamplers = new TreeSet<>();
        private final Set<String> declaredSampled2d = new TreeSet<>();

        ResourceDeclarations(PackAdvancedResourcePlan plan, List<String> imageNames, List<String> samplerNames,
                List<String> sampled2dNames) {
            this.plan = plan;
            this.imageNames = imageNames;
            this.samplerNames = samplerNames;
            this.sampled2dNames = sampled2dNames;
        }

        /** Binds each declared sampler the module owns, 2D ones after the 3D ones, and drops the rest. */
        String bindSamplers(String body) {
            Matcher sampler = SAMPLER_DECLARATION.matcher(body);
            StringBuffer samplerResult = new StringBuffer();
            while (sampler.find()) {
                String type = sampler.group(1);
                String name = sampler.group(2);
                if (type.endsWith("2D") || type.equals("sampler2DShadow")) {
                    if (!sampled2dNames.contains(name)) {
                        if (GlslTokenRewriter.identifierCount(body, name) > 1) {
                            throw new IllegalArgumentException("unsupported compute sampler: " + name);
                        }
                        sampler.appendReplacement(samplerResult, "");
                        continue;
                    }
                    // sampled2dNames holds only resources the pack owner has loaded.
                    int binding = imageNames.size() + samplerNames.size()
                            + sampled2dNames.indexOf(name);
                    String replacement = "layout(binding = " + binding
                            + ") uniform sampler2D " + name + ";";
                    declaredSampled2d.add(name);
                    sampler.appendReplacement(samplerResult, Matcher.quoteReplacement(replacement));
                    continue;
                }
                if (!samplerNames.contains(name)) {
                    if (GlslTokenRewriter.identifierCount(body, name) > 1) {
                        throw new IllegalArgumentException("unsupported compute sampler: " + name);
                    }
                    sampler.appendReplacement(samplerResult, "");
                    continue;
                }
                int binding = imageNames.size() + samplerNames.indexOf(name);
                PackAdvancedResourcePlan.ImageSpec image = plan.imageForSymbol(name);
                String replacement = "layout(binding = " + binding + ") uniform "
                        + samplerType(image) + " " + name + ";";
                declaredSamplers.add(name);
                sampler.appendReplacement(samplerResult, Matcher.quoteReplacement(replacement));
            }
            sampler.appendTail(samplerResult);
            return samplerResult.toString();
        }

        /** Binds each declared storage image, by its own name or by the sampler that aliases it. */
        String bindImages(String body) {
            Matcher image = IMAGE_DECLARATION.matcher(body);
            StringBuffer imageResult = new StringBuffer();
            while (image.find()) {
                String type = image.group(1);
                String name = image.group(2);
                int binding = imageNames.indexOf(name);
                if (binding < 0) binding = indexBySampler(imageNames, name, plan);
                if (binding < 0) {
                    throw new IllegalArgumentException("unsupported compute image: " + name);
                }
                PackAdvancedResourcePlan.ImageSpec spec = plan.imageForSymbol(name);
                String replacement = "layout(" + spec.internalFormat() + ", binding = " + binding
                        + ") uniform " + imageType(spec, type) + " " + name + ";";
                image.appendReplacement(imageResult, Matcher.quoteReplacement(replacement));
            }
            image.appendTail(imageResult);
            return imageResult.toString();
        }

        /** Keeps the bound resource declarations and drops every other uniform the source does not use. */
        String removeUnsupportedUniforms(String body) {
            Matcher unsupportedUniform = ANY_UNIFORM_DECLARATION.matcher(body);
            StringBuffer unsupportedResult = new StringBuffer();
            while (unsupportedUniform.find()) {
                String type = unsupportedUniform.group(1);
                String declarators = unsupportedUniform.group(2).trim();
                if (isTranslatedResourceDeclaration(type, declarators, imageNames, samplerNames,
                        sampled2dNames)) {
                    unsupportedUniform.appendReplacement(unsupportedResult,
                            Matcher.quoteReplacement(unsupportedUniform.group()));
                    continue;
                }
                for (String declarator : declarators.split(",")) {
                    String name = declarator.trim();
                    int initializer = name.indexOf('=');
                    if (initializer >= 0) name = name.substring(0, initializer).trim();
                    name = name.replaceFirst("\\[.*$", "").trim();
                    if (!name.matches("[A-Za-z_]\\w*")) {
                        throw new IllegalArgumentException("unsupported compute uniform declaration: "
                                + type + " " + declarators);
                    }
                    if (GlslTokenRewriter.identifierCount(body, name) > 1) {
                        throw new IllegalArgumentException("unsupported compute uniform: " + name);
                    }
                }
                unsupportedUniform.appendReplacement(unsupportedResult, "");
            }
            unsupportedUniform.appendTail(unsupportedResult);
            return unsupportedResult.toString();
        }

        /** Declarations for the bound samplers the body uses but never declared. */
        String undeclaredSamplers(String body) {
            StringBuilder generated = new StringBuilder();
            for (String name : samplerNames) {
                if (declaredSamplers.contains(name)) continue;
                if (!GlslTokenRewriter.containsIdentifier(body, name)) continue;
                int binding = imageNames.size() + samplerNames.indexOf(name);
                PackAdvancedResourcePlan.ImageSpec imageSpec = plan.imageForSymbol(name);
                generated.append("layout(binding = ").append(binding).append(") uniform ")
                        .append(samplerType(imageSpec)).append(' ').append(name).append(";\n");
            }
            for (String name : sampled2dNames) {
                if (declaredSampled2d.contains(name)) continue;
                if (!GlslTokenRewriter.containsIdentifier(body, name)) continue;
                int binding = imageNames.size() + samplerNames.size() + sampled2dNames.indexOf(name);
                generated.append("layout(binding = ").append(binding)
                        .append(") uniform sampler2D ").append(name).append(";\n");
            }
            return generated.toString();
        }
    }

    private static String normalizeVersion(String source) {
        String body = source.replaceAll("(?m)^\\s*#version\\b[^\\r\\n]*(?:\\r?\\n|$)", "");
        return body;
    }

    private static String addDispatchBoundsGuard(String source, List<String> imageNames) {
        if (source == null || imageNames == null || imageNames.isEmpty()) return source;
        int main = source.indexOf("void main");
        if (main < 0) throw new IllegalArgumentException("compute main function is missing");
        int open = source.indexOf('{', main);
        if (open < 0) throw new IllegalArgumentException("compute main body is missing");
        String image = imageNames.get(0);
        String guard = "\n    ivec3 chimeraDispatchSize = imageSize(" + image + ");\n"
                + "    ivec3 chimeraGlobalId = ivec3(gl_GlobalInvocationID);\n"
                + "    if (any(greaterThanEqual(chimeraGlobalId, chimeraDispatchSize))) return;\n";
        return source.substring(0, open + 1) + guard + source.substring(open + 1);
    }

    private static String removeLegacyExtensions(String source) {
        return source.replaceAll("(?m)^\\s*#extension\\s+GL_ARB_shader_texture_lod\\s*:\\s*"
                + "(?:enable|require|warn)\\s*(?:\\r?\\n|$)", "");
    }

    private static List<ComputeValueBlock.Value> computeUniforms(String source) {
        if (source == null) return List.of();
        Map<String, String> declarations = new TreeMap<>();
        Matcher matcher = VALUE_UNIFORM_DECLARATION.matcher(source);
        while (matcher.find()) {
            String type = matcher.group(1);
            for (String raw : matcher.group(2).split(",")) {
                String name = raw.trim();
                int initializer = name.indexOf('=');
                if (initializer >= 0) name = name.substring(0, initializer).trim();
                if (!name.matches("[A-Za-z_]\\w*")) {
                    throw new IllegalArgumentException("unsupported compute uniform declaration: " + raw);
                }
                if (PackUniformProvider.resolveUniform(name, type) != null
                        && GlslTokenRewriter.identifierCount(source, name) > 1) {
                    declarations.putIfAbsent(name, type);
                } else if (GlslTokenRewriter.identifierCount(source, name) > 1) {
                    throw new IllegalArgumentException("unsupported compute uniform: " + name);
                }
            }
        }
        return ComputeValueBlock.layout(declarations);
    }


    private static boolean isTranslatedResourceDeclaration(
            String type,
            String declarators,
            List<String> imageNames,
            List<String> samplerNames,
            List<String> sampled2dNames
    ) {
        if (type == null || declarators == null) return false;
        if (type.equals("sampler2D")) {
            return sampled2dNames.contains(declarators.trim());
        }
        if (!(type.equals("image3D") || type.equals("uimage3D")
                || type.equals("iimage3D") || type.equals("sampler3D")
                || type.equals("usampler3D"))) return false;
        String name = declarators.trim();
        int initializer = name.indexOf('=');
        if (initializer >= 0) name = name.substring(0, initializer).trim();
        if (name.contains(",") || !name.matches("[A-Za-z_]\\w*")) return false;
        return imageNames.contains(name) || samplerNames.contains(name);
    }

    private static String samplerType(PackAdvancedResourcePlan.ImageSpec spec) {
        return isInteger(spec) ? "usampler3D" : "sampler3D";
    }

    private static String imageType(PackAdvancedResourcePlan.ImageSpec spec, String sourceType) {
        return isInteger(spec) ? "uimage3D" : sourceType;
    }

    private static boolean isInteger(PackAdvancedResourcePlan.ImageSpec spec) {
        return spec != null && (spec.internalFormat().equalsIgnoreCase("r8ui")
                || spec.internalFormat().equalsIgnoreCase("r16ui"));
    }

    private static int indexBySampler(List<String> names, String name, PackAdvancedResourcePlan plan) {
        for (int index = 0; index < names.size(); index++) {
            PackAdvancedResourcePlan.ImageSpec spec = plan.imageForSymbol(names.get(index));
            if (spec != null && spec.sampler().equals(name)) return index;
        }
        return -1;
    }

    /** Resolves only standard sampled resources that already have a pack owner. */
    private static List<String> standardSampledResources(String source, Predicate<String> available) {
        if (source == null) return List.of();
        if (GlslTokenRewriter.containsIdentifier(source, "noisetex") && available.test("noisetex")) {
            return List.of("noisetex");
        }
        return List.of();
    }


    @Override
    public void close() {
        if (kernel != null) kernel.close();
        kernel = null;
    }
}
