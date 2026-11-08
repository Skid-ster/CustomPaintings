#!/usr/bin/env python3
"""Validates every shipped resource JSON and the variant/recipe/item catalogues."""
import json
import pathlib
import sys

RESOURCES = pathlib.Path("src/main/resources")

bad = []
count = 0
for path in sorted(RESOURCES.rglob("*.json")):
    count += 1
    try:
        json.loads(path.read_text())
    except Exception as exc:  # noqa: BLE001 - report every malformed file
        bad.append(f"{path}: {exc}")

if bad:
    print("\n".join(bad))
    sys.exit(1)
print(f"All {count} resource JSON files parse")

variants = RESOURCES / "resourcepacks/library/data/custompaintings/painting_variant"
recipes = RESOURCES / "resourcepacks/library/data/custompaintings/recipe"
items = RESOURCES / "assets/custompaintings/items"

expected_variants = {f"canvas_{w}x{h}.json" for w in range(1, 9) for h in range(1, 9)}
actual_variants = {p.name for p in variants.glob("*.json")}
if actual_variants != expected_variants:
    print("Variant catalogue mismatch")
    print("  missing:", sorted(expected_variants - actual_variants))
    print("  extra:", sorted(actual_variants - expected_variants))
    sys.exit(1)

WOODS = ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry",
         "pale_oak", "bamboo", "crimson", "warped"]
DYES = ["white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow",
        "lime", "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink"]
expected_recipes = {f"craft_{w}_{d}.json" for w in WOODS for d in DYES}
actual_recipes = {p.name for p in recipes.glob("*.json")}
if actual_recipes != expected_recipes:
    print("Recipe catalogue mismatch")
    print("  missing:", sorted(expected_recipes - actual_recipes))
    print("  extra:", sorted(actual_recipes - expected_recipes))
    sys.exit(1)

if {p.name for p in items.glob("*.json")} != {"painting.json"}:
    print("Expected exactly one item definition: painting.json")
    sys.exit(1)

print(f"Catalogues OK: {len(actual_variants)} variants, {len(actual_recipes)} recipes, 1 item")
