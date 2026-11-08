package net.chimera.shaderpack;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Iris's device macros (StandardMacros): one MC_GL_VENDOR_* from the GL driver's GL_VENDOR and one
 * MC_GL_RENDERER_* from its GL_RENDERER, each matched by prefix, and MC_ before each extension the
 * driver lists. Chimera has no GL driver, so it names them from the PackDevice as that vendor's GL
 * driver reports them: NVIDIA's "NVIDIA Corporation", AMD's own driver "ATI Technologies Inc.",
 * Mesa's "AMD" and "Intel", with the device name as the renderer, which Mesa's Intel driver
 * prefixes with "Mesa". It lists an extension only when that driver offers it and the device runs
 * what its directive compiles to. Packs choose vendor paths by them: RenderPearl and Noble take
 * NVIDIA's half floats with MC_GL_VENDOR_NVIDIA alone, Alpha Piscium and photon with
 * MC_GL_NV_gpu_shader5 or MC_GL_AMD_gpu_shader_half_float.
 */
public final class PackDeviceMacros {
    /** A GL extension a driver lists, and when the device runs what its directive compiles to. */
    private record Extension(String name, Predicate<PackDevice> runs) {}

    /** Vertex, fragment and compute: the stages GL_KHR_shader_subgroup offers its operations in. */
    private static final int SUBGROUP_STAGES = PackDevice.STAGE_VERTEX | PackDevice.STAGE_FRAGMENT
            | PackDevice.STAGE_COMPUTE;
    /** Basic through quad: every operation GL_KHR_shader_subgroup names. */
    private static final int SUBGROUP_OPERATIONS = 0xFF;
    private static final int PARTITIONED_OPERATIONS = 0x100;

    /** It compiles as its Vulkan types (GlslVendorExtensions). */
    private static final Extension NV_GPU_SHADER5 = new Extension("GL_NV_gpu_shader5",
            device -> device.sizedTypes().all());
    private static final Extension NV_PARTITIONED = new Extension("GL_NV_shader_subgroup_partitioned",
            device -> device.extensions().contains("VK_NV_shader_subgroup_partitioned")
                    && (device.subgroupOperations() & PARTITIONED_OPERATIONS) != 0);
    private static final Extension KHR_SUBGROUP = new Extension("GL_KHR_shader_subgroup",
            device -> (device.subgroupStages() & SUBGROUP_STAGES) == SUBGROUP_STAGES
                    && (device.subgroupOperations() & SUBGROUP_OPERATIONS) == SUBGROUP_OPERATIONS);
    /** Bliss reads it as leave to store into its light volume from the shadow pass. */
    private static final Extension IMAGE_LOAD_STORE = new Extension("GL_EXT_shader_image_load_store",
            device -> device.features().containsAll(List.of("vertexPipelineStoresAndAtomics",
                    "fragmentStoresAndAtomics")));
    private static final Extension INT64 = new Extension("GL_ARB_gpu_shader_int64",
            device -> device.features().contains("shaderInt64"));
    private static final Extension AMD_HALF_FLOAT = new Extension("GL_AMD_gpu_shader_half_float",
            device -> device.features().containsAll(List.of("shaderFloat16", "storageBuffer16BitAccess")));
    private static final Extension AMD_INT16 = new Extension("GL_AMD_gpu_shader_int16",
            device -> device.features().containsAll(List.of("shaderInt16", "storageBuffer16BitAccess")));
    /** Served natively or written with min and max (GlslVendorExtensions). */
    private static final Extension AMD_TRINARY = new Extension("GL_AMD_shader_trinary_minmax", device -> true);

    private static final List<Extension> NVIDIA = List.of(NV_GPU_SHADER5, NV_PARTITIONED, KHR_SUBGROUP,
            IMAGE_LOAD_STORE, INT64);
    private static final List<Extension> ATI = List.of(AMD_HALF_FLOAT, AMD_INT16, AMD_TRINARY, IMAGE_LOAD_STORE,
            INT64);
    private static final List<Extension> MESA = List.of(AMD_TRINARY, KHR_SUBGROUP, INT64);

    /** Iris's GL_RENDERER prefixes, in the order it tries them. */
    private static final List<Map.Entry<String, String>> RENDERER_PREFIXES = List.of(
            Map.entry("amd", "MC_GL_RENDERER_RADEON"), Map.entry("ati", "MC_GL_RENDERER_RADEON"),
            Map.entry("radeon", "MC_GL_RENDERER_RADEON"), Map.entry("gallium", "MC_GL_RENDERER_GALLIUM"),
            Map.entry("intel", "MC_GL_RENDERER_INTEL"), Map.entry("geforce", "MC_GL_RENDERER_GEFORCE"),
            Map.entry("nvidia", "MC_GL_RENDERER_GEFORCE"), Map.entry("quadro", "MC_GL_RENDERER_QUADRO"),
            Map.entry("nvs", "MC_GL_RENDERER_QUADRO"), Map.entry("mesa", "MC_GL_RENDERER_MESA"),
            Map.entry("apple", "MC_GL_RENDERER_APPLE"));

    private PackDeviceMacros() {}

    /** The device's macros, each defined as 1 like Chimera's other flags. */
    public static Map<String, String> macros(PackDevice device) {
        TreeMap<String, String> result = new TreeMap<>();
        result.put(vendor(device), "1");
        result.put(renderer(device), "1");
        for (Extension extension : extensions(device)) {
            if (extension.runs().test(device)) result.put("MC_" + extension.name(), "1");
        }
        return result;
    }

    /** MC_GL_VENDOR_*: what the vendor's GL driver gives as GL_VENDOR, by Iris's prefixes. */
    static String vendor(PackDevice device) {
        return switch (device.vendorId()) {
            case PackDevice.VENDOR_NVIDIA -> nvidia(device) ? "MC_GL_VENDOR_NVIDIA" : "MC_GL_VENDOR_OTHER";
            case PackDevice.VENDOR_AMD -> device.driverId() == PackDevice.DRIVER_AMD_PROPRIETARY
                    ? "MC_GL_VENDOR_ATI" : "MC_GL_VENDOR_AMD";
            case PackDevice.VENDOR_INTEL -> "MC_GL_VENDOR_INTEL";
            default -> "MC_GL_VENDOR_OTHER";
        };
    }

    /** MC_GL_RENDERER_*: the device name, which GL_RENDERER begins with, by Iris's prefixes. */
    static String renderer(PackDevice device) {
        if (device.vendorId() == PackDevice.VENDOR_INTEL
                && device.driverId() == PackDevice.DRIVER_INTEL_OPEN_SOURCE_MESA) {
            return "MC_GL_RENDERER_MESA";
        }
        if (device.vendorId() == PackDevice.VENDOR_NVIDIA && !nvidia(device)) return "MC_GL_RENDERER_OTHER";
        String name = device.name().toLowerCase(Locale.ROOT);
        return RENDERER_PREFIXES.stream().filter(prefix -> name.startsWith(prefix.getKey())).map(Map.Entry::getValue)
                .findFirst().orElse("MC_GL_RENDERER_OTHER");
    }

    /** The extensions among those Chimera serves that the device's GL driver lists. */
    private static List<Extension> extensions(PackDevice device) {
        return switch (device.vendorId()) {
            case PackDevice.VENDOR_NVIDIA -> nvidia(device) ? NVIDIA : List.of();
            case PackDevice.VENDOR_AMD -> device.driverId() == PackDevice.DRIVER_AMD_PROPRIETARY ? ATI : MESA;
            case PackDevice.VENDOR_INTEL -> device.driverId() == PackDevice.DRIVER_INTEL_OPEN_SOURCE_MESA
                    ? MESA : List.of();
            default -> List.of();
        };
    }

    /** NVIDIA's own driver; nouveau and NVK report another vendor. */
    private static boolean nvidia(PackDevice device) {
        return device.driverId() == PackDevice.DRIVER_NVIDIA_PROPRIETARY;
    }
}
