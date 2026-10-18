package net.chimera.shaderpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    /** const int <target>Format = TOKEN; */
    private static final Pattern COLORTEX_FMT_CONST = Pattern.compile(
            "(?m)^\\s*const\\s+int\\s+" + TARGET_NAME + "Format\\s*=\\s*(\\w+)\\s*;\\s*(?://.*)?$");
    private static final Pattern COLORTEX_CLEAR_CONST = Pattern.compile(
            "(?m)^\\s*const\\s+bool\\s+" + TARGET_NAME + "Clear\\s*=\\s*(true|false)\\s*;\\s*(?://.*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COLORTEX_CLEAR_COLOR_CONST = Pattern.compile(
            "(?m)^\\s*const\\s+vec4\\s+" + TARGET_NAME
                    + "ClearColor\\s*=\\s*vec4\\s*\\(([^)]*)\\)\\s*;\\s*(?://.*)?$");
    private static final Pattern SHADOW_CONST = Pattern.compile(
            "(?m)^\\s*const\\s+(?:int|float)\\s+"
                    + "(shadowMapResolution|shadowDistance|shadowMapSize|shadowMapFov|shadowDistanceRenderMul"
                    + "|shadowIntervalSize|sunPathRotation|sunPathOffset|entityShadowDistanceMul|voxelDistance)"
                    + "\\s*=\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)[fF]?\\s*;\\s*(?://.*)?$");
    private static final Pattern PACK_NUMERIC_CONST = Pattern.compile(
            "(?m)^\\s*const\\s+(int|float)\\s+"
                    + "(shadowMapResolution|shadowDistance|shadowMapSize|shadowMapFov|"
                    + "shadowDistanceRenderMul|sunPathRotation|sunPathOffset)"
                    + "\\s*=\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)[fF]?\\s*;\\s*(?://.*)?$");
    private static final Pattern DRAWBUFFERS_DEFINE = Pattern.compile("#define\\s+DRAWBUFFERS(\\d+)");
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
     * Iris format token -> exact VkFormat. Keep normalized and floating-point
     * storage distinct; target allocation checks device support when required.
     */
    public static final Map<String, Integer> FMT_TO_VK = Map.ofEntries(
            Map.entry("R8", 9),          // VK_FORMAT_R8_UNORM
            Map.entry("RG8", 16),        // VK_FORMAT_R8G8_UNORM
            Map.entry("RGBA8", 37),      // VK_FORMAT_R8G8B8A8_UNORM
            Map.entry("RGB10_A2", 64),   // VK_FORMAT_A2B10G10R10_UNORM_PACK32 (GL_RGB10_A2 bit order)
            Map.entry("R16F", 76),       // VK_FORMAT_R16_SFLOAT
            Map.entry("RG16F", 83),      // VK_FORMAT_R16G16_SFLOAT
            Map.entry("RGBA16", 91),     // VK_FORMAT_R16G16B16A16_UNORM (not RGBA16F)
            Map.entry("RGBA16F", 97),    // VK_FORMAT_R16G16B16A16_SFLOAT
            Map.entry("R32F", 100),      // VK_FORMAT_R32_SFLOAT
            Map.entry("RG32F", 103),     // VK_FORMAT_R32G32_SFLOAT
            Map.entry("RGBA32F", 109)    // VK_FORMAT_R32G32B32A32_SFLOAT
    );
    /** Common real-pack RGB formats approximated by the supported RGBA images. */
    private static final Map<String, Integer> APPROXIMATE_FMT_TO_VK = Map.of(
            "RGB8", 37,
            "RGB8_SNORM", 97,
            "RGBA8_SNORM", 97,
            "RGB16F", 97,
            "R11F_G11F_B10F", 97
    );
    /**
     * The approximations whose GL format has no alpha channel. GL reads alpha as 1 from them, and
     * Solas, for one, keeps its reflections only where that alpha is above 0.
     */
    private static final Set<String> PADDED_ALPHA_FORMATS = Set.of("RGB8", "RGB8_SNORM", "RGB16F", "R11F_G11F_B10F");
    private static final Map<Integer, String> VK_TO_NAME;
    static {
        Map<Integer, String> reverse = new HashMap<>();
        FMT_TO_VK.forEach((token, code) -> reverse.put(code, token));
        VK_TO_NAME = Map.copyOf(reverse);
    }

    private PackConfig() {}

    /**
     * Bytes per texel of a colour-target format in {@link #FMT_TO_VK}, or 0
     * when the format is not one of them. VulkanMod's image builder only
     * sizes its own formats, so pack targets take their size from here.
     */
    public static int formatBytes(int vkFormat) {
        return switch (vkFormat) {
            case 9 -> 1;
            case 16, 76 -> 2;
            case 37, 64, 83, 100 -> 4;
            case 91, 97, 103 -> 8;
            case 109 -> 16;
            default -> 0;
        };
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
            List<String> deviations
    ) {
        public ShadowSettings {
            rawValues = Collections.unmodifiableMap(new TreeMap<>(rawValues));
            deviations = List.copyOf(new TreeSet<>(deviations));
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
            Map<String, String> shaderConstants,
            List<String> deviations,
            PackSettingsPlan settings,
            Map<Integer, TargetSettings> targetSettings,
            Map<String, List<Integer>> flips,
            Map<String, List<Integer>> preFlips,
            // Targets declared in a format without alpha and stored with it (PADDED_ALPHA_FORMATS).
            Set<Integer> paddedAlphaTargets
    ) {
        public PackConfigData(
                Map<Integer, Integer> colortexFormats,
                int drawBufferCount,
                ShadowSettings shadowSettings,
                Map<String, String> shaderConstants,
                List<String> deviations
        ) {
            this(colortexFormats, drawBufferCount, shadowSettings, shaderConstants,
                    deviations, PackSettingsPlan.empty(), Map.of(), Map.of(), Map.of(), Set.of());
        }

        public PackConfigData {
            colortexFormats = Collections.unmodifiableMap(new TreeMap<>(colortexFormats));
            paddedAlphaTargets = Collections.unmodifiableSet(new TreeSet<>(paddedAlphaTargets == null
                    ? Set.of() : paddedAlphaTargets));
            shaderConstants = Collections.unmodifiableMap(new TreeMap<>(shaderConstants));
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
        for (PackProgram program : programs.stream()
                .sorted(Comparator.comparing(PackProgram::name)).toList()) {
            String src = program.executableFragmentSource();
            if (src == null) {
                src = "";
            }
            collectSourceFormats(program.name(), src, collected);
            collectShadowConstants(src, collected.shadowValues);
            collectShadowConstants(program.executableVertexSource(), collected.shadowValues);
            collectPackConstants(src, collected.shaderConstants, collected.deviations);
            collectPackConstants(program.executableVertexSource(), collected.shaderConstants, collected.deviations);
            collectTargetConstants(src, collected.targetSettings, collected.deviations);
            collectTargetConstants(program.executableVertexSource(), collected.targetSettings, collected.deviations);
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

        ShadowSettings shadowSettings = validateShadowSettings(collected.shadowValues);
        addShadowConstants(shadowSettings, collected.shadowValues, collected.shaderConstants);
        return new PackConfigData(collected.colortexFormats, drawBufferCount,
                shadowSettings, collected.shaderConstants, collected.deviations, settings,
                collected.targetSettings, collected.flips, collected.preFlips, collected.paddedAlphaTargets);
    }

    /** What parsing gathers from the sources and shaders.properties before shadow validation. */
    private static final class Collected {
        final Map<Integer, Integer> colortexFormats = new HashMap<>();
        final Set<Integer> paddedAlphaTargets = new TreeSet<>();
        final Map<String, String> shadowValues = new TreeMap<>();
        final Map<String, String> shaderConstants = new TreeMap<>();
        final Map<Integer, TargetSettings> targetSettings = new TreeMap<>();
        final Map<String, List<Integer>> flips = new TreeMap<>();
        final Map<String, List<Integer>> preFlips = new TreeMap<>();
        final List<String> deviations = new ArrayList<>();
    }

    /** A colortex format token's Vulkan format, recording an approximation; null when unsupported. */
    private static Integer formatCode(int slot, String token, List<String> deviations) {
        Integer code = FMT_TO_VK.get(token);
        if (code == null) {
            code = APPROXIMATE_FMT_TO_VK.get(token);
            if (code != null) {
                deviations.add("POST_TARGET_FORMAT_APPROXIMATED:colortex"
                        + slot + "=" + token + "->" + formatName(code));
            }
        }
        return code;
    }

    /** Records a target's format, a later declaration replacing an earlier one; returns the previous format. */
    private static Integer putFormat(int slot, String token, int code, Collected collected) {
        if (PADDED_ALPHA_FORMATS.contains(token)) {
            collected.paddedAlphaTargets.add(slot);
        } else {
            collected.paddedAlphaTargets.remove(slot);
        }
        return collected.colortexFormats.put(slot, code);
    }

    /** The {@code const int colortexNFormat} declarations of one program's fragment source. */
    private static void collectSourceFormats(String program, String src, Collected collected) {
        Matcher fmt = COLORTEX_FMT_CONST.matcher(src);
        while (fmt.find()) {
            int slot = parseTargetIndex(fmt.group(1), collected.deviations);
            if (slot < 0) {
                continue;
            }
            String token = fmt.group(2);
            Integer code = formatCode(slot, token, collected.deviations);
            if (code == null) {
                collected.deviations.add("POST_TARGET_FORMAT_UNSUPPORTED:colortex" + slot + "=" + token);
                LOGGER.warn("[chimera] pack {}: colortex{}Format {}: unhandled format, keeping default",
                        program, slot, token);
                continue;
            }
            Integer previous = putFormat(slot, token, code, collected);
            if (previous != null && !previous.equals(code)) {
                LOGGER.info("[chimera] pack {}: colortex{}Format override {} -> {}", program, slot, previous, code);
            }
        }
    }

    /** One shaders.properties entry: a target format or directive, a shadow setting, or a pack constant. */
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
        } else if (PACK_CONSTANT_NAMES.contains(key)) {
            if (isNumeric(value)) {
                collected.shaderConstants.put(key, normalizeNumeric(value));
                LOGGER.info("[chimera] pack properties: {}={}", key, value);
            } else {
                collected.deviations.add("PACK_CONSTANT_UNSUPPORTED:" + key);
            }
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

    /** The validated shadow settings the converted sources read as constants. */
    private static void addShadowConstants(ShadowSettings shadowSettings, Map<String, String> shadowValues,
                                           Map<String, String> shaderConstants) {
        shaderConstants.put("shadowMapResolution", Integer.toString(shadowSettings.resolution()));
        shaderConstants.put("shadowDistance", Float.toString(shadowSettings.distance()));
        if (shadowValues.containsKey("shadowDistanceRenderMul")) {
            shaderConstants.put("shadowDistanceRenderMul",
                    Float.toString(shadowSettings.distanceRenderMultiplier()));
        }
        if (shadowValues.containsKey("sunPathRotation")) {
            shaderConstants.put("sunPathRotation", Float.toString(shadowSettings.sunPathRotation()));
        }
        if (shadowValues.containsKey("sunPathOffset")) {
            shaderConstants.put("sunPathOffset", Float.toString(shadowSettings.sunPathOffset()));
        }
    }

    private static final List<String> SHADOW_PROPERTIES = List.of(
            "shadowMapResolution", "shadowDistance", "shadowMapSize", "shadowMapFov",
            "shadowDistanceRenderMul", "sunPathRotation", "sunPathOffset");
    private static final Set<String> PACK_CONSTANT_NAMES = Set.of(
            "shadowMapResolution", "shadowDistance", "shadowMapSize", "shadowMapFov",
            "shadowDistanceRenderMul", "sunPathRotation", "sunPathOffset");

    private static void collectShadowConstants(String source, Map<String, String> values) {
        Matcher shadow = SHADOW_CONST.matcher(source == null ? "" : source);
        while (shadow.find()) {
            values.put(shadow.group(1), shadow.group(2));
        }
    }

    private static void collectPackConstants(
            String source,
            Map<String, String> values,
            List<String> deviations
    ) {
        Matcher constants = PACK_NUMERIC_CONST.matcher(source == null ? "" : source);
        while (constants.find()) {
            String name = constants.group(2);
            String value = normalizeNumeric(constants.group(3));
            String previous = values.putIfAbsent(name, value);
            if (previous != null && !previous.equals(value)) {
                deviations.add("PACK_CONSTANT_CONFLICT:" + name);
            }
        }
    }

    private static void collectTargetConstants(
            String source,
            Map<Integer, TargetSettings> values,
            List<String> deviations
    ) {
        String text = source == null ? "" : source;
        Matcher clear = COLORTEX_CLEAR_CONST.matcher(text);
        while (clear.find()) {
            int slot = parseTargetIndex(clear.group(1), deviations);
            if (slot >= 0 && slot <= PostTargetPlan.MAX_TARGET) {
                updateTargetSettings(values, slot, null,
                        Boolean.parseBoolean(clear.group(2)), null, null);
            }
        }
        Matcher color = COLORTEX_CLEAR_COLOR_CONST.matcher(text);
        while (color.find()) {
            int slot = parseTargetIndex(color.group(1), deviations);
            float[] parsed = parseColor(color.group(2));
            if (slot >= 0 && slot <= PostTargetPlan.MAX_TARGET && parsed != null) {
                updateTargetSettings(values, slot, null, null, parsed, null);
            } else {
                deviations.add("POST_TARGET_CLEAR_DEFAULTED:colortex" + slot);
            }
        }
    }

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

    private static float[] parseColor(String value) {
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

    private static boolean isNumeric(String value) {
        try {
            return Float.isFinite(Float.parseFloat(value));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String normalizeNumeric(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.endsWith("f") || normalized.endsWith("F")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static ShadowSettings validateShadowSettings(Map<String, String> rawValues) {
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
        return new ShadowSettings(resolution, distance, rawValues, deviations);
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

    /** The number of draw targets a program writes: DRAWBUFFERS, RENDERTARGETS comment, or gl_FragData usage. */
    public static int drawBufferCountOf(String source) {
        Matcher def = DRAWBUFFERS_DEFINE.matcher(source);
        if (def.find()) {
            try {
                int count = Integer.parseInt(def.group(1));
                if (count >= 1) {
                    return count;
                }
            } catch (NumberFormatException ignored) {
            }
        }
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
        String name = VK_TO_NAME.get(vkCode);
        return name != null ? name : "code " + vkCode;
    }
}
