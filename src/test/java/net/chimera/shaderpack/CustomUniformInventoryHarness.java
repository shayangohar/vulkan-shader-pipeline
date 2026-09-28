package net.chimera.shaderpack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The exact-pack custom-uniform inventory, for the two pinned packs.
 *
 * <p>A pack authors derived uniforms in its {@code shaders.properties}, and Chimera either serves
 * one or refuses it. A refusal is only acceptable if it is loud: the declaration is reported, and
 * no program that reads the name may run. This audit reads both installed packs through the same
 * load-time path the game uses, prints the inventory, and fails when a consumed declaration was
 * rejected, when a rejected one left a consuming program executable, or when a served name was not
 * the pack's own declaration.
 */
public final class CustomUniformInventoryHarness {
    private CustomUniformInventoryHarness() {}

    public static void main(String[] args) {
        verifyExpressionFunctions();
        List<String> failures = new ArrayList<>();
        for (String[] configured : new String[][] {
                {"Complementary", "chimera.customUniform.complementary"},
                {"BSL", "chimera.customUniform.bsl"}}) {
            String value = System.getProperty(configured[1],
                    System.getenv(configured[1].replace('.', '_').toUpperCase(java.util.Locale.ROOT)));
            try {
                assertTrue(value != null && !value.isBlank(), configured[1] + " pack path not provided");
                Path packPath = Path.of(value);
                assertTrue(Files.isRegularFile(packPath) || Files.isDirectory(packPath),
                        configured[0] + ": missing pack " + packPath);
                PackProbe.Analysis analysis = PackProbe.analyze(packPath);
                audit(configured[0], analysis);
                if (configured[0].equals("BSL")) {
                    verifyBslTimeAngle(analysis.plan().runtimeSettings());
                }
                System.out.println("[chimera] custom uniform inventory " + configured[0] + ": PASS");
            } catch (AssertionError | Exception failure) {
                failures.add(configured[0] + ": " + failure);
            }
        }
        assertTrue(failures.isEmpty(),
                "Exact pack custom uniform inventory failed: " + String.join("; ", failures));
        System.out.println("[chimera] exact pack custom uniform inventory: PASS");
    }

    private static void audit(String label, PackProbe.Analysis analysis) {
        PackRuntimeSettings settings = analysis.plan().runtimeSettings();
        Map<String, String> consumers = consumers(analysis);

        for (String name : new TreeSet<>(settings.rejected())) {
            String consumer = consumers.get(name);
            assertTrue(settings.deviations().stream().anyMatch(deviation -> deviation.contains(name)),
                    label + ": refused declaration without a diagnostic: " + name);
            assertTrue(consumer == null,
                    label + ": a refused declaration still has an executable consumer: "
                            + name + " in " + consumer);
        }

        for (Map.Entry<String, UniformRegistry.UniformDescriptor> entry
                : new TreeMap<>(settings.customDescriptors()).entrySet()) {
            UniformRegistry.UniformDescriptor descriptor = entry.getValue();
            if (descriptor.availability() != UniformRegistry.Availability.LIVE) {
                continue;
            }
            String name = entry.getKey();
            assertTrue(!UniformRegistry.isEngineInput(name),
                    label + ": a pack-authored name is also an engine input: " + name);
            String consumer = consumers.get(name);
            if (consumer == null) {
                continue;
            }
            PackProgramPlan program = analysis.plan().program(consumer);
            assertTrue(program != null && program.interfacePlan().deviations()
                            .contains("CUSTOM_UNIFORM_BRIDGE:" + name),
                    label + ": " + consumer + " reads " + name
                            + " without the pack's own declaration serving it");
            assertTrue(descriptor.sourceKey().equals("custom:" + name),
                    label + ": served declaration lost its own name: " + name);
        }

        inventory(label, settings, consumers);

        // The names Chimera used to answer for the packs are gone from the catalog, so nothing can
        // serve a synthetic value under them again.
        for (String retired : new String[] {"eyeBrightnessM", "timeAngle", "timeBrightness",
                "shadowFade", "rainFactor", "blindFactor", "framemod2", "framemod4", "framemod8",
                "framemod600", "frameTimeSmooth", "starter", "isEyeInCave", "isCold", "isDesert",
                "isJungle", "isMesa", "isMushroom", "isSavanna", "isSwamp", "inDry", "inRainy",
                "inSnowy", "inNetherWastes", "inCrimsonForest", "inWarpedForest", "inBasaltDeltas",
                "inSoulValley", "inPaleGarden", "maxBlindnessDarkness"}) {
            assertTrue(UniformRegistry.descriptor(retired) == null,
                    label + ": retired synthetic uniform is back in the catalog: " + retired);
        }
    }

    /**
     * BSL derives its sun colour from {@code timeAngle}, a chain of {@code variable.} values over
     * {@code sunAngle}. At noon (sunAngle 0.25) the chain gives 0.25; a wrong result turns
     * a noon scene into dusk.
     */
    private static void verifyBslTimeAngle(PackRuntimeSettings settings) {
        int index = settings.indexOf("timeAngle");
        assertTrue(index >= 0, "BSL timeAngle is not served");
        PackRuntimeSettings.Session session = settings.newSession();
        settings.evaluate((name, component) -> name.equals("sunAngle") ? 0.25 : 0.0, session, 0.05f);
        double tAmin = 0.25 - 0.033333333;
        double tAlin = tAmin * 1.15384615385;
        double tAfrc = (tAlin * 2.0) % 1.0;
        double tAfrs = tAfrc * tAfrc * (3.0 - 2.0 * tAfrc);
        double expected = (tAfrc * 0.7 + tAfrs * 0.3) * 0.5;
        float actual = session.values()[index];
        assertTrue(Math.abs(actual - expected) < 1.0e-4,
                "BSL timeAngle at noon is " + actual + ", expected " + expected);
    }

    /**
     * OptiFine's documented functions evaluate, and a declaration that reads a refused one is
     * refused with it instead of reading zero in its place.
     */
    private static void verifyExpressionFunctions() {
        double[][] cases = {
                {value("frac(-0.25)"), 0.75}, {value("fmod(-1.0, 4.0)"), 3.0},
                {value("floor(1.5) + ceil(1.5)"), 3.0}, {value("pow(2.0, 3.0)"), 8.0},
                {value("atan(1.0, 1.0)"), Math.PI / 4.0}, {value("todeg(torad(90.0))"), 90.0},
                {value("if(0, 1.0, 1, 2.0, 3.0)"), 2.0}, {value("if(0, 1.0, 0, 2.0, 3.0)"), 3.0},
                {value("between(0.5, 0.0, 1.0)"), 1.0}, {value("equals(1.0, 1.05, 0.1)"), 1.0},
                {value("signum(-3.0) + round(1.6) + abs(-1.0)"), 2.0}};
        for (double[] entry : cases) {
            assertTrue(Math.abs(entry[0] - entry[1]) < 1.0e-9,
                    "custom expression function returned " + entry[0] + ", expected " + entry[1]);
        }
        PackRuntimeSettings settings = PackRuntimeSettings.build(List.of(
                PackRuntimeSettings.declaration(false, "float", "broken", "noSuchFunction(1.0)"),
                PackRuntimeSettings.declaration(true, "float", "reader", "broken + 1.0")),
                Map.of(), List.of());
        assertTrue(settings.rejected().contains("reader") && settings.indexOf("reader") < 0,
                "a value reading a refused declaration was not refused: " + settings.deviations());
    }

    private static double value(String expression) {
        PackExpression.Program program = PackExpression.parse(expression, BiomeIds.constants());
        return program.evaluate((name, component) -> 0.0, program.newState(), 0.0f);
    }

    /** Per name, the first executable program whose stages actually read it. */
    private static Map<String, String> consumers(PackProbe.Analysis analysis) {
        Map<String, String> result = new TreeMap<>();
        for (PackProgramPlan program : analysis.plan().programs()) {
            if (program == null || !program.executable()) {
                continue;
            }
            for (UniformRegistry.ProgramInterface stage : program.interfacePlan().stages().values()) {
                for (UniformRegistry.UniformDeclaration uniform : stage.executableUniforms()) {
                    result.putIfAbsent(uniform.name(), program.name());
                }
            }
        }
        return result;
    }

    private static void inventory(String label, PackRuntimeSettings settings,
                                  Map<String, String> consumers) {
        int served = 0;
        for (Map.Entry<String, UniformRegistry.UniformDescriptor> entry
                : new TreeMap<>(settings.customDescriptors()).entrySet()) {
            if (entry.getValue().availability() != UniformRegistry.Availability.LIVE) {
                continue;
            }
            served++;
            System.out.println("[chimera] inventory " + label + " served=" + entry.getKey()
                    + " consumer=" + consumers.getOrDefault(entry.getKey(), "unused")
                    + " defaultPolicy=" + entry.getValue().defaultPolicy());
        }
        System.out.println("[chimera] inventory " + label + " served=" + served
                + " refused=" + settings.rejected().size()
                + " halfLives(wetness,eyeBrightness)=" + settings.wetnessRiseHalfLife()
                + "," + settings.eyeBrightnessHalfLife());
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
