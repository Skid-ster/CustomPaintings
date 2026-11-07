# Custom Paintings

A Fabric mod for **Minecraft Java 26.3** that turns your PNG/JPG images into **up to 10 distinct custom paintings**. Each upload gets its own named painting variant, creative-inventory item, preview, and easy crafting recipe. Vanilla paintings are left untouched.

Manage everything from inside the game through **Mod Menu**: native multi-file picker or drag-and-drop, thumbnails, rename, resize, remove, progress, and per-file details. The library is server-authoritative and synchronized to connected clients, so placed paintings, crafting, and previews agree in singleplayer and on modded servers.

## Pinned toolchain

| Component | Version |
|---|---|
| Minecraft | `26.3` |
| Fabric Loader | `0.19.5` |
| Fabric API | `0.161.0+26.3` |
| Gradle wrapper | `9.6.0` |
| Fabric Loom | `1.17-SNAPSHOT` (the published 1.17 line) |
| Java | `25` |
| Mod Menu | `21.0.0` |
| Mod ID | `custompaintings` |

The build uses **`net.fabricmc.fabric-loom`**, not the legacy/remapping plugin. Minecraft 26.1+ is unobfuscated, so there is no Yarn/intermediary mappings dependency or remapping step.

## Install and use

1. Install Fabric Loader **0.19.5** for Minecraft **26.3**, using **Java 25**.
2. Put Fabric API **0.161.0+26.3**, Mod Menu **21.0.0**, and `custompaintings-1.2.1.jar` into the **client** mods folder. For multiplayer, install the mod and Fabric API on the **server** too.
3. Launch the game, open **Mods → Custom Paintings → Configure**, and choose **Upload Images**.
4. Select `.png`, `.jpg`, or `.jpeg` files, or drag them onto the screen. Each image becomes a new custom painting in the first free slot.
5. Wait for upload, server data reload, and texture reload. The screen shows previews, names, sizes, and assigned recipes.
6. Find the painting in the **Custom Paintings** creative tab, or beside vanilla paintings in Functional Blocks. Craft it with its shown two-ingredient shapeless recipe, then place it on a wall.

### The 10-painting library

- Maximum **10** custom paintings per world/server library.
- Each occupied slot has a stable id, its own item, variant, name, preview, and recipe.
- **Rename** edits the display name used by the item, creative tab, and newly placed copies; existing placed paintings are renamed too.
- **Size** cycles supported presets: 1×1, 2×1, 1×2, 2×2, 3×2, 2×3, 3×3, 4×4. Placed copies are resized automatically; copies that no longer fit on their wall drop as items.
- **Remove** frees the slot and removes placed copies of that painting.
- Easy recipes are randomly assigned once per library from ten distinct paper + dye combinations, persisted, and never collide with each other.

### Permissions and multiplayer

The singleplayer world owner and server operators (gamemaster permission) can upload, rename, resize, and remove. Other players receive a read-only synced library and can still craft/place paintings whose recipes are unlocked. Images are processed into bounded PNGs before synchronization; arbitrary original files are not broadcast.

The authoritative server stores:

```text
<server>/config/custompaintings/library.json
<server>/config/custompaintings/images/slot_0.png ... slot_9.png
```

Clients keep synchronized images in memory for atlas/preview reloads. **Open Local Folder** opens this computer's folder, which is authoritative only in singleplayer/local hosting.

### Upload safety

- Native SDL3 multi-file picker and 26.3 `Screen.onFilesDrop` drag-and-drop.
- Source limits: **32 MiB compressed / 16,777,216 decoded pixels**; processed canvas at most **1,048,576 pixels**.
- PNG alpha preserved; contents must match PNG/JPEG extension. Corrupt, empty, oversized, folder, and symlink selections are rejected.
- One operation at a time; closing the screen does not abort it. Details reports per-file success/failure.
- Atomic manifest writes and canonical slot PNGs; original source files are untouched.
- Legacy v1.1 replacement-only images are migrated into up to ten 2×2 custom slots on first load.

## How it works

- Ten stable custom items are registered at startup. A built-in data pack registers the fixed slot/size painting variants and slot recipes, avoiding unsafe mutation of frozen dynamic registries while a world runs.
- The server library controls which slots are occupied. Recipe matches/displays and creative entries follow the library; a server data reload refreshes recipe book entries after changes.
- Custom payloads sync metadata and processed PNGs to clients. A client-ready sync request and channel-registration sync avoid the Fabric login race; duplicate snapshots are ignored. The client invalidates cached creative tabs, reloads atlases, and verifies that every occupied slot exists in both the painting and item atlases.
- `SpriteLoader` hooks add custom sprites to the painting and item atlases without replacing vanilla art. GUI previews use official 26.3 `DynamicTexture`.
- A painting mixin makes custom variants drop and pick-block as their own item instead of a generic vanilla painting.

## Build locally

Install a full **JDK 25**, then:

```bash
./gradlew build
```

Outputs:

```text
build/libs/custompaintings-1.2.1.jar
build/libs/custompaintings-1.2.0-sources.jar
```

Install the non-sources JAR. The first build needs Fabric Maven, Mojang libraries, Maven Central, and TerraformersMC Maven. For a development client, run `./gradlew runClient`.

## Tests and CI

`./gradlew build` runs JUnit tests for image decoding/validation/resizing, importer safety, the bounded library, persistence, rename/resize/remove, recipe uniqueness, and slot caps. These tests do not launch Minecraft or require a GPU.

GitHub Actions builds on pushes to `main`, session branches, tags, and pull requests. Successful `main` builds automatically publish a GitHub Release for the current `mod_version` unless that release already exists. Existing releases are never overwritten; bump `mod_version` for each release.

A successful compile is not an in-game smoke test. Before distributing, verify upload/picker/drag-drop, previews, rename, creative entries, crafting, placement, drop/pick-block, multiplayer sync, and several sizes on the target desktop OS.

## License

Mod source and original icon: **MIT**, see `LICENSE`. Gradle wrapper scripts/JAR are Apache-2.0, see `LICENSE.gradle-wrapper`. Minecraft, Fabric API, and Mod Menu retain their respective licenses and are dependencies, not bundled libraries.
