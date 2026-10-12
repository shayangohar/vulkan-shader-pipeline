package net.chimera.shaderpack;

/** Kind of sampled resource exposed to a pack program. */
public enum PackResourceKind {
    TARGET,
    DEPTH,
    SHADOW_DEPTH,
    SHADOW_COLOR,
    NOISE,
    PACK_TEXTURE,
    /**
     * A raw texture file declared with its type, format and size
     * ({@code file TEXTURE_3D RGB16F 32 64 32 RGB HALF_FLOAT}); it replaces a
     * sampler only where the program declares that sampler with the same type.
     */
    RAW_TEXTURE,
    GAME_RESOURCE,
    ADVANCED_IMAGE,
    /** Resource-pack normal/specular map resolved at draw time from the bound albedo. */
    MATERIAL_MAP,
    UNSERVED
}
