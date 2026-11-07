package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable blend directives for one program, as Iris reads them from
 * shaders.properties: {@code blend.<program>} replaces the draw's blend for
 * every buffer, and {@code blend.<program>.<buffer>} replaces it for one
 * colour target. Each value is {@code off} or four OpenGL factor names
 * (source colour, destination colour, source alpha, destination alpha).
 *
 * <p>A target with no directive keeps the host draw's own blend, which is the
 * vanilla render type's blend Iris also leaves in place. A malformed
 * directive is ignored with a named deviation, as Iris ignores it.</p>
 */
public record PackBlendPlan(Mode program, Map<Integer, Mode> buffers, List<String> deviations) {

    /** One attachment's blend: disabled, or enabled with Vulkan blend factors and ADD ops. */
    public record Mode(boolean enabled, int srcColor, int dstColor, int srcAlpha, int dstAlpha) {
        public static final Mode OFF = new Mode(false, 0, 0, 0, 0);

        /**
         * Iris's blendFunc for this blend: the four factors as OpenGL enums, or zero with
         * blending off (CommonUniforms reads GlStateManager's blend state).
         */
        public int[] glFunc() {
            if (!enabled) return new int[4];
            return new int[] {glFactor(srcColor), glFactor(dstColor), glFactor(srcAlpha), glFactor(dstAlpha)};
        }
    }

    // VkBlendFactor values 0-14 -> OpenGL blend factor enums.
    private static final int[] GL_FACTORS = {
            0, 1, 0x0300, 0x0301, 0x0306, 0x0307, 0x0302, 0x0303, 0x0304, 0x0305,
            0x8001, 0x8002, 0x8003, 0x8004, 0x0308};

    /** The OpenGL enum of a Vulkan blend factor; zero for one OpenGL has no name for. */
    public static int glFactor(int vkFactor) {
        return vkFactor >= 0 && vkFactor < GL_FACTORS.length ? GL_FACTORS[vkFactor] : 0;
    }

    // OpenGL factor names -> VkBlendFactor values.
    private static final Map<String, Integer> FACTORS = Map.ofEntries(
            Map.entry("ZERO", 0),
            Map.entry("ONE", 1),
            Map.entry("SRC_COLOR", 2),
            Map.entry("ONE_MINUS_SRC_COLOR", 3),
            Map.entry("DST_COLOR", 4),
            Map.entry("ONE_MINUS_DST_COLOR", 5),
            Map.entry("SRC_ALPHA", 6),
            Map.entry("ONE_MINUS_SRC_ALPHA", 7),
            Map.entry("DST_ALPHA", 8),
            Map.entry("ONE_MINUS_DST_ALPHA", 9),
            Map.entry("SRC_ALPHA_SATURATE", 14)
    );

    public PackBlendPlan {
        buffers = buffers == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(buffers));
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
    }

    public static PackBlendPlan empty() {
        return new PackBlendPlan(null, Map.of(), List.of());
    }

    static PackBlendPlan forProgram(String programName, PackSettingsPlan settings) {
        if (programName == null || programName.isBlank() || settings == null) return empty();
        Map<String, String> properties = settings.propertyValues();
        String prefix = "blend." + programName;
        Mode program = null;
        Map<Integer, Mode> buffers = new TreeMap<>();
        List<String> deviations = new ArrayList<>();
        for (Map.Entry<String, String> entry : new TreeMap<>(properties).entrySet()) {
            String key = entry.getKey();
            if (!key.equals(prefix) && !key.startsWith(prefix + ".")) continue;
            Mode mode = parse(entry.getValue());
            if (mode == null) {
                deviations.add("BLEND_DIRECTIVE_MALFORMED:" + key);
                continue;
            }
            if (key.equals(prefix)) {
                program = mode;
                continue;
            }
            Integer target = PackResourcePlan.targetIndex(key.substring(prefix.length() + 1));
            if (target == null) {
                deviations.add("BLEND_DIRECTIVE_MALFORMED:" + key);
                continue;
            }
            buffers.put(target, mode);
        }
        if (program != null || !buffers.isEmpty()) deviations.add("BLEND_DIRECTIVE_APPLIED:" + programName);
        return new PackBlendPlan(program, buffers, deviations);
    }

    /**
     * Iris ProgramId's built-in BlendModeOverride for the program whose source runs, applied when
     * the pack sets no program-level blend: SpiderEyes draws additively (SRC_ALPHA, ONE, ZERO, ONE).
     * Iris attaches it to the source read from gbuffers_spidereyes (ProgramSet.readProgramSource,
     * ProgramDirectives); a fallback to gbuffers_textured carries none, so eyes keep the host's blend.
     */
    PackBlendPlan withIrisDefault(String sourceProgram) {
        if (program != null || !"gbuffers_spidereyes".equals(sourceProgram)) return this;
        List<String> next = new ArrayList<>(deviations);
        next.add("BLEND_PROGRAM_DEFAULT:" + sourceProgram);
        return new PackBlendPlan(new Mode(true, FACTORS.get("SRC_ALPHA"), FACTORS.get("ONE"),
                FACTORS.get("ZERO"), FACTORS.get("ONE")), buffers, next);
    }

    /**
     * The modes a fullscreen pass draws with, as Iris's renderers apply them: a begin, prepare,
     * deferred or composite pass takes blend.<program> for every buffer and ignores per-buffer
     * directives (CompositeRenderer.java:128,300-303); final always draws unblended
     * (FinalPassRenderer.java:195), as shadowcomp does (ShadowCompositeRenderer.java:186).
     */
    PackBlendPlan forFullscreenPass(String pass) {
        Mode kept = pass.equals("final") || pass.startsWith("shadowcomp") ? null : program;
        if (kept == program && buffers.isEmpty()) return this;
        List<String> next = new ArrayList<>(deviations);
        if (kept == null) next.removeIf(deviation -> deviation.startsWith("BLEND_DIRECTIVE_APPLIED:"));
        next.add("BLEND_DIRECTIVE_IGNORED_AS_IRIS:" + pass);
        return new PackBlendPlan(kept, Map.of(), next);
    }

    static Mode parse(String authored) {
        String value = authored == null ? "" : authored.trim();
        if (value.equalsIgnoreCase("off") || value.equalsIgnoreCase("false")) return Mode.OFF;
        String[] parts = value.split("\\s+");
        if (parts.length != 4) return null;
        int[] factors = new int[4];
        for (int i = 0; i < 4; i++) {
            String name = parts[i].toUpperCase(Locale.ROOT);
            if (name.startsWith("GL_")) name = name.substring(3);
            Integer factor = FACTORS.get(name);
            if (factor == null) return null;
            factors[i] = factor;
        }
        return new Mode(true, factors[0], factors[1], factors[2], factors[3]);
    }

    public boolean overridesAnything() {
        return program != null || !buffers.isEmpty();
    }

    /**
     * Per-attachment modes for the given output order; a null entry keeps
     * the host draw's blend. Returns null when nothing is overridden.
     */
    public Mode[] attachments(List<Integer> targets) {
        if (!overridesAnything() || targets == null) return null;
        Mode[] result = new Mode[targets.size()];
        for (int i = 0; i < result.length; i++) {
            Mode buffer = buffers.get(targets.get(i));
            result[i] = buffer != null ? buffer : program;
        }
        return result;
    }

    /** Stands for the host draw's own blend when attachment modes are compared. */
    private static final Mode HOST = new Mode(true, -1, -1, -1, -1);

    /**
     * The modes a device without independentBlend can draw with, where every attachment's blend state
     * must be identical: the one blend every attachment resolves to, on each of them, else blending
     * off on all. A null entry, or null {@code modes}, is the host draw's blend; an integer
     * attachment never blends, as GL skips blending for it.
     */
    public static Mode[] sharedAttachments(Mode[] modes, boolean[] integer) {
        Mode shared = null;
        for (int i = 0; i < integer.length; i++) {
            Mode mode = integer[i] ? Mode.OFF : modes == null || modes[i] == null ? HOST : modes[i];
            if (shared == null) {
                shared = mode;
            } else if (!shared.equals(mode)) {
                shared = Mode.OFF;
                break;
            }
        }
        Mode[] result = new Mode[integer.length];
        Arrays.fill(result, shared == HOST ? null : shared);
        return result;
    }

    /**
     * One mode per shadow attachment, attachment i drawing shadowcolor i: the pack's directives
     * over Iris's default for every shadow program, blending off (ProgramId's Shadow entries:
     * BlendModeOverride.OFF).
     */
    public Mode[] shadowAttachments(int count) {
        Mode[] result = new Mode[count];
        for (int i = 0; i < count; i++) {
            Mode buffer = buffers.get(i);
            result[i] = buffer != null ? buffer : program != null ? program : Mode.OFF;
        }
        return result;
    }
}
