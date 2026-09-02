package net.chimera.render;

import net.chimera.shaderpack.PostTargetPlan;

import java.util.List;
import java.util.Map;

/** Small deterministic checks for the bounded M6.4 target bridge. */
public final class M64ConformanceHarness {
    private M64ConformanceHarness() {}

    public static void main(String[] args) {
        PostTargetPlan.ParseResult route = PostTargetPlan.parse(
                "composite", "#version 120\n/* RENDERTARGETS: 0,4,7 */\n"
                        + "gl_FragData[0] = vec4(1.0);\n"
                        + "gl_FragData[1] = vec4(0.5);\n"
                        + "gl_FragData[2] = vec4(0.25);\n");
        assert route.executable() : route.deviations();
        assert route.plan().targetSlots().equals(List.of(0, 4, 7));
        assert route.plan().targetForOutput(2) == 7;

        PostTargetPlan.ParseResult duplicate = PostTargetPlan.parse(
                "composite", "/* RENDERTARGETS: 0,4,4 */");
        assert duplicate.deviations().contains("POST_TARGET_DIRECTIVE_DUPLICATE:4");

        assert PackPostTargets.deviceSupportsMrt(4, 4);
        assert !PackPostTargets.deviceSupportsMrt(5, 4);
        assert PackPostTargets.bankAfterFinish(0, 1, false) == 0;
        assert PackPostTargets.bankAfterFinish(0, 1, true) == 1;

        boolean[] written = {true, true, false, false, true, false, false, true};
        assert PackPostTargets.areTargetsAvailable(List.of(0, 4, 7), true, written);
        PackPostTargets.invalidateWrittenTargets(written, List.of(7));
        assert !PackPostTargets.areTargetsAvailable(List.of(7), true, written);
        assert PackPostTargets.areTargetsAvailable(List.of(0, 4), true, written);

        Map<Integer, Integer> formats = Map.of(4, 37, 7, 109);
        PostTargetPlan formatted = PostTargetPlan.parse(
                "composite", "/* RENDERTARGETS: 0,4,7 */", formats).plan();
        assert formatted.outputFormats().equals(List.of(97, 37, 109));
    }
}
