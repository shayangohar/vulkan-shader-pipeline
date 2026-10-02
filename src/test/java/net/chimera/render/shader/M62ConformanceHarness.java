package net.chimera.render.shader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.chimera.shaderpack.ConformanceReport;
import net.chimera.shaderpack.PackProbe;
import net.chimera.shaderpack.PackProgramPlan;
import net.chimera.shaderpack.UniformRegistry;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic M6.2 checks for the canonical uniform catalog and frame state. */
public final class M62ConformanceHarness {
    private M62ConformanceHarness() {}

    public static void main(String[] args) throws IOException {
        Path fixtureRoot = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        Path fixture = fixtureRoot.resolve("m6_2/uniform_catalog");
        Path baselinePath = Path.of(System.getProperty(
                "chimera.m62.baseline", fixtureRoot.resolve("baselines/m6_2.json").toString()));

        verifyCatalog();
        verifyFrameMath();
        verifyStableBuffers();
        verifyFixture(fixture, baselinePath);
        System.out.println("[chimera] M6.2 canonical uniform catalog conformance: PASS");
    }

    private static void verifyCatalog() {
        List<UniformRegistry.UniformDescriptor> catalog = UniformRegistry.catalog();
        List<String> names = catalog.stream().map(UniformRegistry.UniformDescriptor::name).toList();
        List<String> sorted = names.stream().sorted().toList();
        assertEquals(sorted, names, "M6.2 catalog ordering");
        assertEquals(List.of("vec3"), UniformRegistry.descriptor("cameraPosition").acceptedTypes(),
                "M6.2 camera descriptor type");
        assertEquals(UniformRegistry.Availability.LIVE,
                UniformRegistry.descriptor("eyeBrightness").availability(),
                "M6.2 eye brightness status");
        assertEquals(UniformRegistry.Availability.LIVE,
                UniformRegistry.descriptor("wetness").availability(),
                "M6.2 wetness status");
        assertEquals(UniformRegistry.Availability.LIVE,
                UniformRegistry.descriptor("eyeBrightnessSmooth").availability(),
                "M6.2 eye smoothing status");
        assertTrue(UniformRegistry.descriptor("cameraPosition", "float") == null,
                "M6.2 typed catalog accepted a conflicting declaration");
        assertTrue(UniformRegistry.descriptor("cameraPosition", "vec3") != null,
                "M6.2 typed catalog rejected the declared type");

        UniformRegistry.ProgramInterface conflict = UniformRegistry.plan(
                "uniform vec3 cameraPosition; uniform float cameraPosition;"
                        + "void main() { float x = cameraPosition.x; }",
                UniformRegistry.Stage.POST);
        assertTrue(conflict.deviations().contains("UNIFORM_CONFLICT:cameraPosition"),
                "M6.2 conflicting declaration was not reported");
        assertTrue(!conflict.executable(), "M6.2 conflicting declaration remained executable");
    }

    private static void verifyFrameMath() {
        assertEquals(0, PackFrameState.wrapFrameCounter(PackFrameState.FRAME_COUNTER_WRAP),
                "M6.2 frame counter wrap");
        assertEquals(0.5f, PackFrameState.wrapFrameTimeCounter(
                PackFrameState.FRAME_TIME_COUNTER_WRAP + 0.5f), "M6.2 time counter wrap");
        assertEquals(-30000.0, PackFrameState.cameraShift(30001.0, 0.0),
                "M6.2 camera origin shift");
        assertEquals(0.0, PackFrameState.cameraShift(100.0, 0.0),
                "M6.2 camera origin stability");
        assertEquals(0.0f, PackFrameState.smooth(0.0f, 1.0f, 600.0f, 200.0f, 0.0f),
                "M6.2 smoothing with zero delta");
        assertTrue(PackFrameState.smooth(0.0f, 1.0f, 600.0f, 200.0f, 0.1f) > 0.0f,
                "M6.2 smoothing did not advance");

        Matrix4f singular = new Matrix4f().zero();
        Matrix4f inverse = new Matrix4f().zero();
        PackFrameState.invertOrIdentityForTest(singular, inverse);
        assertEquals(1.0f, inverse.m00(), "M6.2 singular matrix fallback");
        assertEquals(1.0f, inverse.m33(), "M6.2 singular matrix identity fallback");
    }

    private static void verifyStableBuffers() {
        PackUniformProvider.resetSession();
        PackUniformProvider provider = PackUniformProvider.shared();
        Uniform.Info firstInfo = Uniform.createUniformInfo("float", "frameTimeCounter");
        MappedBuffer first = provider.supplier(firstInfo).get();
        MappedBuffer second = provider.supplier(firstInfo).get();
        assertTrue(first == second, "M6.2 frame uniform buffer was recreated");

        Uniform.Info vectorInfo = Uniform.createUniformInfo("vec3", "cameraPosition");
        Uniform.Info integerInfo = Uniform.createUniformInfo("ivec3", "cameraPositionInt");
        assertTrue(provider.supplier(vectorInfo).get() != provider.supplier(integerInfo).get(),
                "M6.2 typed bindings reused an incompatible buffer");
    }

    private static void verifyFixture(Path fixture, Path baselinePath) throws IOException {
        PackProbe.Analysis first = PackProbe.analyze(fixture);
        PackProbe.Analysis second = PackProbe.analyze(fixture);
        assertEquals(first.report().toJson(), second.report().toJson(),
                "M6.2 fixture report stability");

        ConformanceReport report = first.report();
        ConformanceReport.ProgramReport composite = report.program("composite");
        assertTrue(composite != null, "M6.2 live fixture composite is missing");
        assertTrue(composite.support() == ConformanceReport.SupportStatus.SUPPORTED
                        || composite.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                "M6.2 live fixture composite is not executable");
        assertTrue(!composite.deviations().contains("UNIFORM_DEFAULTED:wetness"),
                "M6.2 live wetness remained defaulted");
        assertTrue(!composite.deviations().contains("UNIFORM_DEFAULTED:eyeBrightnessSmooth"),
                "M6.2 live smoothing remained defaulted");
        assertTrue(composite.uniforms().contains("cameraPosition"),
                "M6.2 live camera uniform was not inventoried");

        ConformanceReport.ProgramReport unsupported = report.program("composite1");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK, unsupported.support(),
                "M6.2 unsupported uniform fallback");
        assertTrue(unsupported.deviations().contains("UNIFORM_NAME_UNSUPPORTED:fogDensity"),
                "M6.2 unsupported uniform deviation");
        assertTrue(report.shouldAttempt("composite"), "M6.2 supported program was rejected");
        assertTrue(!report.shouldAttempt("composite1"), "M6.2 unsupported program was accepted");

        PackProgramPlan planned = first.plan().program("composite");
        assertTrue(planned != null && planned.executable(),
                "M6.2 runtime plan disagrees with the report");
        assertTrue(planned.interfacePlan().uniforms().stream()
                        .map(UniformRegistry.UniformDeclaration::name)
                        .toList().equals(planned.interfacePlan().uniforms().stream()
                                .map(UniformRegistry.UniformDeclaration::name).sorted().toList()),
                "M6.2 uniform interface ordering is not deterministic");
        assertTrue(planned.convertedFragment() != null,
                "M6.2 fixture did not produce converted post source");

        JsonObject baseline = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        if ("TO_BE_FILLED".equals(baseline.get("reportSha256").getAsString())) {
            System.out.println("[chimera] M6.2 fixture report hash: " + report.sha256());
            System.out.println("[chimera] M6.2 fixture source hashes: " + sourceHashes(report));
            System.out.println("[chimera] M6.2 fixture deviations: " + allDeviations(report));
            return;
        }
        assertEquals(baseline.get("reportSha256").getAsString(), report.sha256(),
                "M6.2 fixture report hash");
        assertEquals(expectedHashes(baseline), sourceHashes(report),
                "M6.2 fixture source hashes");
        assertEquals(strings(baseline.getAsJsonArray("expectedExecutablePrograms")),
                report.programs().stream()
                        .filter(value -> value.support() == ConformanceReport.SupportStatus.SUPPORTED
                                || value.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION)
                        .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                "M6.2 executable program baseline");
        assertEquals(strings(baseline.getAsJsonArray("expectedFallbackPrograms")),
                report.programs().stream()
                        .filter(value -> value.support() == ConformanceReport.SupportStatus.IDENTITY_FALLBACK)
                        .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                "M6.2 fallback program baseline");
        assertEquals(strings(baseline.getAsJsonArray("expectedDeviations")),
                allDeviations(report), "M6.2 deviation baseline");
    }

    private static Map<String, String> sourceHashes(ConformanceReport report) {
        Map<String, String> result = new TreeMap<>();
        for (ConformanceReport.ProgramReport program : report.programs()) {
            program.sourceHashes().forEach(result::putIfAbsent);
        }
        return result;
    }

    private static Map<String, String> expectedHashes(JsonObject baseline) {
        Map<String, String> result = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : baseline.getAsJsonObject("sourceHashes").entrySet()) {
            result.put(entry.getKey(), entry.getValue().getAsString());
        }
        return result;
    }

    private static List<String> allDeviations(ConformanceReport report) {
        List<String> result = new ArrayList<>(report.deviations());
        for (ConformanceReport.ProgramReport program : report.programs()) {
            result.addAll(program.deviations());
        }
        return result.stream().distinct().sorted().toList();
    }

    private static List<String> strings(Iterable<JsonElement> values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.getAsString()));
        return result;
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
