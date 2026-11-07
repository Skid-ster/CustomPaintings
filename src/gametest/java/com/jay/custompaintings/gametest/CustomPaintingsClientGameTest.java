package com.jay.custompaintings.gametest;

import com.jay.custompaintings.client.CustomPaintingsClient;
import com.jay.custompaintings.image.ImageFit;
import com.jay.custompaintings.library.PaintingRuntime;
import com.jay.custompaintings.library.PaintingSizes;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.AtlasIds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import net.minecraft.world.level.block.Blocks;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Launches Minecraft and proves that an uploaded image reaches both atlases, a placed painting,
 * a relogged world, and a manual resource reload. This is the regression test for the v1.2.2
 * checkerboard bug: the old mixin compared atlas definition ids against SpriteLoader's sheet
 * texture ids, so no custom sprite was ever injected.
 */
public final class CustomPaintingsClientGameTest implements FabricClientGameTest {
    private static final int TIMEOUT_TICKS = 2400;

    @Override
    public void runTest(ClientGameTestContext context) {
        Path sourceImage = createTestImage(context);
        TestWorldSave save;
        UUID persistedPaintingId;

        try (TestSingleplayerContext world = context.worldBuilder()
                .adjustSettings(settings -> settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE))
                .create()) {
            save = world.getWorldSave();
            world.getConnection().waitForChunksRender();
            world.getServer().runCommand("time set day");
            world.getServer().runCommand("weather clear");
            context.waitFor(client -> CustomPaintingsClient.library().synced()
                    && CustomPaintingsClient.library().canEdit(), TIMEOUT_TICKS);

            upload(context, sourceImage);
            waitForLibrary(context, 1, 2, 2);
            assertSprites(context, 0, 2, 2);

            Painting placed = placePainting(world, 0, 2, 2);
            UUID placedId = placed.getUUID();
            // Paintings use noUpdateInterval() (Integer.MAX_VALUE), so waitForClientboundEntityUpdates
            // would wait essentially forever. Flush packets, then wait for the concrete entity instead.
            world.getConnection().waitForClientboundPackets();
            context.waitFor(client -> client.level.getEntity(placedId) instanceof Painting, TIMEOUT_TICKS);
            BlockPos paintingPos = context.computeOnClient(client -> client.level.getEntity(placedId).blockPosition());
            context.getInput().lookAt(paintingPos);
            context.waitTicks(20);
            assertScreenshotHasArtwork(context.takeScreenshot("custom-painting-placed"));

            resize(context, 0, 1, 1);
            waitForLibrary(context, 1, 1, 1);
            assertSprites(context, 0, 1, 1);
            context.waitFor(client -> client.level.getEntity(placedId) instanceof Painting painting
                    && painting.getVariant().unwrapKey()
                    .map(key -> key.identifier().equals(PaintingRuntime.variantId(0, 1, 1)))
                    .orElse(false), TIMEOUT_TICKS);

            remove(context, 0);
            waitForLibrary(context, 0, 0, 0);
            context.waitFor(client -> client.level.getEntity(placedId) == null, TIMEOUT_TICKS);
            context.waitFor(client -> CustomPaintingsClient.paintings().snapshot().loadedCount() == 0, TIMEOUT_TICKS);

            // Put the artwork back so the persisted/relogged path is tested too.
            upload(context, sourceImage);
            waitForLibrary(context, 1, 2, 2);
            assertSprites(context, 0, 2, 2);
            Painting reloggedPainting = placePainting(world, 0, 2, 2);
            persistedPaintingId = reloggedPainting.getUUID();
            world.getConnection().waitForClientboundPackets();
            context.waitFor(client -> client.level.getEntity(persistedPaintingId) instanceof Painting, TIMEOUT_TICKS);
            BlockPos reloggedPos = context.computeOnClient(client -> client.level.getEntity(persistedPaintingId).blockPosition());
            context.getInput().lookAt(reloggedPos);
            context.waitTicks(20);
        }

        try (TestSingleplayerContext reopened = save.open()) {
            reopened.getConnection().waitForChunksRender();
            context.waitFor(client -> CustomPaintingsClient.library().synced()
                    && CustomPaintingsClient.library().library().count() == 1, TIMEOUT_TICKS);
            context.waitFor(client -> client.level.getEntity(persistedPaintingId) instanceof Painting, TIMEOUT_TICKS);
            assertSprites(context, 0, 2, 2);
            assertScreenshotHasArtwork(context.takeScreenshot("custom-painting-relogged"));

            long generation = CustomPaintingsClient.paintings().snapshot().generation();
            context.runOnClient(Minecraft::reloadResourcePacks);
            context.waitFor(client -> CustomPaintingsClient.paintings().snapshot().generation() > generation
                    && hasSprites(client, 0, 2, 2), TIMEOUT_TICKS);
            assertSprites(context, 0, 2, 2);
            assertScreenshotHasArtwork(context.takeScreenshot("custom-painting-after-resource-reload"));

            // v1.3: auto-size from image proportions, preserve alpha, and exercise 8×8 + fit modes.
            // Remove the persisted 2×2 first so the next painting does not overlap it on the test wall.
            remove(context, 0);
            waitForLibrary(context, 0, 0, 0);
            context.waitFor(client -> client.level.getEntity(persistedPaintingId) == null, TIMEOUT_TICKS);
            Path wideAlpha = createWideAlphaTestImage(context);
            uploadAuto(context, wideAlpha);
            waitForEntry(context, 0, 1, 2, 1, ImageFit.FIT);
            assertSprites(context, 0, 2, 1);
            assertSpriteHasTransparentCenter(context, 0, 2, 1);
            Painting widePlaced = placePainting(reopened, 0, 2, 1);
            UUID wideId = widePlaced.getUUID();
            reopened.getConnection().waitForClientboundPackets();
            context.waitFor(client -> client.level.getEntity(wideId) instanceof Painting, TIMEOUT_TICKS);
            BlockPos widePos = context.computeOnClient(client -> client.level.getEntity(wideId).blockPosition());
            context.getInput().lookAt(widePos);
            context.waitTicks(20);
            assertScreenshotHasArtwork(context.takeScreenshot("custom-painting-wide-alpha"));

            adjust(context, 0, "Large Mural", 8, 8, ImageFit.FILL);
            waitForEntry(context, 0, 1, 8, 8, ImageFit.FILL);
            assertSprites(context, 0, 8, 8);
            context.waitFor(client -> client.level.getEntity(wideId) instanceof Painting painting
                    && painting.getVariant().unwrapKey()
                    .map(key -> key.identifier().equals(PaintingRuntime.variantId(0, 8, 8)))
                    .orElse(false), TIMEOUT_TICKS);
            context.waitTicks(20);
            assertScreenshotHasArtwork(context.takeScreenshot("custom-painting-8x8-fill"));

            // v1.4: stage edits across several paintings, prove nothing reloads/applies yet,
            // then apply the whole batch together and verify both slots update from one sync.
            upload(context, sourceImage);
            waitForEntry(context, 1, 2, 2, 2, ImageFit.FIT);
            assertSprites(context, 1, 2, 2);
            context.waitTicks(40);
            long generationBeforeDrafts = CustomPaintingsClient.paintings().snapshot().generation();
            long revisionBeforeDrafts = CustomPaintingsClient.library().library().revision();
            context.runOnClient(client -> {
                var library = CustomPaintingsClient.library().library();
                var drafts = CustomPaintingsClient.drafts();
                drafts.stage(library.get(0).orElseThrow().withName("Batch Mural")
                        .withSize(8, 8).withFit(ImageFit.STRETCH));
                drafts.stage(library.get(1).orElseThrow().withName("Batch Two")
                        .withSize(4, 2).withFit(ImageFit.FILL));
                if (drafts.changeCount(library) != 2) {
                    throw new AssertionError("Expected two staged painting edits");
                }
                drafts.stage(library.get(1).orElseThrow().withName("Discarded"));
                drafts.discardSlot(1);
                drafts.stage(library.get(1).orElseThrow().withName("Batch Two")
                        .withSize(4, 2).withFit(ImageFit.FILL));
            });
            context.waitTicks(20);
            context.runOnClient(client -> {
                if (CustomPaintingsClient.paintings().snapshot().generation() != generationBeforeDrafts) {
                    throw new AssertionError("Staging drafts must not reload client textures");
                }
                var library = CustomPaintingsClient.library().library();
                if (library.revision() != revisionBeforeDrafts) {
                    throw new AssertionError("Staging drafts must not change the authoritative revision");
                }
                if (library.get(0).orElseThrow().fit() != ImageFit.FILL
                        || library.get(1).orElseThrow().width() != 2) {
                    throw new AssertionError("Staging drafts must not mutate the applied library");
                }
            });
            context.runOnClient(client -> {
                if (!CustomPaintingsClient.imports().applyDrafts()) {
                    throw new AssertionError("Apply batch did not start");
                }
            });
            waitForEntry(context, 0, 2, 8, 8, ImageFit.STRETCH);
            waitForEntry(context, 1, 2, 4, 2, ImageFit.FILL);
            assertSprites(context, 0, 8, 8);
            assertSprites(context, 1, 4, 2);
            context.runOnClient(client -> {
                if (CustomPaintingsClient.paintings().snapshot().generation() != generationBeforeDrafts + 1) {
                    throw new AssertionError("Apply batch should coalesce into exactly one client atlas reload");
                }
                if (CustomPaintingsClient.library().library().revision() != revisionBeforeDrafts + 1) {
                    throw new AssertionError("Apply batch should save exactly one authoritative revision");
                }
                if (CustomPaintingsClient.drafts().hasChanges(CustomPaintingsClient.library().library())) {
                    throw new AssertionError("Successful Apply should clear staged drafts");
                }
            });
            context.waitFor(client -> client.level.getEntity(wideId) instanceof Painting painting
                    && painting.getVariant().unwrapKey()
                    .map(key -> key.identifier().equals(PaintingRuntime.variantId(0, 8, 8)))
                    .orElse(false), TIMEOUT_TICKS);
            context.waitTicks(20);
            assertScreenshotHasArtwork(context.takeScreenshot("custom-painting-batch-apply"));
        }
    }

    private static Path createTestImage(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            Path file = client.gameDirectory.toPath().resolve("custompaintings-gametest.png");
            BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(Color.RED);
                graphics.fillRect(0, 0, 256, 256);
                graphics.setColor(Color.GREEN);
                graphics.fillRect(256, 0, 256, 256);
                graphics.setColor(Color.BLUE);
                graphics.fillRect(0, 256, 256, 256);
                graphics.setColor(Color.YELLOW);
                graphics.fillRect(256, 256, 256, 256);
            } finally {
                graphics.dispose();
            }
            try {
                ImageIO.write(image, "png", file.toFile());
            } catch (IOException exception) {
                throw new UncheckedIOException("Could not write the generated test artwork", exception);
            }
            return file;
        });
    }

    private static Path createWideAlphaTestImage(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            Path file = client.gameDirectory.toPath().resolve("custompaintings-gametest-wide-alpha.png");
            BufferedImage image = new BufferedImage(512, 256, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(Color.RED);
                graphics.fillRect(0, 0, 512, 256);
                graphics.setComposite(java.awt.AlphaComposite.Clear);
                graphics.fillRect(128, 64, 256, 128);
            } finally {
                graphics.dispose();
            }
            try {
                ImageIO.write(image, "png", file.toFile());
            } catch (IOException exception) {
                throw new UncheckedIOException("Could not write the generated wide alpha artwork", exception);
            }
            return file;
        });
    }

    private static void upload(ClientGameTestContext context, Path file) {
        context.runOnClient(client -> {
            if (!CustomPaintingsClient.imports().importImages(List.of(file), PaintingSizes.DEFAULT)) {
                throw new AssertionError("Upload did not start; the singleplayer owner should be able to edit");
            }
        });
    }

    private static void uploadAuto(ClientGameTestContext context, Path file) {
        context.runOnClient(client -> {
            if (!CustomPaintingsClient.imports().importImages(List.of(file))) {
                throw new AssertionError("Auto-sized upload did not start");
            }
        });
    }

    private static void adjust(ClientGameTestContext context, int slot, String name, int width, int height, ImageFit fit) {
        context.runOnClient(client -> {
            if (!CustomPaintingsClient.imports().adjust(slot, name, PaintingSizes.of(width, height), fit)) {
                throw new AssertionError("Adjust did not start");
            }
        });
    }

    private static void resize(ClientGameTestContext context, int slot, int width, int height) {
        context.runOnClient(client -> {
            if (!CustomPaintingsClient.imports().resize(slot, PaintingSizes.of(width, height))) {
                throw new AssertionError("Resize did not start");
            }
        });
    }

    private static void remove(ClientGameTestContext context, int slot) {
        context.runOnClient(client -> {
            if (!CustomPaintingsClient.imports().remove(slot)) {
                throw new AssertionError("Remove did not start");
            }
        });
    }

    private static void waitForLibrary(ClientGameTestContext context, int count, int width, int height) {
        context.waitFor(client -> {
            var library = CustomPaintingsClient.library();
            if (!library.synced() || library.library().count() != count || CustomPaintingsClient.imports().state().busy()) {
                return false;
            }
            return count == 0 || library.library().get(0)
                    .map(entry -> entry.width() == width && entry.height() == height)
                    .orElse(false);
        }, TIMEOUT_TICKS);
    }

    private static void waitForEntry(ClientGameTestContext context, int slot, int count, int width, int height, ImageFit fit) {
        context.waitFor(client -> {
            var library = CustomPaintingsClient.library();
            if (!library.synced() || library.library().count() != count || CustomPaintingsClient.imports().state().busy()) {
                return false;
            }
            return library.library().get(slot)
                    .map(entry -> entry.width() == width && entry.height() == height && entry.fit() == fit)
                    .orElse(false);
        }, TIMEOUT_TICKS);
    }

    private static void assertSpriteHasTransparentCenter(ClientGameTestContext context, int slot, int width, int height) {
        context.runOnClient(client -> {
            var paintings = client.getAtlasManager().getAtlasOrThrow(AtlasIds.PAINTINGS);
            var sprite = paintings.getSprite(PaintingRuntime.variantAssetId(slot, width, height));
            if (!sprite.contents().isTransparent(0, width * 128, height * 128)) {
                throw new AssertionError("Custom painting sprite lost its transparent center");
            }
        });
    }

    private static void assertSprites(ClientGameTestContext context, int slot, int width, int height) {
        context.waitFor(client -> hasSprites(client, slot, width, height), TIMEOUT_TICKS);
        context.runOnClient(client -> {
            String error = CustomPaintingsClient.paintings().snapshot().error();
            if (error != null) {
                throw new AssertionError("Custom painting texture verification failed: " + error);
            }
            var atlases = client.getAtlasManager();
            var paintings = atlases.getAtlasOrThrow(AtlasIds.PAINTINGS);
            var items = atlases.getAtlasOrThrow(AtlasIds.ITEMS);
            if (paintings.getSprite(PaintingRuntime.variantAssetId(slot, width, height)) == paintings.missingSprite()) {
                throw new AssertionError("Painting atlas is missing " + PaintingRuntime.variantAssetId(slot, width, height));
            }
            if (items.getSprite(PaintingRuntime.itemTextureId(slot)) == items.missingSprite()) {
                throw new AssertionError("Item atlas is missing " + PaintingRuntime.itemTextureId(slot));
            }
        });
    }

    private static boolean hasSprites(Minecraft client, int slot, int width, int height) {
        var atlases = client.getAtlasManager();
        var paintings = atlases.getAtlasOrThrow(AtlasIds.PAINTINGS);
        var items = atlases.getAtlasOrThrow(AtlasIds.ITEMS);
        return paintings.getSprite(PaintingRuntime.variantAssetId(slot, width, height)) != paintings.missingSprite()
                && items.getSprite(PaintingRuntime.itemTextureId(slot)) != items.missingSprite();
    }

    private static Painting placePainting(TestSingleplayerContext world, int slot, int width, int height) {
        return world.getServer().computeOnServer(server -> {
            ServerPlayer player = world.getConnection().getServerPlayer();
            ServerLevel level = player.level();
            BlockPos wall = player.blockPosition().offset(-10, 0, -12);
            for (int x = 0; x < 20; x++) {
                for (int y = 0; y < 20; y++) {
                    level.setBlock(wall.offset(x, y, 0), Blocks.STONE.defaultBlockState(), 3);
                }
            }
            Holder<PaintingVariant> variant = level.registryAccess()
                    .lookupOrThrow(Registries.PAINTING_VARIANT)
                    .get(PaintingRuntime.variantKey(slot, width, height))
                    .orElseThrow(() -> new AssertionError("Custom painting variant is not registered"));
            Painting painting = new Painting(level, wall.offset(10, 10, 1), Direction.SOUTH, variant);
            if (!painting.survives()) {
                throw new AssertionError("Test painting could not be placed on the generated wall");
            }
            if (!level.addFreshEntity(painting)) {
                throw new AssertionError("Test painting was not added to the world");
            }
            return painting;
        });
    }

    /** The test artwork is red/green/blue/yellow; Minecraft's missing texture is magenta/black. */
    private static void assertScreenshotHasArtwork(Path screenshot) {
        try (InputStream input = Files.newInputStream(screenshot); NativeImage image = NativeImage.read(input)) {
            int artworkPixels = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int pixel = image.getPixel(x, y);
                    int red = pixel >> 16 & 255;
                    int green = pixel >> 8 & 255;
                    int blue = pixel & 255;
                    if (red > 90 && red > green * 1.5 && red > blue * 1.5) {
                        artworkPixels++;
                    } else if (green > 90 && green > red * 1.5 && green > blue * 1.5) {
                        artworkPixels++;
                    } else if (blue > 90 && blue > red * 1.5 && blue > green * 1.5) {
                        artworkPixels++;
                    }
                }
            }
            if (artworkPixels < 100) {
                throw new AssertionError("Screenshot did not contain enough uploaded artwork pixels: " + artworkPixels);
            }
        } catch (IOException exception) {
            throw new AssertionError("Could not read screenshot " + screenshot, exception);
        }
    }
}
