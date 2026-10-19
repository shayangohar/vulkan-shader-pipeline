package net.chimera.shaderpack;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Iris binds colortex0 to texture unit 0 in every fullscreen pass (begin, prepare, deferred, composite
 * and final: {@code IrisSamplers.addRenderTargetSamplers} adds it as the default sampler), and a sampler
 * Iris does not serve keeps GL's default unit, 0. A {@code sampler2D} Iris does not know therefore reads
 * colortex0 there. I Like Vanilla's composites read their scene through {@code tex} and declare no
 * colortex0. Renaming such a sampler to colortex0 before planning gives every later step that meaning
 * (KNOW-635). Another sampler type clashes with colortex0 on unit 0 and Iris's draw fails, so it stays
 * unmapped.
 */
final class FullscreenDefaultSampler {
    /** Names Iris binds itself in a fullscreen pass: one Chimera cannot serve is Chimera's gap, not unit 0. */
    private static final Set<String> IRIS_SERVED = Set.of("depthtex0", "depthtex1", "depthtex2", "gdepthtex",
            "shadow", "watershadow", "shadowtex0", "shadowtex1", "shadowtex0HW", "shadowtex1HW", "shadowtex0DH",
            "shadowtex1DH", "shadowcolor", "shadowcolor0", "shadowcolor1", "noisetex", "dhDepthTex", "dhDepthTex0",
            "dhDepthTex1", "iris_centerDepthSmooth");
    private static final Pattern SAMPLER_2D = Pattern.compile(
            "(?m)^[ \\t]*uniform\\s+sampler2D\\s+([A-Za-z_]\\w*)\\s*;");
    private static final Pattern COLORTEX0 = Pattern.compile("\\buniform\\s+(\\w+)\\s+colortex0\\s*;");

    private FullscreenDefaultSampler() {}

    /** {@code source} with each sampler2D that neither Iris nor the pack serves read as colortex0. */
    static String apply(String source, PackSettingsPlan settings, List<String> deviations) {
        if (source == null) return null;
        String stripped = GlslLexer.stripComments(source);
        Matcher colortex0 = COLORTEX0.matcher(stripped);
        boolean declared = colortex0.find();
        if (declared && !colortex0.group(1).equals("sampler2D")) return source;
        Set<String> names = new TreeSet<>();
        Matcher matcher = SAMPLER_2D.matcher(stripped);
        while (matcher.find()) names.add(matcher.group(1));
        GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(stripped);
        String result = source;
        for (String name : names) {
            boolean unused = usage.successful() && !usage.liveSamplers().contains(name);
            if (unused || served(name, settings)) continue;
            // colortex0 is declared once: a second sampler that reads it loses its own declaration.
            if (declared) {
                result = result.replaceAll("(?m)^([ \\t]*)uniform\\s+sampler2D\\s+" + name + "\\s*;", "$1");
            }
            result = GlslScopedReferences.replace(result, Map.of(name, "colortex0"));
            deviations.add("SAMPLER_UNIT0_COLORTEX0:" + name);
            declared = true;
        }
        return result;
    }

    private static boolean served(String name, PackSettingsPlan settings) {
        if (IRIS_SERVED.contains(name) || PackResourcePlan.targetIndex(name) != null) return true;
        String lookup = PackResourcePlan.isProgramShadowAlias(name)
                ? PackResourcePlan.programResource(name, false) : name;
        return InterfacePlanner.samplerSlot(name, lookup, UniformRegistry.Stage.POST,
                settings.customSamplerSlots(), new TreeSet<>()) != null
                || settings.resourceDeclarations().values().stream().anyMatch(value -> name.equals(value.sampler()))
                || PackResourcePlan.advancedImageName(settings.propertyValues(), name) != null;
    }
}
