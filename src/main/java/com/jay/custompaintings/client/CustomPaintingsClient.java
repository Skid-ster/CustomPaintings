package com.jay.custompaintings.client;

import com.jay.custompaintings.CustomPaintingsMod;
import com.jay.custompaintings.library.PaintingLibraryAccess;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class CustomPaintingsClient implements ClientModInitializer {
    private static ClientPaintingLibrary library;
    private static PaintingTextureManager paintings;
    private static PaintingImportService imports;

    public static ClientPaintingLibrary library() {
        return library;
    }

    public static PaintingTextureManager paintings() {
        return paintings;
    }

    public static PaintingImportService imports() {
        return imports;
    }

    @Override
    public void onInitializeClient() {
        library = new ClientPaintingLibrary();
        PaintingLibraryAccess.set(library::library);
        paintings = new PaintingTextureManager(library, CustomPaintingsMod.imagesDirectory());
        imports = new PaintingImportService(library);
        ClientPaintingNetwork.register(library, imports);
        Identifier applyId = CustomPaintingsMod.id("apply_textures");
        ResourceLoader loader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
        loader.registerReloadListener(applyId, new PreparableReloadListener() {
            @Override
            public CompletableFuture<Void> reload(SharedState state, Executor prepareExecutor,
                                                  PreparationBarrier barrier, Executor applyExecutor) {
                return barrier.wait(Boolean.TRUE).thenRunAsync(paintings::installPrepared, applyExecutor);
            }

            @Override
            public String getName() {
                return "Custom Paintings texture application";
            }
        });
        loader.addListenerOrdering(ResourceReloaderKeys.Client.ATLAS, applyId);
        loader.addListenerOrdering(ResourceReloaderKeys.AFTER_VANILLA, applyId);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> paintings.close());
    }
}
