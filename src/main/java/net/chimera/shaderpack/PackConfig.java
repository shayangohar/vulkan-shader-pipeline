package net.chimera.shaderpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
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
 * mapping in FMT_TO_VK; other tokens skip to the default). The narrow M5.4
 * shadow resolution and distance settings are validated here before the
 * shadow framebuffer is created. Draw-buffer counts beyond 1 remain visible
 * to the caller but are not applied.
 */
public final class PackConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");

    /** const int colortexNFormat = TOKEN; (value 0-9 for colortex0-9). */
    private static final Pattern COLORTEX_FMT_CONST =
            Pattern.compile("(?m)^\\s*const\\s+int\\s+colortex(\\d+)Format\\s*=\\s*(\\w+)\\s*;\\s*(?://.*)?$");
    private static final Pattern COLORTEX_CLEAR_CONST = Pattern.compile(
            "(?m)^\\s*const\\s+bool\\s+colortex(\\d+)Clear\\s*=\\s*(true|false)\\s*;\\s*(?://.*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COLORTEX_CLEAR_COLOR_CONST = Pattern.compile(
            "(?m)^\\s*const\\s+vec4\\s+colortex(\\d+)ClearColor\\s*=\\s*vec4\\s*\\(([^)]*)\\)\\s*;\\s*(?://.*)?$");
    private static final Pattern COLORTEX_MIPMAP_CONST = Pattern.compile(
            "(?m)^\\s*const\\s+bool\\s+colortex(\\d+)MipmapEnabled\\s*=\\s*(true|false)\\s*;\\s*(?://.*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHADOW_CONST = Pattern.compile(
            "(?m)^\\s*const\\s+(?:int|float)\\s+"
                    + "(shadowMapResolution|shadowDistance|shadowMapSize|shadowMapFov|shadowDistanceRenderMul|sunPathRotation|sunPathOffset)"
                    + "\\s*=\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)[fF]?\\s*;\\s*(?://.*)?$");
    private static final Pattern PACK_NUMERIC_CONST = Pattern.compile(
            "(?m)^\\s*const\\s+(int|float)\\s+"
                    + "(shadowMapResolution|shadowDistance|shadowMapSize|shadowMapFov|"
                    + "shadowDistanceRenderMul|sunPathRotation|sunPathOffset)"
                    + "\\s*=\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)[fF]?\\s*;\\s*(?://.*)?$");
    private static final Pattern DRAWBUFFERS_DEFINE = Pattern.compile("#define\\s+DRAWBUFFERS(\\d+)");
    private static final Pattern DRAWBUFFERS_COMMENT = Pattern.compile("/\\*\\s*DRAWBUFFERS\\s*:\\s*([0-9,]+)\\s*\\*/");
    private static final Pattern FRAG_DATA = Pattern.compile("gl_FragData\\s*\\[\\s*(\\d+)\\s*\\]");
    private static final Pattern PROPERTY_LINE = Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*([^#\\r\\n]+?)\\s*$");
    /** shaders.properties key shape: colortexNFormat. */
    private static final Pattern COLORTEX_PROPERTY_KEY = Pattern.compile("^colortex(\\d+)Format$");
    private static final Pattern TARGET_SIZE_PROPERTY_KEY =
            Pattern.compile("^size\\.buffer\\.colortex(\\d+)$");
    private static final Pattern TARGET_CLEAR_PROPERTY_KEY =
            Pattern.compile("^colortex(\\d+)Clear$");
    private static final Pattern TARGET_CLEAR_COLOR_PROPERTY_KEY =
            Pattern.compile("^colortex(\\d+)ClearColor$");
    private static final Pattern TARGET_MIPMAP_PROPERTY_KEY =
            Pattern.compile("^colortex(\\d+)MipmapEnabled$");
    private static final Pattern TARGET_FLIP_PROPERTY_KEY =
            Pattern.compile("^flip\\.([A-Za-z0-9_]+)\\.colortex(\\d+)$");

    /** OptiFine format token -> VK format code (verified: 37/97/109 match the pins). */
    public static final Map<String, Integer> FMT_TO_VK = Map.of(
            "RGBA8", 37,
            "RGBA16F", 97,
            "RGBA32F", 109
    );
    /** Common real-pack RGB formats approximated by the supported RGBA images. */
    private static final Map<String, Integer> APPROXIMATE_FMT_TO_VK = Map.of(
            "RGB8", 37,
            "RGB8_SNORM", 97,
            "RGBA8_SNORM", 97,
            "RGB16F", 97,
            "RGBA16", 97,
            "R11F_G11F_B10F", 97
    );
    private static final Map<Integer, String> VK_TO_NAME;
    static {
        Map<Integer, String> reverse = new HashMap<>();
        FMT_TO_VK.forEach((token, code) -> reverse.put(code, token));
        VK_TO_NAME = Map.copyOf(reverse);
    }

    private PackConfig() {}

    public static final int DEFAULT_SHADOW_MAP_RESOLUTION = 2048;
    public static final float DEFAULT_SHADOW_DISTANCE = 128.0F;
    private static final int MIN_SHADOW_MAP_RESOLUTION = 128;
    private static final int MAX_SHADOW_MAP_RESOLUTION = 4096;
    private static final float MIN_SHADOW_DISTANCE = 16.0F;
    private static final float MAX_SHADOW_DISTANCE = 2048.0F;

    /** Validated M5.4 settings. The raw values remain available for logs. */
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

        private float finiteValue(String name, float fallback) {
            try {
                float value = Float.parseFloat(rawValues.getOrDefault(name, Float.toString(fallback)));
                return Float.isFinite(value) ? value : fallback;
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
    }

    /** Load-time target directives. Runtime image allocation is done by M7.4. */
    public record TargetSettings(
            String sizeExpression,
            boolean clear,
            float[] clearColor,
            boolean mipmapped,
            List<String> deviations
    ) {
        public TargetSettings {
            sizeExpression = sizeExpression == null ? "" : sizeExpression.trim();
            clearColor = clearColor == null ? new float[] {0.0F, 0.0F, 0.0F, 0.0F}
                    : clearColor.clone();
            if (clearColor.length != 4) {
                clearColor = new float[] {0.0F, 0.0F, 0.0F, 0.0F};
            }
            deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        }

        public static TargetSettings defaults() {
            return new TargetSettings("", true,
                    new float[] {0.0F, 0.0F, 0.0F, 0.0F}, false, List.of());
        }

        public float[] clearColorCopy() {
            return clearColor.clone();
        }

        @Override
        public float[] clearColor() {
            return clearColor.clone();
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
            Map<String, List<Integer>> preFlips
    ) {
        public PackConfigData(
                Map<Integer, Integer> colortexFormats,
                int drawBufferCount,
                ShadowSettings shadowSettings,
                Map<String, String> shaderConstants,
                List<String> deviations
        ) {
            this(colortexFormats, drawBufferCount, shadowSettings, shaderConstants,
                    deviations, PackSettingsPlan.empty(), Map.of(), Map.of(), Map.of());
        }

        public PackConfigData {
            colortexFormats = Collections.unmodifiableMap(new TreeMap<>(colortexFormats));
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
        Map<Integer, Integer> colortexFormats = new HashMap<>();
        Map<String, String> shadowValues = new TreeMap<>();
        Map<String, String> shaderConstants = new TreeMap<>();
        Map<Integer, TargetSettings> targetSettings = new TreeMap<>();
        Map<String, List<Integer>> flips = new TreeMap<>();
        Map<String, List<Integer>> preFlips = new TreeMap<>();
        List<String> deviations = new ArrayList<>();

        for (PackProgram program : programs.stream()
                .sorted(java.util.Comparator.comparing(PackProgram::name)).toList()) {
            String src = program.executableFragmentSource();
            if (src == null) {
                src = "";
            }
            Matcher fmt = COLORTEX_FMT_CONST.matcher(src);
            while (fmt.find()) {
                int slot;
                try {
                    slot = Integer.parseInt(fmt.group(1));
                } catch (NumberFormatException e) {
                    continue;
                }
                String token = fmt.group(2);
                if (slot > PostTargetPlan.MAX_TARGET) {
                    deviations.add("POST_TARGET_INDEX_UNSUPPORTED:" + slot);
                    continue;
                }
                Integer code = FMT_TO_VK.get(token);
                if (code == null) {
                    code = APPROXIMATE_FMT_TO_VK.get(token);
                    if (code != null) {
                        deviations.add("POST_TARGET_FORMAT_APPROXIMATED:colortex"
                                + slot + "=" + token + "->" + formatName(code));
                    }
                }
                if (code == null) {
                    deviations.add("POST_TARGET_FORMAT_UNSUPPORTED:colortex" + slot + "=" + token);
                    LOGGER.warn("[chimera] pack {}: colortex{}Format {}: unhandled format, keeping default",
                            program.name(), slot, token);
                    continue;
                }
                Integer previous = colortexFormats.put(slot, code);
                if (previous != null && !previous.equals(code)) {
                    LOGGER.info("[chimera] pack {}: colortex{}Format override {} -> {}", program.name(), slot, previous, code);
                }
            }
            collectShadowConstants(src, shadowValues);
            collectShadowConstants(program.executableVertexSource(), shadowValues);
            collectPackConstants(src, shaderConstants, deviations);
            collectPackConstants(program.executableVertexSource(), shaderConstants, deviations);
            collectTargetConstants(src, targetSettings, deviations);
            collectTargetConstants(program.executableVertexSource(), targetSettings, deviations);
        }

        // shaders.properties overrides (OptiFine packs may declare formats here).
        Path properties = shadersDir != null ? shadersDir.resolve("shaders.properties") : null;
        if (properties != null && Files.isRegularFile(properties)) {
            try {
                for (String line : Files.readAllLines(properties, StandardCharsets.UTF_8)) {
                    Matcher m = PROPERTY_LINE.matcher(line);
                    if (!m.find()) {
                        continue;
                    }
                    String key = m.group(1);
                    String value = m.group(2).trim();
                    Matcher colortexKey = COLORTEX_PROPERTY_KEY.matcher(key);
                    if (colortexKey.find()) {
                        int slot;
                        try {
                            slot = Integer.parseInt(colortexKey.group(1));
                        } catch (NumberFormatException e) {
                            LOGGER.warn("[chimera] pack properties: {} is not a valid colortex slot, skipped", key);
                            continue;
                        }
                        if (slot > PostTargetPlan.MAX_TARGET) {
                            deviations.add("POST_TARGET_INDEX_UNSUPPORTED:" + slot);
                            continue;
                        }
                        Integer code = FMT_TO_VK.get(value);
                        if (code == null) {
                            code = APPROXIMATE_FMT_TO_VK.get(value);
                            if (code != null) {
                                deviations.add("POST_TARGET_FORMAT_APPROXIMATED:colortex"
                                        + slot + "=" + value + "->" + formatName(code));
                            }
                        }
                        if (code == null) {
                            deviations.add("POST_TARGET_FORMAT_UNSUPPORTED:" + key + "=" + value);
                            LOGGER.warn("[chimera] pack properties {}: unhandled format, keeping default", value);
                            continue;
                        }
                        colortexFormats.put(slot, code);
                        LOGGER.info("[chimera] pack properties: {}={}", key, value);
                    } else {
                        Matcher sizeKey = TARGET_SIZE_PROPERTY_KEY.matcher(key);
                        Matcher clearKey = TARGET_CLEAR_PROPERTY_KEY.matcher(key);
                        Matcher clearColorKey = TARGET_CLEAR_COLOR_PROPERTY_KEY.matcher(key);
                        Matcher mipmapKey = TARGET_MIPMAP_PROPERTY_KEY.matcher(key);
                        Matcher flipKey = TARGET_FLIP_PROPERTY_KEY.matcher(key);
                        if (sizeKey.matches()) {
                            int slot = parseTargetIndex(sizeKey.group(1), deviations);
                            if (slot >= 0 && slot <= PostTargetPlan.MAX_TARGET) {
                                updateTargetSettings(targetSettings, slot, value, null, null,
                                        null, null);
                            }
                        } else if (clearKey.matches()) {
                            int slot = parseTargetIndex(clearKey.group(1), deviations);
                            Boolean clear = parseBoolean(value);
                            if (slot >= 0 && slot <= PostTargetPlan.MAX_TARGET && clear != null) {
                                updateTargetSettings(targetSettings, slot, null, clear, null,
                                        null, null);
                            } else if (clear != null) {
                                deviations.add("POST_TARGET_CLEAR_DEFAULTED:colortex" + slot);
                            }
                        } else if (clearColorKey.matches()) {
                            int slot = parseTargetIndex(clearColorKey.group(1), deviations);
                            float[] color = parseColor(value);
                            if (slot >= 0 && slot <= PostTargetPlan.MAX_TARGET && color != null) {
                                updateTargetSettings(targetSettings, slot, null, null, color,
                                        null, null);
                            } else {
                                deviations.add("POST_TARGET_CLEAR_DEFAULTED:colortex" + slot);
                            }
                        } else if (mipmapKey.matches()) {
                            int slot = parseTargetIndex(mipmapKey.group(1), deviations);
                            Boolean mipmapped = parseBoolean(value);
                            if (slot >= 0 && slot <= PostTargetPlan.MAX_TARGET && mipmapped != null) {
                                updateTargetSettings(targetSettings, slot, null, null, null,
                                        mipmapped, null);
                            } else {
                                deviations.add("POST_TARGET_MIPMAP_UNSUPPORTED:colortex" + slot);
                            }
                        } else if (flipKey.matches()) {
                            int slot = parseTargetIndex(flipKey.group(2), deviations);
                            Boolean flip = parseBoolean(value);
                            if (slot >= 0 && slot <= PostTargetPlan.MAX_TARGET && flip != null) {
                                Map<String, List<Integer>> destination = flipKey.group(1).endsWith("_pre")
                                        ? preFlips : flips;
                                updateFlip(destination, flipKey.group(1), slot, flip);
                            } else {
                                deviations.add("POST_TARGET_FLIP_UNSUPPORTED:" + key);
                            }
                        } else if (key.startsWith("size.buffer.")
                                || key.startsWith("flip.")) {
                            deviations.add("POST_TARGET_PROPERTY_UNSUPPORTED:" + key);
                        }
                    }
                    if (SHADOW_PROPERTIES.contains(key)) {
                        shadowValues.put(key, value);
                        LOGGER.info("[chimera] pack properties: {}={}", key, value);
                    } else if (PACK_CONSTANT_NAMES.contains(key)) {
                        if (isNumeric(value)) {
                            shaderConstants.put(key, normalizeNumeric(value));
                            LOGGER.info("[chimera] pack properties: {}={}", key, value);
                        } else {
                            deviations.add("PACK_CONSTANT_UNSUPPORTED:" + key);
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("[chimera] pack: cannot read {}: {}", properties, e.getMessage());
            }
        }

        // Draw-buffer count: the geometry program's (targets it writes).
        int drawBufferCount = 1;
        for (PackProgram program : programs) {
            if (program.name().equals("gbuffers_terrain")) {
                drawBufferCount = drawBufferCountOf(program.executableFragmentSource());
                break;
            }
        }

        ShadowSettings shadowSettings = validateShadowSettings(shadowValues);
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
        return new PackConfigData(colortexFormats, drawBufferCount,
                shadowSettings, shaderConstants, deviations, settings,
                targetSettings, flips, preFlips);
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
                        Boolean.parseBoolean(clear.group(2)), null, null, null);
            }
        }
        Matcher color = COLORTEX_CLEAR_COLOR_CONST.matcher(text);
        while (color.find()) {
            int slot = parseTargetIndex(color.group(1), deviations);
            float[] parsed = parseColor(color.group(2));
            if (slot >= 0 && slot <= PostTargetPlan.MAX_TARGET && parsed != null) {
                updateTargetSettings(values, slot, null, null, parsed, null, null);
            } else {
                deviations.add("POST_TARGET_CLEAR_DEFAULTED:colortex" + slot);
            }
        }
        Matcher mipmap = COLORTEX_MIPMAP_CONST.matcher(text);
        while (mipmap.find()) {
            int slot = parseTargetIndex(mipmap.group(1), deviations);
            if (slot >= 0 && slot <= PostTargetPlan.MAX_TARGET) {
                updateTargetSettings(values, slot, null, null, null,
                        Boolean.parseBoolean(mipmap.group(2)), null);
            }
        }
    }

    private static int parseTargetIndex(String value, List<String> deviations) {
        try {
            int slot = Integer.parseInt(value);
            if (slot < 0 || slot > PostTargetPlan.MAX_TARGET) {
                deviations.add("POST_TARGET_INDEX_UNSUPPORTED:" + slot);
                return -1;
            }
            return slot;
        } catch (NumberFormatException e) {
            deviations.add("POST_TARGET_INDEX_UNSUPPORTED:" + value);
            return -1;
        }
    }

    private static void updateTargetSettings(
            Map<Integer, TargetSettings> values,
            int slot,
            String size,
            Boolean clear,
            float[] clearColor,
            Boolean mipmapped,
            List<String> deviations
    ) {
        TargetSettings previous = values.getOrDefault(slot, TargetSettings.defaults());
        values.put(slot, new TargetSettings(
                size == null ? previous.sizeExpression() : size,
                clear == null ? previous.clear() : clear,
                clearColor == null ? previous.clearColorCopy() : clearColor,
                mipmapped == null ? previous.mipmapped() : mipmapped,
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
        int resolution = DEFAULT_SHADOW_MAP_RESOLUTION;
        String rawResolution = rawValues.get("shadowMapResolution");
        if (rawResolution != null) {
            try {
                double parsed = Double.parseDouble(rawResolution);
                if (parsed >= MIN_SHADOW_MAP_RESOLUTION && parsed <= MAX_SHADOW_MAP_RESOLUTION
                        && parsed == Math.rint(parsed)) {
                    resolution = (int) parsed;
                } else {
                    deviations.add("SHADOW_SETTING_DEFAULTED:shadowMapResolution");
                }
            } catch (NumberFormatException e) {
                deviations.add("SHADOW_SETTING_DEFAULTED:shadowMapResolution");
            }
        }

        float distance = DEFAULT_SHADOW_DISTANCE;
        String rawDistance = rawValues.get("shadowDistance");
        if (rawDistance != null) {
            try {
                float parsed = Float.parseFloat(rawDistance);
                if (Float.isFinite(parsed)
                        && parsed >= MIN_SHADOW_DISTANCE && parsed <= MAX_SHADOW_DISTANCE) {
                    distance = parsed;
                } else {
                    deviations.add("SHADOW_SETTING_DEFAULTED:shadowDistance");
                }
            } catch (NumberFormatException e) {
                deviations.add("SHADOW_SETTING_DEFAULTED:shadowDistance");
            }
        }

        validateFiniteShadowSetting(rawValues, deviations, "shadowDistanceRenderMul", 0.0F, 4.0F);
        validateFiniteShadowSetting(rawValues, deviations, "sunPathRotation", -360.0F, 360.0F);
        validateFiniteShadowSetting(rawValues, deviations, "sunPathOffset", -360.0F, 360.0F);

        for (String key : rawValues.keySet()) {
            if (!key.equals("shadowMapResolution") && !key.equals("shadowDistance")
                    && !key.equals("shadowDistanceRenderMul")
                    && !key.equals("sunPathRotation") && !key.equals("sunPathOffset")) {
                deviations.add("SHADOW_SETTING_UNSUPPORTED:" + key);
            }
        }
        return new ShadowSettings(resolution, distance, rawValues, deviations);
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
