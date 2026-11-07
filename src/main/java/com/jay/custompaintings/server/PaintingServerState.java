package com.jay.custompaintings.server;

import com.jay.custompaintings.CustomPaintingsMod;
import com.jay.custompaintings.library.PaintingLibrary;
import com.jay.custompaintings.library.PaintingLibraryStore;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;

/** Authoritative server-side library. Loaded once at mod init so recipe registries can see it. */
public final class PaintingServerState {
    private static final PaintingLibraryStore STORE =
            new PaintingLibraryStore(FabricLoader.getInstance().getConfigDir().resolve(CustomPaintingsMod.MOD_ID));
    private static volatile boolean loaded;

    private PaintingServerState() {
    }

    public static PaintingLibraryStore store() {
        return STORE;
    }

    public static PaintingLibrary library() {
        return STORE.library();
    }

    public static synchronized void ensureLoaded() throws IOException {
        if (!loaded) {
            STORE.load();
            loaded = true;
        }
    }
}
