package net.chimera.shaderpack;

import java.nio.file.Path;

/** Deterministic checks for the bounded M8.3 pack shadow contract. */
public final class M83ConformanceHarness {
    private M83ConformanceHarness() {}

    public static void main(String[] args) {
        Path root = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        PackProbe.Analysis analysis = PackProbe.analyze(root.resolve("m8_3/shadow"));
        PackProgramPlan shadow = analysis.plan().program("shadow");
        assertTrue(shadow != null, "M8.3 shadow program was not discovered");
        assertTrue(shadow.executable(), "M8.3 modern shadow was rejected: " + shadow.deviations()
                + " interface=" + shadow.interfacePlan().deviations());
        assertTrue(shadow.convertedVertex() != null
                        && shadow.convertedVertex().startsWith("#version 460"),
                "M8.3 modern shadow vertex was not translated");
        assertTrue(shadow.convertedFragment() != null
                        && shadow.convertedFragment().contains("ChimeraShadowUniforms")
                        && shadow.convertedFragment().startsWith("#version 460"),
                "M8.3 shadow fragment UBO was not generated");
        assertTrue(shadow.deviations().contains("MODERN_SHADOW_VERTEX_BRIDGE"),
                "M8.3 modern shadow bridge deviation is missing");
        assertTrue(shadow.deviations().contains("LIVE_UNIFORM_BRIDGE")
                        || shadow.deviations().contains("UNIFORM_DEFAULTED:shadowFade"),
                "M8.3 shadow uniform catalog was not used");

        PackConfig.ShadowSettings settings = analysis.config().shadowSettings();
        assertTrue(settings.resolution() == 1024, "M8.3 resolution was not applied");
        assertTrue(settings.distance() == 192.0F, "M8.3 distance was not applied");
        assertTrue(settings.distanceRenderMultiplier() == 1.25F,
                "M8.3 distance multiplier was not applied");
        assertTrue(settings.sunPathRotation() == 35.0F
                        && settings.sunPathOffset() == 4.0F,
                "M8.3 sun-path settings were not applied");

        String legacy = "#version 120\nvoid main(){gl_Position=ftransform();}\n";
        assertTrue(LegacyGlslConverter.supportsShadowVertex(legacy,
                        "#version 120\nvoid main(){gl_FragColor=vec4(1.0);}"),
                "legacy shadow compatibility regressed");
        assertTrue(!LegacyGlslConverter.supportsModernShadow(
                "#version 450\nvoid main(){gl_Position=vec4(0.0);}", ""),
                "unsupported modern shadow version was accepted");
        String externalPack = System.getenv("CHIMERA_M83_PACK");
        if (externalPack != null && !externalPack.isBlank()) {
            PackProgramPlan externalShadow = PackProbe.analyze(Path.of(externalPack))
                    .plan().program("shadow");
            if (externalShadow != null && externalShadow.convertedFragment() != null) {
                assertTrue(externalShadow.executable(),
                        "external shadow plan is not executable");
            }
        }
        System.out.println("[chimera] M8.3 shadow conformance: PASS");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

}
