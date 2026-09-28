package net.chimera.shaderpack;

import net.chimera.render.MaterialMapOwner;
import net.chimera.render.PackResourceOwner;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.descriptor.UBO;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure material planner gates: selectors, stage gating, resource identity, and manifest kind. */
public final class MaterialPlanConformanceHarness {
    private MaterialPlanConformanceHarness() {}

    /** VK_SHADER_STAGE_VERTEX_BIT; the Vulkan bindings are compile-only for main sources. */
    private static final int VK_SHADER_STAGE_VERTEX_BIT = 1;

    private static final String MATERIAL_SNIPPET = """
            #version 120
            uniform sampler2D normals;
            uniform sampler2D specular;
            void main() {
                gl_FragColor = texture2D(normals, vec2(0.5)) + texture2D(specular, vec2(0.5));
            }
            """;

    private static final String ELYTRA_SNIPPET = """
            #version 130
            uniform bool isElytraFlying;
            void main() {
                if (!isElytraFlying) discard;
                gl_FragColor = vec4(1.0);
            }
            """;

    private static final String ELYTRA_VERTEX_SNIPPET = """
            #version 130
            void main() {
                gl_Position = ftransform();
            }
            """;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        PackProbe.Analysis analysis = PackProbe.analyze(root.resolve("material_plan"));
        verifySelectors();
        verifyStageGating();
        verifyFixturePlan(analysis);
        verifyManifest(analysis);
        verifyOwnerIgnoresMaterial(analysis);
        verifyFallbackDispatch();
        verifyExplicitGradientTranslation();
        verifyCompactDirectiveParsing();
        verifyEntityFamilyOutputPlans();
        verifyOrdinaryTransformBlocks();
        verifyEntitySamplerSlots();
        verifyEntityShadowContract();
        verifyEntityVertexConversion();
        verifyElytraUniformBridge();
        System.out.println("[chimera] material planner conformance: PASS");
    }

    private static void verifySelectors() {
        assertEquals(25, SelectorNamespace.NORMALS_SLOT, "normals selector moved");
        assertEquals(26, SelectorNamespace.SPECULAR_SLOT, "specular selector moved");
        assertTrue(SelectorNamespace.isAddressable(25), "normals selector is not addressable");
        assertTrue(SelectorNamespace.isAddressable(26), "specular selector is not addressable");
        assertEquals(17, SelectorNamespace.extendedIndex(25), "normals extended index changed");
        assertEquals(18, SelectorNamespace.extendedIndex(26), "specular extended index changed");
        assertTrue(!SelectorNamespace.PACK_SELECTOR_SLOTS.contains(25)
                && !SelectorNamespace.PACK_SELECTOR_SLOTS.contains(26),
                "material selectors entered the pack texture pool");
        assertEquals(34, SelectorNamespace.LAST_RESERVED + 1, "selector range changed");
        boolean[] cleared = new boolean[SelectorNamespace.LAST_RESERVED + 1];
        SelectorNamespace.clearOwned(slot -> cleared[slot] = true);
        assertTrue(cleared[25] && cleared[26], "material selectors are not cleared on pack change");
    }

    private static void verifyStageGating() {
        for (UniformRegistry.Stage stage : List.of(
                UniformRegistry.Stage.GEOMETRY, UniformRegistry.Stage.TRANSLUCENT,
                UniformRegistry.Stage.ENTITY, UniformRegistry.Stage.BLOCK,
                UniformRegistry.Stage.HAND, UniformRegistry.Stage.PARTICLE)) {
            UniformRegistry.ProgramInterface plan = UniformRegistry.plan(MATERIAL_SNIPPET, stage);
            assertEquals(25, slotOf(plan, "normals"), "normals slot in " + stage);
            assertEquals(26, slotOf(plan, "specular"), "specular slot in " + stage);
            assertTrue(plan.deviations().stream().noneMatch(value ->
                            value.contains("normals") || value.contains("specular")),
                    "material stage rejected material names in " + stage + ": " + plan.deviations());
        }
        for (UniformRegistry.Stage stage : List.of(
                UniformRegistry.Stage.POST, UniformRegistry.Stage.SHADOW,
                UniformRegistry.Stage.SKY, UniformRegistry.Stage.CLOUD)) {
            UniformRegistry.ProgramInterface plan = UniformRegistry.plan(MATERIAL_SNIPPET, stage);
            assertTrue(plan.samplers().stream().noneMatch(value ->
                            value.name().equals("normals") || value.name().equals("specular")),
                    "non-material stage kept material samplers in " + stage);
        }
    }

    private static int slotOf(UniformRegistry.ProgramInterface plan, String name) {
        return plan.samplers().stream().filter(value -> value.name().equals(name))
                .map(UniformRegistry.SamplerBinding::slot).findFirst()
                .orElseThrow(() -> new AssertionError("missing sampler " + name));
    }

    private static void verifyFixturePlan(PackProbe.Analysis analysis) {
        PackProgramPlan terrain = analysis.plan().program("gbuffers_terrain");
        assertTrue(terrain != null, "material fixture terrain is missing");
        Map<String, Integer> slots = new java.util.TreeMap<>();
        terrain.interfacePlan().effective(UniformRegistry.Stage.GEOMETRY).samplers()
                .forEach(value -> slots.put(value.name(), value.slot()));
        assertEquals(25, slots.get("normals"), "fixture terrain normals slot");
        assertEquals(26, slots.get("specular"), "fixture terrain specular slot");

        List<PackResourceBinding> bindings = analysis.plan().resources().bindings("gbuffers_terrain");
        PackResourceBinding normals = binding(bindings, "normals");
        PackResourceBinding specular = binding(bindings, "specular");
        assertTrue(normals.kind() == PackResourceKind.MATERIAL_MAP
                && normals.status() == PackResourceStatus.MATERIAL_MAP
                && normals.available() && normals.slot() == 25, "terrain normals binding");
        assertTrue(specular.kind() == PackResourceKind.MATERIAL_MAP
                && specular.status() == PackResourceStatus.MATERIAL_MAP
                && specular.available() && specular.slot() == 26, "terrain specular binding");

        assertTrue(analysis.report().deviations().stream()
                        .noneMatch(value -> value.startsWith("MATERIAL_MAP_DEFERRED:")),
                "deferred material placeholder survived");
        assertTrue(terrain.deviations().stream().noneMatch(value ->
                        value.contains("MATERIAL_MAP_DEFERRED") || value.contains("NOT_MAPPED")),
                "terrain material deviations: " + terrain.deviations());

        PackProgramPlan composite = analysis.plan().program("composite");
        assertTrue(composite != null, "material fixture composite is missing");
        assertTrue(composite.deviations().contains("SAMPLER_NOT_MAPPED:normals")
                        && composite.deviations().contains("SAMPLER_NOT_MAPPED:specular"),
                "post material declarations are not explicitly unsupported: " + composite.deviations());
        assertTrue(analysis.plan().resources().bindings("composite").stream()
                        .noneMatch(value -> value.kind() == PackResourceKind.MATERIAL_MAP),
                "post program gained material descriptors");

        PackProgramPlan fin = analysis.plan().program("final");
        assertTrue(fin != null, "material fixture final is missing");
        assertTrue(analysis.plan().resources().bindings("final").stream()
                        .noneMatch(value -> value.kind() == PackResourceKind.MATERIAL_MAP),
                "program without material names gained material descriptors");
    }

    private static PackResourceBinding binding(List<PackResourceBinding> bindings, String sampler) {
        return bindings.stream().filter(value -> value.sampler().equals(sampler)).findFirst()
                .orElseThrow(() -> new AssertionError("missing binding " + sampler));
    }

    private static void verifyManifest(PackProbe.Analysis analysis) {
        PackProgramPlan terrain = analysis.plan().program("gbuffers_terrain");
        var ordinary = net.chimera.shaderpack.PackPipelines.ordinaryDescriptorContract(
                terrain, Set.of());
        ProgramImageBindingManifest manifest =
                ProgramImageBindingManifest.from(null, ordinary);
        List<ProgramImageBindingManifest.Entry> material = manifest.entries().stream()
                .filter(value -> value.kind() == ProgramImageBindingManifest.Kind.MATERIAL_MAP)
                .toList();
        assertEquals(2, material.size(), "material manifest entries: " + manifest.entries());
        assertTrue(material.stream().anyMatch(value -> value.slot() == 25)
                        && material.stream().anyMatch(value -> value.slot() == 26),
                "material manifest slots");
        assertTrue(material.stream().map(ProgramImageBindingManifest.Entry::binding)
                        .distinct().count() == 2,
                "material manifest descriptor bindings collide");
        manifest.verify(new java.util.ArrayList<>(ordinary.descriptors()));
    }

    private static void verifyFallbackDispatch() throws Exception {
        assertEquals("normals", MaterialMapOwner.MaterialMapKind.NORMALS.sampler(), "normals name");
        assertEquals("_n", MaterialMapOwner.MaterialMapKind.NORMALS.suffix(), "normals suffix");
        assertEquals(0xFFFF7F7F, MaterialMapOwner.MaterialMapKind.NORMALS.fallbackAbgr(),
                "normals flat texel");
        assertEquals("specular", MaterialMapOwner.MaterialMapKind.SPECULAR.sampler(), "specular name");
        assertEquals("_s", MaterialMapOwner.MaterialMapKind.SPECULAR.suffix(), "specular suffix");
        assertEquals(0x00000000, MaterialMapOwner.MaterialMapKind.SPECULAR.fallbackAbgr(),
                "specular flat texel");
        var constructor = net.vulkanmod.vulkan.texture.VulkanImage.class.getDeclaredConstructor(
                net.vulkanmod.vulkan.texture.VulkanImage.Builder.class);
        constructor.setAccessible(true);
        net.vulkanmod.vulkan.texture.VulkanImage normals = constructor.newInstance(
                net.vulkanmod.vulkan.texture.VulkanImage.builder(1, 1));
        net.vulkanmod.vulkan.texture.VulkanImage specular = constructor.newInstance(
                net.vulkanmod.vulkan.texture.VulkanImage.builder(1, 1));
        MaterialMapOwner owner = MaterialMapOwner.withImages(normals, specular);
        try {
            assertTrue(owner.snapshot("normals").image() == normals, "normals resolved elsewhere");
            assertTrue(owner.snapshot("specular").image() == specular, "specular resolved elsewhere");
            boolean rejected = false;
            try {
                owner.snapshot("colortex0");
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            assertTrue(rejected, "unknown material name did not fail loudly");
        } finally {
            owner.close();
        }
    }

    private static void verifyOwnerIgnoresMaterial(PackProbe.Analysis analysis) {
        PackResourceOwner owner = PackResourceOwner.load(analysis.plan().resources(), null);
        try {
            assertTrue(owner.programAvailable("gbuffers_terrain"),
                    "material entries blocked program availability");
        } finally {
            owner.close();
        }
    }

    private static void verifyExplicitGradientTranslation() {
        String converted = GlslTokenRewriter.rewriteTextureCalls(
                "vec4 albedo = texture2DGradARB(texture, newCoord, dcdx, dcdy);");
        assertTrue(converted.contains("textureGrad(") && !converted.contains("texture2DGradARB"),
                "explicit-gradient sampling was not translated: " + converted);
    }

    // ------------------------------------------------------------------
    // TASK-332: compact directive parsing, entity output plans, slots,
    // and the isElytraFlying int bridge.
    // ------------------------------------------------------------------

    private static void verifyCompactDirectiveParsing() {
        // Complementary-authored shape after branch resolution: the
        // unconditional 036 route plus the surviving guarded 0364 route.
        // The last surviving route wins, matching Iris authoring order.
        GeometryOutputPlan reflectionsOff = GeometryOutputPlan.parse("gbuffers_entities",
                "#version 120\n/* DRAWBUFFERS:036 */\ngl_FragData[2] = vec4(0.0);\n",
                Map.of());
        assertEquals(List.of(0, 3, 6), reflectionsOff.targetSlots(), "036 alone stays");
        GeometryOutputPlan reflectionsOn = GeometryOutputPlan.parse("gbuffers_entities",
                "#version 120\n/* DRAWBUFFERS:036 */\ngl_FragData[2] = vec4(0.0);\n"
                        + "/* DRAWBUFFERS:0364 */\ngl_FragData[3] = vec4(0.0);\n",
                Map.of());
        assertEquals(List.of(0, 3, 6, 4), reflectionsOn.targetSlots(), "surviving 0364 wins");
        // A report containing 36/364/367/1367 as targets is a parser failure.
        for (String bad : List.of("36", "364", "367", "1367")) {
            assertTrue(!reflectionsOn.targetSlots().contains(Integer.decode(bad)),
                    "impossible target " + bad + " leaked into the plan");
        }
        // Length never decides: a shorter later route replaces a longer default.
        GeometryOutputPlan narrowed = GeometryOutputPlan.parse("gbuffers_entities",
                "#version 120\n/* DRAWBUFFERS:0364 */\n/* DRAWBUFFERS:036 */\n", Map.of());
        assertEquals(List.of(0, 3, 6), narrowed.targetSlots(), "later route wins over longer");
        // Divergent survivors stay a hard conflict instead of a guess.
        GeometryOutputPlan divergent = GeometryOutputPlan.parse("gbuffers_entities",
                "#version 120\n/* DRAWBUFFERS:036 */\n/* DRAWBUFFERS:05 */\n", Map.of());
        assertEquals(List.of(0, 5), divergent.targetSlots(), "divergent latest wins");
        assertTrue(divergent.deviations().stream().anyMatch(value -> value.contains("CONFLICT")),
                "divergent survivors did not conflict: " + divergent.deviations());
        // Comma-separated list form stays a list of targets.
        GeometryOutputPlan listed = GeometryOutputPlan.parse("gbuffers_entities",
                "#version 120\n/* RENDERTARGETS: 0,3,6 */\n", Map.of());
        assertEquals(List.of(0, 3, 6), listed.targetSlots(), "list targets kept");
        // Post plan keeps the same rule.
        PostTargetPlan post = PostTargetPlan.parse("composite1",
                "#define DRAWBUFFERS036\n", Map.of()).plan();
        assertEquals(List.of(0, 3, 6), post.targetSlots(), "post compact digits");
    }

    private static void verifyEntityFamilyOutputPlans() throws Exception {
        Path root = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        PackProbe.Analysis entities = PackProbe.analyze(root.resolve("m87_entity"));
        PackProgramPlan plan = entities.plan().program("gbuffers_entities");
        assertTrue(plan.executable(), "entity fixture not executable: " + plan.deviations());
        // Single-output family plan keeps the executable single-target shape.
        assertTrue(plan.geometryOutputPlan() != null
                        && plan.geometryOutputPlan().targetSlots().equals(List.of(0)),
                "entity single-target plan missing");
    }

    /**
     * DOC-365 descriptor identity: the guarded family contract must carry the
     * host transform blocks at bindings 0/1 with the converted preambles'
     * fields, and the applied builder must expose them under their semantic
     * names. VulkanMod names config-declared blocks "UBO: &lt;binding&gt;" and
     * {@code Pipeline.getUBO(String)} compares names exactly, so a numeric block
     * can never satisfy the bridge's lookup.
     */
    private static void verifyOrdinaryTransformBlocks() throws Exception {
        Path root = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        PackProgramPlan plan = PackProbe.analyze(root.resolve("m87_entity"))
                .plan().program("gbuffers_entities");
        OrdinaryDescriptorContract ordinary = PackPipelines.ordinaryDescriptorContract(plan, Set.of());
        PipelineConfig.UB dynamic = blockAt(ordinary, UniformRegistry.DYNAMIC_TRANSFORMS_BINDING);
        PipelineConfig.UB projection = blockAt(ordinary, UniformRegistry.PROJECTION_BINDING);
        assertEquals(List.of("ModelViewMat", "ColorModulator", "ModelOffset", "TextureMat"),
                dynamic.uniforms.stream().map(PipelineConfig.Uniform::name).toList(),
                "DynamicTransforms fields");
        assertEquals(List.of("ProjMat"),
                projection.uniforms.stream().map(PipelineConfig.Uniform::name).toList(),
                "Projection fields");
        assertEquals(VK_SHADER_STAGE_VERTEX_BIT, dynamic.stage,
                "DynamicTransforms stage");
        assertEquals(VK_SHADER_STAGE_VERTEX_BIT, projection.stage,
                "Projection stage");

        Pipeline.Builder builder = new Pipeline.Builder(
                net.chimera.render.vertex.ChimeraVertexFormats.EXTENDED_ENTITY,
                "pack_gbuffers_entities");
        builder.setUniformSupplierGetter(info -> () -> null);
        ordinary.apply(builder);
        List<UBO> blocks = builder.getUBOs();
        assertTrue(names(blocks).contains(UniformRegistry.DYNAMIC_TRANSFORMS_UBO),
                "binding 0 does not answer to " + UniformRegistry.DYNAMIC_TRANSFORMS_UBO
                        + ": " + names(blocks));
        assertTrue(names(blocks).contains(UniformRegistry.PROJECTION_UBO),
                "binding 1 does not answer to " + UniformRegistry.PROJECTION_UBO
                        + ": " + names(blocks));
        assertTrue(blocks.stream()
                        .filter(block -> block.getBinding() == UniformRegistry.DYNAMIC_TRANSFORMS_BINDING
                                || block.getBinding() == UniformRegistry.PROJECTION_BINDING)
                        .noneMatch(block -> block.name.startsWith("UBO: ")),
                "transform blocks kept their numeric names: " + names(blocks));
        // The fragment block carries the per-draw host alpha-test reference.
        assertTrue(plan.interfacePlan().uniforms().stream()
                        .anyMatch(u -> u.name().equals(UniformRegistry.ENTITY_ALPHA_REFERENCE)),
                "entity program lacks the host alpha-test reference uniform");
        // VulkanMod computes the std140 size from the declared fields; the
        // converted preamble declares the same layout, so the blocks must match
        // the host 160B/64B contract or the descriptor would misdescribe it.
        assertEquals(160, sizeOf(blocks, UniformRegistry.DYNAMIC_TRANSFORMS_BINDING),
                "DynamicTransforms std140 size");
        assertEquals(64, sizeOf(blocks, UniformRegistry.PROJECTION_BINDING),
                "Projection std140 size");
    }

    private static PipelineConfig.UB blockAt(OrdinaryDescriptorContract contract, int binding) {
        return contract.uboBlocks().stream().filter(ub -> ub.binding == binding).findFirst()
                .orElseThrow(() -> new AssertionError("no uniform block at binding " + binding));
    }

    private static List<String> names(List<UBO> blocks) {
        return blocks.stream().map(block -> block.name).toList();
    }

    private static int sizeOf(List<UBO> blocks, int binding) {
        return blocks.stream().filter(block -> block.binding == binding).findFirst()
                .orElseThrow(() -> new AssertionError("no built block at binding " + binding)).getSize();
    }

    private static void verifyEntitySamplerSlots() {
        assertEquals(0, UniformRegistry.ENTITY_NAME_TO_SLOT.get("tex"), "tex alias slot");
        assertEquals(SelectorNamespace.SHADOW_TEX1_SLOT,
                UniformRegistry.ENTITY_NAME_TO_SLOT.get("shadowtex1"), "shadowtex1 slot");
        assertEquals(SelectorNamespace.SHADOW_COLOR0_SLOT,
                UniformRegistry.ENTITY_NAME_TO_SLOT.get("shadowcolor0"), "shadowcolor0 slot");
    }

    private static void verifyEntityShadowContract() throws Exception {
        Path root = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        PackProbe.Analysis shadow = PackProbe.analyze(root.resolve("m87_entity_shadow"));
        PackProgramPlan plan = shadow.plan().program("gbuffers_entities");
        assertTrue(plan != null, "shadow fixture program is missing");
        Map<String, Integer> slots = new java.util.TreeMap<>();
        plan.interfacePlan().effective(UniformRegistry.Stage.ENTITY).samplers()
                .forEach(value -> slots.put(value.name(), value.slot()));
        assertEquals(5, slots.get("shadowtex0"), "entity shadowtex0 slot");
        assertEquals(15, slots.get("shadowtex1"), "entity shadowtex1 slot");
        assertTrue(plan.interfacePlan().effective(UniformRegistry.Stage.ENTITY).deviations().stream()
                        .noneMatch(value -> value.startsWith("SAMPLER_SLOT_CONFLICT")),
                "entity shadow slot conflict: "
                        + plan.interfacePlan().effective(UniformRegistry.Stage.ENTITY).deviations());
        // Two images as in Iris: shadowtex0 has every caster, shadowtex1 only
        // those drawn before translucent terrain.
        PackResourceBinding depth0 = binding(shadow.plan().resources().bindings("gbuffers_entities"),
                "shadowtex0");
        PackResourceBinding depth1 = binding(shadow.plan().resources().bindings("gbuffers_entities"),
                "shadowtex1");
        assertTrue(depth0.slot() == 5 && depth0.resourceKey().equals("shadowtex0")
                        && depth0.kind() == PackResourceKind.SHADOW_DEPTH && depth0.available(),
                "shadowtex0 binding");
        assertTrue(depth1.slot() == 15 && depth1.resourceKey().equals("shadowtex1")
                        && depth1.kind() == PackResourceKind.SHADOW_DEPTH && depth1.available(),
                "shadowtex1 canonical binding");
        var ordinary = PackPipelines.ordinaryDescriptorContract(plan, Set.of());
        ProgramImageBindingManifest manifest = ProgramImageBindingManifest.from(null, ordinary);
        long shadowEntries = manifest.entries().stream()
                .filter(value -> value.kind() == ProgramImageBindingManifest.Kind.SHADOW_DEPTH)
                .count();
        assertEquals(2L, shadowEntries, "shadow descriptor bindings: " + manifest.entries());
        assertTrue(manifest.entries().stream().filter(value ->
                        value.kind() == ProgramImageBindingManifest.Kind.SHADOW_DEPTH)
                        .map(ProgramImageBindingManifest.Entry::slot).distinct().count() == 2,
                "shadow bindings collide");
    }

    private static final String ENTITY_VERTEX_SNIPPET = """
            #version 120
            attribute vec4 mc_Entity;
            attribute vec4 mc_midTexCoord;
            attribute vec4 at_tangent;
            uniform int worldTime;
            uniform vec3 cameraPosition;
            uniform mat4 gbufferModelView, gbufferModelViewInverse;
            varying vec2 texCoord, lmCoord;
            varying vec3 normal;
            flat varying vec3 tangentFrame;
            void main() {
                texCoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                vec2 midCoord = (gl_TextureMatrix[0] * mc_midTexCoord).st;
                normal = normalize(gl_NormalMatrix * gl_Normal);
                tangentFrame = normalize(gl_NormalMatrix * at_tangent.xyz);
                vec4 view = gl_ModelViewMatrix * gl_Vertex + gbufferModelViewInverse * vec4(1.0);
                vec3 worldOffset = cameraPosition + vec3(float(worldTime));
                gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex + view + vec4(worldOffset, 0.0);
            }
            """;

    private static final String ENTITY_FRAGMENT_SNIPPET = """
            #version 120
            uniform sampler2D texture;
            uniform sampler2D normals;
            uniform sampler2D specular;
            uniform vec3 cameraPosition;
            uniform mat4 gbufferModelView, gbufferModelViewInverse;
            varying vec2 texCoord, lmCoord;
            varying vec3 normal;
            flat varying vec3 tangentFrame;
            void main() {
                vec4 albedo = texture2D(texture, texCoord);
                vec3 normalSample = texture2D(normals, texCoord).rgb + tangentFrame;
                float smoothness = texture2D(specular, texCoord).r;
                vec3 offset = (gbufferModelView * vec4(1.0)).xyz
                        + (gbufferModelViewInverse * vec4(1.0)).xyz;
                gl_FragColor = vec4(albedo.rgb * normalSample * smoothness + cameraPosition * 0.0
                        + offset * 0.0, albedo.a);
            }
            """;

    private static void verifyEntityVertexConversion() {
        UniformRegistry.ProgramInterfacePlan plan = UniformRegistry.planProgram(
                ENTITY_FRAGMENT_SNIPPET, ENTITY_VERTEX_SNIPPET,
                UniformRegistry.Stage.ENTITY, null, true);
        UniformRegistry.ProgramInterface fragment = plan.effective(UniformRegistry.Stage.ENTITY);
        List<UniformRegistry.UniformDeclaration> uniforms = fragment.executableUniforms();
        assertTrue(uniforms.stream().anyMatch(value -> value.name().equals("cameraPosition")),
                "cameraPosition not executable for entity fixtures");
        LegacyGlslConverter.TerrainVertexConversion vertex;
        try {
            vertex = LegacyGlslConverter.convertEntityVertexChecked(ENTITY_VERTEX_SNIPPET, null,
                    ENTITY_FRAGMENT_SNIPPET, Map.of(), false, uniforms);
        } catch (IllegalArgumentException failure) {
            throw new AssertionError("entity vertex conversion failed: " + failure.getMessage());
        }
        assertTrue(vertex != null, "entity vertex conversion returned null");
        String source = vertex.source();
        assertTrue(source.contains("out flat vec3 tangentFrame"),
                "flat qualifier lost: " + source);
        assertTrue(source.contains("mat4 chimeraEntityInverseModelView()"),
                "inverse model view is not mat4");
        assertTrue(!source.contains("chimeraEntityCameraPosition"),
                "zero camera placeholder survived");
        assertTrue(source.contains("uniform ChimeraEntityUniforms"),
                "live uniform block missing from entity vertex");
        assertTrue(source.contains("vec4 chimeraEntityTangentValue()"),
                "entity tangent sanitizer missing from generated vertex source");
        assertTrue(source.contains("chimeraEntityTangentValue().xyz"),
                "authored tangent did not route through the sanitizer: " + source);
        assertTrue(!source.contains("* Tangent.xyz"),
                "authored tangent bypassed the generated sanitizer: " + source);
        assertTrue(!source.contains("uniform vec3 cameraPosition;"),
                "authored camera declaration collides with the block: " + source);
        String convertedFragment = LegacyGlslConverter.FragmentConversionRequest
                .of(ENTITY_FRAGMENT_SNIPPET, null, true, new int[]{0, 25, 26})
                .withTerrainLayout(vertex.layout())
                .withInterfacePlan(fragment)
                .convert();
        assertTrue(convertedFragment != null, "entity fragment conversion failed");
        compileVertexShader(source, "entity Fixture vertex");
        compileFragmentShader(convertedFragment, "entity Fixture fragment");
        System.out.println("[chimera] entity vertex conversion: PASS");
    }

    private static void compileVertexShader(String source, String what) {
        long compiler = org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize();
        long result = 0;
        try {
            result = org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv(compiler, source,
                    org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader,
                    "entity_fixture.vsh", "main", 0);
            assertTrue(result != 0
                            && org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status(result)
                            == org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success,
                    what + " did not compile: " + (result == 0 ? "no result"
                            : org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message(result)));
        } finally {
            if (result != 0) org.lwjgl.util.shaderc.Shaderc.shaderc_result_release(result);
            org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static void compileFragmentShader(String source, String what) {
        long compiler = org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize();
        long result = 0;
        try {
            result = org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv(compiler, source,
                    org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader,
                    "entity_fixture.fsh", "main", 0);
            assertTrue(result != 0
                            && org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status(result)
                            == org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success,
                    what + " did not compile: " + (result == 0 ? "no result"
                            : org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message(result)));
        } finally {
            if (result != 0) org.lwjgl.util.shaderc.Shaderc.shaderc_result_release(result);
            org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static void verifyElytraUniformBridge() {
        UniformRegistry.UniformDescriptor descriptor =
                UniformRegistry.descriptor("isElytraFlying");
        assertTrue(descriptor != null, "isElytraFlying missing from catalog");
        assertTrue(descriptor.accepts("int") && !descriptor.accepts("bool"),
                "isElytraFlying must be int-typed: " + descriptor.acceptedTypes());
        // Prepared pack sources allow the bool->int translation; the live
        // field then enters the generated UBO and the negation becomes an
        // integer comparison. The entity fragment conversion requires the
        // paired vertex layout, so both stages come from one snippet pair.
        UniformRegistry.ProgramInterfacePlan plan = UniformRegistry.planProgram(
                ELYTRA_SNIPPET, ELYTRA_VERTEX_SNIPPET, UniformRegistry.Stage.ENTITY, null, true);
        UniformRegistry.ProgramInterface fragment = plan.effective(UniformRegistry.Stage.ENTITY);
        assertTrue(fragment.executable(), "elytra interface not executable: " + fragment.deviations());
        assertTrue(fragment.executableUniforms().stream()
                        .anyMatch(uniform -> uniform.name().equals("isElytraFlying")),
                "isElytraFlying did not land in executable uniforms");
        LegacyGlslConverter.TerrainVertexConversion vertex = LegacyGlslConverter.convertEntityVertex(
                ELYTRA_VERTEX_SNIPPET, null, ELYTRA_SNIPPET, java.util.Map.of());
        assertTrue(vertex != null, "elytra vertex bridge failed");
        String converted = LegacyGlslConverter.FragmentConversionRequest
                .of(ELYTRA_SNIPPET, null, true, new int[0])
                .withTerrainLayout(vertex.layout())
                .withInterfacePlan(fragment)
                .convert();
        assertTrue(converted != null, "elytra fragment conversion failed");
        assertTrue(converted.contains("chimeraIsElytraFlying != 0"),
                "bool read was not translated: " + converted);
        assertTrue(!converted.contains("!isElytraFlying"),
                "raw bool negation remained: " + converted);
        assertTrue(converted.contains("int isElytraFlying"),
                "catalog int field was not emitted: " + converted);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
