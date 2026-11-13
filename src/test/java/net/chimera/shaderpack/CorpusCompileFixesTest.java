package net.chimera.shaderpack;

import net.chimera.render.shader.GlslCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The translation defects the widened pack corpus found, each in the pack that showed it. */
final class CorpusCompileFixesTest {
    /**
     * Kappa names a parameter after its lightDir uniform; the parameter and a local hide the uniform.
     * A struct member and a prototype's parameter are declarations, never references to the global.
     */
    @Test
    void aDeclarationNamedLikeAUniformHidesIt() {
        String source = """
                float f(vec3 lightDir) { return lightDir.x; }
                void main() { vec3 a = lightDir; { vec3 lightDir = a; a = lightDir; } a += lightDir + a.lightDir; }
                """;
        assertEquals("""
                float f(vec3 lightDir) { return lightDir.x; }
                void main() { vec3 a = u.lightDir; { vec3 lightDir = a; a = lightDir; } a += u.lightDir + a.lightDir; }
                """, GlslScopedReferences.replace(source, Map.of("lightDir", "u.lightDir")));
        String members = """
                struct Light { vec3 lightDir; };
                float f(vec3 lightDir);
                void main() { for (int lightDir = 0; lightDir < 1; lightDir++) {} vec3 a = lightDir; }
                """;
        assertEquals("""
                struct Light { vec3 lightDir; };
                float f(vec3 lightDir);
                void main() { for (int lightDir = 0; lightDir < 1; lightDir++) {} vec3 a = u.lightDir; }
                """, GlslScopedReferences.replace(members, Map.of("lightDir", "u.lightDir")));
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

    /**
     * Noble passes gl_FragDepth to its shadow lookup and never writes it. GL compiles the read;
     * Vulkan's validator rejects FragDepth without DepthReplacing, which glslang declares only for a
     * write. A read of an unwritten one reads the rasterized depth; a shader that may write it keeps
     * its reads, and a redeclaration stays.
     */
    @Test
    void anUnwrittenFragDepthReadsTheRasterizedDepth() {
        String header = "#version 430\nlayout(location = 0) out vec4 color;\n";
        String noble = LegacyShaderNormalizer.normalize(header + """
                vec4 shadow(vec3 position, float depth) { return vec4(depth < 0.56 ? 1.0 : 0.5); }
                void main() { color = shadow(vec3(0.0), gl_FragDepth) * max(gl_FragDepth, 0.5); }
                """, false).source();
        assertTrue(noble.contains("shadow(vec3(0.0), gl_FragCoord.z) * max(gl_FragCoord.z, 0.5)")
                && !noble.contains("gl_FragDepth"), noble);
        MemoryUtil.memFree(GlslCompiler.compile("noble.fsh", noble, Shaderc.shaderc_glsl_fragment_shader,
                GlslCompiler::optimize));
        for (String writes : List.of(
                "void main() { gl_FragDepth = gl_FragCoord.z; color = vec4(gl_FragDepth); }",
                "void main() { gl_FragDepth += 0.1; color = vec4(gl_FragDepth); }",
                "void store(out float d) { d = 0.5; }\n"
                        + "void main() { store(gl_FragDepth); color = vec4(gl_FragDepth); }",
                "void main() { color = vec4(modf(1.5, gl_FragDepth)); }")) {
            String kept = LegacyShaderNormalizer.normalize(header + writes + "\n", false).source();
            assertTrue(kept.contains(writes), kept);
        }
        String redeclared = LegacyShaderNormalizer.normalize(header
                + "layout(depth_greater) out float gl_FragDepth;\nvoid main() { color = vec4(gl_FragDepth); }\n",
                false).source();
        assertTrue(redeclared.contains("layout(depth_greater) out float gl_FragDepth;")
                && redeclared.contains("color = vec4(gl_FragCoord.z);"), redeclared);
        MemoryUtil.memFree(GlslCompiler.compile("redeclared.fsh", redeclared, Shaderc.shaderc_glsl_fragment_shader,
                GlslCompiler::optimize));
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

    /** RedHat v34's final names a variable sample under DOF; GLSL reserves the word from 4.00. */
    @Test
    void laterKeywordsUsedAsNamesAreRenamedByVersion() {
        String redHat = LegacyShaderNormalizer.normalize("""
                #version 130
                void main() { vec4 sample = vec4(0.0); sample += 1.0; gl_FragData[0] = sample; }
                """, false).source();
        assertTrue(redHat.contains("vec4 chimera_sample = vec4(0.0)") && redHat.contains("chimera_sample += 1.0")
                && redHat.contains("= chimera_sample;"), redHat);
        String qualifier = LegacyShaderNormalizer.normalize(
                "#version 400\nsample in vec4 color;\nvoid main() {}\n", false).source();
        assertTrue(qualifier.contains("sample in vec4 color;") && !qualifier.contains("chimera_"),
                "a keyword the authored version has stays as written: " + qualifier);
        // Glimmer writes storage blocks at #version 330: a keyword use stays as written.
        String glimmer = LegacyShaderNormalizer.normalize("""
                #version 330
                layout(std430) buffer;
                layout(std430) buffer Data { float value; };
                layout(rgba8) uniform restrict writeonly image2D target;
                void main() {}
                """, false).source();
        assertTrue(glimmer.contains("layout(std430) buffer;") && glimmer.contains("layout(std430) buffer Data")
                && glimmer.contains("restrict writeonly image2D") && !glimmer.contains("chimera_"), glimmer);
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
     * Audit GD9: any(isnan(f) || isinf(f)) on a float. NVIDIA's GL compiler takes it, so Iris loads
     * it; VectorLogicalOperators turns it into an or of any() calls, each on a scalar, which compile
     * only with the scalar overloads.
     */
    @Test
    void reductionsOverAnOrOfScalarTestsGetScalarOverloads() {
        String source = LegacyShaderNormalizer.normalize("""
                #version 430
                layout(location = 0) out vec4 color;
                void main() {
                    float f = gl_FragCoord.x - 1.0;
                    vec3 v = vec3(f);
                    color = vec4(any(isnan(f) || isinf(f)) ? 1.0 : 0.0);
                    if (all(isnan(v.x) && isinf(f))) color = vec4(0.5);
                }
                """, false).source();
        assertTrue(source.contains("bool any(bool chimeraValue) { return chimeraValue; }")
                && source.contains("bool all(bool chimeraValue) { return chimeraValue; }"), source);
        assertNull(PackCompileTest.compile(source, false), "the stage must compile as Iris's driver compiles it");
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
        assertTrue(InterfacePlanner.isLocallyDeclared("vec3 a, lightDir = vec3(0.0);", "lightDir"),
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

    /**
     * RenderPearl writes its deferred-ignore flag to colortex2.y as {@code layout(location = 1,
     * component = 1) out uint}. The generated output keeps the component: without it the flag
     * landed in .x, and deferred lit the beacon beam black.
     */
    @Test
    void aModernOutputKeepsItsComponent() {
        String source = """
                #version 460
                /* RENDERTARGETS: 1,2 */
                layout(location = 0) out vec3 colortex1;
                layout(location = 1, component = 1) out uint colortex2;
                void main() { colortex1 = vec3(1.0); colortex2 = 0xe6000000u; }
                """;
        PostTargetPlan plan = PostTargetPlan.parse("gbuffers_beaconbeam", source).plan();
        assertEquals(1, plan.outputComponent(1));
        assertEquals(0, plan.outputComponent(0));
        String declarations = FragmentConverter.postOutputDeclarations(plan);
        assertTrue(declarations.contains("layout(location = 0) out vec3 chimeraFragColor0;")
                && declarations.contains("layout(location = 1, component = 1) out uint chimeraFragColor1;"),
                declarations);
        String converted = FragmentConverter.convertPostOutputs(source, plan);
        String module = "#version 460\n" + declarations + converted.substring(converted.indexOf('\n') + 1);
        MemoryUtil.memFree(GlslCompiler.compile("gbuffers_beaconbeam.fsh", module,
                Shaderc.shaderc_glsl_fragment_shader, options -> {}));
    }

    /**
     * Every format in Iris's InternalTextureFormat list has a target image, the integer ones too.
     * superDuperVanilla's RGB16_SNORM colortex1 had none, and every program writing it fell back;
     * so did every program writing Noble's RGBA32UI colortex1. Insanity's RGB16 and Bloop's R16 are
     * 16-bit UNORM images, and RGB16 pads its alpha. Signed normalized formats are SNORM images, so
     * a write clamps to [-1, 1]: as RGBA16F, Rethinking Voxels' RGBA8_SNORM colortex5 kept
     * reflections brighter than Iris's.
     */
    @Test
    void everyIrisColourFormatHasATarget(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Map<String, Integer> expected = Map.ofEntries(Map.entry("RGBA", 37), Map.entry("R8_SNORM", 10),
                Map.entry("RG8_SNORM", 17), Map.entry("RGB8_SNORM", 38), Map.entry("RGBA8_SNORM", 38),
                Map.entry("R16_SNORM", 71), Map.entry("RG16_SNORM", 78), Map.entry("RGB16_SNORM", 92),
                Map.entry("RGBA16_SNORM", 92),
                Map.entry("RGB32F", 109), Map.entry("RGB565", 37), Map.entry("RGB9_E5", 97),
                Map.entry("RGBA32UI", 107), Map.entry("R32UI", 98), Map.entry("RG16I", 82),
                Map.entry("RGB8UI", 41), Map.entry("RGB10_A2UI", 68), Map.entry("R11F_G11F_B10F", 122),
                Map.entry("R16", 70), Map.entry("RG16", 77), Map.entry("RGB16", 91));
        Set<String> padded = Set.of("RGB8", "RGB8_SNORM", "RGB16", "RGB16_SNORM", "RGB16F", "RGB32F", "R3_G3_B2",
                "RGB565", "RGB9_E5", "RGB8UI", "RGB8I", "RGB16UI", "RGB16I", "RGB32UI", "RGB32I");
        List<String> tokens = List.of("RGBA", "R8", "RG8", "RGB8", "RGBA8", "R8_SNORM", "RG8_SNORM", "RGB8_SNORM",
                "RGBA8_SNORM", "R16", "RG16", "RGB16", "RGBA16", "R16_SNORM", "RG16_SNORM", "RGB16_SNORM",
                "RGBA16_SNORM", "R16F", "RG16F", "RGB16F", "RGBA16F", "R32F", "RG32F", "RGB32F", "RGBA32F",
                "RGBA2", "RGBA4", "R3_G3_B2", "RGB5_A1", "RGB565", "RGB10_A2", "R11F_G11F_B10F", "RGB9_E5",
                "R8I", "RG8I", "RGB8I", "RGBA8I", "R8UI", "RG8UI", "RGB8UI", "RGBA8UI", "R16I", "RG16I", "RGB16I",
                "RGBA16I", "R16UI", "RG16UI", "RGB16UI", "RGBA16UI", "R32I", "RG32I", "RGB32I", "RGBA32I",
                "R32UI", "RG32UI", "RGB32UI", "RGBA32UI", "RGB10_A2UI");
        for (String token : tokens) {
            PackConfig.PackConfigData config = PackConfig.parse(List.of(new PackProgram("composite",
                    "#version 120\nconst int colortex1Format = " + token + ";",
                    shaders.resolve("composite.fsh"), null, null)), shaders);
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
                /*
                const int colortex1Format = RGBA32UI;
                */
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
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackPlan plan = analysis.plan();
        for (String program : List.of("gbuffers_terrain", "composite")) {
            PackProgramPlan planned = plan.program(program);
            assertTrue(planned != null && planned.executable() && planned.deviations().stream()
                            .noneMatch(value -> value.startsWith("POST_TARGET_FORMAT_UNSUPPORTED")
                                    || value.contains("SAMPLER_NOT_MAPPED")),
                    program + " must plan over the integer target: "
                            + (planned == null ? "missing" : planned.deviations()));
        }
        // GL leaves Iris's float clear of colortex1 undefined; Chimera's integer clear is its own choice.
        assertTrue(analysis.config().deviations().contains("POST_TARGET_INTEGER_CLEAR_CHIMERA:colortex1"),
                analysis.config().deviations().toString());
    }

    /**
     * GL refuses glGenerateMipmap on an integer format, so Iris's colortex1 chain stays at its base
     * level; Chimera downsamples it nearest. The report names the choice on the program that asks.
     */
    @Test
    void anIntegerMipChainIsReportedAsChimeras(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 330 compatibility
                /*
                const int colortex1Format = R32UI;
                const bool colortex1Clear = false;
                */
                const bool colortex1MipmapEnabled = true;
                uniform sampler2D colortex0;
                /* RENDERTARGETS: 0,1 */
                layout(location = 0) out vec4 color;
                layout(location = 1) out uint id;
                void main() { color = texture(colortex0, vec2(0.5)); id = 3u; }
                """);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        List<String> composite = analysis.report().program("composite").deviations();
        assertTrue(composite.contains("POST_TARGET_INTEGER_MIPMAP_CHIMERA:colortex1"), composite.toString());
        assertTrue(analysis.config().deviations().stream().noneMatch(value -> value.contains("INTEGER_CLEAR")),
                "colortex1 does not clear");
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

    /**
     * Kappa's translucent program declares its outputs out of location order, translucentColor
     * first at location 2. Numbered in declaration order, the glass colour wrote colortex1 and the
     * normals colortex2, so composite read normals as the glass: stained glass drew black.
     */
    @Test
    void aFragmentOutputKeepsItsDeclaredLocation(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_water.vsh"), """
                #version 430 compatibility
                out vec2 waterUv;
                void main() { waterUv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("gbuffers_water.fsh"), """
                #version 430 compatibility
                in vec2 waterUv;
                /* RENDERTARGETS: 1,2,3,4 */
                layout(location = 2) out vec4 translucentColor;
                layout(location = 0) out vec4 sceneDataA;
                layout(location = 1) out vec4 sceneDataB;
                layout(location = 3) out vec4 reflectionAux;
                void main() {
                    translucentColor = vec4(waterUv, 0.2, 0.5);
                    sceneDataA = vec4(0.0);
                    sceneDataB = vec4(1.0);
                    reflectionAux = vec4(0.3);
                }
                """);
        PackProgramPlan water = PackProbe.analyze(root).plan().program("gbuffers_water");
        assertTrue(water.executable(), water.deviations().toString());
        String fragment = water.convertedFragment();
        assertTrue(fragment.contains("chimeraFragColor2 = vec4(") && fragment.contains("0.2, 0.5)"), fragment);
        assertTrue(fragment.contains("chimeraFragColor0 = vec4(0.0)"), fragment);
        assertTrue(fragment.contains("chimeraFragColor1 = vec4(1.0)"), fragment);
        assertTrue(fragment.contains("chimeraFragColor3 = vec4(0.3)"), fragment);
    }

    /** An unread uniform removed whole takes its layout qualifier, which would qualify the next line. */
    @Test
    void aRemovedUniformTakesItsLayout() {
        String source = "#version 430\nlayout(location = 3) uniform float wetness;\nconst bool clear = false;\n"
                + "void main() {}\n";
        UniformRegistry.ProgramInterface plan = InterfacePlanner.planInternal(source, UniformRegistry.Stage.POST,
                false, true);
        String removed = InterfacePlanner.removeUniformDeclarations(source, plan);
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
        EntityVertexBridge.rejectEntityVertexFeatures(vertex, true, true, Set.of());
        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class, () -> EntityVertexBridge.rejectEntityVertexFeatures(
                        vertex.replace("uniform sampler2D image0Sampler;", "layout(rgba8) uniform image2D img;")
                                .replace("environment.sky", "imageLoad(img, ivec2(0))"), true, true, Set.of()));
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
        PackPlan plan = PackProbe.analyze(root).plan();
        PackProgramPlan entities = plan.program("gbuffers_entities");
        String fragment = entities.convertedFragment();
        assertTrue(entities.executable() && fragment.contains("lightColor(const in int entityId)")
                && fragment.contains("float(entityId) / 255.0") && fragment.contains("lightColor(int(chimera"),
                entities.deviations() + "\n" + fragment);
        // text_bg draws the same source on a host layout, where entityId is the per-draw id.
        PackProgramPlan textBackground = plan.program("text_bg");
        String hostFragment = textBackground.convertedFragment();
        assertTrue(textBackground.executable() && hostFragment.contains("lightColor(const in int entityId)")
                        && hostFragment.contains("float(entityId) / 255.0")
                        && hostFragment.contains("lightColor((" + UniformRegistry.HOST_ENTITY_ID + "))"),
                textBackground.deviations() + "\n" + hostFragment);
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

    /**
     * Iris declares mc_chunkFade in every Sodium terrain and shadow vertex stage (SodiumTransformer
     * .injectVertInit), 1.0 for a section not fading in; VulkanMod fades none, so it reads 1.0.
     */
    @Test
    void terrainVerticesReadChunkFade(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_terrain.vsh"), """
                #version 120
                varying vec2 uv;
                varying float fade;
                void main() { uv = gl_MultiTexCoord0.xy; fade = mc_chunkFade; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("gbuffers_terrain.fsh"), """
                #version 120
                uniform sampler2D texture;
                varying vec2 uv;
                varying float fade;
                void main() { gl_FragData[0] = texture2D(texture, uv) * fade; }
                """);
        // Block entities and block cracks draw outside Sodium, where Iris declares no mc_chunkFade, so
        // they get their own programs rather than falling back to terrain's.
        for (String program : List.of("gbuffers_block", "gbuffers_damagedblock")) {
            Files.writeString(shaders.resolve(program + ".vsh"), """
                    #version 120
                    varying vec2 uv;
                    void main() { uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
                    """);
            Files.writeString(shaders.resolve(program + ".fsh"), """
                    #version 120
                    uniform sampler2D texture;
                    varying vec2 uv;
                    void main() { gl_FragData[0] = texture2D(texture, uv); }
                    """);
        }
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackProgramPlan terrain = analysis.plan().program("gbuffers_terrain");
        assertTrue(terrain.executable() && terrain.convertedVertex().contains("float mc_chunkFade = 1.0;"),
                terrain.deviations() + "\n" + terrain.convertedVertex());
        var compiled = PackCompileTest.run(root, analysis);
        assertTrue(compiled.failures().isEmpty(), compiled.failures().toString());
    }
}
