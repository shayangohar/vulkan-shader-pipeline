package net.chimera.render;

import net.chimera.mixin.ChimeraTextureManagerAccessor;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Production simple-texture index over the live {@code TextureManager}.
 *
 * <p>Matches draw-observed albedo images by exact GPU identity against
 * registered textures. Only resource-backed {@code SimpleTexture}
 * instances qualify: atlases, dynamic textures, lightmaps, overlays, and
 * any texture without a Vulkan image never match, so no maps are
 * invented for them. Read-only; safe at the pump seam.</p>
 */
public final class SimpleTextureIndex {
    private SimpleTextureIndex() {}

    public static List<MaterialMapOwner.SimpleEntry> snapshot(TextureManager manager) {
        List<MaterialMapOwner.SimpleEntry> entries = new ArrayList<>();
        if (!(manager instanceof ChimeraTextureManagerAccessor accessor)) return entries;
        for (Map.Entry<ResourceLocation, AbstractTexture> registered
                : accessor.chimera$texturesByPath().entrySet()) {
            if (!(registered.getValue() instanceof SimpleTexture simple)) continue;
            var image = ChimeraTextures.image(simple);
            if (image == null) continue;
            // A SimpleTexture registers under the resource it loads.
            ResourceLocation resourceId = registered.getKey();
            entries.add(new MaterialMapOwner.SimpleEntry(image,
                    resourceId.getNamespace(), relativeSpritePath(resourceId.getPath())));
        }
        return entries;
    }

    /**
     * Simple-texture resource ids are full file paths
     * ({@code textures/entity/creeper.png}); material sibling lookup works
     * on atlas-style sprite paths ({@code entity/creeper}), so the leading
     * {@code textures/} and the final file extension come off here. Anything
     * else passes through to the CIT rule untouched.
     */
    static String relativeSpritePath(String resourcePath) {
        String path = resourcePath.startsWith("textures/")
                ? resourcePath.substring("textures/".length())
                : resourcePath;
        int extension = path.lastIndexOf('.');
        return extension > path.lastIndexOf('/')
                ? path.substring(0, extension)
                : path;
    }
}
