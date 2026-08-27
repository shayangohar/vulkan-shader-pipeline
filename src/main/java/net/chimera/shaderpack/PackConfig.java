package net.chimera.shaderpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the shader-source declaration block of an OptiFine-format pack:
 * colortex buffer formats (const int colortexNFormat = TOKEN;), shadow
 * settings, and the geometry draw-buffer count (DRAWBUFFERS). A
 * shaders.properties file next to the pass list overrides same-key entries.
 *
 * <p>Honored: colortex formats drive the HDR buffer format (any VK-code
 * mapping in FMT_TO_VK; other tokens skip to the default). Shadow settings
 * and draw-buffer counts beyond 1 are parsed and validated in logs only —
 * honoring them means upstream multi-attachment work (M5).
 */
public final class PackConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");

    /** const int colortexNFormat = TOKEN; (value 0-9 for colortex0-9). */
    private static final Pattern COLORTEX_FMT_CONST =
            Pattern.compile("(?m)^\\s*const\\s+int\\s+colortex(\\d+)Format\\s*=\\s*(\\w+)\\s*;\\s*$");
    private static final Pattern SHADOW_CONST =
            Pattern.compile("(?m)^\\s*const\\s+int\\s+(shadowMapResolution|shadowDistance|shadowMapSize|shadowMapFov)\\s*=\\s*(\\d+)\\s*;\\s*$");
    private static final Pattern DRAWBUFFERS_DEFINE = Pattern.compile("#define\\s+DRAWBUFFERS(\\d+)");
    private static final Pattern DRAWBUFFERS_COMMENT = Pattern.compile("/\\*\\s*DRAWBUFFERS\\s*:\\s*([0-9,]+)\\s*\\*/");
    private static final Pattern FRAG_DATA = Pattern.compile("gl_FragData\\s*\\[\\s*(\\d+)\\s*\\]");
    private static final Pattern PROPERTY_LINE = Pattern.compile("^\\s*([\\w]+)\\s*=\\s*(\\w+)\\s*$");
    /** shaders.properties key shape: colortexNFormat. */
    private static final Pattern COLORTEX_PROPERTY_KEY = Pattern.compile("^colortex(\\d+)Format$");

    /** OptiFine format token -> VK format code (verified: 37/97/109 match the pins). */
    public static final Map<String, Integer> FMT_TO_VK = Map.of(
            "RGBA8", 37,
            "RGBA16F", 97,
            "RGBA32F", 109
    );
    private static final Map<Integer, String> VK_TO_NAME;
    static {
        Map<Integer, String> reverse = new HashMap<>();
        FMT_TO_VK.forEach((token, code) -> reverse.put(code, token));
        VK_TO_NAME = Map.copyOf(reverse);
    }

    private PackConfig() {}

    public record PackConfigData(Map<Integer, Integer> colortexFormats, int drawBufferCount,
                                 Map<String, Integer> shadowSettings) {}

    public static PackConfigData parse(List<PackProgram> programs, Path shadersDir) {
        Map<Integer, Integer> colortexFormats = new HashMap<>();
        Map<String, Integer> shadowSettings = new HashMap<>();

        for (PackProgram program : programs) {
            String src = program.fragmentSource();
            Matcher fmt = COLORTEX_FMT_CONST.matcher(src);
            while (fmt.find()) {
                int slot;
                try {
                    slot = Integer.parseInt(fmt.group(1));
                } catch (NumberFormatException e) {
                    continue;
                }
                String token = fmt.group(2);
                Integer code = FMT_TO_VK.get(token);
                if (code == null) {
                    LOGGER.warn("[chimera] pack {}: colortex{}Format {}: unhandled format, keeping default",
                            program.name(), slot, token);
                    continue;
                }
                Integer previous = colortexFormats.put(slot, code);
                if (previous != null && previous != code) {
                    LOGGER.info("[chimera] pack {}: colortex{}Format override {} -> {}", program.name(), slot, previous, code);
                }
            }
            Matcher shadow = SHADOW_CONST.matcher(src);
            while (shadow.find()) {
                int value;
                try {
                    value = Integer.parseInt(shadow.group(2));
                } catch (NumberFormatException e) {
                    continue;
                }
                shadowSettings.put(shadow.group(1), value);
            }
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
                    String value = m.group(2);
                    Matcher colortexKey = COLORTEX_PROPERTY_KEY.matcher(key);
                    if (colortexKey.find()) {
                        int slot;
                        try {
                            slot = Integer.parseInt(colortexKey.group(1));
                        } catch (NumberFormatException e) {
                            LOGGER.warn("[chimera] pack properties: {} is not a valid colortex slot, skipped", key);
                            continue;
                        }
                        Integer code = FMT_TO_VK.get(value);
                        if (code == null) {
                            LOGGER.warn("[chimera] pack properties {}: unhandled format, keeping default", value);
                            continue;
                        }
                        colortexFormats.put(slot, code);
                        LOGGER.info("[chimera] pack properties: {}={}", key, value);
                    } else if (SHADOW_PROPERTIES.contains(key)) {
                        try {
                            shadowSettings.put(key, Integer.parseInt(value));
                            LOGGER.info("[chimera] pack properties: {}={}", key, value);
                        } catch (NumberFormatException e) {
                            LOGGER.warn("[chimera] pack properties: {}={} is not an int, skipped", key, value);
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
                drawBufferCount = drawBufferCountOf(program.fragmentSource());
                break;
            }
        }

        return new PackConfigData(colortexFormats, drawBufferCount, shadowSettings);
    }

    private static final List<String> SHADOW_PROPERTIES = List.of(
            "shadowMapResolution", "shadowDistance", "shadowMapSize", "shadowMapFov");

    /** The number of draw targets a program writes: DRAWBUFFERS, RENDERTARGETS comment, or gl_FragData usage. */
    public static int drawBufferCountOf(String source) {
        Matcher def = DRAWBUFFERS_DEFINE.matcher(source);
        if (def.find()) {
            try {
                int n = Integer.parseInt(def.group(1));
                if (n >= 1) {
                    return n;
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