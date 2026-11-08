# Custom Paintings

A Fabric mod for **Minecraft Java 26.3** that turns your PNG/JPG images into **up to 10 distinct custom paintings**. Each upload gets its own named painting variant, creative-inventory item, preview, and easy crafting recipe. Vanilla paintings are left untouched.

Manage everything from inside the game through **Mod Menu**: native multi-file picker or drag-and-drop, thumbnails, full editing (name, independent width/height, fit mode, auto-size) with a live aspect-correct draft preview, reversible removals, progress, and per-file details. Edits are **staged as drafts** across as many paintings as you like, then committed together with **Apply** (or thrown away with **Discard**), so changing several sizes no longer triggers a reload per slider/save. The library is server-authoritative and synchronized to connected clients, so placed paintings, crafting, and previews agree in singleplayer and on modded servers.

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
2. Put Fabric API **0.161.0+26.3**, Mod Menu **21.0.0**, and `custompaintings-1.5.0.jar` into the **client** mods folder. For multiplayer, install the mod and Fabric API on the **server** too.
3. Launch the game, open **Mods → Custom Paintings → Configure**, and choose **Upload Images**.
4. Select `.png`, `.jpg`, or `.jpeg` files, or drag them onto the screen. The whole selected batch is uploaded as one operation and becomes new custom paintings in the first free slots.
5. Wait for the single upload/sync/reload. The screen shows previews, names, sizes, and assigned recipes.
6. To change paintings, select one and press **Edit**, adjust name/size/fit, then **Save Draft**. The editor previews the draft at its real aspect ratio over a transparency checkerboard without reloading the atlas. Repeat for as many paintings as you like; pending changes are badged in the list. Nothing is sent to the server and no textures reload while you stage drafts. Closing with unsaved changes asks before discarding; **Revert** clears just that painting.
7. Press **Apply** to commit every staged edit/removal together in one authoritative batch and one texture refresh, or **Discard** to throw the drafts away.
8. Find the painting in the **Custom Paintings** creative tab, or beside vanilla paintings in Functional Blocks. Craft it with its shown two-ingredient shapeless recipe, then place it on a wall.

### The 10-painting library

- Maximum **10** custom paintings per world/server library.
- Each occupied slot has a stable id, its own item, variant, name, preview, and recipe.
- **Edit** changes the display name used by the item, creative tab, and placed copies; existing placed paintings are renamed too. Edits are saved as drafts first.
- **Size & Fit** adjusts width and height independently from **1×1 through 8×8**. Placed copies are resized automatically on Apply; copies that no longer fit on their wall drop as items.
- **Apply / Discard** commits or clears all staged drafts together. A metadata-only Apply batch saves one manifest revision, updates placed copies, syncs once, and reloads client atlases once only if image/canvas/fit pixels changed.
- **Auto Size From Image** chooses the supported canvas closest to the uploaded image's own proportions. New uploads default to this behavior.
- **Fit modes**: **Fit** keeps the whole image undistorted and leaves unused canvas transparent; **Fill** keeps proportions and crops excess edges; **Stretch** fills the canvas exactly. The original aspect-preserving master is retained, so switching modes or sizes never compounds resampling loss.
- **Remove** marks a painting for removal; press **Undo Remove** to restore it. The slot is freed and placed copies are removed only when the staged batch is applied.
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
- PNG alpha is preserved end-to-end: transparent holes reveal the wall behind placed paintings, while opaque artwork remains solid. Contents must match PNG/JPEG extension. Corrupt, empty, oversized, folder, and symlink selections are rejected.
- One operation at a time; closing the screen does not abort it. Details reports per-file success/failure. Multi-file uploads are sent as one bounded batch, not one reload per file.
- Atomic manifest writes and canonical slot PNGs; original source files are untouched. Apply batches validate every staged edit/removal against the expected revision before saving once.
- Legacy v1.1 replacement-only images are migrated into auto-sized custom slots on first load. v1.2 libraries keep their previous stretched appearance until edited.

## How it works

- Ten stable custom items are registered at startup. A built-in data pack pre-registers all 640 slot/size painting variants (10 slots × 8 widths × 8 heights) and slot recipes, avoiding unsafe mutation of frozen dynamic registries while a world runs.
- The server library controls which slots are occupied. Recipe matches/displays and creative entries follow the library; a server data reload refreshes recipe book entries after occupancy changes. Metadata-only Apply batches skip that data reload because all 640 variants are pre-registered and recipes read the live library.
- Custom payloads (protocol v3) sync metadata and aspect-preserving master PNGs to clients. `UploadBatch` sends a whole multi-file import as one mutation; `ApplyBatch` sends staged edits/removals with an expected revision so stale drafts cannot overwrite newer server data. A client-ready sync request and channel-registration sync avoid the Fabric login race; duplicate and stale-revision snapshots are ignored. Disconnects cancel in-flight client operations so late replies cannot affect a new world. The client invalidates cached creative tabs, coalesces overlapping syncs into at most one running atlas reload plus one follow-up, skips atlas reloads for name-only changes, and verifies that every occupied slot exists in both the painting and item atlases.
- `SpriteLoader` hooks add custom sprites to the painting and item atlases without replacing vanilla art. The hook matches the atlas **sheet texture ids** (`minecraft:textures/atlas/paintings.png` and `minecraft:textures/atlas/items.png`), which is the id space `SpriteLoader` actually exposes; atlas verification/rendering uses the separate definition ids (`minecraft:paintings` / `minecraft:items`). GUI previews use official 26.3 `DynamicTexture`.
- A painting mixin makes custom variants drop and pick-block as their own item instead of a generic vanilla painting.
- A client renderer mixin draws custom artwork with the translucent entity pipeline and without vanilla's opaque canvas backing, so PNG transparency actually reveals the wall.

## Build locally

Install a full **JDK 25**, then:

```bash
./gradlew build
```

Outputs:

```text
build/libs/custompaintings-1.5.0.jar
build/libs/custompaintings-1.4.0-sources.jar
```

Install the non-sources JAR. The first build needs Fabric Maven, Mojang libraries, Maven Central, and TerraformersMC Maven. For a development client, run `./gradlew runClient`.

## Tests and CI

`./gradlew build` runs JUnit tests for image decoding/validation/fit/fill/stretch composition, importer safety, auto-sizing, the bounded library, lossless master persistence, rename/adjust/remove, atomic Apply/upload batches, staged drafts/discard/metadata-conflict rebasing/batch acknowledgment, recipe uniqueness, and slot caps. These tests do not launch Minecraft or require a GPU.

There is also a separate **client gametest** source set under `src/gametest`. It launches Minecraft, uploads generated test images, asserts that the painting and item atlas sprites are present, places paintings and checks screenshot pixels, resizes/removes them, exercises live draft previews and reversible removals, relogs into the saved world, triggers a manual resource reload, exercises auto-sized wide art, preserved alpha, 8×8 murals, and fit-mode changes, then stages edits across two paintings and proves nothing reloads or changes server revision until Apply commits the whole batch in one revision/one atlas reload. Run it locally with a display (or CI's headless Xvfb setup):

```bash
./gradlew runClientGameTest
```

GitHub Actions builds on pushes to `main`, session branches, tags, and pull requests. The workflow has a separate in-game client rendering job; releases require both the headless build/unit tests and that client gametest to pass. Successful `main` builds automatically publish a GitHub Release for the current `mod_version` unless that release already exists. Existing releases are never overwritten; bump `mod_version` for each release.

The automated client test is a rendering regression test, not a substitute for all desktop smoke testing. Before distributing, also verify the native picker/drag-drop, previews, rename, creative entries, crafting, drop/pick-block, multiplayer sync, and several sizes on the target desktop OS.

## License

Mod source and original icon: **MIT**, see `LICENSE`. Gradle wrapper scripts/JAR are Apache-2.0, see `LICENSE.gradle-wrapper`. Minecraft, Fabric API, and Mod Menu retain their respective licenses and are dependencies, not bundled libraries.
