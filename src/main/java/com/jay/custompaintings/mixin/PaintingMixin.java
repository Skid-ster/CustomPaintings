package com.jay.custompaintings.mixin;

import com.jay.custompaintings.item.CustomPaintingItems;
import com.jay.custompaintings.library.PaintingData;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingLibraryAccess;
import net.minecraft.network.chat.Component;
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

import java.util.OptionalInt;

/**
 * Custom paintings drop and pick as their own component-carrying item instead of collapsing
 * into vanilla paintings. Identity comes from the synced entity attachment, so this works for
 * any painting id rather than a fixed set of variants.
 */
@Mixin(Painting.class)
public abstract class PaintingMixin {
    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
    private void custompaintings$dropCustomItem(ServerLevel level, Entity causedBy, CallbackInfo ci) {
        Painting self = (Painting) (Object) this;
        OptionalInt found = PaintingData.entityPaintingId(self);
        if (found.isEmpty()) {
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
        self.spawnAtLocation(level, CustomPaintingItems.stack(found.getAsInt()));
    }

    @Inject(method = "getPickResult", at = @At("HEAD"), cancellable = true)
    private void custompaintings$pickCustomItem(CallbackInfoReturnable<ItemStack> ci) {
        Painting self = (Painting) (Object) this;
        OptionalInt found = PaintingData.entityPaintingId(self);
        if (found.isEmpty()) {
            return;
        }
        ItemStack stack = CustomPaintingItems.stack(found.getAsInt());
        PaintingEntry entry = PaintingLibraryAccess.current().get(found.getAsInt()).orElse(null);
        if (entry != null) {
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(entry.name()));
        }
        ci.setReturnValue(stack);
    }
}
