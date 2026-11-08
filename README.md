# Custom Paintings

A Fabric mod for **Minecraft Java 26.3** that turns your PNG/JPG images into an **unlimited library of custom paintings**. Each upload gets its own name, item icon, preview, and — for the first 192 — an easy crafting recipe. A built-in **Painting Menu** lets anyone take any painting in Survival or Creative. Vanilla paintings are left untouched.

Manage everything in game: native multi-file picker or drag-and-drop, searchable thumbnails, full editing (name, independent width/height, fit mode, auto-size) with a live aspect-correct draft preview, reversible removals, progress, and per-file details. Edits are **staged as drafts** across as many paintings as you like, then committed together with **Apply** (or thrown away with **Discard**).

**New in 2.0:** the 10-painting cap is gone. Artwork is streamed in pages and drawn from per-painting dynamic textures instead of a fixed-size atlas, so a library of thousands costs no more GPU memory than the paintings actually on screen — and adding or editing paintings never triggers a resource reload.

### The Painting Menu

Press **`P`** (rebindable), run **`/paintingmenu`**, or click **Painting Menu** in the library screen. Search by name or size, then **Take** or **Take 16**. It works in **Survival and Creative**, and it is the way to get paintings that have no crafting recipe. Server operators can restrict it to operators via `menuAllowedForEveryone` in the config.

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
2. Put Fabric API **0.161.0+26.3**, Mod Menu **21.0.0**, and `custompaintings-2.0.0.jar` into the **client** mods folder. For multiplayer, install the mod and Fabric API on the **server** too.
3. Launch the game, open **Mods → Custom Paintings → Configure**, and choose **Upload Images**.
4. Select `.png`, `.jpg`, or `.jpeg` files, or drag them onto the screen. The whole selected batch is uploaded as one operation and becomes new custom paintings.
5. Wait for the single upload/sync. The screen shows previews, names, sizes, and assigned recipes.
6. To change paintings, select one and press **Edit**, adjust name/size/fit, then **Save Draft**. The editor previews the draft at its real aspect ratio over a transparency checkerboard without reloading resources. Repeat for as many paintings as you like; pending changes are badged in the list. Nothing is sent to the server and no textures reload while you stage drafts. Closing with unsaved changes asks before discarding; **Revert** clears just that painting.
7. Press **Apply** to commit every staged edit/removal together in one authoritative batch and one texture refresh, or **Discard** to throw the drafts away.
8. Take the painting from the **Painting Menu** (`P`), the **Custom Paintings** creative tab, or beside vanilla paintings in Functional Blocks — or craft it with its shown two-ingredient wood + dye recipe. Then place it on a wall.

### The unlimited library

- **No painting cap.** Ids are unbounded and never reused, so an old item in a chest can never silently change artwork.
- Each painting has a stable id, name, preview, and item icon, all driven by one registered item plus a data component.
- **Edit** changes the display name used by the item, creative tab, and placed copies; existing placed paintings are renamed too. Edits are saved as drafts first.
- **Size & Fit** adjusts width and height independently from **1×1 through 8×8**. Placed copies are resized automatically on Apply; copies that no longer fit on their wall drop as items.
- **Apply / Discard** commits or clears all staged drafts together. An Apply batch saves one manifest revision, updates placed copies, and syncs once. Only the affected textures are recomposed — there is no resource reload.
- **Auto Size From Image** chooses the supported canvas closest to the image's own proportions. New uploads default to this.
- **Fit modes**: **Fit** keeps the whole image undistorted and leaves unused canvas transparent; **Fill** keeps proportions and crops excess; **Stretch** fills the canvas exactly. The aspect-preserving master is retained, so switching modes or sizes never compounds resampling loss.
- **Remove** marks a painting for removal; **Undo Remove** restores it. Placed copies are removed only when the batch is applied.
- **Crafting**: 192 unique recipes (12 woods × 16 dyes) are assigned in a stable seeded order and never collide. Paintings beyond the 192nd have no recipe and come from the Painting Menu instead.

### Permissions and multiplayer

The singleplayer world owner and server operators (gamemaster permission) can upload, rename, resize, and remove. Other players receive a read-only synced library, can take paintings from the Painting Menu, and can craft/place paintings whose recipes are unlocked. Images are processed into bounded PNGs before synchronization; arbitrary original files are not broadcast.

The authoritative server stores:

```text
<server>/config/custompaintings/library.json
<server>/config/custompaintings/images/painting_<id>.png
```

Clients page in metadata on demand and keep a bounded LRU of artwork in memory plus a SHA-256 disk cache. **Open Local Folder** opens this computer's folder, which is authoritative only in singleplayer/local hosting.

### Upload safety

- Native SDL3 multi-file picker and 26.3 `Screen.onFilesDrop` drag-and-drop.
- Source limits: **32 MiB compressed / 16,777,216 decoded pixels**; processed canvas at most **1,048,576 pixels**.
- PNG alpha is preserved end-to-end: transparent holes reveal the wall behind placed paintings, while opaque artwork remains solid. Contents must match PNG/JPEG extension. Corrupt, empty, oversized, folder, and symlink selections are rejected.
- One operation at a time; closing the screen does not abort it. Details reports per-file success/failure. Multi-file uploads are sent as one bounded batch, not one reload per file.
- Atomic manifest writes and canonical per-id PNGs; original source files are untouched. Apply batches validate every staged edit/removal against the expected revision before saving once.
- Legacy libraries migrate automatically: v1.x `slot` ids become painting ids, image files are renamed, and old paper recipes are reassigned to the wood/dye catalogue on first load. Placed v1.x paintings keep working.

## How it works

- **One item, 64 variants.** Registries are frozen at startup, so v2 registers exactly one item (`custompaintings:painting`) whose stack carries a `painting_id` component, plus 64 *size-only* painting variants (`canvas_WxH`) that supply the entity's bounding box. Adding paintings therefore never touches a registry.
- **No atlas.** Artwork is uploaded as standalone `DynamicTexture`s keyed by painting id, composed on a worker thread and evicted least-recently-used. This is what removes both the painting cap and the atlas-capacity ceiling, and why edits no longer reload resources.
- **Item icons** come from a Java-built `ItemModel` returned by a `ModelManager` mixin, drawing an aspect-correct quad from the painting's preview texture — no per-painting model JSON.
- **Paged protocol (v4).** Metadata syncs in pages of 128 entries; images stream in 256 KiB chunks with a bounded, expiring per-player staging area. `ApplyBatch` carries an expected revision so stale drafts cannot overwrite newer server data. Duplicate and stale pages are ignored, and disconnects cancel in-flight operations.
- **Recipes** are data-driven: each JSON names one wood/dye combination and resolves its current owner from the live library, so uploading artwork needs no new recipe files or data reload.
- A painting mixin makes custom paintings drop and pick-block as their own component-carrying item. Identity survives save/load through a synced entity attachment.
- A client renderer mixin draws artwork with the translucent entity pipeline and without vanilla's opaque canvas backing, so PNG transparency reveals the wall.

## Build locally

Install a full **JDK 25**, then:

```bash
./gradlew build
```

Outputs:

```text
build/libs/custompaintings-2.0.0.jar
build/libs/custompaintings-2.0.0-sources.jar
```

Install the non-sources JAR. The first build needs Fabric Maven, Mojang libraries, Maven Central, and TerraformersMC Maven. For a development client, run `./gradlew runClient`.

## Tests and CI

`./gradlew build` runs JUnit tests for image decoding/validation/fit/fill/stretch composition, importer safety, auto-sizing, the bounded library, lossless master persistence, rename/adjust/remove, atomic Apply/upload batches, staged drafts/discard/metadata-conflict rebasing/batch acknowledgment, recipe uniqueness, unbounded ids, recipe-pool exhaustion, and the variant/recipe/item resource catalogues. These tests do not launch Minecraft or require a GPU.

There is also a separate **client gametest** source set under `src/gametest`. It launches Minecraft, uploads generated test images, verifies the per-painting dynamic textures and item identity, takes paintings through the Painting Menu, places paintings and checks screenshot pixels, edits/removes them, exercises live draft previews and reversible removals, relogs into the saved world to prove identity persistence, refreshes artwork without a resource reload, exercises auto-sized wide art, preserved alpha, 8×8 murals and fit modes, stages a two-painting batch and proves it commits in exactly one revision, then uploads past the old 10-painting cap and screenshots the Painting Menu. Run it locally with a display (or CI's headless Xvfb setup):

```bash
./gradlew runClientGameTest
```

GitHub Actions builds on pushes to `main`, session branches, tags, and pull requests. The workflow has a separate in-game client rendering job; releases require both the headless build/unit tests and that client gametest to pass. Successful `main` builds automatically publish a GitHub Release for the current `mod_version` unless that release already exists. Existing releases are never overwritten; bump `mod_version` for each release.

The automated client test is a rendering regression test, not a substitute for all desktop smoke testing. Before distributing, also verify the native picker/drag-drop, previews, rename, creative entries, crafting, drop/pick-block, multiplayer sync, and several sizes on the target desktop OS.

## License

Mod source and original icon: **MIT**, see `LICENSE`. Gradle wrapper scripts/JAR are Apache-2.0, see `LICENSE.gradle-wrapper`. Minecraft, Fabric API, and Mod Menu retain their respective licenses and are dependencies, not bundled libraries.
