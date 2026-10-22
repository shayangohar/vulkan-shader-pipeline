package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Pure selector-address contract shared by planning and the runtime backing array. */
public final class SelectorNamespace {
    public static final int FIRST_EXTENDED = 8;
    /** Slot 14 is reserved by the runtime for pack coverage. */
    public static final int COVERAGE_SLOT = 14;
    /** Slot 15 is the distinct post shadowtex1 selector. */
    public static final int SHADOW_TEX1_SLOT = 15;
    /** Pack-owned sampled resources must not overlap reserved selectors. */
    public static final int PACK_SLOT_FIRST = 16;
    public static final int PACK_SLOT_LAST = 21;
    public static final int COLORTEX8_SLOT = 22;
    public static final int SHADOW_COLOR0_SLOT = 23;
    public static final int SHADOW_COLOR1_SLOT = 24;
    /** Slot 25 carries the resource-pack normal map for material-capable stages. */
    public static final int NORMALS_SLOT = 25;
    /** Slot 26 carries the resource-pack specular map for material-capable stages. */
    public static final int SPECULAR_SLOT = 26;
    /** Slots 27 through 33 carry colortex9..colortex15, the rest of Iris's sixteen colour targets. */
    public static final int HIGH_COLORTEX_FIRST_SLOT = 27;
    /** Slots 34 through 39 carry shadowcolor2..shadowcolor7 (Iris HIGHER_SHADOWCOLOR). */
    public static final int HIGH_SHADOW_COLOR_FIRST_SLOT = HIGH_COLORTEX_FIRST_SLOT + 7;
    /**
     * Slots 40 and 41 carry shadowtex0HW and shadowtex1HW: the shadow depths again, through their
     * comparison samplers (Iris SEPARATE_HARDWARE_SAMPLERS).
     */
    public static final int SHADOW_TEX0_HW_SLOT = HIGH_SHADOW_COLOR_FIRST_SLOT + 6;
    public static final int SHADOW_TEX1_HW_SLOT = SHADOW_TEX0_HW_SLOT + 1;
    /**
     * Slots 42 onward carry further pack-owned textures and images. Iris gives every custom
     * texture and image its own unit; Escheridia's textures and Glimmer's LUT images outgrow the
     * ten slots between the host's own.
     */
    public static final int PACK_EXTENDED_FIRST = SHADOW_TEX1_HW_SLOT + 1;
    public static final int PACK_EXTENDED_COUNT = 32;
    /**
     * The last slot carries dhDepthTex, dhDepthTex0 and dhDepthTex1, which come with the render targets
     * (IrisSamplers.addRenderTargetSamplers). Without Distant Horizons all three bind texture 0.
     */
    public static final int DH_DEPTH_SLOT = PACK_EXTENDED_FIRST + PACK_EXTENDED_COUNT;
    /** The slot after it carries iris_centerDepthSmooth, which fullscreen passes read (CenterDepthSmooth). */
    public static final int CENTER_DEPTH_SLOT = DH_DEPTH_SLOT + 1;
    public static final int LAST_RESERVED = CENTER_DEPTH_SLOT;
    public static final int EXTENDED_CAPACITY = LAST_RESERVED - FIRST_EXTENDED + 1;
    /** Unreserved selector slots available to pack-owned sampled resources, in assignment order. */
    public static final List<Integer> PACK_SELECTOR_SLOTS = packSelectorSlots();

    private static List<Integer> packSelectorSlots() {
        List<Integer> slots = new ArrayList<>(List.of(8, 9, 10, 11, 16, 17, 18, 19, 20, 21));
        for (int slot = PACK_EXTENDED_FIRST; slot < DH_DEPTH_SLOT; slot++) slots.add(slot);
        return List.copyOf(slots);
    }

    private SelectorNamespace() {}

    /**
     * The one selector a pack colour target is sampled through, in every
     * stage that may read it: colortex0..3 keep the host post slots,
     * colortex4..7 sit at 8..11, colortex8 at its own slot, and colortex9..15
     * after the reserved range. Returns -1 outside colortex0..15.
     */
    public static int colorTargetSlot(int target) {
        if (target >= 0 && target <= 3) return target;
        if (target >= 4 && target <= 7) return target + 4;
        if (target == 8) return COLORTEX8_SLOT;
        if (target >= 9 && target <= 15) return HIGH_COLORTEX_FIRST_SLOT + target - 9;
        return -1;
    }

    /** The selector shadowcolorN is sampled through; -1 outside shadowcolor0..7. */
    public static int shadowColorSlot(int index) {
        if (index == 0) return SHADOW_COLOR0_SLOT;
        if (index == 1) return SHADOW_COLOR1_SLOT;
        return index >= 2 && index <= 7 ? HIGH_SHADOW_COLOR_FIRST_SLOT + index - 2 : -1;
    }

    /** The selector of a shadowcolorN or a hardware shadow sampler (shadowtex0HW, shadowtex1HW); -1 otherwise. */
    public static int shadowSamplerSlot(String name) {
        if ("shadowtex0HW".equals(name)) return SHADOW_TEX0_HW_SLOT;
        if ("shadowtex1HW".equals(name)) return SHADOW_TEX1_HW_SLOT;
        return shadowColorSlot(PackResourcePlan.shadowColorIndex(name));
    }

    public static boolean isAddressable(int slot) {
        return slot >= 0 && slot <= LAST_RESERVED;
    }

    /** Returns the extended backing-array index, or -1 when the slot is not extended. */
    public static int extendedIndex(int slot) {
        return slot >= FIRST_EXTENDED && isAddressable(slot) ? slot - FIRST_EXTENDED : -1;
    }

    /** Clears every selector that can retain an image from a retired pack. */
    public static void clearOwned(IntConsumer clearSlot) {
        for (int slot = 0; slot <= LAST_RESERVED; slot++) clearSlot.accept(slot);
    }
}
