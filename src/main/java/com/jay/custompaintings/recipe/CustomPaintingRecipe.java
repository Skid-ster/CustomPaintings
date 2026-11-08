package com.jay.custompaintings.recipe;

import com.jay.custompaintings.item.CustomPaintingItems;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingLibrary;
import com.jay.custompaintings.library.PaintingLibraryAccess;
import com.jay.custompaintings.library.RecipeCatalog;
import com.jay.custompaintings.server.PaintingServerState;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Optional;

/**
 * Data-driven wood/dye recipe. Each JSON names one catalogue combination (for example
 * {@code oak_red}); the painting that currently owns that combination is resolved from the
 * live library, so uploading artwork never needs a new recipe file or a data reload.
 *
 * <p>The catalogue is finite (192 combinations). Paintings beyond it have no recipe and are
 * taken from the Painting Menu instead, which works in Survival and Creative.</p>
 */
public final class CustomPaintingRecipe implements CraftingRecipe {
    public static final MapCodec<CustomPaintingRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.fieldOf("recipe").forGetter(recipe -> recipe.recipeId)
    ).apply(instance, CustomPaintingRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, CustomPaintingRecipe> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, recipe -> recipe.recipeId, CustomPaintingRecipe::new);
    public static final RecipeSerializer<CustomPaintingRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    private final String recipeId;
    /** Ingredients never change, so they can be cached; ownership must always be re-resolved. */
    private volatile List<Ingredient> ingredients;
    private volatile PlacementInfo placementInfo;

    private CustomPaintingRecipe(String recipeId) {
        if (!RecipeCatalog.contains(recipeId)) {
            throw new IllegalArgumentException("Unknown crafting recipe: " + recipeId);
        }
        this.recipeId = recipeId;
    }

    private static Optional<PaintingEntry> owner(PaintingLibrary library, String recipeId) {
        // The catalogue is small and libraries are modest; a linear scan avoids any index
        // that could go stale when paintings are added, removed or reassigned.
        for (PaintingEntry entry : library.entries()) {
            if (recipeId.equals(entry.recipeId())) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** Server-authoritative owner, used when actually crafting. */
    private Optional<PaintingEntry> entry() {
        return owner(PaintingServerState.library(), recipeId);
    }

    private Optional<PaintingEntry> entry(Level level) {
        return owner(level.isClientSide() ? PaintingLibraryAccess.current() : PaintingServerState.library(), recipeId);
    }

    private List<Ingredient> ingredients() {
        List<Ingredient> cached = ingredients;
        if (cached == null) {
            cached = RecipeCatalog.get(recipeId).ingredients().stream()
                    .map(id -> Ingredient.of(BuiltInRegistries.ITEM.getOptional(Identifier.parse(id))
                            .orElseThrow(() -> new IllegalStateException("Missing recipe ingredient: " + id))))
                    .toList();
            ingredients = cached;
        }
        return cached;
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        // Unowned combinations craft nothing, but their placement info stays valid so the
        // recipe becomes usable the moment a painting claims the combination.
        if (entry(level).isEmpty() || input.ingredientCount() != 2) {
            return false;
        }
        return input.stackedContents().canCraft(this, null);
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        return entry().map(entry -> CustomPaintingItems.stack(entry.id())).orElse(ItemStack.EMPTY);
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
    public boolean isSpecial() {
        // Dynamic result: never offer it as a static recipe-book entry.
        return true;
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
        PlacementInfo cached = placementInfo;
        if (cached == null) {
            cached = PlacementInfo.create(ingredients());
            placementInfo = cached;
        }
        return cached;
    }

    @Override
    public List<RecipeDisplay> display() {
        return entry().map(entry -> List.<RecipeDisplay>of(new ShapelessCraftingRecipeDisplay(
                ingredients().stream().map(Ingredient::display).toList(),
                new SlotDisplay.ItemStackSlotDisplay(CustomPaintingItems.template(entry.id())),
                new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE))))
                .orElseGet(List::of);
    }
}
