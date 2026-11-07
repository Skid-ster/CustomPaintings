package com.jay.custompaintings.recipe;

import com.jay.custompaintings.item.CustomPaintingItems;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.RecipeCatalog;
import com.jay.custompaintings.server.PaintingServerState;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Optional;

/**
 * Data-driven slot recipe. The JSON is static, while ingredients/result visibility follow the
 * authoritative library. A server data reload refreshes the recipe book after uploads/renames.
 */
public final class CustomPaintingRecipe implements CraftingRecipe {
    public static final MapCodec<CustomPaintingRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.intRange(0, PaintingEntry.MAX_PAINTINGS - 1).fieldOf("slot").forGetter(recipe -> recipe.slot)
    ).apply(instance, CustomPaintingRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, CustomPaintingRecipe> STREAM_CODEC =
            StreamCodec.of((buf, recipe) -> buf.writeVarInt(recipe.slot),
                    buf -> new CustomPaintingRecipe(buf.readVarInt()));
    public static final RecipeSerializer<CustomPaintingRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    private final int slot;
    private PlacementInfo placementInfo;

    private CustomPaintingRecipe(int slot) {
        this.slot = slot;
    }

    private Optional<PaintingEntry> entry() {
        return PaintingServerState.library().get(slot);
    }

    private Optional<PaintingEntry> entry(Level level) {
        return (level.isClientSide() ? com.jay.custompaintings.library.PaintingLibraryAccess.current()
                : PaintingServerState.library()).get(slot);
    }

    private List<Ingredient> ingredients(PaintingEntry entry) {
        Registry<net.minecraft.world.item.Item> registry = BuiltInRegistries.ITEM;
        return RecipeCatalog.get(entry.recipeId()).ingredients().stream()
                .map(id -> Ingredient.of(registry.getOptional(Identifier.parse(id))
                        .orElseThrow(() -> new IllegalStateException("Missing recipe ingredient: " + id))))
                .toList();
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        Optional<PaintingEntry> entry = entry(level);
        if (entry.isEmpty() || input.ingredientCount() != 2) {
            return false;
        }
        List<Ingredient> ingredients = ingredients(entry.get());
        return input.stackedContents().canCraft(ingredients, null);
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        return entry().map(entry -> CustomPaintingItems.stack(slot)).orElse(ItemStack.EMPTY);
    }

    @Override
    public RecipeSerializer<? extends CraftingRecipe> getSerializer() {
        return SERIALIZER;
    }

    @Override
    public CraftingBookCategory category() {
        return CraftingBookCategory.MISC;
    }

    @Override
    public boolean showNotification() {
        return true;
    }

    @Override
    public String group() {
        return "custompaintings";
    }

    @Override
    public PlacementInfo placementInfo() {
        if (placementInfo == null) {
            placementInfo = entry().map(entry -> PlacementInfo.create(ingredients(entry)))
                    .orElse(PlacementInfo.NOT_PLACEABLE);
        }
        return placementInfo;
    }

    @Override
    public List<RecipeDisplay> display() {
        return entry().map(entry -> List.<RecipeDisplay>of(new ShapelessCraftingRecipeDisplay(
                ingredients(entry).stream().map(Ingredient::display).toList(),
                new SlotDisplay.ItemStackSlotDisplay(new ItemStackTemplate(CustomPaintingItems.get(slot))),
                new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE))))
                .orElseGet(List::of);
    }

}
