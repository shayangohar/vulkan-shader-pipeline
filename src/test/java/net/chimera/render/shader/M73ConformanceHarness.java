package net.chimera.render.shader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.chimera.shaderpack.ConformanceReport;
import net.chimera.shaderpack.PackProbe;
import net.chimera.shaderpack.PackProgramPlan;
import net.chimera.shaderpack.PackRuntimeSettings;
import net.chimera.shaderpack.UniformRegistry;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.util.MappedBuffer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic M7.3 checks for live world-state semantics and custom scalars. */
public final class M73ConformanceHarness {
    private M73ConformanceHarness() {}

    public static void main(String[] args) throws Exception {
        Path fixtureRoot = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        Path fixture = fixtureRoot.resolve("m7_3/uniform_semantics");
        Path baselinePath = Path.of(System.getProperty(
                "chimera.m73.baseline", fixtureRoot.resolve("baselines/m7_3.json").toString()));

        verifyCatalog();
        verifyWorldSemantics();
        verifyFixture(fixture, baselinePath);
        System.out.println("[chimera] M7.3 standard uniform and world-state conformance: PASS");
    }

    private static void verifyCatalog() {
        assertEquals(UniformRegistry.Availability.LIVE,
                UniformRegistry.descriptor("wetness").availability(), "M7.3 wetness catalog status");
        assertEquals(UniformRegistry.Availability.LIVE,
                UniformRegistry.descriptor("eyeBrightnessSmooth").availability(),
                "M7.3 eye smoothing catalog status");
        assertEquals(UniformRegistry.Availability.LIVE,
                UniformRegistry.descriptor("dimension").availability(), "M7.3 dimension catalog status");
        assertEquals(UniformRegistry.Availability.DEFAULTED,
                UniformRegistry.descriptor("centerDepthSmooth").availability(),
                "M7.3 depth history remained defaulted");
        assertTrue(UniformRegistry.catalog().stream()
                        .map(UniformRegistry.UniformDescriptor::name).toList().stream().sorted().toList()
                        .equals(UniformRegistry.catalog().stream()
                                .map(UniformRegistry.UniformDescriptor::name).toList()),
                "M7.3 catalog ordering is not deterministic");
    }

    private static void verifyWorldSemantics() {
        assertEquals(0, PackFrameState.irisWorldTimeForTest(12345L, true, false),
                "M7.3 fixed-time world semantics");
        assertEquals(12345, PackFrameState.irisWorldTimeForTest(12345L, true, true),
                "M7.3 Nether/End time semantics");
        assertEquals(3, PackFrameState.irisWorldDayForTest(72000L),
                "M7.3 world day semantics");
        assertEquals(0.007f, PackFrameState.quantizedFrameSecondsForTest(7_250_000L),
                "M7.3 frame time millisecond quantization");
        assertEquals(0.0f, PackFrameState.smooth(0.0f, 1.0f, 600.0f, 200.0f, 0.0f),
                "M7.3 zero-delta smoothing hold");
        assertTrue(PackFrameState.smooth(0.0f, 1.0f, 600.0f, 200.0f, 0.1f) > 0.0f,
                "M7.3 smoothing did not advance");
    }

    private static void verifyFixture(Path fixture, Path baselinePath) throws Exception {
        PackProbe.Analysis first = PackProbe.analyze(fixture);
        PackProbe.Analysis second = PackProbe.analyze(fixture);
        assertEquals(first.report().toJson(), second.report().toJson(),
                "M7.3 report stability");

        PackRuntimeSettings settings = first.plan().runtimeSettings();
        assertTrue(settings.customDescriptors().containsKey("m73Marker"),
                "M7.3 exposed custom uniform was not planned");
        assertTrue(!settings.customDescriptors().containsKey("m73CycleA"),
                "M7.3 invalid cycle became executable");
        assertTrue(settings.deviations().contains("CUSTOM_VALUE_CYCLE:m73CycleA"),
                "M7.3 cycle was not reported");
        assertTrue(settings.deviations().contains("CUSTOM_EXPRESSION_UNKNOWN:m73Unknown:missingM73Value"),
                "M7.3 unknown custom dependency was not reported");
        assertEquals(300.0f, settings.wetnessRiseHalfLife(),
                "M7.3 pack wetness half-life");
        assertEquals(20.0f, settings.eyeBrightnessHalfLife(),
                "M7.3 pack eye brightness half-life");

        double[] scratch = new double[settings.valueCount()];
        float[] output = new float[settings.valueCount()];
        settings.evaluate(name -> name.equals("frameTimeCounter") ? 1.0 : 0.25,
                scratch, output);
        int marker = settings.indexOf("m73Marker");
        assertTrue(marker >= 0 && output[marker] > 2.0f && output[marker] < 2.5f,
                "M7.3 custom dependency evaluation was incorrect");

        PackProgramPlan composite = first.plan().program("composite");
        assertTrue(composite != null && composite.executable(),
                "M7.3 fixture composite was not executable");
        assertTrue(composite.interfacePlan().uniforms().stream()
                        .anyMatch(value -> value.name().equals("m73Marker")),
                "M7.3 custom uniform was not in the shared interface plan");
        assertTrue(composite.interfacePlan().deviations().contains("CUSTOM_UNIFORM_BRIDGE:m73Marker"),
                "M7.3 custom uniform bridge deviation was not recorded");

        PackUniformProvider.resetSession();
        PackUniformProvider.installRuntimeSettings(settings);
        Uniform.Info info = Uniform.createUniformInfo("float", "m73Marker");
        MappedBuffer firstBuffer = PackUniformProvider.shared().supplier(info).get();
        MappedBuffer secondBuffer = PackUniformProvider.shared().supplier(info).get();
        assertTrue(firstBuffer == secondBuffer, "M7.3 custom uniform buffer was recreated");
        PackUniformProvider.resetSession();

        verifyBaseline(first, baselinePath);
    }

    private static void verifyBaseline(PackProbe.Analysis analysis, Path baselinePath) throws Exception {
        JsonObject baseline = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        if ("TO_BE_FILLED".equals(baseline.get("reportSha256").getAsString())) {
            System.out.println("[chimera] M7.3 report hash: " + analysis.report().sha256());
            System.out.println("[chimera] M7.3 source hashes: " + sourceHashes(analysis.report()));
            System.out.println("[chimera] M7.3 settings deviations: "
                    + analysis.plan().runtimeSettings().deviations());
            return;
        }
        assertEquals(baseline.get("reportSha256").getAsString(), analysis.report().sha256(),
                "M7.3 report hash");
        Map<String, String> expected = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry
                : baseline.getAsJsonObject("sourceHashes").entrySet()) {
            expected.put(entry.getKey(), entry.getValue().getAsString());
        }
        assertEquals(expected, sourceHashes(analysis.report()), "M7.3 source hashes");
        assertEquals(strings(baseline.getAsJsonArray("expectedCustomUniforms")),
                analysis.plan().runtimeSettings().customDescriptors().keySet().stream().sorted().toList(),
                "M7.3 custom uniform baseline");
        assertEquals(strings(baseline.getAsJsonArray("expectedSettingsDeviations")),
                analysis.plan().runtimeSettings().deviations(),
                "M7.3 settings deviation baseline");
        assertEquals(strings(baseline.getAsJsonArray("expectedExecutablePrograms")),
                analysis.report().programs().stream()
                        .filter(value -> value.support() == ConformanceReport.SupportStatus.SUPPORTED
                                || value.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION)
                        .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                "M7.3 executable program baseline");
        assertEquals(strings(baseline.getAsJsonArray("expectedFallbackPrograms")),
                analysis.report().programs().stream()
                        .filter(value -> value.support() == ConformanceReport.SupportStatus.IDENTITY_FALLBACK)
                        .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                "M7.3 fallback program baseline");
    }

    private static Map<String, String> sourceHashes(ConformanceReport report) {
        Map<String, String> result = new TreeMap<>();
        for (ConformanceReport.ProgramReport program : report.programs()) {
            program.sourceHashes().forEach(result::putIfAbsent);
        }
        return result;
    }

    private static List<String> strings(Iterable<JsonElement> values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.getAsString()));
        return result;
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
