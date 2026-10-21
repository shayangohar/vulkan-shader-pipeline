package net.chimera.shaderpack;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Iris's InternalTextureFormat list as colour-target formats. Each token maps to the Vulkan format
 * its texels are stored in, how many bytes one takes, and whether shaders read it as floats or as
 * unsigned or signed integers. A format Vulkan cannot draw to as GL declares it is approximated:
 * an RGB format gains alpha (which reads as 1, as GL's missing alpha does), a signed normalized
 * one becomes a float with at least its precision, and a packed or low-precision one widens.
 */
public final class IrisColorFormats {
    private IrisColorFormats() {}

    /** How shaders read a format's texels: GL's normalized and float formats, or its integer ones. */
    public enum Kind { FLOAT, UINT, SINT }

    /** One token: its Vulkan format, bytes per texel, kind, and whether Vulkan stores it exactly. */
    public record Format(String token, int vkFormat, int bytes, Kind kind, boolean exact, boolean paddedAlpha) {}

    private static final Map<String, Format> FORMATS = new LinkedHashMap<>();
    private static final Map<Integer, Format> BY_VK = new HashMap<>();

    static {
        exact("R8", 9, 1, Kind.FLOAT);              // VK_FORMAT_R8_UNORM
        exact("RG8", 16, 2, Kind.FLOAT);            // VK_FORMAT_R8G8_UNORM
        exact("RGBA8", 37, 4, Kind.FLOAT);          // VK_FORMAT_R8G8B8A8_UNORM
        exact("RGB10_A2", 64, 4, Kind.FLOAT);       // VK_FORMAT_A2B10G10R10_UNORM_PACK32 (GL bit order)
        exact("R16", 70, 2, Kind.FLOAT);            // VK_FORMAT_R16_UNORM
        exact("R16F", 76, 2, Kind.FLOAT);           // VK_FORMAT_R16_SFLOAT
        exact("RG16", 77, 4, Kind.FLOAT);           // VK_FORMAT_R16G16_UNORM
        exact("RG16F", 83, 4, Kind.FLOAT);          // VK_FORMAT_R16G16_SFLOAT
        exact("RGBA16", 91, 8, Kind.FLOAT);         // VK_FORMAT_R16G16B16A16_UNORM
        exact("RGBA16F", 97, 8, Kind.FLOAT);        // VK_FORMAT_R16G16B16A16_SFLOAT
        exact("R32F", 100, 4, Kind.FLOAT);          // VK_FORMAT_R32_SFLOAT
        exact("RG32F", 103, 8, Kind.FLOAT);         // VK_FORMAT_R32G32_SFLOAT
        exact("RGBA32F", 109, 16, Kind.FLOAT);      // VK_FORMAT_R32G32B32A32_SFLOAT
        exact("R8UI", 13, 1, Kind.UINT);            // VK_FORMAT_R8_UINT
        exact("R8I", 14, 1, Kind.SINT);             // VK_FORMAT_R8_SINT
        exact("RG8UI", 20, 2, Kind.UINT);           // VK_FORMAT_R8G8_UINT
        exact("RG8I", 21, 2, Kind.SINT);            // VK_FORMAT_R8G8_SINT
        exact("RGBA8UI", 41, 4, Kind.UINT);         // VK_FORMAT_R8G8B8A8_UINT
        exact("RGBA8I", 42, 4, Kind.SINT);          // VK_FORMAT_R8G8B8A8_SINT
        exact("RGB10_A2UI", 68, 4, Kind.UINT);      // VK_FORMAT_A2B10G10R10_UINT_PACK32
        exact("R16UI", 74, 2, Kind.UINT);           // VK_FORMAT_R16_UINT
        exact("R16I", 75, 2, Kind.SINT);            // VK_FORMAT_R16_SINT
        exact("RG16UI", 81, 4, Kind.UINT);          // VK_FORMAT_R16G16_UINT
        exact("RG16I", 82, 4, Kind.SINT);           // VK_FORMAT_R16G16_SINT
        exact("RGBA16UI", 95, 8, Kind.UINT);        // VK_FORMAT_R16G16B16A16_UINT
        exact("RGBA16I", 96, 8, Kind.SINT);         // VK_FORMAT_R16G16B16A16_SINT
        exact("R32UI", 98, 4, Kind.UINT);           // VK_FORMAT_R32_UINT
        exact("R32I", 99, 4, Kind.SINT);            // VK_FORMAT_R32_SINT
        exact("RG32UI", 101, 8, Kind.UINT);         // VK_FORMAT_R32G32_UINT
        exact("RG32I", 102, 8, Kind.SINT);          // VK_FORMAT_R32G32_SINT
        exact("RGBA32UI", 107, 16, Kind.UINT);      // VK_FORMAT_R32G32B32A32_UINT
        exact("RGBA32I", 108, 16, Kind.SINT);       // VK_FORMAT_R32G32B32A32_SINT
        approximate("RGB8", "RGBA8", true);
        approximate("R8_SNORM", "R16F", false);
        approximate("RG8_SNORM", "RG16F", false);
        approximate("RGB8_SNORM", "RGBA16F", true);
        approximate("RGBA8_SNORM", "RGBA16F", false);
        approximate("RGB16", "RGBA16", true);
        approximate("R16_SNORM", "R32F", false);
        approximate("RG16_SNORM", "RG32F", false);
        approximate("RGB16_SNORM", "RGBA32F", true);
        approximate("RGBA16_SNORM", "RGBA32F", false);
        approximate("RGB16F", "RGBA16F", true);
        approximate("RGB32F", "RGBA32F", true);
        approximate("RGBA2", "RGBA8", false);
        approximate("RGBA4", "RGBA8", false);
        approximate("R3_G3_B2", "RGBA8", true);
        approximate("RGB5_A1", "RGBA8", false);
        approximate("RGB565", "RGBA8", true);
        approximate("R11F_G11F_B10F", "RGBA16F", true);
        approximate("RGB9_E5", "RGBA16F", true);
        approximate("RGB8UI", "RGBA8UI", true);
        approximate("RGB8I", "RGBA8I", true);
        approximate("RGB16UI", "RGBA16UI", true);
        approximate("RGB16I", "RGBA16I", true);
        approximate("RGB32UI", "RGBA32UI", true);
        approximate("RGB32I", "RGBA32I", true);
    }

    /** Iris's {@code RGBA}, the unsized default, which GL stores as RGBA8. */
    private static final Map<String, String> ALIASES = Map.of("RGBA", "RGBA8");

    private static void exact(String token, int vkFormat, int bytes, Kind kind) {
        Format format = new Format(token, vkFormat, bytes, kind, true, false);
        FORMATS.put(token, format);
        BY_VK.put(vkFormat, format);
    }

    private static void approximate(String token, String storedAs, boolean paddedAlpha) {
        Format stored = FORMATS.get(storedAs);
        FORMATS.put(token, new Format(token, stored.vkFormat(), stored.bytes(), stored.kind(), false, paddedAlpha));
    }

    /** The format a colortexNFormat token names; null for a token Iris does not know. */
    public static Format of(String token) {
        if (token == null) return null;
        // Iris InternalTextureFormat.fromString reads the name in any case.
        String name = token.toUpperCase(Locale.ROOT);
        return FORMATS.get(ALIASES.getOrDefault(name, name));
    }

    /** Every token Iris names, in declaration order. */
    public static Set<String> tokens() {
        return FORMATS.keySet();
    }

    /** The exact token stored in {@code vkFormat}; null when no target uses that format. */
    public static Format stored(int vkFormat) {
        return BY_VK.get(vkFormat);
    }

    /** Bytes per texel of a target format; 0 when no target uses it. */
    public static int bytes(int vkFormat) {
        Format format = stored(vkFormat);
        return format == null ? 0 : format.bytes();
    }

    /** Whether a target format holds integers, which are never filtered, blended or mipmapped linearly. */
    public static boolean integer(int vkFormat) {
        Format format = stored(vkFormat);
        return format != null && format.kind() != Kind.FLOAT;
    }
}
