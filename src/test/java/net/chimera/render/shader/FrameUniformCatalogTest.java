package net.chimera.render.shader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.chimera.shaderpack.ConformanceReport;
import net.chimera.shaderpack.PackProbe;
import net.chimera.shaderpack.PackProgramPlan;
import net.chimera.shaderpack.UniformRegistry;
import net.chimera.testing.TestPacks;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The canonical uniform catalog and the frame state it serves. */
final class FrameUniformCatalogTest {
    @Test
    void fixturePack() throws Exception {
        verifyFixture(TestPacks.fixture("m6_2/uniform_catalog"), TestPacks.baseline("m6_2"));
    }

    @Test
    void catalog() {
        List<UniformRegistry.UniformDescriptor> catalog = UniformRegistry.catalog();
        List<String> names = catalog.stream().map(UniformRegistry.UniformDescriptor::name).toList();
        List<String> sorted = names.stream().sorted().toList();
        assertEquals(sorted, names, "catalog ordering");
        assertEquals(List.of("vec3"), UniformRegistry.descriptor("cameraPosition").acceptedTypes(),
                "camera descriptor type");
        assertEquals(UniformRegistry.Availability.LIVE,
                UniformRegistry.descriptor("eyeBrightness").availability(),
                "eye brightness status");
        assertEquals(UniformRegistry.Availability.LIVE,
                UniformRegistry.descriptor("wetness").availability(),
                "wetness status");
        assertEquals(UniformRegistry.Availability.LIVE,
                UniformRegistry.descriptor("eyeBrightnessSmooth").availability(),
                "eye smoothing status");
        assertTrue(UniformRegistry.descriptor("cameraPosition", "float") == null,
                "typed catalog accepted a conflicting declaration");
        assertTrue(UniformRegistry.descriptor("cameraPosition", "vec3") != null,
                "typed catalog rejected the declared type");

        UniformRegistry.ProgramInterface conflict = UniformRegistry.plan(
                "uniform vec3 cameraPosition; uniform float cameraPosition;"
                        + "void main() { float x = cameraPosition.x; }",
                UniformRegistry.Stage.POST);
        assertTrue(conflict.deviations().contains("UNIFORM_CONFLICT:cameraPosition"),
                "conflicting declaration was not reported");
        assertTrue(!conflict.executable(), "conflicting declaration remained executable");
    }

    @Test
    void frameMath() {
        assertEquals(0, PackFrameState.wrapFrameCounter(PackFrameState.FRAME_COUNTER_WRAP),
                "frame counter wrap");
        assertEquals(0.0f, PackFrameState.wrapFrameTimeCounter(
                PackFrameState.FRAME_TIME_COUNTER_WRAP + 0.5f), "Iris time counter reset");
        assertEquals(-30000.0, PackFrameState.cameraShift(30001.0, 0.0),
                "camera origin shift");
        assertEquals(0.0, PackFrameState.cameraShift(100.0, 0.0),
                "camera origin stability");
        assertEquals(0.0f, PackFrameState.smooth(0.0f, 1.0f, 600.0f, 200.0f, 0.0f),
                "smoothing with zero delta");
        assertTrue(PackFrameState.smooth(0.0f, 1.0f, 600.0f, 200.0f, 0.1f) > 0.0f,
                "smoothing did not advance");

        Matrix4f singular = new Matrix4f().zero();
        Matrix4f inverse = new Matrix4f().zero();
        PackFrameState.invertOrIdentityForTest(singular, inverse);
        assertEquals(1.0f, inverse.m00(), "singular matrix fallback");
        assertEquals(1.0f, inverse.m33(), "singular matrix identity fallback");
    }

    @Test
    void frameClock() {
        PackFrameState state = new PackFrameState();
        MappedBuffer buffer = new MappedBuffer(4);
        state.advanceFrameTime(0.0f);
        assertFrameFloat(state, buffer, "frameTime", 0.0f, "first frame duration");
        assertFrameFloat(state, buffer, "frameTimeCounter", 0.0f, "first frame counter");

        float stalledFrame = PackFrameState.quantizedFrameSecondsForTest(640_750_000L);
        state.advanceFrameTime(stalledFrame);
        assertFrameFloat(state, buffer, "frameTime", 0.640f, "uncapped stall duration");
        assertFrameFloat(state, buffer, "frameTimeCounter", 0.640f, "uncapped stall counter");
        float shortFrame = PackFrameState.quantizedFrameSecondsForTest(16_900_000L);
        state.advanceFrameTime(shortFrame);
        assertFrameFloat(state, buffer, "frameTime", 0.016f, "short frame quantization");
        assertFrameFloat(state, buffer, "frameTimeCounter", stalledFrame + shortFrame,
                "short frame accumulation");
        state.advanceFrameTime(PackFrameState.quantizedFrameSecondsForTest(-1L));
        assertFrameFloat(state, buffer, "frameTime", 0.0f, "negative elapsed duration");
        assertFrameFloat(state, buffer, "frameTimeCounter", stalledFrame + shortFrame,
                "negative elapsed counter hold");

        state.advanceFrameTime(3600.5f);
        assertFrameFloat(state, buffer, "frameTime", 3600.5f, "long stall duration");
        assertFrameFloat(state, buffer, "frameTimeCounter", 0.0f, "hour crossing reset");
        state.advanceFrameTime(shortFrame);
        assertFrameFloat(state, buffer, "frameTimeCounter", shortFrame, "post-hour accumulation");
        state.resetSession();
        assertFrameFloat(state, buffer, "frameTime", 0.0f, "session duration reset");
        assertFrameFloat(state, buffer, "frameTimeCounter", 0.0f, "session counter reset");
        state.advanceFrameTime(0.0f);
        assertFrameFloat(state, buffer, "frameTimeCounter", 0.0f, "new session first frame");

        // Closed-form smoothing uses elapsed time too; a long frame needs no integrator cap.
        float oneStep = PackFrameState.smooth(0.0f, 1.0f, 600.0f, 200.0f, stalledFrame);
        float twoSteps = PackFrameState.smooth(
                PackFrameState.smooth(0.0f, 1.0f, 600.0f, 200.0f, 0.320f),
                1.0f, 600.0f, 200.0f, 0.320f);
        assertTrue(Math.abs(oneStep - twoSteps) < 1.0e-6f,
                "elapsed-time smoothing consistency");
    }

    private static void assertFrameFloat(PackFrameState state, MappedBuffer buffer,
                                         String name, float expected, String message) {
        state.write(UniformRegistry.descriptor(name), "float", buffer);
        assertEquals(expected, buffer.getFloat(0), message);
    }

    @Test
    void stableBuffers() {
        PackUniformProvider.resetSession();
        PackUniformProvider provider = PackUniformProvider.shared();
        MappedBuffer first = provider.supplier("frameTimeCounter", "float").get();
        MappedBuffer second = provider.supplier("frameTimeCounter", "float").get();
        assertTrue(first == second, "frame uniform buffer was recreated");

        assertTrue(provider.supplier("cameraPosition", "vec3").get()
                        != provider.supplier("cameraPositionInt", "ivec3").get(),
                "typed bindings reused an incompatible buffer");
    }

    private static void verifyFixture(Path fixture, Path baselinePath) throws IOException {
        PackProbe.Analysis first = PackProbe.analyze(fixture);
        PackProbe.Analysis second = PackProbe.analyze(fixture);
        assertEquals(first.report().toJson(), second.report().toJson(),
                "fixture report stability");

        ConformanceReport report = first.report();
        ConformanceReport.ProgramReport composite = report.program("composite");
        assertTrue(composite != null, "live fixture composite is missing");
        assertTrue(composite.support() == ConformanceReport.SupportStatus.SUPPORTED
                        || composite.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                "live fixture composite is not executable");
        assertTrue(!composite.deviations().contains("UNIFORM_DEFAULTED:wetness"),
                "live wetness remained defaulted");
        assertTrue(!composite.deviations().contains("UNIFORM_DEFAULTED:eyeBrightnessSmooth"),
                "live smoothing remained defaulted");
        assertTrue(composite.uniforms().contains("cameraPosition"),
                "live camera uniform was not inventoried");

        ConformanceReport.ProgramReport unsupported = report.program("composite1");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK, unsupported.support(),
                "unsupported uniform fallback");
        assertTrue(unsupported.deviations().contains("UNIFORM_NAME_UNSUPPORTED:fogDensity"),
                "unsupported uniform deviation");
        assertTrue(report.shouldAttempt("composite"), "supported program was rejected");
        assertTrue(!report.shouldAttempt("composite1"), "unsupported program was accepted");

        PackProgramPlan planned = first.plan().program("composite");
        assertTrue(planned != null && planned.executable(),
                "runtime plan disagrees with the report");
        assertTrue(planned.interfacePlan().uniforms().stream()
                        .map(UniformRegistry.UniformDeclaration::name)
                        .toList().equals(planned.interfacePlan().uniforms().stream()
                                .map(UniformRegistry.UniformDeclaration::name).sorted().toList()),
                "uniform interface ordering is not deterministic");
        assertTrue(planned.convertedFragment() != null,
                "fixture did not produce converted post source");

        JsonObject baseline = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        if ("TO_BE_FILLED".equals(baseline.get("reportSha256").getAsString())) {
            System.out.println("[chimera] M6.2 fixture report hash: " + report.sha256());
            System.out.println("[chimera] M6.2 fixture source hashes: " + sourceHashes(report));
            System.out.println("[chimera] M6.2 fixture deviations: " + allDeviations(report));
            return;
        }
        assertEquals(baseline.get("reportSha256").getAsString(), report.sha256(),
                "fixture report hash");
        assertEquals(expectedHashes(baseline), sourceHashes(report),
                "fixture source hashes");
        assertEquals(strings(baseline.getAsJsonArray("expectedExecutablePrograms")),
                report.programs().stream()
                        .filter(value -> value.support() == ConformanceReport.SupportStatus.SUPPORTED
                                || value.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION)
                        .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                "executable program baseline");
        assertEquals(strings(baseline.getAsJsonArray("expectedFallbackPrograms")),
                report.programs().stream()
                        .filter(value -> value.support() == ConformanceReport.SupportStatus.IDENTITY_FALLBACK)
                        .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                "fallback program baseline");
        assertEquals(strings(baseline.getAsJsonArray("expectedDeviations")),
                allDeviations(report), "deviation baseline");
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
}
