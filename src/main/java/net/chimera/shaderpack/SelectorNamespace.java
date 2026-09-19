package net.chimera.shaderpack;

import java.util.List;

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
    public static final int LAST_RESERVED = SPECULAR_SLOT;
    public static final int EXTENDED_CAPACITY = LAST_RESERVED - FIRST_EXTENDED + 1;
    /** Unreserved selector slots available to pack-owned sampled resources. */
    public static final List<Integer> PACK_SELECTOR_SLOTS = List.of(
            8, 9, 10, 11, 16, 17, 18, 19, 20, 21);

    private SelectorNamespace() {}

    public static boolean isAddressable(int slot) {
        return slot >= 0 && slot <= LAST_RESERVED;
    }

    /** Returns the extended backing-array index, or -1 when the slot is not extended. */
    public static int extendedIndex(int slot) {
        return slot >= FIRST_EXTENDED && isAddressable(slot) ? slot - FIRST_EXTENDED : -1;
    }

    /** Clears every selector that can retain an image from a retired pack. */
    public static void clearOwned(java.util.function.IntConsumer clearSlot) {
        for (int slot = 0; slot <= LAST_RESERVED; slot++) clearSlot.accept(slot);
    }
}
