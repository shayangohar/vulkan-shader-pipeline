package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iris's MC_GL_VENDOR_*, MC_GL_RENDERER_* and MC_GL_<extension> macros, named from the Vulkan device
 * as its vendor's GL driver reports it. RenderPearl 2.8 takes NVIDIA's half floats and subgroups with
 * MC_GL_VENDOR_NVIDIA, and Alpha Piscium its half floats with MC_GL_NV_gpu_shader5; without them both
 * ran their generic paths on the owner's RTX 5080, unlike under Iris.
 */
final class PackDeviceMacrosTest {
    @Test
    void eachProfileNamesItsDriverAndTheExtensionsItLists() {
        assertEquals(Set.of("MC_GL_VENDOR_NVIDIA", "MC_GL_RENDERER_GEFORCE", "MC_GL_NV_gpu_shader5",
                        "MC_GL_NV_shader_subgroup_partitioned", "MC_GL_KHR_shader_subgroup",
                        "MC_GL_EXT_shader_image_load_store", "MC_GL_ARB_gpu_shader_int64"),
                PackDeviceMacros.macros(PackDevice.offline("nvidia")).keySet());
        assertEquals(Set.of("MC_GL_VENDOR_ATI", "MC_GL_RENDERER_RADEON", "MC_GL_AMD_gpu_shader_half_float",
                        "MC_GL_AMD_gpu_shader_int16", "MC_GL_AMD_shader_trinary_minmax",
                        "MC_GL_EXT_shader_image_load_store", "MC_GL_ARB_gpu_shader_int64"),
                PackDeviceMacros.macros(PackDevice.offline("amd")).keySet());
        assertEquals(Set.of("MC_GL_VENDOR_INTEL", "MC_GL_RENDERER_INTEL"),
                PackDeviceMacros.macros(PackDevice.offline("intel")).keySet());
        assertTrue(PackDeviceMacros.macros(PackDevice.offline("nvidia")).values().stream().allMatch("1"::equals));
    }

    /** Iris matches GL_RENDERER by prefix; Mesa drivers report their own vendor and renderer. */
    @Test
    void renderersFollowIrisPrefixes() {
        assertEquals("MC_GL_RENDERER_QUADRO", PackDeviceMacros.renderer(device(PackDevice.VENDOR_NVIDIA,
                PackDevice.DRIVER_NVIDIA_PROPRIETARY, "Quadro P4000")));
        assertEquals("MC_GL_RENDERER_APPLE", PackDeviceMacros.renderer(device(0x106B, 14, "Apple M1")));
        assertEquals("MC_GL_VENDOR_OTHER", PackDeviceMacros.vendor(device(0x106B, 14, "Apple M1")));
        assertEquals("MC_GL_RENDERER_OTHER", PackDeviceMacros.renderer(device(0x10005, 10, "llvmpipe (LLVM 17)")));
        PackDevice intelMesa = device(PackDevice.VENDOR_INTEL, PackDevice.DRIVER_INTEL_OPEN_SOURCE_MESA,
                "Intel(R) Arc(tm) A770 Graphics (DG2)");
        assertEquals(Set.of("MC_GL_VENDOR_INTEL", "MC_GL_RENDERER_MESA", "MC_GL_AMD_shader_trinary_minmax",
                "MC_GL_KHR_shader_subgroup", "MC_GL_ARB_gpu_shader_int64"),
                PackDeviceMacros.macros(intelMesa).keySet());
        PackDevice radv = device(PackDevice.VENDOR_AMD, 3, "AMD Radeon RX 7900 XTX (RADV NAVI31)");
        assertEquals("MC_GL_VENDOR_AMD", PackDeviceMacros.vendor(radv));
        assertEquals("MC_GL_RENDERER_RADEON", PackDeviceMacros.renderer(radv));
        PackDevice nouveau = device(PackDevice.VENDOR_NVIDIA, 24, "NVIDIA GeForce RTX 4070 (NVK AD104)");
        assertEquals(Set.of("MC_GL_VENDOR_OTHER", "MC_GL_RENDERER_OTHER"), PackDeviceMacros.macros(nouveau).keySet());
    }

    /** An extension is listed only when the device runs what its directive compiles to. */
    @Test
    void anExtensionTheDeviceCannotRunIsNotListed() {
        PackDevice nvidia = PackDevice.offline("nvidia");
        PackDevice withoutHalf = new PackDevice("test", nvidia.vendorId(), nvidia.driverId(), nvidia.name(),
                nvidia.features().stream().filter(feature -> !feature.equals("shaderFloat16"))
                        .collect(Collectors.toCollection(TreeSet::new)),
                nvidia.subgroupStages(), 0x7F, new TreeSet<>());
        Map<String, String> macros = PackDeviceMacros.macros(withoutHalf);
        assertTrue(macros.containsKey("MC_GL_VENDOR_NVIDIA") && !macros.containsKey("MC_GL_NV_gpu_shader5")
                && !macros.containsKey("MC_GL_KHR_shader_subgroup")
                && !macros.containsKey("MC_GL_NV_shader_subgroup_partitioned"), macros.toString());
    }

    @Test
    void theStandardEnvironmentCarriesTheDeviceAndTheMipmapLevel() {
        PackDevice.set(PackDevice.offline("amd"));
        assertEquals("4", PackEngineDefines.standard().get("MC_MIPMAP_LEVEL"));
        PackEngineDefines.setMipmapLevel(2);
        Map<String, String> standard = PackEngineDefines.standard();
        assertEquals("2", standard.get("MC_MIPMAP_LEVEL"));
        assertTrue(standard.containsKey("MC_GL_VENDOR_ATI") && standard.containsKey("MC_VERSION"), standard.toString());
        assertTrue(PackEngineDefines.lockedNames(Set.of(), false).contains("MC_GL_AMD_gpu_shader_half_float"));
    }

    private static PackDevice device(int vendor, int driver, String name) {
        return new PackDevice("test", vendor, driver, name, PackDevice.offline("nvidia").features(), 0x3F, 0xFF,
                new TreeSet<>());
    }
}
