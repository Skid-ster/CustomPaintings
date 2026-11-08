package com.jay.custompaintings.item;

import com.jay.custompaintings.library.PaintingData;
import com.jay.custompaintings.library.PaintingRuntime;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

/** Registration and stack helpers for the one item that represents every custom painting. */
public final class CustomPaintingItems {
    private static CustomPaintingItem item;

    private CustomPaintingItems() {
    }

    public static void register() {
        item = new CustomPaintingItem(new Item.Properties()
                .setId(PaintingRuntime.itemKey())
                .stacksTo(16));
        Registry.register(BuiltInRegistries.ITEM, PaintingRuntime.itemKey(), item);
    }

    public static CustomPaintingItem get() {
        return item;
    }

    /** A stack representing one library painting. */
    public static ItemStack stack(int paintingId) {
        ItemStack stack = new ItemStack(item);
        stack.set(PaintingData.PAINTING_ID, paintingId);
        return stack;
    }

    public static ItemStack stack(int paintingId, int count) {
        ItemStack stack = stack(paintingId);
        stack.setCount(Math.max(1, Math.min(stack.getMaxStackSize(), count)));
        return stack;
    }

    /** Recipe-book/creative display template carrying the painting id component. */
    public static ItemStackTemplate template(int paintingId) {
        return new ItemStackTemplate(item.builtInRegistryHolder(), 1,
                DataComponentPatch.builder().set(PaintingData.PAINTING_ID, paintingId).build());
    }

    public static int paintingId(ItemStack stack) {
        Integer id = stack.get(PaintingData.PAINTING_ID);
        return id == null ? -1 : id;
    }

    public static boolean isCustomPainting(ItemStack stack) {
        return !stack.isEmpty() && stack.is(item) && paintingId(stack) >= 0;
    }
}
