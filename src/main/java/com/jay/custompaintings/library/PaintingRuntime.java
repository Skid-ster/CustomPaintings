package com.jay.custompaintings.library;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;

import java.util.Optional;

/** Shared identifiers and registry helpers for the ten fixed custom-painting slots. */
public final class PaintingRuntime {
    private PaintingRuntime() {
    }

    public static Identifier variantId(int slot, int width, int height) {
        return Identifier.fromNamespaceAndPath("custompaintings", "slot_" + slot + "_" + width + "x" + height);
    }

    public static ResourceKey<PaintingVariant> variantKey(int slot, int width, int height) {
        return ResourceKey.create(Registries.PAINTING_VARIANT, variantId(slot, width, height));
    }

    public static Identifier variantAssetId(int slot, int width, int height) {
        return variantId(slot, width, height);
    }

    public static Identifier itemId(int slot) {
        return Identifier.fromNamespaceAndPath("custompaintings", "slot_" + slot);
    }

    public static ResourceKey<net.minecraft.world.item.Item> itemKey(int slot) {
        return ResourceKey.create(Registries.ITEM, itemId(slot));
    }

    public static Identifier itemTextureId(int slot) {
        return Identifier.fromNamespaceAndPath("custompaintings", "item/slot_" + slot);
    }

    public static Optional<Integer> slotFromVariant(Identifier id) {
        if (!id.getNamespace().equals("custompaintings")) {
            return Optional.empty();
        }
        String path = id.getPath();
        if (!path.startsWith("slot_")) {
            return Optional.empty();
        }
        int underscore = path.indexOf('_', 5);
        if (underscore < 0) {
            return Optional.empty();
        }
        try {
            int slot = Integer.parseInt(path.substring(5, underscore));
            return slot >= 0 && slot < PaintingEntry.MAX_PAINTINGS ? Optional.of(slot) : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    public static Optional<PaintingVariant> variant(Registry<PaintingVariant> registry, PaintingEntry entry) {
        return registry.getOptional(variantKey(entry.slot(), entry.width(), entry.height()));
    }
}
