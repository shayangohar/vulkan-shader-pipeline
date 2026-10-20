package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
    private FullscreenDefaultSampler() {}

    /**
     * {@code source} with each sampler2D that neither Iris nor the pack serves read as colortex0. A
     * source the tree cannot parse stays as written; its conversion reports the failure.
     */
    static String apply(String source, PackSettingsPlan settings, List<String> deviations) {
        if (source == null) return null;
        GlslTree tree = GlslDeclarations.parseOrNull(source);
        if (tree == null) return source;
        String colortex0 = null;
        Set<String> names = new TreeSet<>();
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (variable.name().equals("colortex0") && variable.qualified("uniform") && colortex0 == null) {
                colortex0 = variable.type();
            }
            if (lineSampler2D(tree, variable)) names.add(variable.name());
        }
        if (colortex0 != null && !colortex0.equals("sampler2D")) return source;
        boolean declared = colortex0 != null;
        GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(GlslLexer.stripComments(source));
        String result = source;
        for (String name : names) {
            boolean unused = usage.successful() && !usage.liveSamplers().contains(name);
            if (unused || served(name, settings)) continue;
            // colortex0 is declared once: a second sampler that reads it loses its own declaration.
            if (declared) result = withoutDeclaration(result, name);
            result = GlslScopedReferences.replace(result, Map.of(name, "colortex0"));
            deviations.add("SAMPLER_UNIT0_COLORTEX0:" + name);
            declared = true;
        }
        return result;
    }

    /** {@code uniform sampler2D name;} alone at the start of its line, after any indentation. */
    private static boolean lineSampler2D(GlslTree tree, GlslDeclarations.Variable variable) {
        return variable.qualifiers().equals(List.of("uniform")) && variable.type().equals("sampler2D")
                && variable.plain() && GlslDeclarations.indentationStart(tree.source(),
                tree.offset(variable.declaration().range().start())) >= 0;
    }

    /** The source without the line-starting {@code uniform sampler2D name;}; its indentation stays. */
    private static String withoutDeclaration(String source, String name) {
        GlslTree tree = GlslTree.parse(source);
        List<GlslDeclarations.TextEdit> edits = new ArrayList<>();
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (variable.name().equals(name) && lineSampler2D(tree, variable)) {
                edits.add(new GlslDeclarations.TextEdit(tree.offset(variable.declaration().range().start()),
                        tree.offset(variable.declaration().range().end()), ""));
            }
        }
        return GlslDeclarations.apply(source, edits);
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
