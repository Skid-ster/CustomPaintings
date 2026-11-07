package com.jay.custompaintings.client.mixin;

import com.jay.custompaintings.client.CustomPaintingsClient;
import com.jay.custompaintings.client.PaintingTextureManager;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/**
 * Replaces pixels before atlas stitching, rather than trying to rebind an atlas
 * sprite to a standalone texture (paintings sample UVs from the painting atlas).
 * This small hook preserves vanilla geometry, lighting, mipmaps and the back texture.
 */
@Mixin(SpriteLoader.class)
public abstract class SpriteLoaderMixin {
    @Shadow
    @Final
    private Identifier location;

    @ModifyVariable(method = "stitch", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private List<SpriteContents> custompaintings$replacePaintingPixels(List<SpriteContents> sprites) {
        PaintingTextureManager manager = CustomPaintingsClient.paintings();
        if (manager == null || (!this.location.equals(AtlasIds.PAINTINGS) && !this.location.equals(AtlasIds.ITEMS))) {
            return sprites;
        }
        return manager.prepareSprites(sprites, this.location);
    }
}
