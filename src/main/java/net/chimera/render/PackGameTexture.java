package net.chimera.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.vulkan.texture.VulkanImage;

/**
 * A namespaced pack texture, read as Iris's CustomTextureManager reads CustomTextureData.ResourceData:
 * the game texture at that location (an atlas, a dynamic texture, or a resource file the
 * TextureManager loads on first ask), queried at every bind so a resource reload never leaves a stale
 * image. A path ending in a PBR suffix ({@code _n}, {@code _s}) names the normal or specular map of
 * the texture without the suffix, or the flat map when it has none (PBRTextureManager's default
 * holder). A texture the game cannot load is its missing texture.
 */
final class PackGameTexture {
    private PackGameTexture() {
    }

    /** A texture location without its PBR suffix, and the map the suffix names (null: the texture itself). */
    record Source(String namespace, String path, MaterialMapOwner.MaterialMapKind map) {
        /**
         * Iris's split of {@code namespace:path} (ShaderPack.parseCustomTexture): the first two parts,
         * then PBRType.fromFileLocation on the path without its extension. Null when not namespaced.
         */
        static Source parse(String source) {
            String[] parts = source == null ? new String[0] : source.trim().split(":");
            if (parts.length < 2) return null;
            String path = parts[1];
            int dot = path.lastIndexOf('.');
            int extension = dot > path.lastIndexOf('/') ? dot : path.length();
            String stem = path.substring(0, extension);
            for (MaterialMapOwner.MaterialMapKind kind : MaterialMapOwner.MaterialMapKind.values()) {
                if (stem.endsWith(kind.suffix())) {
                    String base = stem.substring(0, stem.length() - kind.suffix().length());
                    return new Source(parts[0], base + path.substring(extension), kind);
                }
            }
            return new Source(parts[0], path, null);
        }
    }

    /**
     * The image {@code source} names now, or null when no image or flat map can stand for it yet: a
     * pack loads with the renderer, before Minecraft has a TextureManager, and Iris's TextureWrapper
     * looks only at bind time.
     */
    static VulkanImage image(Source source, MaterialMapOwner materials) {
        Minecraft client = Minecraft.getInstance();
        TextureManager textures = client == null ? null : client.getTextureManager();
        if (textures == null) return null;
        AbstractTexture texture = ResourceLocation.isValidNamespace(source.namespace())
                && ResourceLocation.isValidPath(source.path())
                ? textures.getTexture(ResourceLocation.fromNamespaceAndPath(source.namespace(), source.path()))
                : null;
        VulkanImage base = ChimeraTextures.image(texture);
        if (base == null) base = ChimeraTextures.image(MissingTextureAtlasSprite.getTexture());
        if (source.map() == null) return base;
        if (materials == null) return null;
        String sampler = source.map().sampler();
        ChimeraTextureBindingState.Snapshot companion = materials.companionFor(base, sampler);
        if (companion != null) return companion.image();
        // Iris loads a texture's PBR holder on first use; a simple texture's maps build at the next pump.
        materials.noteDrawAlbedo(base);
        return materials.image(sampler);
    }
}
