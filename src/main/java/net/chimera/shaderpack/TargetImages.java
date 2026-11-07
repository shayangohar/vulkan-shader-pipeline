package net.chimera.shaderpack;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Iris's colorimgN: colour target N as a storage image, the texture the program samples as
 * colortexN (IrisImages.addRenderTargetImages, the flip state before the pass). Chimera binds it in
 * the prepare, deferred and composite passes, as pass computes bind it (PassComputePlan), and in
 * the hand programs, which draw in their own lane runs (PackFamilyWindows) that hold the target for
 * them. Iris also gives it to the other gbuffers programs and to final, where Chimera still refuses
 * it (KNOW-826).
 */
public final class TargetImages {
    private static final Pattern SYMBOL = Pattern.compile("colorimg(1[0-5]|[0-9])");

    private TargetImages() {}

    /** The target {@code symbol} names as colorimgN, or -1. */
    public static int target(String symbol) {
        if (symbol == null) return -1;
        Matcher matcher = SYMBOL.matcher(symbol);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    /** The resource a colorimgN binding names: the colour target, as its sampler names it. */
    static String resource(int target) {
        return "colortex" + target;
    }

    /** Whether Chimera binds colorimgN in {@code program}: a post pass other than final, or a hand program. */
    public static boolean servedIn(String program) {
        return PostTargetPlan.isPostProgramName(program) && !"final".equals(program)
                || FamilyAdapterRegistry.isHandFamily(program);
    }

    /** Whether {@code program} draws the world, where it reads the targets as world programs do. */
    static boolean world(String program) {
        return !PostTargetPlan.isPostProgramName(program);
    }

    /**
     * The targets a program names as colorimgN in a declaration its stage references, ascending,
     * which it reads as its samplers read their targets (PackTargetGraphPlan.readTargets).
     */
    static Set<Integer> referenced(PackProgramPlan program) {
        Set<Integer> targets = new TreeSet<>();
        if (program == null || !servedIn(program.name())) return targets;
        for (PreparedShaderSource stage : program.stages().values()) {
            String source = stage == null ? null : stage.source();
            if (source == null || !source.contains("colorimg")) continue;
            Matcher declaration = GraphicsImageScan.GRAPHICS_IMAGE_DECLARATION.matcher(source);
            while (declaration.find()) {
                int target = target(declaration.group(3));
                if (target >= 0 && GlslTokenRewriter.identifierCount(source, declaration.group(3)) > 1) {
                    targets.add(target);
                }
            }
        }
        return targets;
    }
}
