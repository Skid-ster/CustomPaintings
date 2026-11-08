package com.jay.custompaintings.client.mixin;

/** Duck interface carrying the custom painting id from the entity into its render state. */
public interface PaintingRenderStateAccess {
    int custompaintings$getPaintingId();

    void custompaintings$setPaintingId(int id);
}
