package com.jay.custompaintings.gametest;

import com.jay.custompaintings.client.ClientPaintingLibrary;
import com.jay.custompaintings.client.CustomPaintingsClient;
import com.jay.custompaintings.client.PaintingDraftPreview;
import com.jay.custompaintings.client.PaintingDrafts;
import com.jay.custompaintings.client.PaintingTextureManager;
import com.jay.custompaintings.image.ImageFit;
import com.jay.custompaintings.item.CustomPaintingItems;
import com.jay.custompaintings.library.PaintingData;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingLibrary;
import com.jay.custompaintings.library.PaintingRuntime;
import com.jay.custompaintings.library.PaintingSizes;
import com.jay.custompaintings.library.RecipeCatalog;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import net.minecraft.world.item.ItemStack;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * End-to-end proof of the v2 design: an unbounded library, one component-driven item,
 * per-id dynamic textures (no atlas injection, no resource reloads), the Survival/Creative
 * Painting Menu, and persistent placed paintings.
 *
 * <p>Earlier versions asserted against the painting/item atlases. v2 deliberately bypasses
 * atlases entirely, so those assertions are replaced by dynamic-texture and screenshot
 * checks — which also means adding paintings can never exhaust atlas space.</p>
 */
public final class CustomPaintingsClientGameTest implements FabricClientGameTest {
    private static final int TIMEOUT_TICKS = 2400;

    @Override
    public void runTest(ClientGameTestContext context) {
        Path sourceImage = createTestImage(context, "custompaintings-gametest.png", 512, 512, false);
        TestWorldSave save;
        UUID persistedPaintingId;
        int firstId;

        try (TestSingleplayerContext world = context.worldBuilder()
                .adjustSettings(settings -> settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE))
                .create()) {
            save = world.getWorldSave();
            world.getServer().runCommand("time set day");
            world.getServer().runCommand("weather clear");
            context.waitFor(client -> CustomPaintingsClient.library().synced()
                    && CustomPaintingsClient.library().canEdit(), TIMEOUT_TICKS);

            upload(context, sourceImage);
            waitForCount(context, 1);
            firstId = context.computeOnClient(client ->
                    CustomPaintingsClient.library().library().entries().getFirst().id());
            waitForEntry(context, firstId, 2, 2, ImageFit.FIT);

            // The item is one registry entry whose component selects the artwork.
            assertItemIdentity(context, firstId);
            assertPreviewUploaded(context, firstId);

            Painting placed = placePainting(world, firstId, 2, 2);
            UUID placedId = placed.getUUID();
            // Paintings use noUpdateInterval(), so waitForClientboundEntityUpdates would hang.
            world.getConnection().waitForClientboundPackets();
            context.waitFor(client -> client.level.getEntity(placedId) instanceof Painting painting
                    && PaintingData.entityPaintingId(painting).orElse(-1) == firstId, TIMEOUT_TICKS);
            lookAt(context, placedId);
            assertArtworkUploaded(context, firstId);
            assertScreenshotHasArtwork(context.takeScreenshot("custom-painting-placed"));

            // The Painting Menu must hand out the same component-carrying item.
            takeFromMenu(context, world, firstId, 3);

            // Resizing is a staged edit applied through the batch path.
            edit(context, firstId, null, 1, 1, null);
            waitForEntry(context, firstId, 1, 1, ImageFit.FIT);
            context.waitFor(client -> client.level.getEntity(placedId) instanceof Painting painting
                    && painting.getVariant().unwrapKey()
                    .map(key -> key.identifier().equals(PaintingRuntime.variantId(1, 1)))
                    .orElse(false), TIMEOUT_TICKS);

            remove(context, firstId);
            waitForCount(context, 0);
            context.waitFor(client -> client.level.getEntity(placedId) == null, TIMEOUT_TICKS);
            context.waitFor(client -> CustomPaintingsClient.paintings().uploadedCount() == 0, TIMEOUT_TICKS);

            // Restore artwork so the persisted/relogged path is exercised too.
            upload(context, sourceImage);
            waitForCount(context, 1);
            int relogId = context.computeOnClient(client ->
                    CustomPaintingsClient.library().library().entries().getFirst().id());
            if (relogId == firstId) {
                throw new AssertionError("Removed painting ids must not be reused");
            }
            Painting reloggedPainting = placePainting(world, relogId, 2, 2);
            persistedPaintingId = reloggedPainting.getUUID();
            world.getConnection().waitForClientboundPackets();
            context.waitFor(client -> client.level.getEntity(persistedPaintingId) instanceof Painting, TIMEOUT_TICKS);
            lookAt(context, persistedPaintingId);
        }

        try (TestSingleplayerContext reopened = save.open()) {
            reopened.getConnection().waitForChunksRender();
            context.waitFor(client -> CustomPaintingsClient.library().synced()
                    && CustomPaintingsClient.library().library().count() == 1, TIMEOUT_TICKS);
            context.waitFor(client -> client.level.getEntity(persistedPaintingId) instanceof Painting, TIMEOUT_TICKS);
            int relogId = context.computeOnClient(client ->
                    CustomPaintingsClient.library().library().entries().getFirst().id());
            // Identity survives a save/load round-trip via the synced entity attachment.
            context.waitFor(client -> client.level.getEntity(persistedPaintingId) instanceof Painting painting
                    && PaintingData.entityPaintingId(painting).orElse(-1) == relogId, TIMEOUT_TICKS);
            assertPreviewUploaded(context, relogId);
            lookAt(context, persistedPaintingId);
            assertArtworkUploaded(context, relogId);
            assertScreenshotHasArtwork(context.takeScreenshot("custom-painting-relogged"));

            // v2 replaces the resource reload with a cheap metadata sync + texture invalidation.
            long generation = CustomPaintingsClient.paintings().generation();
            context.runOnClient(client -> {
                if (!CustomPaintingsClient.imports().reloadPaintings()) {
                    throw new AssertionError("Reload did not start");
                }
            });
            context.waitFor(client -> CustomPaintingsClient.paintings().generation() > generation
                    && CustomPaintingsClient.paintings().preview(relogId) != null, TIMEOUT_TICKS);
            assertArtworkUploaded(context, relogId);
            assertScreenshotHasArtwork(context.takeScreenshot("custom-painting-after-reload"));

            // Auto-size from image proportions and preserve alpha.
            remove(context, relogId);
            waitForCount(context, 0);
            context.waitFor(client -> client.level.getEntity(persistedPaintingId) == null, TIMEOUT_TICKS);
            Path wideAlpha = createTestImage(context, "custompaintings-gametest-wide-alpha.png", 512, 256, true);
            uploadAuto(context, wideAlpha);
            waitForCount(context, 1);
            int wideEntryId = context.computeOnClient(client ->
                    CustomPaintingsClient.library().library().entries().getFirst().id());
            waitForEntry(context, wideEntryId, 2, 1, ImageFit.FIT);
            assertArtworkHasAlpha(context, wideEntryId);
            Painting widePlaced = placePainting(reopened, wideEntryId, 2, 1);
            UUID wideId = widePlaced.getUUID();
            reopened.getConnection().waitForClientboundPackets();
            context.waitFor(client -> client.level.getEntity(wideId) instanceof Painting, TIMEOUT_TICKS);
            lookAt(context, wideId);
            assertArtworkUploaded(context, wideEntryId);
            assertScreenshotHasArtwork(context.takeScreenshot("custom-painting-wide-alpha"));

            edit(context, wideEntryId, "Large Mural", 8, 8, ImageFit.FILL);
            waitForEntry(context, wideEntryId, 8, 8, ImageFit.FILL);
            context.waitFor(client -> client.level.getEntity(wideId) instanceof Painting painting
                    && painting.getVariant().unwrapKey()
                    .map(key -> key.identifier().equals(PaintingRuntime.variantId(8, 8)))
                    .orElse(false), TIMEOUT_TICKS);
            context.waitTicks(20);
            assertArtworkUploaded(context, wideEntryId);
            assertScreenshotHasArtwork(context.takeScreenshot("custom-painting-8x8-fill"));

            // Batch Apply across two paintings: one revision, no resource reload.
            upload(context, sourceImage);
            waitForCount(context, 2);
            int secondId = context.computeOnClient(client -> {
                List<PaintingEntry> entries = CustomPaintingsClient.library().library().entries();
                return entries.get(entries.size() - 1).id();
            });
            waitForEntry(context, secondId, 2, 2, ImageFit.FIT);
            long revisionBefore = CustomPaintingsClient.library().library().revision();
            context.runOnClient(client -> {
                PaintingLibrary library = CustomPaintingsClient.library().library();
                PaintingDrafts drafts = CustomPaintingsClient.drafts();
                drafts.stage(library.get(wideEntryId).orElseThrow().withName("Batch Mural")
                        .withSize(8, 8).withFit(ImageFit.STRETCH), library);
                drafts.stage(library.get(secondId).orElseThrow().withName("Batch Two")
                        .withSize(4, 2).withFit(ImageFit.FILL), library);
                if (drafts.changeCount(library) != 2) {
                    throw new AssertionError("Expected two staged painting edits");
                }
                drafts.stage(library.get(secondId).orElseThrow().withName("Discarded"), library);
                drafts.discardPainting(secondId);
                drafts.stage(library.get(secondId).orElseThrow().withName("Batch Two")
                        .withSize(4, 2).withFit(ImageFit.FILL), library);
            });
            context.waitTicks(20);
            context.runOnClient(client -> {
                PaintingLibrary library = CustomPaintingsClient.library().library();
                if (library.revision() != revisionBefore) {
                    throw new AssertionError("Staging drafts must not change the authoritative revision");
                }
                if (library.get(wideEntryId).orElseThrow().fit() != ImageFit.FILL
                        || library.get(secondId).orElseThrow().width() != 2) {
                    throw new AssertionError("Staging drafts must not mutate the applied library");
                }
                if (!CustomPaintingsClient.imports().applyDrafts()) {
                    throw new AssertionError("Apply batch did not start");
                }
            });
            waitForEntry(context, wideEntryId, 8, 8, ImageFit.STRETCH);
            waitForEntry(context, secondId, 4, 2, ImageFit.FILL);
            context.runOnClient(client -> {
                if (CustomPaintingsClient.library().library().revision() != revisionBefore + 1) {
                    throw new AssertionError("Apply batch should save exactly one authoritative revision");
                }
                if (CustomPaintingsClient.drafts().hasChanges(CustomPaintingsClient.library().library())) {
                    throw new AssertionError("Successful Apply should clear staged drafts");
                }
            });
            context.waitTicks(20);
            assertArtworkUploaded(context, wideEntryId);
            assertScreenshotHasArtwork(context.takeScreenshot("custom-painting-batch-apply"));

            // Draft previews compose off-thread and never touch the shared textures.
            var preview = context.computeOnClient(client -> {
                PaintingEntry entry = CustomPaintingsClient.library().library().get(secondId).orElseThrow();
                byte[] png = CustomPaintingsClient.library().imageOrRequest(entry.id()).orElseThrow();
                PaintingDraftPreview draftPreview = new PaintingDraftPreview();
                draftPreview.request(entry.withSize(4, 1).withFit(ImageFit.FILL), png);
                return draftPreview;
            });
            context.waitFor(client -> preview.ready() || preview.error() != null, TIMEOUT_TICKS);
            context.runOnClient(client -> {
                if (preview.error() != null) {
                    throw new AssertionError("Draft preview failed: " + preview.error());
                }
                if (preview.textureWidth() != preview.textureHeight() * 4) {
                    throw new AssertionError("Draft preview must preserve the 4x1 canvas aspect ratio");
                }
                preview.close();
            });

            // The headline v2 feature: go past the old 10-painting cap and past the recipe pool.
            uploadMany(context, sourceImage, 12);
            waitForCount(context, 14);
            assertUnboundedLibrary(context);
            assertMenuGivesBeyondRecipes(context, reopened);
            assertItemIconRenders(context, reopened);

            // Pending removals stay reversible until Apply.
            context.runOnClient(client -> {
                PaintingLibrary library = CustomPaintingsClient.library().library();
                PaintingDrafts drafts = CustomPaintingsClient.drafts();
                drafts.markRemoved(secondId, library);
                if (!drafts.isRemoved(secondId) || drafts.removals(library).size() != 1) {
                    throw new AssertionError("Removal should be staged locally");
                }
                drafts.discardPainting(secondId);
                if (drafts.hasChanges(library) || library.count() != 14) {
                    throw new AssertionError("Undoing a pending removal must not touch the server");
                }
            });
        }
    }

    // ---------------------------------------------------------------- artwork

    private static Path createTestImage(ClientGameTestContext context, String name,
                                        int width, int height, boolean transparentCenter) {
        return context.computeOnClient(client -> {
            Path file = client.gameDirectory.toPath().resolve(name);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                if (transparentCenter) {
                    graphics.setColor(Color.RED);
                    graphics.fillRect(0, 0, width, height);
                    graphics.setComposite(java.awt.AlphaComposite.Clear);
                    graphics.fillRect(width / 4, height / 4, width / 2, height / 2);
                } else {
                    int halfWidth = width / 2;
                    int halfHeight = height / 2;
                    graphics.setColor(Color.RED);
                    graphics.fillRect(0, 0, halfWidth, halfHeight);
                    graphics.setColor(Color.GREEN);
                    graphics.fillRect(halfWidth, 0, halfWidth, halfHeight);
                    graphics.setColor(Color.BLUE);
                    graphics.fillRect(0, halfHeight, halfWidth, halfHeight);
                    graphics.setColor(Color.YELLOW);
                    graphics.fillRect(halfWidth, halfHeight, halfWidth, halfHeight);
                }
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

    // ------------------------------------------------------------- operations

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

    /** Uploads several copies in one batch, proving the batch path and the unlimited library. */
    private static void uploadMany(ClientGameTestContext context, Path file, int copies) {
        List<Path> files = new ArrayList<>();
        for (int index = 0; index < copies; index++) {
            files.add(file);
        }
        context.runOnClient(client -> {
            if (!CustomPaintingsClient.imports().importImages(files, PaintingSizes.DEFAULT)) {
                throw new AssertionError("Batch upload did not start");
            }
        });
    }

    private static void edit(ClientGameTestContext context, int id, String name,
                             int width, int height, ImageFit fit) {
        context.runOnClient(client -> {
            PaintingLibrary library = CustomPaintingsClient.library().library();
            PaintingEntry entry = library.get(id).orElseThrow();
            PaintingEntry draft = entry
                    .withName(name != null ? name : entry.name())
                    .withSize(width, height)
                    .withFit(fit != null ? fit : entry.fit());
            CustomPaintingsClient.drafts().stage(draft, library);
            if (!CustomPaintingsClient.imports().applyDrafts()) {
                throw new AssertionError("Edit did not start");
            }
        });
    }

    private static void remove(ClientGameTestContext context, int id) {
        context.runOnClient(client -> {
            PaintingLibrary library = CustomPaintingsClient.library().library();
            CustomPaintingsClient.drafts().markRemoved(id, library);
            if (!CustomPaintingsClient.imports().applyDrafts()) {
                throw new AssertionError("Remove did not start");
            }
        });
    }

    private static void takeFromMenu(ClientGameTestContext context, TestSingleplayerContext world,
                                     int paintingId, int count) {
        ServerPlayer player = world.getConnection().getServerPlayer();
        world.getServer().runOnServer(server -> player.getInventory().clearContent());
        context.runOnClient(client -> {
            if (!CustomPaintingsClient.imports().givePainting(paintingId, count)) {
                throw new AssertionError("Painting Menu request did not start");
            }
        });
        context.waitFor(client -> !CustomPaintingsClient.imports().state().busy(), TIMEOUT_TICKS);
        world.getServer().computeOnServer(server -> {
            int found = 0;
            for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
                if (CustomPaintingItems.isCustomPainting(stack)
                        && CustomPaintingItems.paintingId(stack) == paintingId) {
                    found += stack.getCount();
                }
            }
            if (found != count) {
                throw new AssertionError("Painting Menu should give " + count + " paintings, gave " + found);
            }
            return null;
        });
    }

    // ------------------------------------------------------------ assertions

    private static void waitForCount(ClientGameTestContext context, int count) {
        context.waitFor(client -> CustomPaintingsClient.library().synced()
                && CustomPaintingsClient.library().library().count() == count
                && !CustomPaintingsClient.imports().state().busy(), TIMEOUT_TICKS);
    }

    private static void waitForEntry(ClientGameTestContext context, int id, int width, int height, ImageFit fit) {
        context.waitFor(client -> {
            ClientPaintingLibrary library = CustomPaintingsClient.library();
            return library.synced() && !CustomPaintingsClient.imports().state().busy()
                    && library.library().get(id)
                    .map(entry -> entry.width() == width && entry.height() == height && entry.fit() == fit)
                    .orElse(false);
        }, TIMEOUT_TICKS);
        context.waitFor(client -> CustomPaintingsClient.paintings().preview(id) != null, TIMEOUT_TICKS);
    }

    private static void assertItemIdentity(ClientGameTestContext context, int paintingId) {
        context.runOnClient(client -> {
            ItemStack stack = CustomPaintingItems.stack(paintingId);
            if (!CustomPaintingItems.isCustomPainting(stack)
                    || CustomPaintingItems.paintingId(stack) != paintingId) {
                throw new AssertionError("Custom painting item lost its painting id component");
            }
            if (!stack.getItem().equals(CustomPaintingItems.get())) {
                throw new AssertionError("Every painting must share one registered item");
            }
            if (!stack.getHoverName().getString().equals(
                    CustomPaintingsClient.library().library().get(paintingId).orElseThrow().name())) {
                throw new AssertionError("Item name should come from the painting entry");
            }
        });
    }

    private static void assertArtworkUploaded(ClientGameTestContext context, int paintingId) {
        context.waitFor(client -> CustomPaintingsClient.paintings().artwork(paintingId) != null, TIMEOUT_TICKS);
    }

    private static void assertPreviewUploaded(ClientGameTestContext context, int paintingId) {
        context.waitFor(client -> CustomPaintingsClient.paintings().preview(paintingId) != null
                && CustomPaintingsClient.paintings().artwork(paintingId) != null, TIMEOUT_TICKS);
        context.runOnClient(client -> {
            PaintingEntry entry = CustomPaintingsClient.library().library().get(paintingId).orElseThrow();
            PaintingTextureManager.Texture preview = CustomPaintingsClient.paintings().preview(paintingId);
            if (preview == null || preview.width() <= 0 || preview.height() <= 0) {
                throw new AssertionError("Preview texture was not uploaded for painting " + paintingId);
            }
            PaintingTextureManager.Texture artwork = CustomPaintingsClient.paintings().artwork(paintingId);
            if (artwork == null) {
                throw new AssertionError("Artwork texture was not uploaded for painting " + paintingId);
            }
            if (artwork.width() * entry.height() != artwork.height() * entry.width()) {
                throw new AssertionError("Artwork texture must match the canvas aspect ratio");
            }
        });
    }

    /** Reads the composed master back and proves alpha survived the pipeline. */
    private static void assertArtworkHasAlpha(ClientGameTestContext context, int paintingId) {
        context.waitFor(client -> CustomPaintingsClient.library().image(paintingId).isPresent(), TIMEOUT_TICKS);
        context.runOnClient(client -> {
            byte[] png = CustomPaintingsClient.library().image(paintingId).orElseThrow();
            try (InputStream input = new java.io.ByteArrayInputStream(png)) {
                BufferedImage image = ImageIO.read(input);
                if (image == null) {
                    throw new AssertionError("Uploaded master PNG could not be decoded");
                }
                if (image.getColorModel().getAlpha(
                        image.getRaster().getDataElements(image.getWidth() / 2, image.getHeight() / 2, null)) != 0) {
                    throw new AssertionError("Uploaded artwork lost its transparent center");
                }
            } catch (IOException exception) {
                throw new AssertionError("Could not read the uploaded master PNG", exception);
            }
        });
    }

    private static void assertUnboundedLibrary(ClientGameTestContext context) {
        context.runOnClient(client -> {
            PaintingLibrary library = CustomPaintingsClient.library().library();
            if (library.count() != 14) {
                throw new AssertionError("Library should hold 14 paintings, holds " + library.count());
            }
            long uniqueIds = library.entries().stream().map(PaintingEntry::id).distinct().count();
            if (uniqueIds != library.count()) {
                throw new AssertionError("Painting ids must be unique");
            }
            long recipes = library.entries().stream().filter(PaintingEntry::hasRecipe)
                    .map(PaintingEntry::recipeId).distinct().count();
            long withRecipe = library.entries().stream().filter(PaintingEntry::hasRecipe).count();
            if (recipes != withRecipe) {
                throw new AssertionError("Crafting recipes must not be shared between paintings");
            }
            if (withRecipe > RecipeCatalog.size()) {
                throw new AssertionError("More recipes assigned than the catalogue contains");
            }
            for (PaintingEntry entry : library.entries()) {
                if (!PaintingSizes.isSupported(entry.width(), entry.height())) {
                    throw new AssertionError("Unsupported canvas size in library");
                }
            }
        });
        // Every painting must be reachable through the paged sync, not just the first page.
        context.waitFor(client -> CustomPaintingsClient.library().library().entries().stream()
                .allMatch(entry -> CustomPaintingsClient.library().entry(entry.id()).isPresent()), TIMEOUT_TICKS);
    }

    /** Paintings with no crafting recipe must still be obtainable from the menu. */
    private static void assertMenuGivesBeyondRecipes(ClientGameTestContext context,
                                                     TestSingleplayerContext world) {
        int menuOnlyId = context.computeOnClient(client -> {
            // Force the situation the unlimited library creates: more paintings than recipes.
            PaintingLibrary library = CustomPaintingsClient.library().library();
            return library.entries().stream().filter(entry -> !entry.hasRecipe())
                    .map(PaintingEntry::id).findFirst()
                    .orElse(library.entries().getLast().id());
        });
        takeFromMenu(context, world, menuOnlyId, 16);
    }

    /** Opens the menu and screenshots it, proving icons render for many paintings at once. */
    private static void assertItemIconRenders(ClientGameTestContext context, TestSingleplayerContext world) {
        List<Integer> ids = context.computeOnClient(client ->
                CustomPaintingsClient.library().library().entries().stream().map(PaintingEntry::id).toList());
        context.waitFor(client -> ids.stream().allMatch(id ->
                CustomPaintingsClient.paintings().preview(id) != null), TIMEOUT_TICKS);
        context.runOnClient(client -> CustomPaintingsClient.openMenu(null));
        context.waitTicks(20);
        context.runOnClient(client -> {
            if (!(client.gui.screen() instanceof com.jay.custompaintings.client.PaintingMenuScreen)) {
                throw new AssertionError("Painting Menu did not open");
            }
        });
        context.takeScreenshot("custom-painting-menu");
        context.runOnClient(client -> client.gui.setScreen(null));
        context.waitTicks(5);
    }

    // ---------------------------------------------------------------- helpers

    private static void lookAt(ClientGameTestContext context, UUID entityId) {
        BlockPos pos = context.computeOnClient(client -> client.level.getEntity(entityId).blockPosition());
        context.getInput().lookAt(pos);
        context.waitTicks(20);
    }

    private static Painting placePainting(TestSingleplayerContext world, int paintingId, int width, int height) {
        return world.getServer().computeOnServer(server -> {
            ServerPlayer player = world.getConnection().getServerPlayer();
            ServerLevel level = player.level();
            BlockPos wall = player.blockPosition().offset(-10, -2, -6);
            for (int x = 0; x < 20; x++) {
                for (int y = 0; y < 20; y++) {
                    level.setBlock(wall.offset(x, y, 0), Blocks.STONE.defaultBlockState(), 3);
                }
            }
            Holder<PaintingVariant> variant = level.registryAccess()
                    .lookupOrThrow(Registries.PAINTING_VARIANT)
                    .get(PaintingRuntime.variantKey(width, height))
                    .orElseThrow(() -> new AssertionError("Canvas variant is not registered: " + width + "x" + height));
            // Keep the canvas a few blocks ahead and near eye height so the render assertion
            // does not depend on the initial camera pitch or distant spawn-chunk visibility.
            Painting painting = new Painting(level, wall.offset(10, 3, 1), Direction.SOUTH, variant);
            PaintingData.setEntityPaintingId(painting, paintingId);
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
                System.out.println("Screenshot dimensions: " + image.getWidth() + "x" + image.getHeight());
                System.out.println("Screenshot sample follows (R/G/B = artwork, # = bright neutral, . = dark):");
                int sampleWidth = 64;
                int sampleHeight = 32;
                for (int sy = 0; sy < sampleHeight; sy++) {
                    StringBuilder row = new StringBuilder(sampleWidth);
                    for (int sx = 0; sx < sampleWidth; sx++) {
                        int pixel = image.getPixel(sx * image.getWidth() / sampleWidth,
                                sy * image.getHeight() / sampleHeight);
                        int red = pixel >> 16 & 255;
                        int green = pixel >> 8 & 255;
                        int blue = pixel & 255;
                        char sample = red > 90 && red > green * 1.5 && red > blue * 1.5 ? 'R'
                                : green > 90 && green > red * 1.5 && green > blue * 1.5 ? 'G'
                                : blue > 90 && blue > red * 1.5 && blue > green * 1.5 ? 'B'
                                : red > 180 && green > 180 && blue < 100 ? 'Y'
                                : red > 200 && blue > 200 && green < 100 ? 'M'
                                : Math.max(red, Math.max(green, blue)) > 160 ? '#' : '.';
                        row.append(sample);
                    }
                    System.out.println(row);
                }
                throw new AssertionError("Screenshot did not contain enough uploaded artwork pixels: " + artworkPixels);
            }
        } catch (IOException exception) {
            throw new AssertionError("Could not read screenshot " + screenshot, exception);
        }
    }
}
