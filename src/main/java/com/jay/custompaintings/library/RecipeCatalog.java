package com.jay.custompaintings.library;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Ten cheap, shapeless, mutually distinct recipes. The random assignment is persisted per library. */
public final class RecipeCatalog {
    public record EasyRecipe(String id, List<String> ingredients, String label) {
        public EasyRecipe {
            ingredients = List.copyOf(ingredients);
        }
    }

    public static final List<EasyRecipe> RECIPES = List.of(
            recipe("paper_black", "minecraft:paper", "minecraft:black_dye", "Paper + Black Dye"),
            recipe("paper_blue", "minecraft:paper", "minecraft:blue_dye", "Paper + Blue Dye"),
            recipe("paper_red", "minecraft:paper", "minecraft:red_dye", "Paper + Red Dye"),
            recipe("paper_yellow", "minecraft:paper", "minecraft:yellow_dye", "Paper + Yellow Dye"),
            recipe("paper_green", "minecraft:paper", "minecraft:green_dye", "Paper + Green Dye"),
            recipe("paper_white", "minecraft:paper", "minecraft:white_dye", "Paper + White Dye"),
            recipe("paper_orange", "minecraft:paper", "minecraft:orange_dye", "Paper + Orange Dye"),
            recipe("paper_purple", "minecraft:paper", "minecraft:purple_dye", "Paper + Purple Dye"),
            recipe("paper_cyan", "minecraft:paper", "minecraft:cyan_dye", "Paper + Cyan Dye"),
            recipe("paper_magenta", "minecraft:paper", "minecraft:magenta_dye", "Paper + Magenta Dye"));

    private RecipeCatalog() {
    }

    private static EasyRecipe recipe(String id, String first, String second, String label) {
        return new EasyRecipe(id, List.of(first, second), label);
    }

    public static boolean contains(String id) {
        return RECIPES.stream().anyMatch(recipe -> recipe.id().equals(id));
    }

    public static EasyRecipe get(String id) {
        return RECIPES.stream().filter(recipe -> recipe.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown easy recipe: " + id));
    }

    /** A seeded shuffle gives every slot a different easy recipe and survives restarts. */
    public static List<String> shuffledIds(long seed) {
        List<String> ids = new ArrayList<>(RECIPES.stream().map(EasyRecipe::id).toList());
        Collections.shuffle(ids, new Random(seed));
        return List.copyOf(ids);
    }
}
