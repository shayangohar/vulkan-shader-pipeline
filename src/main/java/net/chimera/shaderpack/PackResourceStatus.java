package net.chimera.shaderpack;

/** Load-time status for one sampled pack resource. */
public enum PackResourceStatus {
    HOST_ALIAS,
    PACK_FILE,
    GAME_RESOURCE,
    /** A material map is always servable: a missing file resolves to the flat fallback at runtime. */
    MATERIAL_MAP,
    /**
     * Chimera makes the image itself: Iris's noisetex when the pack declares no texture.noise, or the
     * unbound texture Iris leaves under the DH depth names.
     */
    GENERATED,
    UNAVAILABLE,
    UNSUPPORTED
}
