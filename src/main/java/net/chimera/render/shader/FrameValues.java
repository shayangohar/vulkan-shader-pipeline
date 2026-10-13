package net.chimera.render.shader;

import net.chimera.shaderpack.UniformRegistry;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.function.IntToDoubleFunction;

/** Builds and writes the frame-value table: each served uniform name, its width and its component source. */
final class FrameValues {
    private FrameValues() {}

    static void scalar(Map<String, PackFrameState.FrameValue> table, DoubleSupplier value, String... names) {
        serve(table, new PackFrameState.FrameValue(1, component -> value.getAsDouble()), names);
    }

    static void vector(Map<String, PackFrameState.FrameValue> table, int width, IntToDoubleFunction component,
                               String... names) {
        serve(table, new PackFrameState.FrameValue(width, component), names);
    }

    static void vector(Map<String, PackFrameState.FrameValue> table, Vector3f value, String... names) {
        vector(table, 3, component -> componentOf(value.x, value.y, value.z, component), names);
    }

    static void ints(Map<String, PackFrameState.FrameValue> table, int[] values, String... names) {
        vector(table, values.length, component -> values[component], names);
    }

    static void floats(Map<String, PackFrameState.FrameValue> table, float[] values, String... names) {
        vector(table, values.length, component -> values[component], names);
    }

    static void matrix(Map<String, PackFrameState.FrameValue> table, Matrix4f matrix, String... names) {
        vector(table, 16, component -> matrix.get(component / 4, component % 4), names);
    }

    static void serve(Map<String, PackFrameState.FrameValue> table, PackFrameState.FrameValue value, String... names) {
        for (String name : names) {
            if (table.put(name, value) != null) throw new IllegalStateException("frame value served twice: " + name);
        }
    }

    static void put(MappedBuffer target, int component, double value, boolean integral) {
        if (integral) {
            target.putInt(component * 4, (int) value);
        } else {
            target.putFloat(component * 4, (float) value);
        }
    }

    static double componentOf(float x, float y, float z, int component) {
        return component == 0 ? x : component == 1 ? y : z;
    }

    static void writeDefault(UniformRegistry.DefaultPolicy policy,
                                     String type, MappedBuffer target) {
        if (type.equals("mat4")) {
            for (int i = 0; i < 16; i++) {
                target.putFloat(i * 4, i % 5 == 0 ? 1.0f : 0.0f);
            }
            return;
        }
        if (type.startsWith("i")) {
            for (int i = 0; i < UniformRegistry.pipelineCount(type); i++) {
                target.putInt(i * 4, 0);
            }
            return;
        }
        float value = switch (policy) {
            case ONE -> 1.0f;
            case CLOUD_HEIGHT -> 192.0f;
            case NEAR_CLIP -> 0.05f;
            default -> 0.0f;
        };
        for (int i = 0; i < UniformRegistry.pipelineCount(type); i++) {
            target.putFloat(i * 4, value);
        }
    }
}
