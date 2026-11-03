package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The GPU pack GLSL compiles for: its identity, which a GL driver reports as GL_VENDOR and
 * GL_RENDERER, and what the logical device was created with, which bounds the SPIR-V a pipeline
 * may be built from ({@link #missing}). A GL driver refuses a stage that needs what it lacks when
 * it compiles the stage; a Vulkan driver handed such a module can crash instead (NVIDIA's did, on
 * RenderPearl's deferred1, 2026-11-02). In game it is the device VulkanMod created
 * (ChimeraDeviceFeaturesMixin). Before that, as offline in the tests, the corpus gate and the
 * option sweep, it is the {@code chimera.device} profile: nvidia (the default) or amd, each recorded
 * from a driver on the owner's PC, or intel, typical of an Arc GPU on Intel's Windows driver.
 *
 * @param features the Vulkan features enabled, by their VkPhysicalDevice*Features member names
 * @param subgroupStages VkPhysicalDeviceSubgroupProperties.supportedStages
 * @param subgroupOperations VkPhysicalDeviceSubgroupProperties.supportedOperations
 * @param extensions the device extensions enabled
 */
public record PackDevice(String profile, int vendorId, int driverId, String name, SortedSet<String> features,
                         int subgroupStages, int subgroupOperations, SortedSet<String> extensions) {
    public static final int VENDOR_AMD = 0x1002;
    public static final int VENDOR_NVIDIA = 0x10DE;
    public static final int VENDOR_INTEL = 0x8086;
    /** VkDriverId values. */
    public static final int DRIVER_AMD_PROPRIETARY = 1;
    public static final int DRIVER_NVIDIA_PROPRIETARY = 4;
    public static final int DRIVER_INTEL_PROPRIETARY_WINDOWS = 5;
    public static final int DRIVER_INTEL_OPEN_SOURCE_MESA = 6;

    /** VkShaderStageFlagBits a module can be compiled for. */
    public static final int STAGE_VERTEX = 0x1;
    public static final int STAGE_TESSELLATION_CONTROL = 0x2;
    public static final int STAGE_TESSELLATION_EVALUATION = 0x4;
    public static final int STAGE_GEOMETRY = 0x8;
    public static final int STAGE_FRAGMENT = 0x10;
    public static final int STAGE_COMPUTE = 0x20;

    /**
     * VkPhysicalDeviceFeatures members Chimera enables where the device offers them: those pack GLSL
     * compiles to, as a GL driver offers them all, and tessellationShader, which VulkanMod enables.
     */
    public static final List<String> CORE_FEATURES = List.of("vertexPipelineStoresAndAtomics",
            "fragmentStoresAndAtomics", "shaderStorageImageExtendedFormats", "shaderStorageImageReadWithoutFormat",
            "shaderStorageImageWriteWithoutFormat", "shaderStorageImageMultisample", "independentBlend",
            "geometryShader", "tessellationShader", "shaderTessellationAndGeometryPointSize", "shaderInt16",
            "shaderInt64", "shaderFloat64", "shaderImageGatherExtended", "shaderClipDistance", "shaderCullDistance",
            "sampleRateShading", "imageCubeArray", "shaderResourceMinLod", "multiViewport");
    /** VkPhysicalDeviceVulkan11Features members; VulkanMod enables shaderDrawParameters itself. */
    public static final List<String> VULKAN11_FEATURES = List.of("storageBuffer16BitAccess",
            "uniformAndStorageBuffer16BitAccess", "storagePushConstant16", "storageInputOutput16",
            "shaderDrawParameters");
    /** VkPhysicalDeviceVulkan12Features members. */
    public static final List<String> VULKAN12_FEATURES = List.of("shaderFloat16", "shaderInt8",
            "storageBuffer8BitAccess", "uniformAndStorageBuffer8BitAccess", "storagePushConstant8",
            "shaderSubgroupExtendedTypes", "subgroupBroadcastDynamicId", "shaderBufferInt64Atomics",
            "shaderSharedInt64Atomics");
    /**
     * Device extensions pack GLSL compiles to that need no feature struct: RenderPearl's
     * GL_AMD_shader_trinary_minmax, Alpha Piscium's GL_NV_shader_subgroup_partitioned, and GLSL
     * 4.60's anyInvocation, allInvocations and allInvocationsEqual, which RenderPearl 2.8 calls
     * without GL_KHR_shader_subgroup.
     */
    public static final List<String> PACK_EXTENSIONS = List.of("VK_AMD_shader_trinary_minmax",
            "VK_NV_shader_subgroup_partitioned", "VK_EXT_shader_subgroup_vote");

    /** SPIR-V capabilities and the feature each needs (SPIR-V 1.5 numbering). */
    private static final Map<Integer, String> CAPABILITY_FEATURES = Map.ofEntries(
            Map.entry(2, "geometryShader"), Map.entry(3, "tessellationShader"), Map.entry(9, "shaderFloat16"),
            Map.entry(10, "shaderFloat64"), Map.entry(11, "shaderInt64"), Map.entry(12, "shaderBufferInt64Atomics"),
            Map.entry(22, "shaderInt16"), Map.entry(23, "shaderTessellationAndGeometryPointSize"),
            Map.entry(24, "shaderTessellationAndGeometryPointSize"), Map.entry(25, "shaderImageGatherExtended"),
            Map.entry(27, "shaderStorageImageMultisample"), Map.entry(32, "shaderClipDistance"),
            Map.entry(33, "shaderCullDistance"), Map.entry(34, "imageCubeArray"), Map.entry(35, "sampleRateShading"),
            Map.entry(39, "shaderInt8"), Map.entry(42, "shaderResourceMinLod"), Map.entry(45, "imageCubeArray"),
            Map.entry(49, "shaderStorageImageExtendedFormats"), Map.entry(55, "shaderStorageImageReadWithoutFormat"),
            Map.entry(56, "shaderStorageImageWriteWithoutFormat"), Map.entry(57, "multiViewport"),
            Map.entry(4427, "shaderDrawParameters"), Map.entry(4433, "storageBuffer16BitAccess"),
            Map.entry(4434, "uniformAndStorageBuffer16BitAccess"), Map.entry(4435, "storagePushConstant16"),
            Map.entry(4436, "storageInputOutput16"), Map.entry(4448, "storageBuffer8BitAccess"),
            Map.entry(4449, "uniformAndStorageBuffer8BitAccess"), Map.entry(4450, "storagePushConstant8"));
    /** GroupNonUniform* capabilities and their VkSubgroupFeatureFlagBits; the first is basic. */
    private static final Map<Integer, Integer> SUBGROUP_OPERATIONS = Map.of(61, 0x1, 62, 0x2, 63, 0x4, 64, 0x8,
            65, 0x10, 66, 0x20, 67, 0x40, 68, 0x80, 5297, 0x100);
    private static final List<String> SUBGROUP_NAMES = List.of("basic", "vote", "arithmetic", "ballot", "shuffle",
            "shuffle_relative", "clustered", "quad", "partitioned");
    /** Float16, Int64, Int16 and Int8: a subgroup operation on one needs shaderSubgroupExtendedTypes. */
    private static final List<Integer> SIZED_TYPES = List.of(9, 11, 22, 39);

    private static final Map<String, PackDevice> PROFILES = Map.of(
            "nvidia", new PackDevice("nvidia", VENDOR_NVIDIA, DRIVER_NVIDIA_PROPRIETARY, "NVIDIA GeForce RTX 5080",
                    allFeaturesExcept("storageInputOutput16"), 0x3FFF, 0x7FF,
                    sorted(List.of("VK_KHR_swapchain", "VK_KHR_dynamic_rendering",
                            "VK_NV_shader_subgroup_partitioned", "VK_EXT_shader_subgroup_vote"))),
            "amd", new PackDevice("amd", VENDOR_AMD, DRIVER_AMD_PROPRIETARY, "AMD Radeon(TM) Graphics",
                    allFeaturesExcept("storagePushConstant16", "storagePushConstant8"), 0x3FFF, 0x6FF,
                    sorted(List.of("VK_KHR_swapchain", "VK_KHR_dynamic_rendering", "VK_AMD_shader_trinary_minmax",
                            "VK_EXT_shader_subgroup_vote"))),
            "intel", new PackDevice("intel", VENDOR_INTEL, DRIVER_INTEL_PROPRIETARY_WINDOWS,
                    "Intel(R) Arc(TM) A770 Graphics", allFeaturesExcept("shaderFloat64"), 0x3F, 0xFF,
                    sorted(List.of("VK_KHR_swapchain", "VK_KHR_dynamic_rendering", "VK_EXT_shader_subgroup_vote"))));

    private static volatile PackDevice current = offline(System.getProperty("chimera.device", "nvidia"));

    public PackDevice {
        features = sorted(features);
        extensions = sorted(extensions);
    }

    /** The device pack GLSL compiles for now. */
    public static PackDevice current() {
        return current;
    }

    public static void set(PackDevice device) {
        current = device;
    }

    /** The offline profile {@code name}: nvidia, amd or intel. */
    public static PackDevice offline(String name) {
        PackDevice profile = PROFILES.get(name.toLowerCase(Locale.ROOT));
        if (profile == null) {
            throw new IllegalArgumentException("chimera.device is " + name + "; expected one of "
                    + new TreeSet<>(PROFILES.keySet()));
        }
        return profile;
    }

    /** This device created with {@code enabled} as its extensions. */
    public PackDevice withExtensions(Collection<String> enabled) {
        return new PackDevice(profile, vendorId, driverId, name, features, subgroupStages, subgroupOperations,
                sorted(enabled));
    }

    /** The sized arithmetic types a pack's GL vendor extensions map onto (GlslVendorExtensions). */
    public GlslVendorExtensions.SizedTypes sizedTypes() {
        return new GlslVendorExtensions.SizedTypes(features.contains("shaderFloat16"), features.contains("shaderInt16"),
                features.contains("shaderInt64"));
    }

    /**
     * Whether the device serves GL_AMD_shader_trinary_minmax natively; without it the extension's
     * functions compile as min and max (GlslVendorExtensions).
     */
    public boolean trinaryMinmax() {
        return extensions.contains("VK_AMD_shader_trinary_minmax");
    }

    /**
     * What a module for {@code stage} (a STAGE_* bit) needs that the device was created without:
     * its device extensions, the features its capabilities need, and its subgroup operations, which
     * the device runs in the stages it names. Empty when the device can run it.
     */
    public List<String> missing(SpirvDeviceRequirements.Requirements module, int stage) {
        List<String> result = new ArrayList<>();
        module.extensions().stream().filter(extension -> !extensions.contains(extension))
                .forEach(extension -> result.add("device extension " + extension));
        module.capabilities().stream().map(CAPABILITY_FEATURES::get).filter(feature -> feature != null)
                .distinct().filter(feature -> !features.contains(feature))
                .forEach(feature -> result.add("feature " + feature));
        int operations = module.capabilities().stream().map(SUBGROUP_OPERATIONS::get)
                .filter(bit -> bit != null).reduce(0, (a, b) -> a | b);
        if (operations == 0) return result;
        if ((subgroupStages & stage) == 0) result.add("subgroup operations in this stage");
        for (int bit = 0; bit < SUBGROUP_NAMES.size(); bit++) {
            if ((operations & ~subgroupOperations & 1 << bit) != 0) {
                result.add("subgroup " + SUBGROUP_NAMES.get(bit) + " operations");
            }
        }
        boolean typed = SIZED_TYPES.stream().anyMatch(module.capabilities()::contains);
        if (typed && (operations & ~0x3) != 0 && !features.contains("shaderSubgroupExtendedTypes")) {
            result.add("feature shaderSubgroupExtendedTypes");
        }
        return result;
    }

    private static SortedSet<String> allFeaturesExcept(String... absent) {
        SortedSet<String> result = new TreeSet<>();
        Stream.of(CORE_FEATURES, VULKAN11_FEATURES, VULKAN12_FEATURES).forEach(result::addAll);
        List.of(absent).forEach(result::remove);
        return result;
    }

    private static SortedSet<String> sorted(Collection<String> values) {
        return Collections.unmodifiableSortedSet(new TreeSet<>(values));
    }
}
