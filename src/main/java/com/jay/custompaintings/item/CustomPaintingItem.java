package com.jay.custompaintings.item;

import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingLibrary;
import com.jay.custompaintings.library.PaintingRuntime;
import com.jay.custompaintings.server.PaintingServerState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;

import java.util.Optional;
import java.util.function.Consumer;

/** A distinct survival-friendly item that always places its own registered painting variant. */
public final class CustomPaintingItem extends Item {
    private final int slot;

    public CustomPaintingItem(int slot, Properties properties) {
        super(properties);
        this.slot = slot;
    }

    public int slot() {
        return slot;
    }

    @Override
    public Component getName(ItemStack stack) {
        return CustomPaintingItems.name(slot);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Direction face = context.getClickedFace();
        if (face.getAxis().isVertical()) {
            return InteractionResult.FAIL;
        }
        PaintingLibrary library = level.isClientSide()
                ? com.jay.custompaintings.library.PaintingLibraryAccess.current() : PaintingServerState.library();
        Optional<PaintingEntry> entry = library.get(slot);
        if (entry.isEmpty()) {
            return InteractionResult.FAIL;
        }
        BlockPos pos = context.getClickedPos().relative(face);
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        if (player != null && !player.mayUseItemAt(pos, face, stack)) {
            return InteractionResult.FAIL;
        }
        var holder = level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                .get(PaintingRuntime.variantKey(slot, entry.get().width(), entry.get().height())).orElse(null);
        if (holder == null) {
            return InteractionResult.CONSUME;
        }
        Painting painting = new Painting(level, pos, face, holder);
        painting.setCustomName(Component.literal(entry.get().name()));
        if (!painting.survives()) {
            return InteractionResult.CONSUME;
        }
        if (!level.isClientSide()) {
            painting.playPlacementSound();
            level.gameEvent(player, GameEvent.ENTITY_PLACE, painting.position());
            level.addFreshEntity(painting);
        }
        stack.shrink(1);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> lines, TooltipFlag flag) {
        com.jay.custompaintings.library.PaintingLibraryAccess.current().get(slot).ifPresent(entry -> {
            lines.accept(Component.translatable("tooltip.custompaintings.size", entry.sizeLabel()));
            lines.accept(Component.translatable("tooltip.custompaintings.recipe",
                    com.jay.custompaintings.library.RecipeCatalog.get(entry.recipeId()).label()));
        });
    }
}
