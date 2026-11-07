package com.jay.custompaintings.client;

import com.jay.custompaintings.CustomPaintingsMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class CustomPaintingsClient implements ClientModInitializer {
    private static PaintingTextureManager paintings;

    public static PaintingTextureManager paintings() {
        return paintings;
    }

    @Override
    public void onInitializeClient() {
        paintings = new PaintingTextureManager(CustomPaintingsMod.imagesDirectory());
        Identifier applyId = CustomPaintingsMod.id("apply_textures");
        ResourceLoader loader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
        loader.registerReloadListener(applyId, new PreparableReloadListener() {
            @Override
            public CompletableFuture<Void> reload(SharedState state, Executor prepareExecutor,
                                                  PreparationBarrier barrier, Executor applyExecutor) {
                // SpriteLoaderMixin has already prepared pixels during atlas loading.
                // The barrier guarantees GPU registration happens on the apply thread.
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
