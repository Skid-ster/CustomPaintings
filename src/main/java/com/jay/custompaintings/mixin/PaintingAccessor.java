package com.jay.custompaintings.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Allows authoritative resize operations to update already-placed custom paintings. */
@Mixin(Painting.class)
public interface PaintingAccessor {
    @Invoker("setVariant")
    void custompaintings$setVariant(Holder<PaintingVariant> variant);
}
