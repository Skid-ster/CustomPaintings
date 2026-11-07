package com.jay.custompaintings.library;

import java.util.function.Supplier;

/** Side-safe name lookup for items. The client supplier is replaced while an integrated/server world is active. */
public final class PaintingLibraryAccess {
    private static volatile Supplier<PaintingLibrary> supplier = () -> PaintingLibrary.EMPTY;

    private PaintingLibraryAccess() {
    }

    public static void set(Supplier<PaintingLibrary> supplier) {
        PaintingLibraryAccess.supplier = supplier;
    }

    public static PaintingLibrary current() {
        return supplier.get();
    }
}
