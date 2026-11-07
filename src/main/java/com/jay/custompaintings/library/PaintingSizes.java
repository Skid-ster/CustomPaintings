package com.jay.custompaintings.library;

import java.util.List;

/** Fixed size presets keep the synchronized painting registry stable while a world is running. */
public final class PaintingSizes {
    public record Size(int width, int height) {
        public String label() {
            return width + " × " + height;
        }

        public String pathSuffix() {
            return width + "x" + height;
        }
    }

    public static final List<Size> PRESETS = List.of(
            new Size(1, 1), new Size(2, 1), new Size(1, 2), new Size(2, 2),
            new Size(3, 2), new Size(2, 3), new Size(3, 3), new Size(4, 4));
    public static final Size DEFAULT = new Size(2, 2);

    private PaintingSizes() {
    }

    public static boolean isSupported(int width, int height) {
        return PRESETS.stream().anyMatch(size -> size.width() == width && size.height() == height);
    }

    public static Size of(int width, int height) {
        return PRESETS.stream().filter(size -> size.width() == width && size.height() == height)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unsupported painting size"));
    }
}
