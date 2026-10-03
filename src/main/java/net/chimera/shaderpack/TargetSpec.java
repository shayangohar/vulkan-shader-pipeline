package net.chimera.shaderpack;

import java.util.List;

/** Immutable description of one logical pack color target. */
public record TargetSpec(
        int index,
        int format,
        int width,
        int height,
        boolean clear,
        float[] clearColor,
        boolean persistent,
        boolean doubled,
        boolean mipmapped,
        List<String> deviations
) {
    public TargetSpec {
        clearColor = clearColor == null
                ? new float[] {0.0F, 0.0F, 0.0F, 0.0F}
                : clearColor.clone();
        if (clearColor.length != 4) {
            clearColor = new float[] {0.0F, 0.0F, 0.0F, 0.0F};
        }
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
    }

    public float[] clearColorCopy() {
        return clearColor.clone();
    }

    @Override
    public float[] clearColor() {
        return clearColor.clone();
    }

    public boolean sameExtent(TargetSpec other) {
        return other != null && width == other.width && height == other.height;
    }

    /** A full chain down to 1x1, as glGenerateMipmap builds, or the base level alone. */
    public int mipLevels() {
        return mipmapped ? 32 - Integer.numberOfLeadingZeros(Math.max(1, Math.max(width, height))) : 1;
    }

    /** Persistent doubled targets need one extra side for previous-frame reads. */
    public boolean requiresHistory() {
        return persistent && doubled;
    }
}
