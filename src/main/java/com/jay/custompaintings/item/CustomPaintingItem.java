package com.jay.custompaintings.item;

import com.jay.custompaintings.library.PaintingData;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingLibrary;
import com.jay.custompaintings.library.PaintingLibraryAccess;
import com.jay.custompaintings.library.PaintingRuntime;
import com.jay.custompaintings.library.RecipeCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.painting.Painting;
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

/**
 * The single custom-painting item. The stack's {@link PaintingData#PAINTING_ID} component
 * selects which of the unlimited library paintings it represents, so no per-painting
 * registration is ever needed.
 */
public final class CustomPaintingItem extends Item {
    public CustomPaintingItem(Properties properties) {
        super(properties);
    }

    public static Optional<PaintingEntry> entryOf(ItemStack stack) {
        Integer id = stack.get(PaintingData.PAINTING_ID);
        if (id == null) {
            return Optional.empty();
        }
        return PaintingLibraryAccess.current().get(id);
    }

    @Override
    public Component getName(ItemStack stack) {
        return entryOf(stack).<Component>map(entry -> Component.literal(entry.name()))
                .orElseGet(() -> Component.translatable("item.custompaintings.painting"));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Direction face = context.getClickedFace();
        if (face.getAxis().isVertical()) {
            return InteractionResult.FAIL;
        }
        ItemStack stack = context.getItemInHand();
        Optional<PaintingEntry> found = entryOf(stack);
        if (found.isEmpty()) {
            return InteractionResult.FAIL;
        }
        PaintingEntry entry = found.get();
        BlockPos pos = context.getClickedPos().relative(face);
        Player player = context.getPlayer();
        if (player != null && !player.mayUseItemAt(pos, face, stack)) {
            return InteractionResult.FAIL;
        }
        var holder = level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                .get(PaintingRuntime.variantKey(entry.width(), entry.height())).orElse(null);
        if (holder == null) {
            return InteractionResult.CONSUME;
        }
        Painting painting = new Painting(level, pos, face, holder);
        PaintingData.setEntityPaintingId(painting, entry.id());
        painting.setCustomName(Component.literal(entry.name()));
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
        Optional<PaintingEntry> found = entryOf(stack);
        if (found.isEmpty()) {
            lines.accept(Component.translatable("tooltip.custompaintings.unknown"));
            return;
        }
        PaintingEntry entry = found.get();
        lines.accept(Component.translatable("tooltip.custompaintings.size", entry.sizeLabel()));
        lines.accept(Component.translatable("tooltip.custompaintings.fit",
                Component.translatable(entry.fit().translationKey())));
        if (entry.hasRecipe()) {
            lines.accept(Component.translatable("tooltip.custompaintings.recipe",
                    RecipeCatalog.get(entry.recipeId()).label()));
        } else {
            lines.accept(Component.translatable("tooltip.custompaintings.menuOnly"));
        }
    }

    /** Convenience for callers that already hold a library snapshot. */
    public static Optional<PaintingEntry> entryOf(ItemStack stack, PaintingLibrary library) {
        Integer id = stack.get(PaintingData.PAINTING_ID);
        return id == null ? Optional.empty() : library.get(id);
    }
}
