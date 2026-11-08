package com.jay.custompaintings.client.mixin;

import com.jay.custompaintings.client.PaintingItemModel;
import com.jay.custompaintings.library.PaintingRuntime;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Serves our painting item model without touching Minecraft's private, bootstrap-only item
 * model codec registry. Every custom-painting stack resolves to the same model instance,
 * which then draws the icon for that stack's painting id.
 */
@Mixin(ModelManager.class)
public abstract class ModelManagerMixin {
    @Unique
    private final ItemModel custompaintings$paintingModel = new PaintingItemModel();

    @Inject(method = "getItemModel", at = @At("HEAD"), cancellable = true)
    private void custompaintings$paintingItemModel(Identifier id, CallbackInfoReturnable<ItemModel> ci) {
        if (id.equals(PaintingRuntime.itemId())) {
            ci.setReturnValue(custompaintings$paintingModel);
        }
    }
}
