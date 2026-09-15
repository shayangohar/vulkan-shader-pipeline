package net.chimera.shaderpack;

/** Kind of sampled resource exposed to a pack program. */
public enum PackResourceKind {
    TARGET,
    DEPTH,
    SHADOW_DEPTH,
    SHADOW_COLOR,
    NOISE,
    PACK_TEXTURE,
    GAME_RESOURCE,
    ADVANCED_IMAGE,
    MATERIAL_ATLAS,
    UNSERVED
}
