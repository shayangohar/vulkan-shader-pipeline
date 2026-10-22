package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A sampler Iris does not register keeps GL's default texture unit, 0 (KNOW-635). In every fullscreen
 * pass (begin, prepare, deferred, composite and final) Iris binds colortex0 there:
 * {@code IrisSamplers.addRenderTargetSamplers} adds it as the default sampler. In a gbuffers or shadow
 * program unit 0 holds the albedo texture ({@code IrisSamplers.addLevelSamplers}, ALBEDO_TEXTURE_UNIT);
 * a draw without texture coordinates binds the white pixel instead, which Iris registers under tex,
 * texture, gtexture, gcolor and colortex0 and Chimera's lanes serve as gtexture. A {@code sampler2D}
 * Iris does not know therefore reads that texture: I Like Vanilla's composites read their scene through
 * {@code tex} and declare no colortex0, and Kappa's particles read gcolor, which a gbuffers program never
 * registers (it gets colortex4 and up). Renaming such a sampler before planning gives every later step
 * that meaning. Another sampler type clashes with the unit's sampler2D and Iris's draw fails, so it
 * stays unmapped.
 */
final class UnitZeroSampler {
    /** What unit 0 holds in a renderer, and the deviation that names a sampler read through it. */
    enum Unit {
        /** A fullscreen pass: colortex0. */
        FULLSCREEN("colortex0", "SAMPLER_UNIT0_COLORTEX0:"),
        /** A gbuffers or shadow program: the draw's albedo texture. */
        WORLD("gtexture", "SAMPLER_UNIT0_ALBEDO:");

        private final String texture;
        private final String deviation;

        Unit(String texture, String deviation) {
            this.texture = texture;
            this.deviation = deviation;
        }

        /** The unit a program's renderer leaves an unregistered sampler on. */
        static Unit of(String program) {
            return PostTargetPlan.isPostProgramName(program) ? FULLSCREEN : WORLD;
        }
    }

    /** Names Iris binds itself in a fullscreen pass: one Chimera cannot serve is Chimera's gap, not unit 0. */
    private static final Set<String> IRIS_SERVED = Set.of("depthtex0", "depthtex1", "depthtex2", "gdepthtex",
            "shadow", "watershadow", "shadowtex0", "shadowtex1", "shadowcolor", "shadowcolor0", "shadowcolor1",
            "noisetex", "dhDepthTex", "dhDepthTex0", "dhDepthTex1", "iris_centerDepthSmooth");

    private UnitZeroSampler() {}

    /**
     * {@code source} with each sampler2D that neither Iris nor the pack serves read as what {@code unit}
     * holds. shadowtex0DH and shadowtex1DH are only probed (IrisSamplers.hasShadowSamplers), never
     * registered. A source the tree cannot parse stays as written; its conversion reports the failure.
     */
    static String apply(String source, PackConfig.PackConfigData config, List<String> deviations, Unit unit) {
        if (source == null) return null;
        if (unit == Unit.FULLSCREEN) source = withCompositeAliases(source, deviations);
        GlslTree tree = GlslDeclarations.parseOrNull(source);
        if (tree == null) return source;
        String unitType = uniformType(tree, unit.texture);
        if (unitType != null && !unitType.equals("sampler2D")) return source;
        boolean declared = unitType != null;
        GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(GlslLexer.stripComments(source));
        String result = source;
        for (String name : lineSampler2Ds(tree)) {
            boolean unused = usage.successful() && !usage.liveSamplers().contains(name);
            if (unused || name.equals(unit.texture) || served(name, config, unit)) continue;
            // The unit's texture is declared once: a second sampler that reads it loses its own declaration.
            if (declared) result = withoutDeclaration(result, name);
            result = GlslScopedReferences.replace(result, Map.of(name, unit.texture));
            deviations.add(unit.deviation + name);
            declared = true;
        }
        return result;
    }

    /**
     * Iris registers gdepthtex together with depthtex0 (IrisSamplers.addCompositeSamplers), so in a
     * fullscreen pass or a compute both names read the one texture: a gdepthtex sampler2D reads as
     * depthtex0, and loses its declaration where depthtex0 is declared too. A stage texture of
     * either name still replaces it (IrisSamplerOrder.Order.names). A source where that cannot be
     * done cleanly stays as written.
     */
    static String withCompositeAliases(String source, List<String> deviations) {
        if (source == null || !source.contains("gdepthtex")) return source;
        GlslTree tree = GlslDeclarations.parseOrNull(source);
        if (tree == null) return source;
        GlslDeclarations.Variable alias = null;
        String depthType = null;
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (!variable.qualified("uniform")) continue;
            if (variable.name().equals("gdepthtex")) alias = variable;
            if (variable.name().equals("depthtex0")) depthType = variable.type();
        }
        if (alias == null || !lineSampler2D(tree, alias) || depthType != null && !depthType.equals("sampler2D")) {
            return source;
        }
        String result = depthType != null ? withoutDeclaration(source, "gdepthtex") : source;
        if (GlslTokenRewriter.identifierCount(source, "gdepthtex") > 1) {
            deviations.add("STANDARD_RESOURCE_ALIAS:gdepthtex:depthtex0");
        }
        return GlslScopedReferences.replace(result, Map.of("gdepthtex", "depthtex0"));
    }

    /** The type of the first uniform named {@code name}, or null when none is declared. */
    private static String uniformType(GlslTree tree, String name) {
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (variable.name().equals(name) && variable.qualified("uniform")) return variable.type();
        }
        return null;
    }

    /** The names declared as line-starting {@code uniform sampler2D name;}, sorted. */
    private static Set<String> lineSampler2Ds(GlslTree tree) {
        Set<String> names = new TreeSet<>();
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (lineSampler2D(tree, variable)) names.add(variable.name());
        }
        return names;
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

    private static boolean served(String name, PackConfig.PackConfigData config, Unit unit) {
        // shadowcolor2-7 and shadowtexNHW only where Iris binds them; unbound, they read unit 0.
        if (PackResourcePlan.shadowColorIndex(name) >= 0 || name.matches("shadowtex[01]HW")) {
            return ShadowTargetDirectives.bound(name, config);
        }
        PackSettingsPlan settings = config == null ? PackSettingsPlan.empty() : config.settings();
        // A customTexture entry or an image registers its name. A texture.<stage> entry only replaces a
        // registration, so alone it leaves the sampler on unit 0, slot or no slot (KNOW-801).
        List<PackResourceDeclaration> declared = settings.resourceDeclarations().values().stream()
                .filter(value -> name.equals(value.sampler())).toList();
        boolean customTexture = declared.stream().anyMatch(value -> value.key().startsWith("customTexture."));
        boolean image = PackResourcePlan.advancedImageName(settings.propertyValues(), name) != null;
        // Every name addGbufferOrShadowSamplers registers: colortex4 and up, the level, depth, noise
        // and shadow samplers, the DH depth textures, custom textures and images.
        if (unit == Unit.WORLD) return IrisSamplerOrder.WORLD.last(name, image, customTexture) != null;
        if (IRIS_SERVED.contains(name) || PackResourcePlan.targetIndex(name) != null) return true;
        if (customTexture || image) return true;
        String lookup = PackResourcePlan.isProgramShadowAlias(name)
                ? PackResourcePlan.programResource(name, false) : name;
        return declared.isEmpty() && InterfacePlanner.samplerSlot(name, lookup, UniformRegistry.Stage.POST,
                settings.customSamplerSlots(), new TreeSet<>()) != null;
    }
}
