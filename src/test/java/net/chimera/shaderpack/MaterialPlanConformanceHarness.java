package net.chimera.shaderpack;

import net.chimera.render.MaterialMapOwner;
import net.chimera.render.PackResourceOwner;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure material planner gates: selectors, stage gating, resource identity, and manifest kind. */
public final class MaterialPlanConformanceHarness {
    private MaterialPlanConformanceHarness() {}

    private static final String MATERIAL_SNIPPET = """
            #version 120
            uniform sampler2D normals;
            uniform sampler2D specular;
            void main() {
                gl_FragColor = texture2D(normals, vec2(0.5)) + texture2D(specular, vec2(0.5));
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
        assertEquals(27, SelectorNamespace.LAST_RESERVED + 1, "selector range changed");
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

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
