package com.jay.custompaintings.client.mixin;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Allows the synced library to invalidate vanilla's cached creative-tab contents. */
@Mixin(CreativeModeTabs.class)
public interface CreativeModeTabsMixin {
    @Accessor("CACHED_PARAMETERS")
    static void custompaintings$setCachedParameters(CreativeModeTab.ItemDisplayParameters parameters) {
        throw new AssertionError();
    }
}
