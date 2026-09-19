package net.chimera.shaderpack;

/** Load-time status for one sampled pack resource. */
public enum PackResourceStatus {
    HOST_ALIAS,
    PACK_FILE,
    GAME_RESOURCE,
    /** A material map is always servable: a missing file resolves to the flat fallback at runtime. */
    MATERIAL_MAP,
    UNAVAILABLE,
    UNSUPPORTED
}
