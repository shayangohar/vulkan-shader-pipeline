package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Who observes the pack's shadow pass, and so whether it runs. Iris 1.21.1 builds its ShadowRenderer
 * only when {@code shadow.enabled} is true or a program outside the shadow pass samples a shadow
 * target (IrisRenderingPipeline: the ShaderMap's gbuffer programs and the composite renderers create
 * the targets before the renderer is chosen), and turns vanilla entity shadows off with it
 * (shouldDisableVanillaEntityShadows). Iris also skips a shadow pass whose programs only write pack
 * images, so a pack voxelizing its lights there gets none (Mellow's COLORED_LIGHTS; Bloop forces
 * {@code shadow.enabled} under LPV_ENABLED for this). Chimera runs that pass as well.
 *
 * @param enabled the pack's {@code shadow.enabled}, read as Iris reads it
 * @param readers the programs and pass computes outside the shadow pass that read a shadow target
 * @param writers the shadow-pass programs and shadowcomp computes that write a pack image, a colour
 *                target or a storage buffer
 */
public record ShadowPassUse(Enabled enabled, List<String> readers, List<String> writers, List<String> deviations) {
    /** The pass for a session without a plan: runs, as before any pack is read. */
    public static final ShadowPassUse ALWAYS =
            new ShadowPassUse(Enabled.UNSET, List.of("(no plan)"), List.of(), List.of());

    private static final String KEY = "shadow.enabled";
    private static final Set<PassComputePlan.Kind> SHADOW_READS = EnumSet.of(PassComputePlan.Kind.SHADOW_DEPTH,
            PassComputePlan.Kind.SHADOW_COMPARE, PassComputePlan.Kind.SHADOW_COLOR,
            PassComputePlan.Kind.SHADOW_COLOR_IMAGE);
    private static final Set<PassComputePlan.Kind> OUTSIDE_WRITES = EnumSet.of(PassComputePlan.Kind.PACK_IMAGE,
            PassComputePlan.Kind.TARGET_IMAGE, PassComputePlan.Kind.STORAGE_BUFFER);

    /** Iris's OptionalBoolean for {@code shadow.enabled}. */
    public enum Enabled { UNSET, TRUE, FALSE }

    public ShadowPassUse {
        enabled = enabled == null ? Enabled.UNSET : enabled;
        readers = readers == null ? List.of() : List.copyOf(readers);
        writers = writers == null ? List.of() : List.copyOf(writers);
        deviations = deviations == null ? List.of() : List.copyOf(deviations);
    }

    /** Whether a program or pass compute outside the shadow pass reads a shadow target. */
    public boolean mapRead() {
        return !readers.isEmpty();
    }

    /** Whether the shadow pass writes beyond the shadow targets. */
    public boolean sideEffect() {
        return !writers.isEmpty();
    }

    /** Whether the shadow pass runs: not switched off, and its map is read or it writes beyond its targets. */
    public boolean runs() {
        return enabled != Enabled.FALSE && (shadowMap() || sideEffect());
    }

    /** Whether the pack has a shadow map in Iris's sense, which turns vanilla entity shadows off. */
    public boolean replacesEntityShadows() {
        return enabled != Enabled.FALSE && shadowMap();
    }

    private boolean shadowMap() {
        return enabled == Enabled.TRUE || mapRead();
    }

    public static ShadowPassUse of(PackPlan plan, PassComputePlan computes) {
        if (plan == null) return ALWAYS;
        PassComputePlan passComputes = computes == null ? PassComputePlan.empty() : computes;
        List<String> deviations = new ArrayList<>();
        return new ShadowPassUse(enabled(plan.settings().propertyValues(), deviations),
                shadowReaders(plan, passComputes), shadowWriters(plan, passComputes), deviations);
    }

    /** {@code shadow.enabled}: an explicit value reads the same under either fallback; absent or malformed does not. */
    private static Enabled enabled(Map<String, String> properties, List<String> deviations) {
        String malformed = "SHADOW_DIRECTIVE_MALFORMED:" + KEY;
        boolean whenTrue = PackBooleanDirective.read(properties, KEY, true, deviations, malformed);
        boolean whenFalse = PackBooleanDirective.read(properties, KEY, false, new ArrayList<>(), malformed);
        if (whenTrue != whenFalse) return Enabled.UNSET;
        return whenTrue ? Enabled.TRUE : Enabled.FALSE;
    }

    private static List<String> shadowReaders(PackPlan plan, PassComputePlan computes) {
        Set<String> readers = new TreeSet<>();
        plan.resources().bindings().values().stream().flatMap(List::stream)
                .filter(binding -> runs(plan, binding.program()) && !inShadowPass(binding.program()))
                .filter(binding -> binding.kind() == PackResourceKind.SHADOW_DEPTH
                        || binding.kind() == PackResourceKind.SHADOW_COLOR)
                .forEach(binding -> readers.add(binding.program() + ":" + binding.sampler()));
        computes.computes().stream()
                .filter(compute -> runs(plan, compute) && !PassComputePlan.isShadowcomp(compute.pass()))
                .forEach(compute -> compute.bindings().stream().filter(binding -> SHADOW_READS.contains(binding.kind()))
                        .forEach(binding -> readers.add(compute.name() + ":" + binding.symbol())));
        return List.copyOf(readers);
    }

    private static List<String> shadowWriters(PackPlan plan, PassComputePlan computes) {
        PackAdvancedResourcePlan advanced = plan.advancedResources();
        Set<String> writers = new TreeSet<>();
        plan.activePrograms().stream().map(PackProgramPlan::name)
                .filter(name -> plan.shouldAttempt(name) && inShadowPass(name))
                .filter(name -> !advanced.graphicsImages(name).isEmpty() || !advanced.storageBuffers(name).isEmpty())
                .forEach(writers::add);
        computes.computes().stream()
                .filter(compute -> runs(plan, compute) && PassComputePlan.isShadowcomp(compute.pass()))
                .filter(compute -> compute.bindings().stream().map(PassComputePlan.Binding::kind)
                        .anyMatch(OUTSIDE_WRITES::contains))
                .forEach(compute -> writers.add(compute.name()));
        return List.copyOf(writers);
    }

    private static boolean runs(PackPlan plan, String program) {
        return program != null && !plan.isProgramDisabled(program) && plan.shouldAttempt(program);
    }

    private static boolean runs(PackPlan plan, PassComputePlan.PassCompute compute) {
        return compute.supported() && !plan.isProgramDisabled(compute.pass());
    }

    /** The shadow programs and the shadowcomp passes Iris runs with its shadow renderer. */
    private static boolean inShadowPass(String program) {
        return FamilyAdapterRegistry.isShadowFamily(program) || PassComputePlan.isShadowcomp(program);
    }
}
