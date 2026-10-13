package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pack-wide directives: GLSL versions, property syntax, held items and screen overlays. */
final class PackDirectivesTest {
    /**
     * One version rule (any desktop 110-460, compatibility or core), modern declarations in the
     * entity-style converters, Iris core-profile names, and GL's discard of an output past the
     * route, on a Photon-shaped pack.
     */
    @Test
    void glslBreadth(@TempDir Path root) throws IOException {
        for (String accepted : List.of("#version 110", "#version 120e", "#version 150", "#version 330 core",
                "#version 400 compatibility", "#version 430 compatibility", "#version 460")) {
            assertTrue(GlslVersion.declaredAndSupported(accepted + "\nvoid main() {}"), accepted + " is a desktop version");
        }
        for (String refused : List.of("#version 300 es", "#version 100", "#version 470", "#version 200")) {
            assertTrue(!GlslVersion.declaredAndSupported(refused + "\nvoid main() {}"), refused + " is not");
        }
        assertTrue(GlslVersion.first("#version 400").irisCore() && GlslVersion.first("#version 140 core").irisCore()
                        && !GlslVersion.first("#version 400 compatibility").irisCore()
                        && !GlslVersion.first("#version 130").irisCore(),
                "Iris core mode: the core profile, or 150 and above with no profile");
        assertEquals("writeonly uniform image3D a;\nwriteonly uniform image3D b;",
                GlslTokenRewriter.splitUniformDeclarators("writeonly uniform image3D a, b;"),
                "each image of a list takes its own binding");
        assertEquals("layout(rgba8) uniform writeonly image3D a;\nlayout(rgba8) uniform writeonly image3D b;",
                GlslTokenRewriter.splitUniformDeclarators("layout(rgba8) uniform writeonly image3D a, b;"),
                "qualifiers on either side of uniform stay on every declarator");
        assertEquals("#define W writeonly\nuniform sampler3D a;\nuniform sampler3D b;",
                GlslTokenRewriter.splitUniformDeclarators("#define W writeonly\nuniform sampler3D a, b;"),
                "a directive before the declaration is not a qualifier");
        assertEquals("/* a comment\nover two lines */\n\nin vec3 mc_Entity;",
                GlslTokenRewriter.expandStorageQualifierAliases(
                        "/* a comment\nover two lines */\n#define attribute in\nattribute vec3 mc_Entity;"),
                "a storage-qualifier alias applies after its definition, lexed as one stream");

        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_entities.vsh"), """
                #version 400 compatibility
                #define attribute in
                out vec2 uv;
                flat out uint material_mask;
                flat out mat3 tbn;
                attribute vec4 at_tangent;
                attribute vec3 mc_Entity;
                attribute vec2 mc_midTexCoord;
                void main() {
                    uv = gl_MultiTexCoord0.xy + mc_midTexCoord * 0.0;
                    material_mask = uint(gl_VertexID & 1) + uint(mc_Entity.x);
                    tbn = mat3(at_tangent.xyz, gl_Normal, cross(at_tangent.xyz, gl_Normal));
                    gl_Position = gl_ProjectionMatrix * (gl_ModelViewMatrix * gl_Vertex);
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_entities.fsh"), """
                #version 400 compatibility
                uniform sampler2D gtexture;
                in vec2 uv;
                flat in uint material_mask;
                flat in mat3 tbn;
                layout (location = 0) out vec4 gbuffer_data_0;
                layout (location = 1) out vec4 gbuffer_data_1;
                /* RENDERTARGETS: 1 */
                float[2] unused_pair() { return float[2](0.0, 1.0); }
                void main() {
                    gbuffer_data_0 = texture(gtexture, uv) * float(material_mask + 1u) * tbn[0].x;
                    gbuffer_data_1 = vec4(tbn[2], 1.0);
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_basic.vsh"), """
                #version 400
                in vec3 vaPosition;
                in vec4 vaColor;
                in ivec2 vaUV2;
                uniform mat4 modelViewMatrix;
                uniform mat4 projectionMatrix;
                out vec4 tint;
                out vec2 light_levels;
                void main() {
                    tint = vaColor;
                    light_levels = vec2(vaUV2) / 240.0;
                    gl_Position = projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0);
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_basic.fsh"), """
                #version 400
                in vec4 tint;
                in vec2 light_levels;
                layout (location = 0) out vec4 color;
                /* RENDERTARGETS: 0 */
                void main() {
                    color = tint * light_levels.x;
                }
                """);
        Files.writeString(shaders.resolve("shadow.vsh"), """
                #version 430 compatibility
                out vec2 uv;
                attribute vec3 mc_Entity;
                void main() {
                    uv = gl_MultiTexCoord0.xy + mc_Entity.xy * 0.0;
                    gl_Position = ftransform();
                }
                """);
        Files.writeString(shaders.resolve("shadow.fsh"), """
                #version 430 compatibility
                uniform sampler2D gtexture;
                in vec2 uv;
                layout (location = 0) out vec3 shadowcolor0_out;
                /* RENDERTARGETS: 0 */
                void main() {
                    shadowcolor0_out = texture(gtexture, uv).rgb;
                }
                """);
        var analysis = PackProbe.analyze(root);
        for (String name : List.of("gbuffers_entities", "gbuffers_basic", "shadow")) {
            var plan = analysis.plan().program(name);
            assertTrue(plan != null && plan.executable(),
                    name + " must convert: " + (plan == null ? "missing" : plan.deviations()));
        }
        var entities = analysis.plan().program("gbuffers_entities");
        assertTrue(entities.convertedVertex().startsWith("#version 460")
                        && entities.convertedVertex().contains("gl_VertexIndex")
                        && !entities.convertedVertex().contains("attribute"),
                "a 400 compatibility entity vertex converts with its modern declarations: "
                        + entities.convertedVertex());
        assertTrue(entities.deviations().contains("FRAGMENT_OUTPUT_DISCARDED:1")
                        && entities.convertedFragment().contains("vec4 gbuffer_data_1;")
                        && !entities.convertedFragment().contains("unused_pair"),
                "an output past the route is written to nothing, and an unread array function goes: "
                        + entities.deviations() + "\n" + entities.convertedFragment());
        String basic = analysis.plan().program("gbuffers_basic").convertedVertex();
        assertTrue(!basic.contains("vaPosition") && !basic.contains("modelViewMatrix")
                        && basic.contains("ivec2(vec4(vec2(UV2), 0.0, 1.0).xy)"),
                "Iris core names become the host inputs: " + basic);
        assertTrue(analysis.plan().program("shadow").convertedFragment()
                        .contains("layout(location = 0) out vec3 chimeraShadowColor0;"),
                "a shadow colour keeps the type the pack writes");
        var compiled = PackCompileTest.run(root, analysis);
        assertTrue(compiled.failures().isEmpty(), "GLSL breadth compilation: " + compiled.failures());
    }

    /**
     * Properties.load skips a line's leading whitespace. Photon indents its composite17 switch
     * inside {@code #ifdef MC_GL_RENDERER_INTEL}; read with the indent, the switch never applied.
     */
    @Test
    void indentedProperties(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"),
                "#ifdef MC_GL_RENDERER_INTEL\n\tprogram.composite1.enabled = true\n#else\n"
                        + "\tprogram.composite1.enabled = false\n#endif\n");
        String post = "#version 120\nuniform sampler2D colortex0;\n"
                + "void main() { gl_FragColor = texture2D(colortex0, vec2(0.5)); }\n";
        Files.writeString(shaders.resolve("composite1.fsh"), post);
        Files.writeString(shaders.resolve("final.fsh"), post);
        var analysis = PackProbe.analyze(root);
        assertEquals(Boolean.FALSE, analysis.settings().programEnabled().get("composite1"),
                "an indented program switch applies");
        assertEquals(1, analysis.plan().disabledProgramCount(), "the switched-off program is disabled");
    }

    /**
     * Iris serves heldItemId, heldBlockLightValue and heldBlockLightColor (and their 2 variants)
     * from the pack's item.properties and the held stacks (IdMapUniforms). Chimera served 0, so
     * a held torch never lit anything: Solas's hand light only runs for heldItemId 1..83, and
     * BSL and Complementary read heldBlockLightValue.
     */
    @Test
    void heldItems(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("item.properties"), """
                # Item ids
                item.5=torchflower torch \\
                    minecraft:lantern
                item.6=soul_torch leaves:waterlogged=false
                #if MC_VERSION >= 12100
                item.44028=soul_lantern
                #else
                item.9=soul_lantern
                #endif
                item.7=end_rod
                item.7=glowstone
                item.40008=air
                """);
        var parsed = PackItemIdResolver.parse(shaders, PackEngineDefines.standard());
        var items = parsed.resolver();
        assertTrue(parsed.present() && parsed.deviations().isEmpty(), "item.properties parses: " + parsed.deviations());
        assertEquals(5, items.resolve("minecraft:torch"), "a bare name is in minecraft");
        assertEquals(5, items.resolve("minecraft:lantern"), "a continuation line joins its key's list");
        assertEquals(6, items.resolve("soul_torch"), "a lookup without a namespace means minecraft");
        assertEquals(44028, items.resolve("minecraft:soul_lantern"), "#if branches follow the engine macros");
        assertEquals(7, items.resolve("minecraft:glowstone"), "a later line for a key replaces it");
        assertEquals(PackItemIdResolver.UNMAPPED, items.resolve("minecraft:end_rod"),
                "the replaced line's names are gone, as java.util.Properties keeps one value per key");
        assertEquals(40008, items.resolve("minecraft:air"), "an empty hand looks up minecraft:air");
        assertEquals(PackItemIdResolver.UNMAPPED, items.resolve("minecraft:stone"), "an unlisted item is -1");
        assertTrue(items.mappings().keySet().stream().noneMatch(name -> name.contains("=")),
                "state selectors are skipped, as Iris does for items");
        assertTrue(!PackItemIdResolver.parse(root, PackEngineDefines.standard()).present()
                        && PackItemIdResolver.empty().resolve("minecraft:torch") == PackItemIdResolver.UNMAPPED,
                "without item.properties every held item is -1");

        assertTrue(HandLightDirectives.fromProperties(Map.of()).oldHandLight(), "Iris's oldHandLight defaults to on");
        assertTrue(!HandLightDirectives.fromProperties(Map.of("oldHandLight", "false")).oldHandLight(),
                "oldHandLight=false lights each hand separately");
        assertTrue(HandLightDirectives.fromProperties(Map.of("oldHandLight", "nope")).deviations()
                        .contains("OLD_HAND_LIGHT_DIRECTIVE_MALFORMED"), "an unexpected oldHandLight is reported");

        for (String name : List.of("heldItemId", "heldItemId2", "heldBlockLightValue", "heldBlockLightValue2")) {
            var descriptor = UniformRegistry.descriptor(name, "int");
            assertTrue(descriptor != null && descriptor.availability() == UniformRegistry.Availability.LIVE,
                    name + " is served live");
        }
        for (String name : List.of("heldBlockLightColor", "heldBlockLightColor2")) {
            var descriptor = UniformRegistry.descriptor(name, "vec3");
            assertTrue(descriptor != null && descriptor.availability() == UniformRegistry.Availability.LIVE,
                    name + " is served live");
        }

        var pass = MixinBytecode.nativeClass("net/chimera/render/PackSessionLifecycle");
        for (String name : List.of("loadPackConfig", "cleanUpPackVariant")) {
            var method = pass.methods.stream().filter(value -> value.name.equals(name)).findFirst()
                    .orElseThrow(() -> new AssertionError("PackSessionLifecycle." + name + " is missing"));
            boolean installs = false;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/chimera/render/shader/PackUniformProvider")
                        && call.name.equals("installHeldItems")) {
                    installs = true;
                }
            }
            assertTrue(installs, "PackSessionLifecycle." + name + " installs the held-item map");
        }
    }

    /**
     * Iris skips Minecraft's HUD vignette and underwater overlay while a pack is active unless
     * shaders.properties keeps them (vignette, underwaterOverlay; both default off). Chimera drew
     * the vignette over Solas's finished image, so a dark room's walls ended about a fifth darker.
     */
    @Test
    void screenOverlays(@TempDir Path root) throws IOException {
        assertEquals(ScreenOverlayDirectives.DEFAULTS, ScreenOverlayDirectives.fromProperties(Map.of()),
                "Iris's vignette and underwaterOverlay default to off");
        for (String key : List.of("vignette", "underwaterOverlay")) {
            for (String value : List.of("true", "1", " true ")) {
                var directives = ScreenOverlayDirectives.fromProperties(Map.of(key, value));
                assertTrue(screenOverlay(directives, key) && directives.deviations().isEmpty(),
                        key + "=" + value + " keeps the overlay");
            }
            for (String value : List.of("false", "0")) {
                var directives = ScreenOverlayDirectives.fromProperties(Map.of(key, value));
                assertTrue(!screenOverlay(directives, key) && directives.deviations().isEmpty(),
                        key + "=" + value + " skips the overlay");
            }
            var malformed = ScreenOverlayDirectives.fromProperties(Map.of(key, "yes"));
            assertTrue(!screenOverlay(malformed, key)
                            && malformed.deviations().contains("SCREEN_OVERLAY_DIRECTIVE_MALFORMED:" + key),
                    "an unexpected " + key + " keeps Iris's default and is reported: " + malformed);
        }

        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), "vignette=true\nunderwaterOverlay=1\n");
        Files.writeString(shaders.resolve("final.fsh"), """
                #version 120
                uniform sampler2D colortex0;
                void main() {
                    gl_FragColor = texture2D(colortex0, vec2(0.5));
                }
                """);
        var overlays = PackProbe.analyze(root).plan().screenOverlays();
        assertTrue(overlays.vignette() && overlays.underwaterOverlay(),
                "the pack plan reads shaders.properties vignette and underwaterOverlay: " + overlays);

        MixinBytecode.assertCancellingHeadInject("net/chimera/mixin/ChimeraGuiVignetteMixin",
                "net/minecraft/client/gui/Gui", "renderVignette", false);
        MixinBytecode.assertCancellingHeadInject("net/chimera/mixin/ChimeraScreenEffectWaterMixin",
                "net/minecraft/client/renderer/ScreenEffectRenderer", "renderWater", true);
        MixinBytecode.assertMixinRegistered("ChimeraGuiVignetteMixin");
        MixinBytecode.assertMixinRegistered("ChimeraScreenEffectWaterMixin");

        var pass = MixinBytecode.nativeClass("net/chimera/render/PackSessionLifecycle");
        boolean loads = false;
        boolean clears = false;
        for (var method : pass.methods) {
            for (var instruction : method.instructions) {
                if (method.name.equals("loadPackConfig") && instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/chimera/shaderpack/PackPlan") && call.name.equals("screenOverlays")) {
                    loads = true;
                }
                if (method.name.equals("cleanUpPackVariant") && instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.PUTFIELD && field.name.equals("screenOverlays")) {
                    clears = true;
                }
            }
        }
        assertTrue(loads && clears, "the main pass reads the overlay switches per pack session and drops them with it");
    }

    static boolean screenOverlay(ScreenOverlayDirectives directives, String key) {
        return key.equals("vignette") ? directives.vignette() : directives.underwaterOverlay();
    }
}
