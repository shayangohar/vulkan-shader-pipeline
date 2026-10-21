package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The translation defects the widened pack corpus found, each in the pack that showed it. */
final class CorpusCompileFixesTest {
    /** Kappa names a parameter after its lightDir uniform; the parameter and a local hide the uniform. */
    @Test
    void aParameterOrLocalNamedLikeAUniformHidesIt() {
        String source = """
                float f(vec3 lightDir) { return lightDir.x; }
                void main() { vec3 a = lightDir; { vec3 lightDir = a; a = lightDir; } a += lightDir + a.lightDir; }
                """;
        assertEquals("""
                float f(vec3 lightDir) { return lightDir.x; }
                void main() { vec3 a = u.lightDir; { vec3 lightDir = a; a = lightDir; } a += u.lightDir + a.lightDir; }
                """, GlslScopedReferences.replace(source, Map.of("lightDir", "u.lightDir")));
    }

    /** A struct member and a prototype's parameter are declarations, never references to the global. */
    @Test
    void membersAndPrototypeParametersKeepTheirNames() {
        String source = """
                struct Light { vec3 lightDir; };
                float f(vec3 lightDir);
                void main() { for (int lightDir = 0; lightDir < 1; lightDir++) {} vec3 a = lightDir; }
                """;
        assertEquals("""
                struct Light { vec3 lightDir; };
                float f(vec3 lightDir);
                void main() { for (int lightDir = 0; lightDir < 1; lightDir++) {} vec3 a = u.lightDir; }
                """, GlslScopedReferences.replace(source, Map.of("lightDir", "u.lightDir")));
    }

    /** RedHat writes gl_FrontColor and gl_TexCoord[0]; Vulkan has no such built-in varyings. */
    @Test
    void legacyColourAndTexCoordVaryingsBecomeOrdinaryOnes() {
        String vertex = LegacyShaderNormalizer.normalize("""
                #version 120
                void main() { gl_FrontColor = gl_Color; gl_TexCoord[0] = gl_MultiTexCoord0; }
                """, true).source();
        assertTrue(vertex.contains("varying vec4 chimera_FrontColor;"), vertex);
        assertTrue(vertex.contains("chimera_FrontColor = gl_Color") && vertex.contains("chimera_TexCoord0 ="),
                vertex);
        String fragment = LegacyShaderNormalizer.normalize("""
                #version 120
                void main() { gl_FragColor = gl_Color * gl_TexCoord[0]; }
                """, false).source();
        assertTrue(fragment.contains("chimera_FrontColor * chimera_TexCoord0"), fragment);
    }

    /** I Like Vanilla declares varyings through #define in_out in; Bloop defines packSnorm4x8 at 1.40. */
    @Test
    void qualifierMacrosExpandAndLateBuiltinsAreRenamedByVersion() {
        String fragment = LegacyShaderNormalizer.normalize(GlslMacroExpander.expand("""
                #version 330
                #define in_out in
                in_out vec2 texcoord;
                void main() {}
                """), false).source();
        assertTrue(fragment.contains("in vec2 texcoord;") && !fragment.contains("in_out"), fragment);
        String bloop = LegacyShaderNormalizer.normalize("""
                #version 140
                uint packSnorm4x8(vec4 x) { return 0u; }
                void main() { uint p = packSnorm4x8(vec4(0.0)); }
                """, false).source();
        assertTrue(bloop.contains("uint chimera_packSnorm4x8(vec4 x)")
                && bloop.contains("= chimera_packSnorm4x8(vec4(0.0))"), bloop);
        String tanh = "#version 130\nfloat tanh(float x) { return x; }\nvoid main() {}\n";
        assertFalse(LegacyShaderNormalizer.normalize(tanh, false).source().contains("chimera_tanh"),
                "a built-in the authored version already has stays as written");
        // RenderPearl, without a float16 extension: glslang declares unpackFloat2x16 at any version.
        String renderPearl = LegacyShaderNormalizer.normalize("""
                #version 460
                vec2 unpackFloat2x16(uint v) { return unpackHalf2x16(v); }
                void main() { vec2 octa = unpackFloat2x16(0u); }
                """, false).source();
        assertTrue(renderPearl.contains("vec2 chimera_unpackFloat2x16(uint v)")
                && renderPearl.contains("= chimera_unpackFloat2x16(0u)"), renderPearl);
    }

    /** Escheridia's SSAO calls any(isnan(occlusion)) on a float; GLSL's any takes only a bvecN. */
    @Test
    void reductionsOverAScalarGetScalarOverloads() {
        String escheridia = LegacyShaderNormalizer.normalize("""
                #version 430 compatibility
                float ssao(vec3 color) {
                    float occlusion = 0.0;
                    for (int i = 0; i < 4; i++) {
                        occlusion += color.r;
                        if (any(isnan(occlusion))) occlusion = 1.0;
                    }
                    return all(isnan(color.r)) ? 0.0 : occlusion;
                }
                void main() { gl_FragData[0] = vec4(ssao(vec3(1.0))); }
                """, false).source();
        assertTrue(escheridia.contains("bool any(bool chimeraValue) { return chimeraValue; }")
                && escheridia.contains("bool all(bool chimeraValue) { return chimeraValue; }"), escheridia);
        String vectors = """
                #version 430 compatibility
                void main() {
                    vec3 color = vec3(1.0);
                    if (any(isnan(color)) || all(greaterThan(color, vec3(0.0)))) color = vec3(0.0);
                    gl_FragData[0] = vec4(color, 1.0);
                }
                """;
        assertEquals(vectors, LegacyShaderNormalizer.normalize(vectors, false).source());
    }

    /**
     * Mellow writes its own Normal, which the entity preamble also declares, and its own Tangent.
     * Iris binds no attribute named Tangent, so the pack's name is its own and an input of that
     * name reads GL's unbound default.
     */
    @Test
    void packOutputsNamedLikeHostInputsAreRenamed() {
        String renamed = GlslVertexPreambles.renameHostNameCollisions(
                "out vec3 Normal;\nflat out vec4 Tangent;\nvoid main() { Normal = vec3(0.0); Tangent = vec4(0.0); }");
        assertTrue(renamed.contains("out vec3 chimera_pack_Normal;")
                && renamed.contains("flat out vec4 Tangent;") && renamed.contains("Tangent = vec4(0.0)"), renamed);
        assertEquals("const vec4 Tangent = vec4(0.0, 0.0, 0.0, 1.0);\nvoid main() { vec4 t = Tangent; }",
                UnboundAttributes.substitute("in vec4 Tangent;\nvoid main() { vec4 t = Tangent; }",
                        UnboundAttributes::irisUnbound), "an input Tangent reads the unbound default");
    }

    /** Bloop ends its unused uniform declarations with a comment; precision qualifiers are allowed. */
    @Test
    void unreadUniformsGoWhateverFollowsTheSemicolon() {
        String stripped = LegacyGlslConverter.stripUnusableUniformDeclarations("""
                uniform float maxLuminance;      // absolute
                uniform highp float spare;
                uniform float used;
                void main() { float x = used; }
                """, List.of());
        assertFalse(stripped.contains("maxLuminance") || stripped.contains("spare"), stripped);
        assertTrue(stripped.contains("uniform float used;"), stripped);
        assertTrue(UniformRegistry.isLocallyDeclared("vec3 a, lightDir = vec3(0.0);", "lightDir"),
                "a later name of a declarator list is declared");
    }

    /** Spring reads a sampler in a global initializer; Noble reads one inside a macro body. */
    @Test
    void globalInitializersAndUsedMacroBodiesReadSamplers() {
        Set<String> live = GlslResourceUsage.liveSamplers(GlslMacroExpander.expand("""
                uniform sampler2D colortex4;
                uniform sampler2D colortex5;
                uniform sampler2D colortex6;
                #define SAMPLE(uv) texture(colortex5, uv)
                #define UNUSED texture(colortex6, vec2(0.0))
                vec2 texel = 1.0 / vec2(textureSize(colortex4, 0));
                void main() { vec4 c = SAMPLE(texel); }
                """));
        assertEquals(Set.of("colortex4", "colortex5"), live);
    }

    /** Noble defines projectionMatrix as a macro; Iris's core name never reaches the transformer. */
    @Test
    void aCoreNameDefinedAsAMacroIsThePacks() {
        String source = """
                #version 330 core
                #define projectionMatrix gbufferProjection
                void main() { vec4 p = projectionMatrix[0]; }
                """;
        String prepared = IrisCoreProfile.prepareIrisCoreProfile(GlslMacroExpander.expand(source), null, false,
                "ivec2(0)", true, IrisCoreProfile.Patch.VANILLA);
        assertFalse(prepared.contains("gl_ProjectionMatrix"), prepared);
        assertTrue(prepared.contains("vec4 p = gbufferProjection[0];"), prepared);
    }

    /** I Like Vanilla writes integer data through a modern uvec4 output. */
    @Test
    void integerModernOutputsAreRouted() {
        PostTargetPlan.ParseResult parsed = PostTargetPlan.parse("composite", """
                #version 330
                /* RENDERTARGETS: 3 */
                layout(location = 0) out uvec4 packedData;
                void main() { packedData = uvec4(1u); }
                """);
        assertEquals(List.of(3), parsed.plan().targetSlots(), parsed.deviations().toString());
    }

    /** Insanity's RGB16 and Bloop's R16 targets have Vulkan images: RGB16 pads its alpha. */
    @Test
    void sixteenBitNormalizedTargetsAllocate(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), "colortex6Format=RGB16\n");
        PackConfig.PackConfigData config = PackConfig.parse(List.of(new PackProgram("deferred1",
                "#version 120\nconst int colortex3Format = R16;\nconst int colortex4Format = RG16;",
                shaders.resolve("deferred1.fsh"), null, null)), shaders);
        assertEquals(Map.of(3, 70, 4, 77, 6, 91), config.colortexFormats(), "R16, RG16 and RGBA16 UNORM");
        assertEquals(Set.of(6), config.paddedAlphaTargets());
        assertTrue(config.deviations().stream().noneMatch(value -> value.startsWith("POST_TARGET_FORMAT_UNSUPPORTED")),
                config.deviations().toString());
    }

    /**
     * Every format in Iris's InternalTextureFormat list has a target image, the integer ones too.
     * superDuperVanilla's RGB16_SNORM colortex1 had none, and every program writing it fell back;
     * so did every program writing Noble's RGBA32UI colortex1.
     */
    @Test
    void everyIrisColourFormatHasATarget(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Map<String, Integer> expected = Map.ofEntries(Map.entry("RGBA", 37), Map.entry("RGB16_SNORM", 109),
                Map.entry("RGBA16_SNORM", 109), Map.entry("R16_SNORM", 100), Map.entry("RG8_SNORM", 83),
                Map.entry("RGB32F", 109), Map.entry("RGB565", 37), Map.entry("RGB9_E5", 97),
                Map.entry("RGBA32UI", 107), Map.entry("R32UI", 98), Map.entry("RG16I", 82),
                Map.entry("RGB8UI", 41), Map.entry("RGB10_A2UI", 68));
        Set<String> padded = Set.of("RGB8", "RGB8_SNORM", "RGB16", "RGB16_SNORM", "RGB16F", "RGB32F", "R3_G3_B2",
                "RGB565", "R11F_G11F_B10F", "RGB9_E5", "RGB8UI", "RGB8I", "RGB16UI", "RGB16I", "RGB32UI", "RGB32I");
        List<String> tokens = List.of("RGBA", "R8", "RG8", "RGB8", "RGBA8", "R8_SNORM", "RG8_SNORM", "RGB8_SNORM",
                "RGBA8_SNORM", "R16", "RG16", "RGB16", "RGBA16", "R16_SNORM", "RG16_SNORM", "RGB16_SNORM",
                "RGBA16_SNORM", "R16F", "RG16F", "RGB16F", "RGBA16F", "R32F", "RG32F", "RGB32F", "RGBA32F",
                "RGBA2", "RGBA4", "R3_G3_B2", "RGB5_A1", "RGB565", "RGB10_A2", "R11F_G11F_B10F", "RGB9_E5",
                "R8I", "RG8I", "RGB8I", "RGBA8I", "R8UI", "RG8UI", "RGB8UI", "RGBA8UI", "R16I", "RG16I", "RGB16I",
                "RGBA16I", "R16UI", "RG16UI", "RGB16UI", "RGBA16UI", "R32I", "RG32I", "RGB32I", "RGBA32I",
                "R32UI", "RG32UI", "RGB32UI", "RGBA32UI", "RGB10_A2UI");
        for (String token : tokens) {
            Files.writeString(shaders.resolve("shaders.properties"), "colortex1Format=" + token + "\n");
            PackConfig.PackConfigData config = PackConfig.parse(List.of(), shaders);
            Integer format = config.colortexFormats().get(1);
            assertTrue(format != null && PackConfig.formatBytes(format) > 0, token + " has a target: " + format);
            if (expected.containsKey(token)) assertEquals(expected.get(token), format, token);
            assertEquals(padded.contains(token), config.paddedAlphaTargets().contains(1), token + " pads its alpha");
            assertEquals(token.endsWith("I"), IrisColorFormats.integer(format), token + " reads as integers");
            // Iris InternalTextureFormat.fromString reads a name in any case.
            assertEquals(IrisColorFormats.of(token), IrisColorFormats.of(token.toLowerCase(Locale.ROOT)), token);
        }
    }

    /** A program writes an integer target through a uvec4 output, and a later pass reads it as usampler2D. */
    @Test
    void integerTargetsAreWrittenAndReadAsIntegers(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), "colortex1Format=RGBA32UI\n");
        Files.writeString(shaders.resolve("gbuffers_terrain.vsh"), """
                #version 330 compatibility
                out vec2 uv;
                void main() { uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("gbuffers_terrain.fsh"), """
                #version 330 compatibility
                uniform sampler2D gtexture;
                in vec2 uv;
                /* RENDERTARGETS: 0,1 */
                layout(location = 0) out vec4 color;
                layout(location = 1) out uvec4 material;
                void main() { color = texture(gtexture, uv); material = uvec4(7u, 0u, 0u, 1u); }
                """);
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 330 compatibility
                uniform sampler2D colortex0;
                uniform usampler2D colortex1;
                /* RENDERTARGETS: 0 */
                layout(location = 0) out vec4 color;
                void main() {
                    uint id = texelFetch(colortex1, ivec2(gl_FragCoord.xy), 0).r;
                    color = texelFetch(colortex0, ivec2(gl_FragCoord.xy), 0) * float(id);
                }
                """);
        Files.writeString(shaders.resolve("final.fsh"), """
                #version 120
                uniform sampler2D colortex0;
                void main() { gl_FragColor = texture2D(colortex0, vec2(0.5)); }
                """);
        PackPlan plan = PackProbe.analyze(root).plan();
        for (String program : List.of("gbuffers_terrain", "composite")) {
            PackProgramPlan planned = plan.program(program);
            assertTrue(planned != null && planned.executable() && planned.deviations().stream()
                            .noneMatch(value -> value.startsWith("POST_TARGET_FORMAT_UNSUPPORTED")
                                    || value.contains("SAMPLER_NOT_MAPPED")),
                    program + " must plan over the integer target: "
                            + (planned == null ? "missing" : planned.deviations()));
        }
    }

    /**
     * Iris gives every gbuffers program depthtex0, depthtex1 and depthtex2. superDuperVanilla's
     * gbuffers_block reads depthtex0 for its end portal's depth, and was refused with
     * ENTITY_SAMPLER_UNSUPPORTED, which turned the whole pack to vanilla.
     */
    @Test
    void entityFamilyProgramsReadEveryDepthTexture(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_block.vsh"), """
                #version 120
                varying vec2 uv;
                void main() {
                    uv = gl_MultiTexCoord0.xy;
                    gl_Position = ftransform();
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_block.fsh"), """
                #version 120
                uniform sampler2D texture;
                uniform sampler2D depthtex0;
                uniform sampler2D depthtex2;
                uniform int blockEntityId;
                varying vec2 uv;
                void main() {
                    float behind = texture2D(depthtex0, gl_FragCoord.xy / vec2(1920.0, 1080.0)).x;
                    float portal = blockEntityId == 12000 ? gl_FragCoord.z - behind : 0.0;
                    gl_FragData[0] = texture2D(texture, uv) + portal + texture2D(depthtex2, uv).x;
                }
                """);
        Files.writeString(shaders.resolve("final.fsh"), """
                #version 120
                uniform sampler2D colortex0;
                void main() { gl_FragColor = texture2D(colortex0, vec2(0.5)); }
                """);
        var plan = PackProbe.analyze(root).plan().program("gbuffers_block");
        assertTrue(plan != null && plan.executable() && plan.deviations().stream()
                        .noneMatch(value -> value.startsWith("ENTITY_SAMPLER_UNSUPPORTED")),
                "gbuffers_block reads depthtex0 and depthtex2: " + (plan == null ? "missing" : plan.deviations()));
    }

    /** Pegasus keeps includes in shaders/shaders/; the shaders folder at the zip's root is the pack. */
    @Test
    void aShadersFolderAtTheRootWinsOverANestedOne() {
        assertEquals("", PackSource.findShadersPrefix(List.of("shaders/composite.fsh", "shaders/shaders/clouds.glsl")));
        assertEquals("Pack/", PackSource.findShadersPrefix(List.of("Pack/shaders/final.fsh")));
        assertEquals(null, PackSource.findShadersPrefix(List.of("A/shaders/final.fsh", "B/shaders/final.fsh")),
                "two nested packs stay ambiguous");
    }

    /** Mellow, RedHat and Sildur's sample noisetex without texture.noise: Iris generates it. */
    @Test
    void aPackWithoutTextureNoiseSamplesGeneratedNoise(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.createDirectories(shaders.resolve("tex"));
        Files.writeString(shaders.resolve("tex/noise.png"), "not read without texture.noise");
        Files.writeString(shaders.resolve("final.fsh"), """
                #version 120
                const int noiseTextureResolution = 128;
                uniform sampler2D noisetex;
                void main() { gl_FragColor = texture2D(noisetex, gl_FragCoord.xy / 128.0); }
                """);
        PackPlan plan = PackProbe.analyze(root).plan();
        PackResourceBinding noise = plan.resources().bindings("final").stream()
                .filter(binding -> binding.sampler().equals("noisetex")).findFirst().orElseThrow();
        assertEquals(PackResourceStatus.GENERATED, noise.status(), noise.deviations().toString());
        assertEquals(128, PackResourcePlan.generatedNoiseSize(noise.source()));
        assertTrue(plan.shouldAttempt("final"), "the program samples the generated noise");
    }

    /** Sildur's terrain and water pass varying mat3 tbnMatrix; the fragment reads it at the layout's location. */
    @Test
    void legacyMatrixVaryingsTakeTheirLayoutLocation() {
        LegacyGlslConverter.TerrainVaryingLayout layout = new LegacyGlslConverter.TerrainVaryingLayout(
                Map.of("color", "vec4", "tbnMatrix", "mat3"), Map.of("color", 0, "tbnMatrix", 1));
        String converted = LegacyGlslConverter.convertVaryings(
                "#version 120\nvarying vec4 color;\nvarying mat3 tbnMatrix;\nvoid main() {}\n",
                true, layout, null, UniformRegistry.Stage.GEOMETRY);
        assertTrue(converted.contains("layout(location = 1) in mat3 tbnMatrix;"), converted);
    }

    /** Noble's composite99.csh runs before composite99's draw; it is not a stage of that program. */
    @Test
    void passComputesShareTheirPassName() {
        assertTrue(PassComputePlan.isPassComputeProgram("composite99"));
        assertTrue(PassComputePlan.isPassComputeProgram("deferred1"));
        assertTrue(PassComputePlan.isPassComputeProgram("prepare"));
        assertFalse(PassComputePlan.isPassComputeProgram("gbuffers_terrain"));
        assertFalse(PassComputePlan.isPassComputeProgram("final"));
    }

    /**
     * Reverie's shadow lanes and Mellow's entities write their own Normal, a name the entity-style
     * preambles declare. The plan renames it in both stages, so the varying keeps its pair.
     */
    @Test
    void aVaryingNamedLikeAHostInputIsRenamedInBothStages(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_entities.vsh"), """
                #version 330 compatibility
                out vec3 Normal;
                void main() { Normal = gl_Normal; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("gbuffers_entities.fsh"), """
                #version 330 compatibility
                in vec3 Normal;
                /* RENDERTARGETS: 0 */
                layout(location = 0) out vec4 color;
                void main() { color = vec4(Normal, 1.0); }
                """);
        PackProgramPlan entities = PackProbe.analyze(root).plan().program("gbuffers_entities");
        assertTrue(entities.executable(), entities.deviations().toString());
        assertTrue(entities.convertedFragment().contains("chimera_pack_Normal")
                && entities.convertedVertex().contains("chimera_pack_Normal"), entities.convertedFragment());
    }

    /**
     * Alpha Piscium writes out uint frag_materialID and reads it flat. Since GLSL 4.40 the
     * fragment's interpolation decides, and an integer vertex output needs no flat.
     */
    @Test
    void anEntityVaryingTakesTheFragmentsInterpolation(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_entities.vsh"), """
                #version 460 compatibility
                out uint materialId;
                void main() { materialId = 7u; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("gbuffers_entities.fsh"), """
                #version 460 compatibility
                flat in uint materialId;
                /* RENDERTARGETS: 0 */
                layout(location = 0) out vec4 color;
                void main() { color = vec4(float(materialId)); }
                """);
        PackProgramPlan entities = PackProbe.analyze(root).plan().program("gbuffers_entities");
        assertTrue(entities.executable(), entities.deviations().toString());
    }

    /** An unread uniform removed whole takes its layout qualifier, which would qualify the next line. */
    @Test
    void aRemovedUniformTakesItsLayout() {
        String source = "#version 430\nlayout(location = 3) uniform float wetness;\nconst bool clear = false;\n"
                + "void main() {}\n";
        UniformRegistry.ProgramInterface plan = InterfacePlanner.planInternal(source, UniformRegistry.Stage.POST,
                false, true);
        String removed = UniformRegistry.removeUniformDeclarations(source, plan);
        assertFalse(removed.contains("layout(location = 3)") || removed.contains("wetness"), removed);
        assertTrue(removed.contains("const bool clear = false;"), removed);
    }

    /**
     * Glimmer's and Reverie's entities include their storage blocks, laid out std430, and Reverie
     * declares a sampler named image0Sampler. Neither is a pack-authored vertex layout or an image.
     */
    @Test
    void anEntityVertexKeepsItsStorageBlocksAndImageNamedSamplers() {
        String vertex = """
                #version 430
                layout(std430, binding = 0) buffer environmentData { vec4 sky; } environment;
                uniform sampler2D image0Sampler;
                void main() { gl_Position = environment.sky; }
                """;
        EntityVertexFeatureCheck.rejectEntityVertexFeatures(vertex, true, true);
        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class, () -> EntityVertexFeatureCheck.rejectEntityVertexFeatures(
                        vertex.replace("uniform sampler2D image0Sampler;", "layout(rgba8) uniform image2D img;")
                                .replace("environment.sky", "imageLoad(img, ivec2(0))"), true, true));
        assertTrue(refused.getMessage().contains("unsupported entity vertex"), refused.getMessage());
    }

    /**
     * Escheridia's vertex and fragment stages include one header declaring entityColor. Iris deletes
     * the uniform from every stage; a vertex copy left in the shared block met the fragment's global.
     */
    @Test
    void anEntityVertexLosesTheEntityColorItDoesNotRead(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        // A shared helper names it too, but main never calls the helper.
        Files.writeString(shaders.resolve("gbuffers_entities.vsh"), """
                #version 120
                uniform vec4 entityColor;
                varying vec2 uv;
                vec3 tinted(vec3 color) { return mix(color, entityColor.rgb, entityColor.a); }
                void main() { uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("gbuffers_entities.fsh"), """
                #version 120
                uniform sampler2D texture;
                uniform vec4 entityColor;
                varying vec2 uv;
                void main() {
                    vec4 color = texture2D(texture, uv);
                    gl_FragData[0] = vec4(mix(color.rgb, entityColor.rgb, entityColor.a), color.a);
                }
                """);
        PackProgramPlan entities = PackProbe.analyze(root).plan().program("gbuffers_entities");
        assertTrue(entities.executable() && !entities.deviations().contains("UNIFORM_DEFAULTED:entityColor"),
                entities.deviations().toString());
        assertFalse(entities.convertedVertex().contains("entityColor"), entities.convertedVertex());
    }

    /**
     * Shrimple's entity fragment reads the entityId uniform and also names a parameter entityId
     * (GetSceneEntityLightColor(const in int entityId)). Only the uniform becomes the varying.
     */
    @Test
    void aParameterNamedEntityIdKeepsItsName(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_entities.vsh"), """
                #version 120
                varying vec2 uv;
                void main() { uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("gbuffers_entities.fsh"), """
                #version 120
                uniform sampler2D texture;
                uniform int entityId;
                varying vec2 uv;
                vec4 lightColor(const in int entityId) { return vec4(float(entityId) / 255.0); }
                void main() { gl_FragData[0] = texture2D(texture, uv) * lightColor(entityId); }
                """);
        PackProgramPlan entities = PackProbe.analyze(root).plan().program("gbuffers_entities");
        String fragment = entities.convertedFragment();
        assertTrue(entities.executable() && fragment.contains("lightColor(const in int entityId)")
                && fragment.contains("float(entityId) / 255.0") && fragment.contains("lightColor(int(chimera"),
                entities.deviations() + "\n" + fragment);
    }

    /**
     * Escheridia's programs share a header declaring textures most of them never read. GL leaves
     * such a sampler inactive, so one with no slot is dropped rather than refused.
     */
    @Test
    void anUnreadSamplerWithoutASlotIsInactive(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_entities.vsh"), """
                #version 120
                varying vec2 uv;
                void main() { uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("gbuffers_entities.fsh"), """
                #version 120
                uniform sampler2D texture;
                uniform sampler2D clouds;
                varying vec2 uv;
                void main() { gl_FragData[0] = texture2D(texture, uv); }
                """);
        PackProgramPlan entities = PackProbe.analyze(root).plan().program("gbuffers_entities");
        assertTrue(entities.executable() && entities.deviations().contains("SAMPLER_DECLARATION_UNUSED:clouds"),
                entities.deviations().toString());
        assertFalse(entities.convertedFragment().contains("clouds"), entities.convertedFragment());
    }
}
