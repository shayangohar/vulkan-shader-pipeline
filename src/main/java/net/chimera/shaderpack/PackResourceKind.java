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
    /** Resource-pack normal/specular map resolved at draw time from the bound albedo. */
    MATERIAL_MAP,
    UNSERVED
}
