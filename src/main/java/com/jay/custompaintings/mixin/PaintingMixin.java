package com.jay.custompaintings.mixin;

import com.jay.custompaintings.item.CustomPaintingItems;
import com.jay.custompaintings.library.PaintingRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Custom variants drop and pick as their own item instead of collapsing into vanilla paintings. */
@Mixin(Painting.class)
public abstract class PaintingMixin {
    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
    private void custompaintings$dropCustomItem(ServerLevel level, Entity causedBy, CallbackInfo ci) {
        Painting self = (Painting) (Object) this;
        var key = self.getVariant().unwrapKey().orElse(null);
        if (key == null || PaintingRuntime.slotFromVariant(key.identifier()).isEmpty()) {
            return;
        }
        ci.cancel();
        if (!level.getGameRules().get(GameRules.ENTITY_DROPS).booleanValue()) {
            return;
        }
        self.playSound(net.minecraft.sounds.SoundEvents.PAINTING_BREAK, 1.0f, 1.0f);
        if (causedBy instanceof Player player && player.hasInfiniteMaterials()) {
            return;
        }
        int slot = PaintingRuntime.slotFromVariant(key.identifier()).orElseThrow();
        self.spawnAtLocation(level, CustomPaintingItems.stack(slot));
    }

    @Inject(method = "getPickResult", at = @At("HEAD"), cancellable = true)
    private void custompaintings$pickCustomItem(CallbackInfoReturnable<ItemStack> ci) {
        Painting self = (Painting) (Object) this;
        self.getVariant().unwrapKey().flatMap(key -> PaintingRuntime.slotFromVariant(key.identifier()))
                .ifPresent(slot -> ci.setReturnValue(CustomPaintingItems.stack(slot)));
    }
}
