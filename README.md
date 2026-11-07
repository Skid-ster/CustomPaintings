# Custom Paintings

A client-side Fabric mod for **Minecraft Java 26.3** that replaces vanilla painting canvases with PNG/JPG images from your own config folder. Includes a Mod Menu screen with image previews, assignment details, loading errors, and a resource-reload button.

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

**Why `1.17-SNAPSHOT`?** Fabric's published plugin metadata has 1.17 alphas, `1.17-SNAPSHOT`, and patch releases beginning at `1.17.1`; it does not contain a `1.17.0` artifact. The 26.3 release of Mod Menu also built against `1.17-SNAPSHOT`. This project uses that exact published 1.17 coordinate rather than an invalid version. If you require a non-changing Loom artifact, `1.17.1` is the nearest stable patch, but it is not the version configured here.

The build uses **`net.fabricmc.fabric-loom`**, not the legacy/remapping plugin. Minecraft 26.1+ is unobfuscated, so there is no Yarn/intermediary mappings dependency or remapping step.

## Install and use

1. Install Fabric Loader **0.19.5** for Minecraft **26.3**, using **Java 25**.
2. Put Fabric API **0.161.0+26.3**, Mod Menu **21.0.0**, and `custompaintings-1.0.0.jar` into your instance's `mods` folder.
3. Launch the game once. The mod creates:
   ```text
   .minecraft/config/custompaintings/images/
   ```
   Launcher-specific instances use their own game directory, so the actual path may differ. The Mod Menu screen displays the exact path.
4. Copy `.png`, `.jpg`, or `.jpeg` images into that folder. Only files directly inside the folder are scanned; subfolders and symbolic links are not followed.
5. Press **F3 + T**, or open **Mods → Custom Paintings → Configure → Reload Paintings**. Both reload client resources; no restart is needed.
6. Place paintings in the world. Their fronts use your images; vanilla geometry, lighting, frame edges, and back textures remain intact.

The mod is client-only. It does not upload your images to a server or change other players' paintings. It does not add new painting registry entries or item variants.

### Which image replaces which painting?

Assignment is deterministic, so reloading the same folder gives the same result:

- **Exact names take priority.** `kebab.png` replaces `minecraft:kebab`; `minecraft_kebab.png` also works. Matching is case-insensitive.
- Other images are sorted by filename and assigned to the remaining vanilla canvases alphabetically.
- If there are fewer images than remaining canvases, the images repeat. A single image can replace every vanilla painting.
- If all supplied images are exact matches, they also fill any unmatched canvases.
- Minecraft 26.3 has **51 vanilla painting canvases**. Additional images are still decoded and shown in Mod Menu, marked as having no available slot. Showing more than 51 different images simultaneously would require adding painting variants, which is outside this mod's vanilla-replacement scope.
- Only the vanilla `minecraft` namespace is changed. The painting `back` texture and other mods' painting textures are left alone.

Images are stretched to each canvas's exact dimensions using bicubic `Graphics2D` interpolation. Vanilla paintings use 16 pixels per block: a 1×1 painting is 16×16, a 2×1 painting is 32×16, and a 4×4 painting is 64×64. **64×64 is not the universal size for a 1×1 painting.** The loader reads the active atlas sprite dimensions, preserving compatibility with higher-resolution painting resource packs. Use source images with a similar aspect ratio to avoid distortion.

Invalid images are reported in the menu and skipped without replacing their targets. If the folder is empty or cannot be read, paintings remain vanilla. Removing images and reloading releases the old dynamic preview textures.

### Limits

To avoid unbounded memory use, each source image may be at most **32 MiB compressed / 16,777,216 decoded pixels**. A resized canvas may be at most **1,048,576 pixels**. Normal painting images are far below these limits. Images are decoded sequentially on resource-loading workers, never on the render thread.

## Mod Menu screen

`CustomPaintingsModMenu` implements `ModMenuApi#getModConfigScreenFactory()`. Its screen provides:

- A scrollable, keyboard-navigable list of every supported image file found.
- Thumbnails uploaded as dynamic textures.
- Target painting IDs, output dimensions, and unassigned/error status.
- Folder opening through 26.3's SDL-backed `Blaze3D.openPath` API (not the removed `Util.OS.openFile`).
- **Open Images Folder**, **Reload Paintings**, and **Done** controls.

The screen uses 26.3's `GuiGraphicsExtractor` and `RenderPipelines.GUI_TEXTURED`, not obsolete immediate-mode rendering calls.

## Rendering implementation

Registering a standalone texture does **not** automatically change a painting: vanilla painting renderers sample sprites from the painting atlas. This project therefore:

1. Hooks `SpriteLoader.stitch` only for the vanilla paintings atlas.
2. Scans the config folder and decodes/resizes images with `ImageIO` and `Graphics2D`.
3. Replaces `SpriteContents` pixels before stitching, retaining atlas UVs and vanilla rendering behavior.
4. Uses a current Fabric `ResourceLoader` reload listener, ordered after atlas/vanilla preparation, to register previews on the client thread.
5. Releases old preview texture IDs before registering the next set.

In official Minecraft 26.3 names, the native-image-backed texture class is **`net.minecraft.client.renderer.texture.DynamicTexture`**. **`NativeImageBackedTexture` was its Yarn name**, not a class available in the unobfuscated 26.3 game. This mod uses the official equivalent; adding a fake Yarn import or remapping the game would break the requested toolchain.

The resource API is `net.fabricmc.fabric.api.resource.v1.ResourceLoader`; the deprecated `ResourceManagerHelper` API is not used.

## Build locally

Install a full **JDK 25** (a JRE alone cannot compile the mod), then:

```bash
java -version
./gradlew build
```

On Windows:

```powershell
gradlew.bat build
```

Outputs:

```text
build/libs/custompaintings-1.0.0.jar
build/libs/custompaintings-1.0.0-sources.jar
```

Install the non-sources JAR. The Gradle wrapper is executable in Git and its JAR/distribution are checksum-pinned. The wrapper JAR matches Gradle's published SHA-256:

```text
497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7
```

The first build needs access to Fabric's Maven, Mojang's libraries, Maven Central, and TerraformersMC's Maven. Loom downloads Minecraft; this repository does not redistribute game code or assets.

For a development client, run `./gradlew runClient`. Its game directory is `run/`, with images in `run/config/custompaintings/images/`.

## Tests and CI

`./gradlew build` runs JUnit tests for PNG/JPG decoding, resizing, alpha preservation, malformed-file handling, size validation, deterministic assignment, exact-name overrides, and cycling behavior. These tests do not launch Minecraft or require a GPU.

The GitHub Actions workflow runs on pushes to `main`, session branches, pull requests, and manual dispatch. It uses the requested `actions/checkout@v4`, Temurin Java 25 through `actions/setup-java@v4`, `gradle/actions/setup-gradle@v4`, and `actions/upload-artifact@v4`. Download `custompaintings-jars` from a successful workflow run.

A successful compilation is not a substitute for an in-game smoke test. Before distributing a build, launch 26.3, add a PNG and JPG, reload, check Mod Menu previews, and place paintings of several aspect ratios.

## Project layout

```text
src/main/java/com/jay/custompaintings/
├── CustomPaintingsMod.java
├── client/
│   ├── CustomPaintingsClient.java
│   ├── CustomPaintingsModMenu.java
│   ├── CustomPaintingsScreen.java
│   ├── PaintingTextureManager.java
│   └── mixin/SpriteLoaderMixin.java
└── image/
    ├── ImageAssignment.java
    └── ImageProcessor.java
src/main/resources/
├── fabric.mod.json
├── custompaintings.mixins.json
└── assets/custompaintings/
    ├── icon.png
    └── lang/en_us.json
src/test/java/
.github/workflows/build.yml
```

## License

Mod source and the original icon: **MIT**, see `LICENSE`. The unmodified Gradle wrapper scripts/JAR are Gradle authors' Apache-2.0 work; their license is included in `LICENSE.gradle-wrapper`. Minecraft, Fabric API, and Mod Menu retain their respective licenses and are dependencies, not bundled libraries.
