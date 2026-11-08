package com.jay.custompaintings.client.mixin;

import net.minecraft.client.renderer.entity.state.PaintingRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Adds the custom painting id to vanilla's painting render state. */
@Mixin(PaintingRenderState.class)
public abstract class PaintingRenderStateMixin implements PaintingRenderStateAccess {
    @Unique
    private int custompaintings$paintingId = -1;

    @Override
    public int custompaintings$getPaintingId() {
        return custompaintings$paintingId;
    }

    @Override
    public void custompaintings$setPaintingId(int id) {
        custompaintings$paintingId = id;
    }
}
