package net.chimera.shaderpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the shader-source declaration block of an OptiFine-format pack:
 * colortex buffer formats (const int colortexNFormat = TOKEN;), shadow
 * settings, and the geometry draw-buffer count (DRAWBUFFERS). A
 * shaders.properties file next to the pass list overrides same-key entries.
 *
 * <p>Honored: colortex formats drive the HDR buffer format (any VK-code
 * mapping in FMT_TO_VK; other tokens skip to the default). The shadow
 * resolution and distance settings are validated here before the
 * shadow framebuffer is created. Draw-buffer counts beyond 1 remain visible
 * to the caller but are not applied.
 */
public final class PackConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");

    /**
     * A colour target in a declaration name: colortexN or one of the legacy
     * aliases Iris accepts the same way (gaux2Format is colortex5Format).
     */
    private static final String TARGET_NAME = "(colortex\\d+|gcolor|gdepth|gnormal|composite|gaux[1-4])";
    /** A colour target directive: {@code <target>Format} (int), {@code Clear} (bool), {@code ClearColor} (vec4). */
    private static final Pattern TARGET_DIRECTIVE = Pattern.compile(TARGET_NAME + "(Format|ClearColor|Clear)");
    private static final Map<String, String> TARGET_DIRECTIVE_TYPES = Map.of(
            "Format", "int", "Clear", "bool", "ClearColor", "vec4");
    /** The shadow settings a const directive declares, with the type it must be declared as. */
    private static final Map<String, String> SHADOW_DIRECTIVE_TYPES = Map.ofEntries(
            Map.entry("shadowMapResolution", "int"), Map.entry("shadowDistance", "float"),
            Map.entry("shadowMapSize", "float"), Map.entry("shadowMapFov", "float"),
            Map.entry("shadowDistanceRenderMul", "float"), Map.entry("shadowIntervalSize", "float"),
            Map.entry("sunPathRotation", "float"), Map.entry("sunPathOffset", "float"),
            Map.entry("entityShadowDistanceMul", "float"), Map.entry("voxelDistance", "float"));
    private static final Pattern DRAWBUFFERS_COMMENT = Pattern.compile("/\\*\\s*DRAWBUFFERS\\s*:\\s*([0-9,]+)\\s*\\*/");
    private static final Pattern FRAG_DATA = Pattern.compile("gl_FragData\\s*\\[\\s*(\\d+)\\s*\\]");
    private static final Pattern PROPERTY_KEY = Pattern.compile("[A-Za-z0-9_.-]+");
    /** shaders.properties key shape: <target>Format. */
    private static final Pattern COLORTEX_PROPERTY_KEY = Pattern.compile("^" + TARGET_NAME + "Format$");
    private static final Pattern TARGET_SIZE_PROPERTY_KEY =
            Pattern.compile("^size\\.buffer\\." + TARGET_NAME + "$");
    private static final Pattern TARGET_CLEAR_PROPERTY_KEY =
            Pattern.compile("^" + TARGET_NAME + "Clear$");
    private static final Pattern TARGET_CLEAR_COLOR_PROPERTY_KEY =
            Pattern.compile("^" + TARGET_NAME + "ClearColor$");
    private static final Pattern TARGET_FLIP_PROPERTY_KEY =
            Pattern.compile("^flip\\.([A-Za-z0-9_]+)\\.colortex(\\d+)$");

    /**
     * Iris format token -> the VkFormat that stores it exactly (IrisColorFormats). Normalized,
     * floating-point and integer storage stay distinct; target allocation checks device support
     * when required.
     */
    public static final Map<String, Integer> FMT_TO_VK = exactFormats();

    private PackConfig() {}

    private static Map<String, Integer> exactFormats() {
        Map<String, Integer> exact = new HashMap<>();
        for (String token : IrisColorFormats.tokens()) {
            IrisColorFormats.Format format = IrisColorFormats.of(token);
            if (format.exact()) exact.put(token, format.vkFormat());
        }
        return Map.copyOf(exact);
    }

    /**
     * Bytes per texel of a colour-target format in {@link #FMT_TO_VK}, or 0
     * when the format is not one of them. VulkanMod's image builder only
     * sizes its own formats, so pack targets take their size from here.
     */
    public static int formatBytes(int vkFormat) {
        return IrisColorFormats.bytes(vkFormat);
    }

    public static final int DEFAULT_SHADOW_MAP_RESOLUTION = 2048;
    public static final float DEFAULT_SHADOW_DISTANCE = 128.0F;
    /** Iris PackShadowDirectives default. */
    public static final float DEFAULT_SHADOW_INTERVAL_SIZE = 2.0F;
    private static final int MIN_SHADOW_MAP_RESOLUTION = 128;
    private static final int MAX_SHADOW_MAP_RESOLUTION = 4096;
    private static final float MIN_SHADOW_DISTANCE = 16.0F;
    private static final float MAX_SHADOW_DISTANCE = 2048.0F;

    /** Validated shadow settings. The raw values remain available for logs. */
    public record ShadowSettings(
            int resolution,
            float distance,
            Map<String, String> rawValues,
            List<String> deviations,
            ShadowTargetDirectives targets,
            int colorTargetCount
    ) {
        public ShadowSettings {
            rawValues = Collections.unmodifiableMap(new TreeMap<>(rawValues));
            deviations = List.copyOf(new TreeSet<>(deviations));
            targets = targets == null ? ShadowTargetDirectives.defaults() : targets;
            colorTargetCount = Math.clamp(colorTargetCount, ShadowTargetDirectives.LEGACY_COLOR_TARGETS,
                    ShadowTargetDirectives.MAX_COLOR_TARGETS);
        }

        /** The shadow pass's colour attachment formats: each target's, in index order. */
        public List<Integer> colorAttachmentFormats() {
            return targets.attachmentFormats(colorTargetCount);
        }

        /** Settings with Iris's default shadow target directives and two targets. */
        public ShadowSettings(int resolution, float distance, Map<String, String> rawValues, List<String> deviations) {
            this(resolution, distance, rawValues, deviations, null, ShadowTargetDirectives.LEGACY_COLOR_TARGETS);
        }

        public float distanceRenderMultiplier() {
            return finiteValue("shadowDistanceRenderMul", 1.0F);
        }

        public float sunPathRotation() {
            return finiteValue("sunPathRotation", 0.0F);
        }

        public float sunPathOffset() {
            return finiteValue("sunPathOffset", 0.0F);
        }

        /** Iris's shadow grid cell; the shadow view snaps to it so texels stay put in the world. */
        public float intervalSize() {
            return finiteValue("shadowIntervalSize", DEFAULT_SHADOW_INTERVAL_SIZE);
        }

        /** Iris entityShadowDistanceMul: the entity caster distance relative to terrain's. */
        public float entityDistanceMultiplier() {
            return finiteValue("entityShadowDistanceMul", 1.0F);
        }

        /**
         * Iris shadowDistanceRenderMul as shadow culling reads it: -1, Iris's default, when the
         * pack sets none (ShadowCullingPlan).
         */
        public float cullingRenderMultiplier() {
            return rawValues.containsKey("shadowDistanceRenderMul") ? distanceRenderMultiplier() : -1.0F;
        }

        /** Iris voxelDistance: the half-size of the box safe-zone shadow culling always draws. */
        public float voxelDistance() {
            return finiteValue("voxelDistance", 0.0F);
        }

        private float finiteValue(String name, float fallback) {
            try {
                float value = Float.parseFloat(rawValues.getOrDefault(name, Float.toString(fallback)));
                return Float.isFinite(value) ? value : fallback;
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
    }

    /**
     * Load-time target directives; the target graph allocates the images at runtime. A null
     * {@code clearColor} means the pack declared none, so the target clears to Iris's default.
     */
    public record TargetSettings(
            String sizeExpression,
            boolean clear,
            float[] clearColor,
            List<String> deviations
    ) {
        public TargetSettings {
            sizeExpression = sizeExpression == null ? "" : sizeExpression.trim();
            clearColor = clearColor == null || clearColor.length != 4 ? null : clearColor.clone();
            deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        }

        public static TargetSettings defaults() {
            return new TargetSettings("", true, null, List.of());
        }

        /** The declared clear colour, or null when the pack declared none. */
        public float[] clearColorCopy() {
            return clearColor == null ? null : clearColor.clone();
        }

        @Override
        public float[] clearColor() {
            return clearColorCopy();
        }

        /**
         * The colour Iris's ClearPassCreator clears colortex{@code index} to: the declared one,
         * else the fog colour with alpha 1 for colortex0, opaque white for colortex1, and
         * transparent black for the rest.
         */
        public float[] irisClearColor(int index, float[] fogColor) {
            if (clearColor != null) return clearColor.clone();
            if (index == 0) return new float[] {fogColor[0], fogColor[1], fogColor[2], 1.0F};
            return index == 1 ? new float[] {1.0F, 1.0F, 1.0F, 1.0F} : new float[] {0.0F, 0.0F, 0.0F, 0.0F};
        }
    }

    public record PackConfigData(
            Map<Integer, Integer> colortexFormats,
            int drawBufferCount,
            ShadowSettings shadowSettings,
            List<String> deviations,
            PackSettingsPlan settings,
            Map<Integer, TargetSettings> targetSettings,
            Map<String, List<Integer>> flips,
            Map<String, List<Integer>> preFlips,
            // Targets declared in a format without alpha and stored with it (IrisColorFormats).
            Set<Integer> paddedAlphaTargets
    ) {
        public PackConfigData(
                Map<Integer, Integer> colortexFormats,
                int drawBufferCount,
                ShadowSettings shadowSettings,
                List<String> deviations
        ) {
            this(colortexFormats, drawBufferCount, shadowSettings,
                    deviations, PackSettingsPlan.empty(), Map.of(), Map.of(), Map.of(), Set.of());
        }

        public PackConfigData {
            colortexFormats = Collections.unmodifiableMap(new TreeMap<>(colortexFormats));
            paddedAlphaTargets = Collections.unmodifiableSet(new TreeSet<>(paddedAlphaTargets == null
                    ? Set.of() : paddedAlphaTargets));
            deviations = List.copyOf(new TreeSet<>(deviations));
            settings = settings == null ? PackSettingsPlan.empty() : settings;
            targetSettings = Collections.unmodifiableMap(new TreeMap<>(targetSettings == null
                    ? Map.of() : targetSettings));
            flips = immutableTargetLists(flips);
            preFlips = immutableTargetLists(preFlips);
        }

        private static Map<String, List<Integer>> immutableTargetLists(
                Map<String, List<Integer>> source
        ) {
            Map<String, List<Integer>> result = new TreeMap<>();
            if (source != null) {
                source.forEach((key, value) -> result.put(key,
                        value == null ? List.of() : value.stream().distinct().sorted().toList()));
            }
            return Collections.unmodifiableMap(result);
        }
    }

    public static PackConfigData parse(List<PackProgram> programs, Path shadersDir) {
        return parse(programs, shadersDir, PackSettingsPlan.parse(programs, shadersDir));
    }

    public static PackConfigData parse(
            List<PackProgram> programs,
            Path shadersDir,
            PackSettingsPlan settings
    ) {
        Collected collected = new Collected();
        for (PackProgram program : IrisConstDirectives.programs(programs)) {
            for (IrisConstDirectives.Directive directive
                    : IrisConstDirectives.find(program.executableFragmentSource())) {
                acceptDirective(program.name(), directive, collected);
            }
        }

        // shaders.properties overrides (OptiFine packs may declare formats here), as Iris reads
        // them: inside the #if branches that hold, the last value of a key, and option names in a
        // value expanded (Complementary sizes colortex1 by REFLECTION_RES).
        settings.propertyValues().forEach((key, raw) -> {
            int comment = raw.indexOf('#');
            String value = (comment < 0 ? raw : raw.substring(0, comment)).trim();
            if (PROPERTY_KEY.matcher(key).matches() && !value.isEmpty()) {
                applyProperty(key, PackConditionals.expandMacros(value, settings.propertyMacros()).trim(), collected);
            }
        });

        // Draw-buffer count: the geometry program's (targets it writes).
        int drawBufferCount = 1;
        for (PackProgram program : programs) {
            if (program.name().equals("gbuffers_terrain")) {
                drawBufferCount = drawBufferCountOf(program.executableFragmentSource());
                break;
            }
        }

        ShadowSettings shadowSettings = validateShadowSettings(collected.shadowValues, shadowTargets(programs),
                shadowColorTargetCount(programs, settings));
        return new PackConfigData(collected.colortexFormats, drawBufferCount,
                shadowSettings, collected.deviations, settings,
                collected.targetSettings, collected.flips, collected.preFlips, collected.paddedAlphaTargets);
    }

    /** The shadow target directives, from the sources Iris reads directives from, in its order. */
    private static ShadowTargetDirectives shadowTargets(List<PackProgram> programs) {
        return ShadowTargetDirectives.parse(IrisConstDirectives.programs(programs).stream()
                .map(PackProgram::executableFragmentSource)
                .toList());
    }

    /**
     * One const directive: a colour target's format, clear or clear colour, or a shadow setting.
     * A directive declared with another type than Iris reads it as is ignored, as Iris ignores it.
     */
    private static void acceptDirective(String program, IrisConstDirectives.Directive directive, Collected collected) {
        Matcher target = TARGET_DIRECTIVE.matcher(directive.name());
        if (target.matches() && directive.type().equals(TARGET_DIRECTIVE_TYPES.get(target.group(2)))) {
            acceptTargetDirective(program, target.group(1), target.group(2), directive.value(), collected);
        } else if (directive.type().equals(SHADOW_DIRECTIVE_TYPES.get(directive.name()))
                && irisNumber(directive.type(), directive.value())) {
            collected.shadowValues.put(directive.name(), directive.value());
        }
    }

    /** Whether Iris parses {@code value} as its {@code int} (Integer.parseInt) or {@code float} (Float.parseFloat). */
    private static boolean irisNumber(String type, String value) {
        try {
            if (type.equals("int")) Integer.parseInt(value);
            else Float.parseFloat(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static final Pattern SHADOW_COLOR_NAME = Pattern.compile("\\bshadowcolor([0-7])\\b");

    /**
     * The shadowcolor targets the shadow pass renders. Iris has two (MAX_SHADOW_COLOR_BUFFERS_OF);
     * with HIGHER_SHADOWCOLOR it has eight and creates each on first use, so the pass renders every
     * one up to the highest a program reads or a shadow program writes.
     */
    private static int shadowColorTargetCount(List<PackProgram> programs, PackSettingsPlan settings) {
        if (!settings.hasFeature(PackFeatureFlags.HIGHER_SHADOWCOLOR)) {
            return ShadowTargetDirectives.LEGACY_COLOR_TARGETS;
        }
        int highest = ShadowTargetDirectives.LEGACY_COLOR_TARGETS - 1;
        for (PackProgram program : programs) {
            for (String source : Arrays.asList(program.executableFragmentSource(), program.executableVertexSource())) {
                Matcher name = SHADOW_COLOR_NAME.matcher(source == null ? "" : source);
                while (name.find()) highest = Math.max(highest, Integer.parseInt(name.group(1)));
            }
            if (!FamilyAdapterRegistry.isShadowFamily(program.name())) continue;
            String fragment = program.executableFragmentSource();
            for (int target : PostTargetPlan.parse("shadow", fragment == null ? "" : fragment).plan().targetSlots()) {
                if (target < ShadowTargetDirectives.MAX_COLOR_TARGETS) highest = Math.max(highest, target);
            }
        }
        return highest + 1;
    }

    /** What parsing gathers from the sources and shaders.properties before shadow validation. */
    private static final class Collected {
        final Map<Integer, Integer> colortexFormats = new HashMap<>();
        final Set<Integer> paddedAlphaTargets = new TreeSet<>();
        final Map<String, String> shadowValues = new TreeMap<>();
        final Map<Integer, TargetSettings> targetSettings = new TreeMap<>();
        final Map<String, List<Integer>> flips = new TreeMap<>();
        final Map<String, List<Integer>> preFlips = new TreeMap<>();
        final List<String> deviations = new ArrayList<>();
    }

    /** A colortex format token's Vulkan format, recording an approximation; null when unsupported. */
    private static Integer formatCode(int slot, String token, List<String> deviations) {
        IrisColorFormats.Format format = IrisColorFormats.of(token);
        if (format == null) return null;
        if (!format.exact()) {
            deviations.add("POST_TARGET_FORMAT_APPROXIMATED:colortex"
                    + slot + "=" + token + "->" + formatName(format.vkFormat()));
        }
        return format.vkFormat();
    }

    /** Records a target's format, a later declaration replacing an earlier one; returns the previous format. */
    private static Integer putFormat(int slot, String token, int code, Collected collected) {
        IrisColorFormats.Format format = IrisColorFormats.of(token);
        if (format != null && format.paddedAlpha()) {
            collected.paddedAlphaTargets.add(slot);
        } else {
            collected.paddedAlphaTargets.remove(slot);
        }
        return collected.colortexFormats.put(slot, code);
    }

    /**
     * A colour target directive: a format token, a clear switch Iris reads only as exactly true or
     * false, or a clear colour.
     */
    private static void acceptTargetDirective(String program, String target, String setting, String value,
                                              Collected collected) {
        int slot = parseTargetIndex(target, collected.deviations);
        if (slot < 0) return;
        switch (setting) {
            case "Format" -> acceptFormatDirective(program, slot, value, collected);
            case "Clear" -> {
                if (value.equals("true") || value.equals("false")) {
                    updateTargetSettings(collected.targetSettings, slot, null, value.equals("true"), null, null);
                }
            }
            default -> {
                float[] parsed = parseColor(value);
                if (parsed != null) {
                    updateTargetSettings(collected.targetSettings, slot, null, null, parsed, null);
                } else {
                    collected.deviations.add("POST_TARGET_CLEAR_DEFAULTED:colortex" + slot);
                }
            }
        }
    }

    private static void acceptFormatDirective(String program, int slot, String token, Collected collected) {
        Integer code = formatCode(slot, token, collected.deviations);
        if (code == null) {
            collected.deviations.add("POST_TARGET_FORMAT_UNSUPPORTED:colortex" + slot + "=" + token);
            LOGGER.warn("[chimera] pack {}: colortex{}Format {}: unhandled format, keeping default",
                    program, slot, token);
            return;
        }
        Integer previous = putFormat(slot, token, code, collected);
        if (previous != null && !previous.equals(code)) {
            LOGGER.info("[chimera] pack {}: colortex{}Format override {} -> {}", program, slot, previous, code);
        }
    }

    /** One shaders.properties entry: a target format or directive, or a shadow setting. */
    private static void applyProperty(String key, String value, Collected collected) {
        Matcher colortexKey = COLORTEX_PROPERTY_KEY.matcher(key);
        if (colortexKey.find()) {
            if (!applyFormatProperty(key, value, colortexKey.group(1), collected)) {
                return;
            }
        } else {
            applyTargetProperty(key, value, collected);
        }
        if (SHADOW_PROPERTIES.contains(key)) {
            collected.shadowValues.put(key, value);
            LOGGER.info("[chimera] pack properties: {}={}", key, value);
        }
    }

    /** A {@code colortexNFormat} property; false when it names no target or no supported format. */
    private static boolean applyFormatProperty(String key, String value, String index, Collected collected) {
        int slot = parseTargetIndex(index, collected.deviations);
        if (slot < 0) {
            return false;
        }
        Integer code = formatCode(slot, value, collected.deviations);
        if (code == null) {
            collected.deviations.add("POST_TARGET_FORMAT_UNSUPPORTED:" + key + "=" + value);
            LOGGER.warn("[chimera] pack properties {}: unhandled format, keeping default", value);
            return false;
        }
        putFormat(slot, value, code, collected);
        LOGGER.info("[chimera] pack properties: {}={}", key, value);
        return true;
    }

    /** A target size, clear, clear color or flip directive. */
    private static void applyTargetProperty(String key, String value, Collected collected) {
        Matcher sizeKey = TARGET_SIZE_PROPERTY_KEY.matcher(key);
        Matcher clearKey = TARGET_CLEAR_PROPERTY_KEY.matcher(key);
        Matcher clearColorKey = TARGET_CLEAR_COLOR_PROPERTY_KEY.matcher(key);
        Matcher flipKey = TARGET_FLIP_PROPERTY_KEY.matcher(key);
        if (sizeKey.matches()) {
            applyTargetSize(sizeKey.group(1), value, collected);
        } else if (clearKey.matches()) {
            applyTargetClear(clearKey.group(1), value, collected);
        } else if (clearColorKey.matches()) {
            applyTargetClearColor(clearColorKey.group(1), value, collected);
        } else if (flipKey.matches()) {
            applyTargetFlip(key, flipKey, value, collected);
        } else if (key.startsWith("size.buffer.") || key.startsWith("flip.")) {
            collected.deviations.add("POST_TARGET_PROPERTY_UNSUPPORTED:" + key);
        }
    }

    /** A {@code size.buffer.colortexN} size expression. */
    private static void applyTargetSize(String index, String value, Collected collected) {
        int slot = parseTargetIndex(index, collected.deviations);
        if (slot >= 0 && slot <= PostTargetPlan.MAX_TARGET) {
            updateTargetSettings(collected.targetSettings, slot, value, null, null, null);
        }
    }

    /** A {@code colortexNClear} switch; a value that is no boolean is ignored. */
    private static void applyTargetClear(String index, String value, Collected collected) {
        int slot = parseTargetIndex(index, collected.deviations);
        Boolean clear = parseBoolean(value);
        if (slot >= 0 && slot <= PostTargetPlan.MAX_TARGET && clear != null) {
            updateTargetSettings(collected.targetSettings, slot, null, clear, null, null);
        } else if (clear != null) {
            collected.deviations.add("POST_TARGET_CLEAR_DEFAULTED:colortex" + slot);
        }
    }

    /** A {@code colortexNClearColor} colour. */
    private static void applyTargetClearColor(String index, String value, Collected collected) {
        int slot = parseTargetIndex(index, collected.deviations);
        float[] color = parseColor(value);
        if (slot >= 0 && slot <= PostTargetPlan.MAX_TARGET && color != null) {
            updateTargetSettings(collected.targetSettings, slot, null, null, color, null);
        } else {
            collected.deviations.add("POST_TARGET_CLEAR_DEFAULTED:colortex" + slot);
        }
    }

    /** A {@code flip.<program>.colortexN} switch; a {@code <program>_pre} key's flips are kept apart. */
    private static void applyTargetFlip(String key, Matcher flipKey, String value, Collected collected) {
        int slot = parseTargetIndex(flipKey.group(2), collected.deviations);
        Boolean flip = parseBoolean(value);
        if (slot >= 0 && slot <= PostTargetPlan.MAX_TARGET && flip != null) {
            updateFlip(flipKey.group(1).endsWith("_pre") ? collected.preFlips : collected.flips,
                    flipKey.group(1), slot, flip);
        } else {
            collected.deviations.add("POST_TARGET_FLIP_UNSUPPORTED:" + key);
        }
    }

    private static final List<String> SHADOW_PROPERTIES = List.of(
            "shadowMapResolution", "shadowDistance", "shadowMapSize", "shadowMapFov",
            "shadowDistanceRenderMul", "sunPathRotation", "sunPathOffset");

    /** Resolves colortexN, a legacy alias, or a bare index; -1 with a deviation when unsupported. */
    private static int parseTargetIndex(String value, List<String> deviations) {
        Integer slot = value != null && value.matches("\\d+")
                ? Integer.valueOf(Integer.parseInt(value))
                : PackResourcePlan.targetIndex(value);
        if (slot == null) {
            deviations.add("POST_TARGET_INDEX_UNSUPPORTED:" + value);
            return -1;
        }
        if (slot < 0 || slot > PostTargetPlan.MAX_TARGET) {
            deviations.add("POST_TARGET_INDEX_UNSUPPORTED:" + slot);
            return -1;
        }
        return slot;
    }

    private static void updateTargetSettings(
            Map<Integer, TargetSettings> values,
            int slot,
            String size,
            Boolean clear,
            float[] clearColor,
            List<String> deviations
    ) {
        TargetSettings previous = values.getOrDefault(slot, TargetSettings.defaults());
        values.put(slot, new TargetSettings(
                size == null ? previous.sizeExpression() : size,
                clear == null ? previous.clear() : clear,
                clearColor == null ? previous.clearColorCopy() : clearColor,
                deviations == null ? previous.deviations() : deviations));
    }

    private static void updateFlip(
            Map<String, List<Integer>> flips,
            String program,
            int slot,
            boolean enabled
    ) {
        List<Integer> values = new ArrayList<>(flips.getOrDefault(program, List.of()));
        values.removeIf(value -> value == slot);
        if (enabled) {
            values.add(slot);
        }
        values.sort(Integer::compareTo);
        flips.put(program, List.copyOf(values));
    }

    private static Boolean parseBoolean(String value) {
        if (value == null) {
            return null;
        }
        if (value.trim().equalsIgnoreCase("true") || value.trim().equals("1")) {
            return Boolean.TRUE;
        }
        if (value.trim().equalsIgnoreCase("false") || value.trim().equals("0")) {
            return Boolean.FALSE;
        }
        return null;
    }

    static float[] parseColor(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim()
                .replaceFirst("(?i)^vec4\\s*\\(", "")
                .replaceFirst("\\)$", "");
        String[] parts = normalized.split(",");
        if (parts.length != 4) {
            return null;
        }
        float[] result = new float[4];
        try {
            for (int i = 0; i < result.length; i++) {
                result[i] = Float.parseFloat(normalizeNumeric(parts[i]));
                if (!Float.isFinite(result[i])) {
                    return null;
                }
            }
            return result;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalizeNumeric(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.endsWith("f") || normalized.endsWith("F")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static ShadowSettings validateShadowSettings(Map<String, String> rawValues,
                                                         ShadowTargetDirectives targets, int colorTargetCount) {
        List<String> deviations = new ArrayList<>();
        int resolution = validShadowMapResolution(rawValues.get("shadowMapResolution"), deviations);
        float distance = validShadowDistance(rawValues.get("shadowDistance"), deviations);

        validateFiniteShadowSetting(rawValues, deviations, "shadowDistanceRenderMul", 0.0F, 4.0F);
        validateFiniteShadowSetting(rawValues, deviations, "sunPathRotation", -360.0F, 360.0F);
        validateFiniteShadowSetting(rawValues, deviations, "sunPathOffset", -360.0F, 360.0F);
        validateFiniteShadowSetting(rawValues, deviations, "shadowIntervalSize", 0.0F, 64.0F);
        validateFiniteShadowSetting(rawValues, deviations, "entityShadowDistanceMul", -64.0F, 64.0F);
        validateFiniteShadowSetting(rawValues, deviations, "voxelDistance", 0.0F, MAX_SHADOW_DISTANCE);

        for (String key : rawValues.keySet()) {
            if (!SUPPORTED_SHADOW_SETTINGS.contains(key)) {
                deviations.add("SHADOW_SETTING_UNSUPPORTED:" + key);
            }
        }
        deviations.addAll(targets.deviations());
        return new ShadowSettings(resolution, distance, rawValues, deviations, targets, colorTargetCount);
    }

    /** The shadow settings validated above; any other collected key is reported unsupported. */
    private static final Set<String> SUPPORTED_SHADOW_SETTINGS = Set.of(
            "shadowMapResolution", "shadowDistance", "shadowDistanceRenderMul", "shadowIntervalSize",
            "sunPathRotation", "sunPathOffset", "entityShadowDistanceMul", "voxelDistance");

    /** The authored shadow map resolution when it is a whole number in range, else the default. */
    private static int validShadowMapResolution(String raw, List<String> deviations) {
        if (raw == null) {
            return DEFAULT_SHADOW_MAP_RESOLUTION;
        }
        try {
            double parsed = Double.parseDouble(raw);
            if (parsed >= MIN_SHADOW_MAP_RESOLUTION && parsed <= MAX_SHADOW_MAP_RESOLUTION
                    && parsed == Math.rint(parsed)) {
                return (int) parsed;
            }
            deviations.add("SHADOW_SETTING_DEFAULTED:shadowMapResolution");
        } catch (NumberFormatException e) {
            deviations.add("SHADOW_SETTING_DEFAULTED:shadowMapResolution");
        }
        return DEFAULT_SHADOW_MAP_RESOLUTION;
    }

    /** The authored shadow distance when it is finite and in range, else the default. */
    private static float validShadowDistance(String raw, List<String> deviations) {
        if (raw == null) {
            return DEFAULT_SHADOW_DISTANCE;
        }
        try {
            float parsed = Float.parseFloat(raw);
            if (Float.isFinite(parsed)
                    && parsed >= MIN_SHADOW_DISTANCE && parsed <= MAX_SHADOW_DISTANCE) {
                return parsed;
            }
            deviations.add("SHADOW_SETTING_DEFAULTED:shadowDistance");
        } catch (NumberFormatException e) {
            deviations.add("SHADOW_SETTING_DEFAULTED:shadowDistance");
        }
        return DEFAULT_SHADOW_DISTANCE;
    }

    private static void validateFiniteShadowSetting(
            Map<String, String> rawValues,
            List<String> deviations,
            String name,
            float minimum,
            float maximum
    ) {
        String raw = rawValues.get(name);
        if (raw == null) {
            return;
        }
        try {
            float value = Float.parseFloat(raw);
            if (!Float.isFinite(value) || value < minimum || value > maximum) {
                deviations.add("SHADOW_SETTING_DEFAULTED:" + name);
            }
        } catch (NumberFormatException ignored) {
            deviations.add("SHADOW_SETTING_DEFAULTED:" + name);
        }
    }

    /** The number of draw targets a program writes: its DRAWBUFFERS comment, or its gl_FragData usage. */
    public static int drawBufferCountOf(String source) {
        Matcher comment = DRAWBUFFERS_COMMENT.matcher(source);
        if (comment.find()) {
            int count = comment.group(1).split(",").length;
            if (count >= 1) {
                return count;
            }
        }
        int max = 0;
        Matcher fragData = FRAG_DATA.matcher(source);
        while (fragData.find()) {
            int index;
            try {
                index = Integer.parseInt(fragData.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            max = Math.max(max, index + 1);
        }
        return max >= 1 ? max : 1;
    }

    /** Token name for a VK code (for logs); "code N" when unknown. */
    public static String formatName(int vkCode) {
        IrisColorFormats.Format format = IrisColorFormats.stored(vkCode);
        return format != null ? format.token() : "code " + vkCode;
    }
}
