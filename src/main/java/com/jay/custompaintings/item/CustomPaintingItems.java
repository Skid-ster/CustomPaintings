package com.jay.custompaintings.item;

import com.jay.custompaintings.CustomPaintingsMod;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingLibraryAccess;
import com.jay.custompaintings.library.PaintingRuntime;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Ten stable item ids; occupied slots are exposed through creative tabs and crafting. */
public final class CustomPaintingItems {
    private static final List<CustomPaintingItem> ITEMS = new ArrayList<>();

    private CustomPaintingItems() {
    }

    public static void register() {
        for (int slot = 0; slot < PaintingEntry.MAX_PAINTINGS; slot++) {
            CustomPaintingItem item = new CustomPaintingItem(slot, new Item.Properties()
                    .setId(PaintingRuntime.itemKey(slot)).stacksTo(16));
            Registry.register(BuiltInRegistries.ITEM, PaintingRuntime.itemKey(slot), item);
            ITEMS.add(item);
        }
    }

    public static CustomPaintingItem get(int slot) {
        return ITEMS.get(slot);
    }

    public static ItemStack stack(int slot) {
        return new ItemStack(get(slot));
    }

    public static Component name(int slot) {
        return PaintingLibraryAccess.current().get(slot)
                .<Component>map(entry -> Component.literal(entry.name()))
                .orElseGet(() -> Component.translatable("item.custompaintings.empty_slot", slot + 1));
    }
}
