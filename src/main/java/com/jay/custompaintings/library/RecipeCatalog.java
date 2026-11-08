package com.jay.custompaintings.library;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The crafting catalogue: every combination of a vanilla wood plank and a dye, giving
 * {@code 12 × 16 = 192} cheap, mutually distinct shapeless recipes. Each painting claims one
 * free combination; once all 192 are taken, additional paintings are obtained from the
 * Painting Menu instead of crafting.
 */
public final class RecipeCatalog {
    /** Vanilla plank items, including the nether stems and bamboo. */
    public static final List<String> WOODS = List.of(
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
            "mangrove", "cherry", "pale_oak", "bamboo", "crimson", "warped");

    public static final List<String> DYES = List.of(
            "white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow",
            "lime", "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink");

    public record EasyRecipe(String id, List<String> ingredients, String label) {
        public EasyRecipe {
            ingredients = List.copyOf(ingredients);
        }
    }

    public static final List<EasyRecipe> RECIPES = buildRecipes();

    private RecipeCatalog() {
    }

    private static List<EasyRecipe> buildRecipes() {
        List<EasyRecipe> recipes = new ArrayList<>();
        for (String wood : WOODS) {
            for (String dye : DYES) {
                String id = wood + "_" + dye;
                recipes.add(new EasyRecipe(id,
                        List.of("minecraft:" + wood + "_planks", "minecraft:" + dye + "_dye"),
                        title(wood) + " Planks + " + title(dye) + " Dye"));
            }
        }
        return List.copyOf(recipes);
    }

    private static String title(String token) {
        String[] words = token.split("_");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return builder.toString();
    }

    public static int size() {
        return RECIPES.size();
    }

    public static boolean contains(String id) {
        return findIndex(id) >= 0;
    }

    private static int findIndex(String id) {
        if (id == null) {
            return -1;
        }
        for (int index = 0; index < RECIPES.size(); index++) {
            if (RECIPES.get(index).id().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    public static EasyRecipe get(String id) {
        return RECIPES.get(indexOf(id));
    }

    /** Stable id used by the data-driven recipe JSON for one catalogue combination. */
    public static String recipePath(int index) {
        return "craft_" + RECIPES.get(index).id().toLowerCase(Locale.ROOT);
    }

    public static int indexOf(String id) {
        int index = findIndex(id);
        if (index < 0) {
            throw new IllegalArgumentException("Unknown crafting recipe: " + id);
        }
        return index;
    }

    /** A seeded shuffle spreads wood types across early paintings and survives restarts. */
    public static List<String> shuffledIds(long seed) {
        List<String> ids = new ArrayList<>(RECIPES.stream().map(EasyRecipe::id).toList());
        Collections.shuffle(ids, new Random(seed));
        return List.copyOf(ids);
    }
}
