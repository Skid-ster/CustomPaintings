# Custom Paintings

A client-side Fabric mod for **Minecraft Java 26.3** that replaces vanilla painting canvases with your own PNG/JPG images. **Import images from inside the running game through Mod Menu**: use the native multi-file picker or drag files onto the config screen. The mod validates and saves them locally, refreshes previews, and applies the paintings automatically—no manual folder copying or game restart required.

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
2. Put Fabric API **0.161.0+26.3**, Mod Menu **21.0.0**, and `custompaintings-1.1.0.jar` into your instance's `mods` folder.
3. Launch the game, open **Mods → Custom Paintings → Configure**, and choose **Upload Images…**.
4. Select one or more `.png`, `.jpg`, or `.jpeg` files in the native file picker. Alternatively, drag them from your desktop onto the Custom Paintings screen.
5. Wait for the import and automatic resource reload to finish. The screen shows thumbnails and assignment details; **Details** reports saved filenames, duplicates, and per-file errors.
6. Place paintings in the world. Their fronts use your images; vanilla geometry, lighting, frame edges, and back textures remain intact.

“Upload” means **copying a local file into this instance's mod folder**. Nothing is uploaded to Minecraft servers, multiplayer hosts, or the internet, and other players do not see your custom images. The mod is client-only and does not add painting registry entries or item variants.

The managed folder is:

```text
.minecraft/config/custompaintings/images/
```

Launcher-specific instances use their own game directory; the config screen displays the exact path. **Open Folder** is still available for bulk management, and **F3 + T** still reloads paintings. Only image files directly inside the folder are scanned; subfolders and symbolic links are not followed.

### In-game import safety

- Native SDL3 multi-file picker matches Minecraft 26.3's windowing backend; no Swing/AWT dialog is mixed into the game loop.
- Drag-and-drop uses 26.3's `Screen.onFilesDrop` API.
- Up to **64 files per batch**. Copying and image validation run sequentially on an IO worker, not the render thread.
- PNG alpha is preserved. The actual file contents must be PNG/JPEG and match the extension; corrupt, empty, oversized, folder, and symlink selections are rejected.
- Existing images are never silently overwritten. Identical content under the same name is skipped; different content gets a suffix such as `holiday-1.png`.
- Portable filename sanitization prevents path traversal and Windows reserved-name problems. A hidden `.imports` staging folder keeps incomplete files out of the painting library.
- One import/reload runs at a time. Closing the screen does not abort an active import; reopening shows its progress/result.
- Select a loaded image and choose **Remove** to delete only the mod's managed copy, with confirmation. Your original source file is untouched.

If the operating system cannot provide a native picker, the screen reports it and drag-and-drop remains available. Linux picker support depends on the desktop's SDL/portal backend.

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

- **Upload Images…** native multi-file selection and direct drag-and-drop importing.
- A scrollable, keyboard-navigable list of every supported image file found, with dynamic-texture thumbnails.
- Target painting IDs, output dimensions, and unassigned/error status.
- Live import/reload progress and a scrollable per-file **Details** report.
- Confirmed **Remove** action for a selected managed image.
- Folder opening through 26.3's SDL-backed `Blaze3D.openPath` API (not the removed `Util.OS.openFile`).
- Responsive controls for small GUI sizes, translated labels/tooltips, and **Reload** / **Done** controls.

The screen uses 26.3's `GuiGraphicsExtractor` and `RenderPipelines.GUI_TEXTURED`, not obsolete immediate-mode rendering calls. Native picker callbacks copy SDL's temporary path list immediately, marshal results back to the client thread, and release callback/filter allocations after completion.

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
build/libs/custompaintings-1.1.0.jar
build/libs/custompaintings-1.1.0-sources.jar
```

Install the non-sources JAR. The Gradle wrapper is executable in Git and its JAR/distribution are checksum-pinned. The wrapper JAR matches Gradle's published SHA-256:

```text
497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7
```

The first build needs access to Fabric's Maven, Mojang's libraries, Maven Central, and TerraformersMC's Maven. Loom downloads Minecraft; this repository does not redistribute game code or assets.

For a development client, run `./gradlew runClient`. Its game directory is `run/`, with images in `run/config/custompaintings/images/`.

## Tests and CI

`./gradlew build` runs JUnit tests for PNG/JPG decoding, format/extension validation, resizing, alpha preservation, malformed-file handling, size validation, deterministic assignment, exact-name overrides, and cycling behavior. Importer tests cover byte-preserving copies, duplicate skipping, collision suffixes, rejected folders/symlinks, batch limits, progress, portable filenames, and staging cleanup. These tests do not launch Minecraft or require a GPU.

The GitHub Actions workflow runs on pushes to `main`, session branches, version tags (`v*`), pull requests, and manual dispatch. It uses the requested `actions/checkout@v4`, Temurin Java 25 through `actions/setup-java@v4`, `gradle/actions/setup-gradle@v4`, and `actions/upload-artifact@v4`. Download `custompaintings-jars` from a successful workflow run for temporary CI artifacts.

### GitHub Releases

Permanent JAR releases are published to the repository's [GitHub Releases](https://github.com/Skid-ster/CustomPaintings/releases) page, not only as short-lived CI artifacts. After a successful build, the release job runs when any of these is true:

- the build is a push to `main` (including a merged pull request);
- a version tag such as `v1.0.0` is pushed, and the tag matches `mod_version` in `gradle.properties`; or
- the workflow is manually dispatched on `main` with `publish_release` set to `true`.

The release attaches `custompaintings-<version>.jar` and `custompaintings-<version>-sources.jar`, with SHA-256 checksums in the release notes. Install the non-sources JAR.

Existing releases are never overwritten. If `v<version>` already exists, automatic publication skips that version; bump `mod_version` and merge the change into `main` to publish the next release. Session-branch pushes and pull requests still build and test the mod, but do not create releases.

A successful compilation is not a substitute for an in-game smoke test. Before distributing a build, launch 26.3, import a transparent PNG and a JPG through both the picker and drag-and-drop, check Details and Mod Menu previews, cancel/reopen the picker, remove a managed copy, and place paintings of several aspect ratios. Test the native picker on each target desktop OS.

## Project layout

```text
src/main/java/com/jay/custompaintings/
├── CustomPaintingsMod.java
├── client/
│   ├── CustomPaintingsClient.java
│   ├── CustomPaintingsModMenu.java
│   ├── CustomPaintingsScreen.java
│   ├── ImageFileChooser.java
│   ├── ImportDetailsScreen.java
│   ├── PaintingImportService.java
│   ├── PaintingTextureManager.java
│   └── mixin/SpriteLoaderMixin.java
└── image/
    ├── ImageAssignment.java
    ├── ImageImporter.java
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
