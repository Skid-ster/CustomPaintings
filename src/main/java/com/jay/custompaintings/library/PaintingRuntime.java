package com.jay.custompaintings.library;

import com.jay.custompaintings.CustomPaintingsMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import net.minecraft.world.item.Item;

import java.util.Optional;

/**
 * Identifiers for the unbounded painting system.
 *
 * <p>Registries are frozen at startup, so instead of one item and 64 variants <i>per painting</i>
 * there is exactly <b>one</b> item ({@code custompaintings:painting}) whose stack carries the
 * painting id as a data component, and <b>64 size-only</b> variants ({@code canvas_WxH}) that
 * provide the entity's bounding box. The artwork itself comes from a per-id dynamic texture,
 * so adding paintings never touches a registry or an atlas.</p>
 */
public final class PaintingRuntime {
    /** Shared placeholder atlas asset for every canvas variant; the renderer overrides it. */
    public static final Identifier CANVAS_ASSET = CustomPaintingsMod.id("canvas");

    private PaintingRuntime() {
    }

    public static Identifier itemId() {
        return CustomPaintingsMod.id("painting");
    }

    public static ResourceKey<Item> itemKey() {
        return ResourceKey.create(Registries.ITEM, itemId());
    }

    public static Identifier variantId(int width, int height) {
        return CustomPaintingsMod.id("canvas_" + width + "x" + height);
    }

    public static ResourceKey<PaintingVariant> variantKey(int width, int height) {
        return ResourceKey.create(Registries.PAINTING_VARIANT, variantId(width, height));
    }

    public static Optional<PaintingVariant> variant(Registry<PaintingVariant> registry, int width, int height) {
        return registry.getOptional(variantKey(width, height)).map(holder -> holder.value());
    }

    /** True for our size-only canvas variants (and legacy v1.x slot variants during migration). */
    public static boolean isCustomVariant(Identifier id) {
        return id.getNamespace().equals(CustomPaintingsMod.MOD_ID)
                && (id.getPath().startsWith("canvas_") || id.getPath().startsWith("slot_"));
    }

    /** Per-painting dynamic texture used by the world renderer. */
    public static Identifier artworkTexture(int id) {
        return CustomPaintingsMod.id("artwork/" + id);
    }

    /** Per-painting dynamic texture used for item icons and menu previews. */
    public static Identifier previewTexture(int id) {
        return CustomPaintingsMod.id("preview/" + id);
    }
}
