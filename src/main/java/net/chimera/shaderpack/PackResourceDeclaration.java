package net.chimera.shaderpack;

/** Immutable active resource declaration from shaders.properties. */
public record PackResourceDeclaration(
        String key,
        String stage,
        String sampler,
        String source,
        PackResourceKind kind
) {
    public PackResourceDeclaration {
        key = key == null ? "" : key.trim();
        stage = stage == null ? "*" : stage.trim();
        sampler = sampler == null ? "" : sampler.trim();
        source = source == null ? "" : source.trim();
        kind = kind == null ? PackResourceKind.PACK_TEXTURE : kind;
    }
}
