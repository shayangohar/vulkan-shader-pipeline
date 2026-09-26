package net.chimera.mixin;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Read-only access to the pinned TextureManager texture registry. */
@Mixin(TextureManager.class)
public interface ChimeraTextureManagerAccessor {
    @Accessor("byPath")
    Map<Identifier, AbstractTexture> chimera$texturesByPath();
}
