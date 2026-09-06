package net.chimera.shaderpack;

/** Load-time status for one sampled pack resource. */
public enum PackResourceStatus {
    HOST_ALIAS,
    PACK_FILE,
    GAME_RESOURCE,
    MATERIAL_ATLAS,
    UNAVAILABLE,
    UNSUPPORTED
}
