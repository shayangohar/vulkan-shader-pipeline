package net.chimera.shaderpack;

import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a pack fragment stage to Vulkan GLSL for its family (post, terrain, water, shadow,
 * entity, particle, sky and cloud): its outputs, sampler bindings and generated uniform blocks.
 * Deterministic, with no external state; a conversion that fails returns null, so the caller
 * falls back to the identity pipeline.
 *
 * <ul>
 * <li>Post stages draw fullscreen: varyings take location 0, an optional generated pack UBO
 *     sits at binding 0 and samplers follow at 1, 2, ... (0, 1, ... without uniforms).
 * <li>World stages read the converted vertex stage's varyings by location, and their samplers
 *     follow the host uniform blocks of their pipeline config.
 * </ul>
 */
public final class FragmentConverter {
    private FragmentConverter() {}

    static final Pattern MODERN_GEOMETRY_OUTPUT_DECL = Pattern.compile(
            "(?m)^\\s*out\\s+vec4\\s+(\\w+)\\s*;\\s*");

    static final Pattern POST_DRAWBUFFERS_DEFINE = Pattern.compile(
            "(?m)^\\s*#define\\s+DRAWBUFFERS[0-9]+\\s*$");

    /** Three UBO blocks precede samplers in the terrain pipeline config. */
    static final int GEOMETRY_SAMPLER_BINDING_BASE = 3;

    static int geometrySamplerBindingBase() {
        return GEOMETRY_SAMPLER_BINDING_BASE;
    }

    /**
     * Immutable inputs for one fragment conversion. Replaces the former
     * positional overload ladder: callers start from {@link #of} or
     * {@link #withAutoPlan} and add only the plans their stage needs.
     * Missing plans keep the old overload defaults (empty or disabled),
     * so migrating a caller is behavior-preserving by construction.
     */
    public record FragmentConversionRequest(
            String source,
            Path sourceFile,
            boolean geometryStage,
            int[] geometrySamplerSlots,
            LegacyGlslConverter.TerrainVaryingLayout terrainLayout,
            UniformRegistry.ProgramInterface interfacePlan,
            PostTargetPlan targetPlan,
            GeometryOutputPlan geometryOutputPlan,
            Map<String, String> packConstants,
            PostVertexBridge.PostVaryingLayout postVaryingLayout,
            Set<String> atlasSamplers,
            PackAlphaTestPlan alphaTestPlan
    ) {
        public FragmentConversionRequest {
            packConstants = packConstants == null ? Map.of() : packConstants;
            atlasSamplers = atlasSamplers == null ? Set.of() : atlasSamplers;
            alphaTestPlan = alphaTestPlan == null ? PackAlphaTestPlan.disabled() : alphaTestPlan;
        }

        /** Minimal request; every plan defaults to empty or disabled. */
        public static FragmentConversionRequest of(String source, Path sourceFile,
                boolean geometryStage, int[] geometrySamplerSlots) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    null, null, null, null, Map.of(), null, Set.of(), PackAlphaTestPlan.disabled());
        }

        /** Minimal request with the interface plan derived from the source, as the old shims did. */
        public static FragmentConversionRequest withAutoPlan(String source, Path sourceFile,
                boolean geometryStage, int[] geometrySamplerSlots,
                        LegacyGlslConverter.TerrainVaryingLayout terrainLayout) {
            return of(source, sourceFile, geometryStage, geometrySamplerSlots)
                    .withTerrainLayout(terrainLayout)
                    .withInterfacePlan(UniformRegistry.plan(source, LegacyGlslConverter.stageOf(geometryStage)));
        }

        public FragmentConversionRequest withTerrainLayout(LegacyGlslConverter.TerrainVaryingLayout terrainLayout) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    packConstants, postVaryingLayout, atlasSamplers, alphaTestPlan);
        }

        public FragmentConversionRequest withInterfacePlan(UniformRegistry.ProgramInterface interfacePlan) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    packConstants, postVaryingLayout, atlasSamplers, alphaTestPlan);
        }

        public FragmentConversionRequest withTargetPlan(PostTargetPlan targetPlan) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    packConstants, postVaryingLayout, atlasSamplers, alphaTestPlan);
        }

        public FragmentConversionRequest withGeometryOutputPlan(GeometryOutputPlan geometryOutputPlan) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    packConstants, postVaryingLayout, atlasSamplers, alphaTestPlan);
        }

        public FragmentConversionRequest withPackConstants(Map<String, String> packConstants) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    packConstants, postVaryingLayout, atlasSamplers, alphaTestPlan);
        }

        public FragmentConversionRequest withPostVaryingLayout(PostVertexBridge.PostVaryingLayout postVaryingLayout) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    packConstants, postVaryingLayout, atlasSamplers, alphaTestPlan);
        }

        public FragmentConversionRequest withAtlasSamplers(Set<String> atlasSamplers) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    packConstants, postVaryingLayout, atlasSamplers, alphaTestPlan);
        }

        public FragmentConversionRequest withAlphaTestPlan(PackAlphaTestPlan alphaTestPlan) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    packConstants, postVaryingLayout, atlasSamplers, alphaTestPlan);
        }

        /** Runs the conversion; returns null when the source is outside the executable contract. */
        public String convert() {
            return convertFragment(this);
        }
    }

    /** Converts a post fragment with its output target plan. */
    public static String convertPostFragment(
            String source,
            Path sourceFile,
            UniformRegistry.ProgramInterface interfacePlan,
            PostTargetPlan targetPlan
    ) {
        return convertPostFragment(source, sourceFile, interfacePlan, targetPlan, Map.of());
    }

    /** Converts a post fragment using the pack constants already parsed by PackConfig. */
    public static String convertPostFragment(
            String source,
            Path sourceFile,
            UniformRegistry.ProgramInterface interfacePlan,
            PostTargetPlan targetPlan,
            Map<String, String> packConstants
    ) {
        return FragmentConversionRequest.of(source, sourceFile, false, null)
                .withInterfacePlan(interfacePlan)
                .withTargetPlan(targetPlan)
                .withPackConstants(packConstants)
                .convert();
    }

    /** Converts a post fragment against a paired fullscreen vertex layout. */
    public static String convertPostFragment(
            String source,
            Path sourceFile,
            UniformRegistry.ProgramInterface interfacePlan,
            PostTargetPlan targetPlan,
            Map<String, String> packConstants,
            Map<String, Integer> varyingLocations,
            Map<String, String> varyingTypes
    ) {
        PostVertexBridge.PostVaryingLayout layout = varyingLocations == null
                ? null : new PostVertexBridge.PostVaryingLayout(varyingLocations, varyingTypes);
        return FragmentConversionRequest.of(source, sourceFile, false, null)
                .withInterfacePlan(interfacePlan)
                .withTargetPlan(targetPlan)
                .withPackConstants(packConstants)
                .withPostVaryingLayout(layout)
                .convert();
    }

    static String convertFragment(FragmentConversionRequest request) {
        try {
            return new FragmentConversion(request).convert();
        } catch (Exception e) {
            // The planner reports POST_CONVERTER_UNSUPPORTED; the cause belongs in the log.
            LoggerFactory.getLogger("chimera").warn(
                    "[chimera] fragment conversion failed: {}", e.toString());
            return null;
        }
    }

    /** One fragment stage's conversion: the request, and its source as each step rewrites it. */
    private static final class FragmentConversion {
        private final FragmentConversionRequest request;
        private final UniformRegistry.ProgramInterface interfacePlan;
        private final UniformRegistry.Stage stage;
        private final boolean geometryStage;
        private String src;
        /** A world stage authored for 450 and later keeps its own version line and layout. */
        private boolean modern;

        FragmentConversion(FragmentConversionRequest request) {
            this.request = request;
            this.interfacePlan = request.interfacePlan();
            this.geometryStage = request.geometryStage();
            boolean geometryInterface = interfacePlan != null && interfacePlan.stage() != UniformRegistry.Stage.POST;
            if (interfacePlan == null || !interfacePlan.executable() || geometryInterface != geometryStage) {
                throw new IllegalArgumentException("pack interface is outside the executable contract");
            }
            this.stage = interfacePlan.stage();
            PostTargetPlan targetPlan = request.targetPlan();
            if (targetPlan != null && (geometryStage || stage != UniformRegistry.Stage.POST
                    || !targetPlan.executable())) {
                throw new IllegalArgumentException("post target plan is outside the executable contract");
            }
            GeometryOutputPlan geometryOutputPlan = request.geometryOutputPlan();
            if (geometryOutputPlan != null && (!geometryStage || !geometryOutputPlan.executable())) {
                throw new IllegalArgumentException("geometry output plan is outside the executable contract");
            }
            if (request.source() == null) {
                throw new IllegalArgumentException("pack fragment source is missing");
            }
        }

        String convert() {
            prepare();
            rewriteDeclarations();
            bindSamplers();
            String outputDeclarations = rewriteOutputs();
            String terrainHostInstance = appendAlphaRejection();
            return assemble(outputDeclarations, uniformBlock(terrainHostInstance));
        }

        /** Inlines includes, drops unreachable functions and settles the version line. */
        private void prepare() {
            src = LegacyGlslConverter.prepareSource(request.source(), request.sourceFile(), false);
            GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(src);
            if (!usage.successful()) {
                throw new IllegalArgumentException(usage.deviations().toString());
            }
            src = GlslTokenRewriter.removeUnreachableFunctions(src, usage);
            if (!geometryStage) {
                PostVertexBridge.validatePostVersion(src);
                src = LegacyGlslConverter.VERSION_LINE.matcher(src).replaceAll("");
                return;
            }
            GlslVersion version = GlslVersion.first(src);
            modern = (version != null && version.number() >= 450)
                    || (stage.shadowPass() && TerrainVertexBridge.MODERN_TERRAIN_DECL.matcher(src).find());
            // One version line for Vulkan, where an authored profile means nothing.
            src = LegacyGlslConverter.VERSION_LINE.matcher(src).replaceAll("");
            if (modern) {
                src = "#version 460\n" + src;
            }
        }

        /** Drops what the host serves (metadata constants, uniforms, entity ids) and maps the varyings. */
        private void rewriteDeclarations() {
            Map<String, String> packConstants = request.packConstants();
            src = LegacyGlslConverter.removePackMetadataConstants(src);
            src = LegacyGlslConverter.stripUnusedConsumedConsts(src, packConstants);
            src = LegacyGlslConverter.KNOWN_LEGACY_EXTENSIONS.matcher(src).replaceAll("");
            src = UniformRegistry.removeUniformDeclarations(src, interfacePlan);
            if (stage.perVertexEntityIdentity()) {
                src = EntityVertexBridge.removeEntityIdDeclarations(src, interfacePlan);
                src = EntityVertexBridge.replaceEntityIdReferences(src, request.terrainLayout());
            }
            if (entityStage(stage)) {
                src = rewriteElytraFlyingBool(src, interfacePlan);
            }
            if (geometryStage && modern && request.geometryOutputPlan() == null && !stage.shadowPass()) {
                src = convertModernGeometryOutputs(src);
            }
            src = LegacyGlslConverter.convertVaryings(src, geometryStage, request.terrainLayout(),
                    request.postVaryingLayout(), stage);
            src = LegacyGlslConverter.injectPackConstants(src, packConstants);
        }

        /**
         * Rewrites texture calls and binds each sampler to its descriptor. Only the projected
         * stage resources are emitted; descriptor positions still come from the canonical program
         * layout, never the filtered stage order.
         */
        private void bindSamplers() {
            List<UniformRegistry.SamplerBinding> samplers = interfacePlan.samplers();
            int bindingBase = geometryStage
                    ? geometrySamplerBindingBase(interfacePlan)
                    : (interfacePlan.executableUniforms().isEmpty() ? 0 : 1);
            if (geometryStage) {
                // GLSL 460: 'texture' is the sampling function name, so a pack sampler declared as
                // 'uniform sampler2D texture;' (the OptiFine atlas convention) is renamed uniformly.
                // Runs BEFORE convertTextureCalls so the function-name rewrite below creates
                // texture(chimeraTexture, ...) instead of renaming the function into a variable call.
                src = GlslTokenRewriter.renameSamplerIdentifier(src, "texture", "chimeraTexture");
            }
            src = LegacyGlslConverter.convertTextureCalls(src);
            if (terrainContract(stage)) {
                Set<String> emittedAtlasSamplers = new HashSet<>();
                for (String name : request.atlasSamplers()) {
                    emittedAtlasSamplers.add(name.equals("texture") ? "chimeraTexture" : name);
                }
                src = AtlasSampleRewriter.rewriteTerrainAtlasSamples(src, emittedAtlasSamplers);
            }
            src = removeLegacyShadowSamplerHelper(src);
            if (GlslTokenRewriter.containsIdentifier(src, "shadow2DProj")
                    || GlslTokenRewriter.containsIdentifier(src, "shadow2DProjLod")) {
                throw new IllegalArgumentException("projected shadow lookup is outside the supported bridge");
            }
            Map<String, String> samplerTypes = new TreeMap<>();
            for (UniformRegistry.SamplerBinding sampler : samplers) {
                samplerTypes.put(sampler.name(), sampler.glslType());
            }
            src = GlslTokenRewriter.rewriteShadowCalls(src, samplerTypes);
            for (UniformRegistry.SamplerBinding sampler : samplers) {
                String name = sampler.name();
                String srcName = geometryStage && name.equals("texture") ? "chimeraTexture" : name;
                int binding = geometryStage
                        ? geometrySamplerBinding(name, request.geometrySamplerSlots(), interfacePlan)
                        : bindingBase + interfacePlan.samplerIndex(name);
                if (binding < bindingBase) {
                    throw new IllegalArgumentException("sampler is missing from the generated config: " + name);
                }
                if (hasSamplerDeclaration(src, srcName)) {
                    src = rewriteSamplerDeclaration(src, srcName, binding);
                }
            }
        }

        /** Routes the authored outputs to their attachments; returns their declarations, or null. */
        private String rewriteOutputs() {
            if (request.targetPlan() != null) {
                src = convertPostOutputs(src, request.targetPlan());
                return postOutputDeclarations(request.targetPlan());
            }
            if (request.geometryOutputPlan() != null) {
                src = convertGeometryOutputs(src, request.geometryOutputPlan());
                return geometryOutputDeclarations(request.geometryOutputPlan());
            }
            if (stage.shadowPass()) {
                return rewriteShadowOutputs();
            }
            if (src.contains("gl_FragColor") || src.contains("gl_FragData")) {
                src = GlslTokenRewriter.rewriteSingleOutput(src);
                // A single-attachment pass consumes nothing beyond target 0.
                src = src.replaceAll("gl_FragData\\s*\\[\\s*[1-9][0-9]*\\s*\\]\\s*=\\s*[^;]+;\\s*", "");
                return "layout(location = 0) out vec4 fragColor;";
            }
            return null;
        }

        /**
         * Shadow colours keep the type the pack writes (Photon writes a vec3), as post outputs
         * do; a colour the pack does not write is declared vec4.
         */
        private String rewriteShadowOutputs() {
            PostTargetPlan shadowOutputs = PostTargetPlan.parse("shadow", src).plan();
            if (!shadowOutputs.executable() || shadowOutputs.targetSlots().stream()
                    .anyMatch(target -> target < 0 || target > 1)) {
                throw new IllegalArgumentException("SHADOW_OUTPUT_TARGET_UNSUPPORTED");
            }
            src = convertPostOutputs(src, shadowOutputs);
            Map<String, String> outputs = new TreeMap<>();
            String[] types = {"vec4", "vec4"};
            for (int location : shadowOutputs.outputLocations()) {
                int target = shadowOutputs.targetForOutput(location);
                outputs.put("chimeraFragColor" + location, "chimeraShadowColor" + target);
                types[target] = shadowOutputs.outputType(location);
            }
            src = GlslTokenRewriter.replaceIdentifiers(src, outputs);
            return "layout(location = 0) out " + types[0] + " chimeraShadowColor0;\n"
                    + "layout(location = 1) out " + types[1] + " chimeraShadowColor1;"
                    + (shadowOutputs.discardedOutputs().isEmpty() ? ""
                    : "\n" + discardedOutputDeclarations(shadowOutputs).stripTrailing());
        }

        /**
         * Appends the pack's alpha test to a routed terrain stage; returns the host block
         * instance its threshold reads, or null when it reads none.
         */
        private String appendAlphaRejection() {
            PackAlphaTestPlan alpha = request.alphaTestPlan();
            GeometryOutputPlan geometryOutputPlan = request.geometryOutputPlan();
            if (!geometryStage || !terrainContract(stage) || geometryOutputPlan == null || !alpha.active()) {
                return null;
            }
            String terrainHostInstance = alpha.needsHostThreshold()
                    ? GlslTokenRewriter.uniqueIdentifier(src, "chimeraTerrainHost") : null;
            String rejection = alpha.rejection(geometryOutputPlan.locationZeroOutputName(), terrainHostInstance);
            if (!rejection.isBlank()) {
                src = GlslTokenRewriter.appendMainEpilogue(src, rejection);
            }
            return terrainHostInstance;
        }

        /** The generated uniform block of the stage's family, empty when it reads no uniforms. */
        private String uniformBlock(String terrainHostInstance) {
            List<UniformRegistry.UniformDeclaration> uniforms = interfacePlan.executableUniforms();
            if (stage.shadowPass() && !uniforms.isEmpty()) {
                src = qualifyShadowUniformReferences(src, uniforms);
                return generatedShadowUniformBlock(uniforms, shadowUniformBinding(stage));
            }
            if (geometryStage && terrainContract(stage)) {
                StringBuilder geometryBlocks = new StringBuilder();
                if (terrainHostInstance != null) {
                    geometryBlocks.append(terrainHostUniformBlock(src, terrainHostInstance));
                }
                if (!uniforms.isEmpty()) {
                    geometryBlocks.append(generatedGeometryUniformBlock(uniforms));
                }
                return geometryBlocks.toString();
            }
            if ((entityStage(stage) || stage == UniformRegistry.Stage.SKY || stage == UniformRegistry.Stage.CLOUD)
                    && !uniforms.isEmpty()) {
                return entityUniformBlock(uniforms);
            }
            return !geometryStage && !uniforms.isEmpty() ? generatedUniformBlock(uniforms, src) : "";
        }

        /**
         * GLSL requires global declarations to precede their first use; an output declared at the
         * end of the file is a forward reference glslang rejects ("'fragColor' : undeclared
         * identifier"). The declarations go directly after the version line.
         */
        private String assemble(String outputDeclarations, String uniformBlock) {
            String declarations = (stage.perVertexEntityIdentity()
                    ? chimeraFragmentVaryings(request.terrainLayout()) : "")
                    + (outputDeclarations != null ? outputDeclarations + "\n" : "") + uniformBlock;
            if (stage.shadowPass()) {
                declarations = SHADOW_RENDER_STAGE_DEFINES + declarations;
            }
            if (!geometryStage || !modern) {
                return "#version 460\n" + declarations + src;
            }
            return declarations.isEmpty() ? src : LegacyGlslConverter.insertAfterFirstLine(src, declarations);
        }
    }

    private static boolean entityStage(UniformRegistry.Stage stage) {
        return stage == UniformRegistry.Stage.ENTITY || stage == UniformRegistry.Stage.BLOCK
                || stage == UniformRegistry.Stage.HAND || stage == UniformRegistry.Stage.PARTICLE;
    }

    static String convertPostOutputs(String source, PostTargetPlan targetPlan) {
        String result = POST_DRAWBUFFERS_DEFINE.matcher(source).replaceAll("");
        result = rewriteModernOutputs(result, targetPlan);
        return GlslTokenRewriter.rewritePostOutputs(result, targetPlan);
    }

    /** Keeps the historical single-attachment geometry symbol stable. */
    static String convertGeometryOutputs(String source, GeometryOutputPlan outputPlan) {
        String result = convertPostOutputs(source, outputPlan.targetPlan());
        if (outputPlan.targetSlots().size() == 1) {
            result = GlslTokenRewriter.replaceIdentifiers(result,
                    Map.of("chimeraFragColor0", "fragColor"));
        }
        return result;
    }

    static String geometryOutputDeclarations(GeometryOutputPlan outputPlan) {
        String result = postOutputDeclarations(outputPlan.targetPlan());
        if (outputPlan.targetSlots().size() == 1) {
            result = GlslTokenRewriter.replaceIdentifiers(result,
                    Map.of("chimeraFragColor0", "fragColor"));
        }
        return result;
    }

    static String rewriteModernOutputs(String source, PostTargetPlan targetPlan) {
        if (targetPlan == null) {
            return source;
        }
        List<PostTargetPlan.ModernOutput> outputs = PostTargetPlan.modernOutputs(source);
        if (outputs.isEmpty()) {
            return source;
        }
        StringBuilder result = new StringBuilder();
        Map<String, String> replacements = new TreeMap<>();
        int last = 0;
        for (PostTargetPlan.ModernOutput output : outputs) {
            String declaration = source.substring(output.start(), output.end());
            if (targetPlan.discardedOutputs().contains(output.location())) {
                // Past the route: GL discards it, so it becomes a private global the
                // pack still writes. Line breaks stay so line numbers do not move.
                result.append(source, last, output.start())
                        .append(declaration.replaceAll("[^\\r\\n]", ""))
                        .append(output.type()).append(' ').append(output.name()).append(';');
                last = output.end();
                continue;
            }
            if (!targetPlan.outputLocations().contains(output.location())) {
                throw new IllegalArgumentException("modern fragment output exceeds target route: " + output.name());
            }
            replacements.put(output.name(), "chimeraFragColor" + output.location());
            // The generated declaration replaces this one; keep its line breaks.
            result.append(source, last, output.start())
                    .append(declaration.replaceAll("[^\\r\\n]", ""));
            last = output.end();
        }
        result.append(source.substring(last));
        return GlslTokenRewriter.replaceIdentifiers(result.toString(), replacements);
    }

    static String postOutputDeclarations(PostTargetPlan targetPlan) {
        StringBuilder declarations = new StringBuilder();
        for (int location : targetPlan.outputLocations()) {
            declarations.append("layout(location = ").append(location).append(") out ")
                    .append(targetPlan.outputType(location))
                    .append(" chimeraFragColor").append(location).append(";\n");
        }
        return declarations.append(discardedOutputDeclarations(targetPlan)).toString();
    }

    /** Private globals for discarded gl_FragData writes; a discarded modern output keeps its own. */
    static String discardedOutputDeclarations(PostTargetPlan targetPlan) {
        StringBuilder declarations = new StringBuilder();
        for (int location : targetPlan.discardedOutputs()) {
            declarations.append("vec4 ").append(GlslTokenRewriter.DISCARDED_OUTPUT).append(location).append(";\n");
        }
        return declarations.toString();
    }

    /** Converts a particle fragment with the shared host sampler and varying rules. */
    public static String convertParticleFragment(
            String source,
            Path sourceFile,
            int[] samplerSlots,
            LegacyGlslConverter.TerrainVaryingLayout particleLayout,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        return convertParticleFragment(source, sourceFile, samplerSlots,
                particleLayout, interfacePlan, null);
    }

    /** Particle fragment conversion carrying the authored geometry output plan. */
    public static String convertParticleFragment(
            String source,
            Path sourceFile,
            int[] samplerSlots,
            LegacyGlslConverter.TerrainVaryingLayout particleLayout,
            UniformRegistry.ProgramInterface interfacePlan,
            GeometryOutputPlan geometryOutputPlan
    ) {
        try {
            String prepared = LegacyGlslConverter.prepareSource(source, sourceFile, false);
            return FragmentConversionRequest.of(prepared, null, true, samplerSlots)
                    .withTerrainLayout(particleLayout)
                    .withInterfacePlan(interfacePlan)
                    .withGeometryOutputPlan(geometryOutputPlan)
                    .convert();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Converts an entity fragment with the shared varying and descriptor rules. */
    public static String convertEntityFragment(
            String source,
            Path sourceFile,
            int[] samplerSlots,
            LegacyGlslConverter.TerrainVaryingLayout entityLayout,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        return convertEntityFragment(source, sourceFile, samplerSlots, entityLayout,
                interfacePlan, null);
    }

    /** Entity fragment conversion carrying the authored geometry output plan. */
    public static String convertEntityFragment(
            String source,
            Path sourceFile,
            int[] samplerSlots,
            LegacyGlslConverter.TerrainVaryingLayout entityLayout,
            UniformRegistry.ProgramInterface interfacePlan,
            GeometryOutputPlan geometryOutputPlan
    ) {
        try {
            String prepared = LegacyGlslConverter.prepareSource(source, sourceFile, false);
            validateEntityFragmentVersion(prepared);
            return FragmentConversionRequest.of(prepared, null, true, samplerSlots)
                    .withTerrainLayout(entityLayout)
                    .withInterfacePlan(interfacePlan)
                    .withGeometryOutputPlan(geometryOutputPlan)
                    .convert();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static String convertSkyFragment(
            String source, Path sourceFile, int[] samplerSlots,
            LegacyGlslConverter.TerrainVaryingLayout layout, UniformRegistry.ProgramInterface interfacePlan,
            GeometryOutputPlan outputPlan
    ) {
        return FragmentConversionRequest.of(source, sourceFile, true, samplerSlots)
                .withTerrainLayout(layout)
                .withInterfacePlan(interfacePlan)
                .withGeometryOutputPlan(outputPlan)
                .convert();
    }

    /** Adds the coverage output to a converted geometry fragment. */
    public static String withCoverageOutput(String source) {
        if (source == null || source.contains("gl_FragDepth")
                || source.matches("(?s).*\\bchimeraCoverage\\b.*")) {
            throw new IllegalArgumentException("coverage requires implicit fragment depth");
        }
        Matcher matcher = Pattern.compile(
                "layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*out\\s+"
                        + "(?:float|vec2|vec3|vec4)\\s+\\w+\\s*;").matcher(source);
        int max = -1;
        while (matcher.find()) max = Math.max(max, Integer.parseInt(matcher.group(1)));
        if (max < 0 || max >= 7) {
            throw new IllegalArgumentException("coverage output has no safe color location");
        }
        String declaration = "layout(location = " + (max + 1) + ") out float chimeraCoverage;\n";
        int versionEnd = source.indexOf('\n');
        int insertAt = versionEnd < 0 ? 0 : versionEnd + 1;
        String result = source.substring(0, insertAt) + declaration + source.substring(insertAt);
        return GlslTokenRewriter.appendMainEpilogue(result,
                "chimeraCoverage = gl_FragCoord.z;");
    }

    /**
     * gbuffers_terrain and gbuffers_water share one terrain contract: the
     * terrain vertex inputs, the generated pack uniform block at binding 3,
     * the host terrain block at binding 1, and the terrain alpha test. They
     * differ only in the sampled inputs their stage table admits.
     */
    static boolean terrainContract(UniformRegistry.Stage stage) {
        return stage == UniformRegistry.Stage.GEOMETRY || stage == UniformRegistry.Stage.TRANSLUCENT;
    }

    /** Position of the sampler's registry slot in the emitted geometry config array. */
    /** First sampler binding of a geometry-family program, after its host and pack UBOs. */
    static int geometrySamplerBindingBase(UniformRegistry.ProgramInterface interfacePlan) {
        UniformRegistry.Stage stage = interfacePlan.stage();
        boolean uniforms = !interfacePlan.executableUniforms().isEmpty();
        if (stage == UniformRegistry.Stage.ENTITY || stage == UniformRegistry.Stage.BLOCK
                || stage == UniformRegistry.Stage.HAND || stage == UniformRegistry.Stage.PARTICLE
                || stage == UniformRegistry.Stage.SHADOW_ENTITY) {
            return uniforms ? 3 : 2;
        }
        return GEOMETRY_SAMPLER_BINDING_BASE + (uniforms ? 1 : 0);
    }

    /**
     * Descriptor binding of one geometry-family sampler. Both stages of a
     * program share one descriptor layout (VulkanMod exposes every image
     * binding to all graphics stages), so the vertex and fragment stages
     * declare a sampler at the same binding.
     */
    static int geometrySamplerBinding(
            String name, int[] slots, UniformRegistry.ProgramInterface interfacePlan
    ) {
        int index = LegacyGlslConverter.configIndexOf(name, slots, interfacePlan.stage(), interfacePlan);
        return index < 0 ? -1 : geometrySamplerBindingBase(interfacePlan) + index;
    }

    /**
     * One block layout shared by both stages. A stage that declares its own file-scope global of
     * a member's name (Bliss deferred.vsh: {@code vec3 sunVec = ...} beside the fragment's
     * {@code uniform vec3 sunVec}) keeps the slot under a reserved name, so its global wins in
     * that stage as it does under Iris, where each stage owns its uniforms.
     */
    static String generatedUniformBlock(List<UniformRegistry.UniformDeclaration> uniforms,
                                                String stageSource) {
        Set<String> globals = LegacyGlslConverter.fileScopeGlobals(stageSource);
        StringBuilder block = new StringBuilder("layout(binding = 0) uniform ChimeraPackUniforms {\n");
        for (UniformRegistry.UniformDeclaration uniform : uniforms) {
            String name = globals.contains(uniform.name()) ? "chimera_shadowed_" + uniform.name() : uniform.name();
            block.append("    ").append(uniform.glslType()).append(' ').append(name).append(";\n");
        }
        return block.append("};\n").toString();
    }

    /**
     * The shadow program's pack uniform block follows its lane's host blocks: binding 3 after
     * the terrain blocks, binding 2 after the entity transform blocks.
     */
    static int shadowUniformBinding(UniformRegistry.Stage stage) {
        return stage == UniformRegistry.Stage.SHADOW_ENTITY ? 2 : 3;
    }

    static String generatedShadowUniformBlock(
            List<UniformRegistry.UniformDeclaration> uniforms,
            int binding
    ) {
        StringBuilder block = new StringBuilder("layout(binding = " + binding + ") uniform ChimeraShadowUniforms {\n");
        for (UniformRegistry.UniformDeclaration uniform : uniforms) {
            block.append("    ").append(uniform.glslType()).append(' ')
                    .append(uniform.name()).append(";\n");
        }
        return block.append("} chimeraShadowUniforms;\n").toString();
    }

    /**
     * Vulkan GLSL requires named shadow-block members to be accessed through
     * the block instance. Keep canonical field names in PipelineConfig and
     * qualify only executable source references.
     */
    static String qualifyShadowUniformReferences(
            String source,
            List<UniformRegistry.UniformDeclaration> uniforms
    ) {
        Map<String, String> replacements = new TreeMap<>();
        for (UniformRegistry.UniformDeclaration uniform : uniforms) {
            replacements.put(uniform.name(), "chimeraShadowUniforms." + uniform.name());
        }
        return replacements.isEmpty()
                ? source : GlslTokenRewriter.replaceIdentifiers(source, replacements);
    }

    /** Ordinary terrain pack uniforms share the binding reserved after host section data. */
    static String generatedGeometryUniformBlock(
            List<UniformRegistry.UniformDeclaration> uniforms
    ) {
        StringBuilder block = new StringBuilder("layout(binding = 3) uniform ChimeraTerrainPackUniforms {\n");
        for (UniformRegistry.UniformDeclaration uniform : uniforms) {
            block.append("    ").append(uniform.glslType()).append(' ')
                    .append(uniform.name()).append(";\n");
        }
        return block.append("};\n").toString();
    }

    /** Exact binding-1 layout, with an instance reserved for generated alpha testing. */
    static String terrainHostUniformBlock(String source, String instance) {
        String block = GlslTokenRewriter.uniqueIdentifier(source, "ChimeraTerrainHostBlock");
        return """
                layout(binding = 1) uniform %s {
                    vec4 FogColor;
                    float FogEnvironmentalStart;
                    float FogEnvironmentalEnd;
                    float FogRenderDistanceStart;
                    float FogRenderDistanceEnd;
                    float FogSkyEnd;
                    float FogCloudsEnd;
                    float AlphaCutout;
                    ivec2 TextureSize;
                    vec2 TexelSize;
                    int UseRgss;
                } %s;
                """.formatted(block, instance);
    }

    /** Generated entity fragment UBO. Vertex transform blocks are supplied by the host. */
    static String entityUniformBlock(List<UniformRegistry.UniformDeclaration> uniforms) {
        StringBuilder block = new StringBuilder("layout(binding = 2) uniform ChimeraEntityUniforms {\n");
        for (UniformRegistry.UniformDeclaration uniform : uniforms) {
            block.append("    ").append(uniform.glslType()).append(' ')
                    .append(uniform.name()).append(";\n");
        }
        return block.append("};\n").toString();
    }

    static void validateEntityFragmentVersion(String source) {
        GlslVersion.requireSupported(source, "entity fragment", true);
    }

    /**
     * Rewrites the boolean Iris isElytraFlying uniform onto the live int
     * catalog field. Pack sources read it as {@code !isElytraFlying}, which
     * VulkanMod's admitted int UBO type cannot express, so the negation
     * becomes an integer comparison and other uses cast to bool.
     */
    static String rewriteElytraFlyingBool(
            String source, UniformRegistry.ProgramInterface interfacePlan
    ) {
        if (source == null || !GlslTokenRewriter.containsIdentifier(source, "isElytraFlying")) {
            return source;
        }
        boolean live = interfacePlan != null && interfacePlan.executableUniforms().stream()
                .anyMatch(uniform -> uniform.name().equals("isElytraFlying"));
        if (!live) {
            return source;
        }
        String result = source.replaceAll(
                "(?s)\\b!\\s*isElytraFlying\\b", "chimeraIsElytraFlying == 0");
        return GlslTokenRewriter.replaceIdentifiers(result,
                Map.of("isElytraFlying", "(chimeraIsElytraFlying != 0)"));
    }

    static String chimeraFragmentVaryings(LegacyGlslConverter.TerrainVaryingLayout layout) {
        if (layout == null) return "";
        String declarations = "";
        for (String name : EntityVertexBridge.ENTITY_ID_NAMES) {
            String varying = EntityVertexBridge.entityIdVarying(name);
            if (layout.locations().containsKey(varying)) {
                declarations += "layout(location = " + layout.location(varying)
                        + ") flat in uint " + varying + ";\n";
            }
        }
        if (layout.locations().containsKey(EntityOverlayColor.UV_VARYING)) {
            declarations += "layout(location = " + layout.location(EntityOverlayColor.UV_VARYING)
                    + ") flat in ivec2 " + EntityOverlayColor.UV_VARYING + ";\n";
        }
        return declarations;
    }

    static String convertModernGeometryOutputs(String source) {
        Matcher matcher = MODERN_GEOMETRY_OUTPUT_DECL.matcher(source);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        if (names.isEmpty()) {
            return source;
        }
        if (names.size() != 1) {
            throw new IllegalArgumentException("modern terrain MRT output is unsupported");
        }
        String result = matcher.replaceAll("");
        String name = names.get(0);
        if (!name.equals("fragColor")) {
            result = GlslTokenRewriter.replaceIdentifiers(result, Map.of(name, "fragColor"));
        }
        // Keep the version directive at the start of the source. The final
        // declaration pass inserts its other generated declarations after it.
        return LegacyGlslConverter.insertAfterFirstLine(result, "layout(location = 0) out vec4 fragColor;\n");
    }

    static String removeLegacyShadowSamplerHelper(String source) {
        if (source == null || !source.contains("texture2DShadow")) return source;
        return source.replaceAll(
                "(?s)\\bfloat\\s+texture2DShadow\\s*\\(\\s*sampler2DShadow\\s+"
                        + "([A-Za-z_]\\w*)\\s*,\\s*vec3\\s+([A-Za-z_]\\w*)\\s*\\)\\s*"
                        + "\\{\\s*return\\s+shadow2D\\s*\\(\\s*\\1\\s*,\\s*\\2\\s*\\)"
                        + "\\s*\\.x\\s*;\\s*\\}", "");
    }

    static String rewriteSamplerDeclaration(String source, String name, int binding) {
        Pattern declaration = Pattern.compile(
                "(?:layout\\s*\\([^;{}]*\\)\\s*)?uniform\\s+"
                        + "((?:[iu]?sampler3D|sampler2D(?:Shadow)?))\\s+"
                        + Pattern.quote(name) + "\\s*;");
        Matcher matcher = declaration.matcher(source);
        if (!matcher.find()) {
            throw new IllegalArgumentException("sampler declaration is missing: " + name);
        }
        String replacement = "layout(binding = " + binding + ") uniform "
                + matcher.group(1) + " " + name + ";";
        return source.substring(0, matcher.start()) + replacement + source.substring(matcher.end());
    }

    static boolean hasSamplerDeclaration(String source, String name) {
        return Pattern.compile("(?:layout\\s*\\([^;{}]*\\)\\s*)?uniform\\s+"
                + "(?:[iu]?sampler3D|sampler2D(?:Shadow)?)\\s+"
                + Pattern.quote(name) + "\\s*;")
                .matcher(source).find();
    }

    /**
     * Iris injects these scheduler constants before a shadow shader runs.
     * Production sources receive these from PackEngineDefines before
     * preprocessing. Direct converter fixtures still use the canonical ABI.
     */
    static final String SHADOW_RENDER_STAGE_DEFINES = """
            #ifndef MC_RENDER_STAGE_TERRAIN_SOLID
            #define MC_RENDER_STAGE_TERRAIN_SOLID 8
            #endif
            #ifndef MC_RENDER_STAGE_TERRAIN_TRANSLUCENT
            #define MC_RENDER_STAGE_TERRAIN_TRANSLUCENT 17
            #endif
            #ifndef MC_RENDER_STAGE_TERRAIN_CUTOUT
            #define MC_RENDER_STAGE_TERRAIN_CUTOUT 10
            #endif
            #ifndef MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED
            #define MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED 9
            #endif
            """;
}
