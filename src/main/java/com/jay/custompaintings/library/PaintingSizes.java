package com.jay.custompaintings.library;

import java.util.ArrayList;
import java.util.List;

/** All block sizes from 1×1 through 8×8. Variants are pre-registered so worlds never need registry mutation. */
public final class PaintingSizes {
    public static final int MIN_BLOCK = 1;
    public static final int MAX_BLOCK = 8;

    public record Size(int width, int height) {
        public Size {
            if (!isSupported(width, height)) {
                throw new IllegalArgumentException("Painting dimensions must be between " + MIN_BLOCK + " and " + MAX_BLOCK);
            }
        }

        public String label() {
            return width + " × " + height;
        }

        public String pathSuffix() {
            return width + "x" + height;
        }

        public int area() {
            return width * height;
        }
    }

    public static final List<Size> PRESETS = buildPresets();
    public static final Size DEFAULT = new Size(2, 2);

    private PaintingSizes() {
    }

    private static List<Size> buildPresets() {
        List<Size> sizes = new ArrayList<>();
        for (int width = MIN_BLOCK; width <= MAX_BLOCK; width++) {
            for (int height = MIN_BLOCK; height <= MAX_BLOCK; height++) {
                sizes.add(new Size(width, height));
            }
        }
        return List.copyOf(sizes);
    }

    public static boolean isSupported(int width, int height) {
        return width >= MIN_BLOCK && width <= MAX_BLOCK && height >= MIN_BLOCK && height <= MAX_BLOCK;
    }

    public static Size of(int width, int height) {
        if (!isSupported(width, height)) {
            throw new IllegalArgumentException("Unsupported painting size: " + width + "x" + height);
        }
        return new Size(width, height);
    }

    /**
     * Chooses the supported canvas whose aspect ratio is closest to the source image.
     * Exact shape ties prefer the smaller canvas, then the narrower one, so tiny icons
     * do not accidentally become huge wall murals.
     */
    public static Size auto(int sourceWidth, int sourceHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException("Source image dimensions must be positive");
        }
        double sourceRatio = (double) sourceWidth / sourceHeight;
        Size best = DEFAULT;
        double bestError = Double.POSITIVE_INFINITY;
        for (Size size : PRESETS) {
            double error = Math.abs(Math.log(sourceRatio / ((double) size.width() / size.height())));
            boolean better = error < bestError - 1.0e-12;
            if (!better && Math.abs(error - bestError) <= 1.0e-12) {
                if (size.equals(DEFAULT)) {
                    better = true;
                } else if (!best.equals(DEFAULT)) {
                    better = size.area() < best.area() || (size.area() == best.area() && size.width() < best.width());
                }
            }
            if (better) {
                best = size;
                bestError = error;
            }
        }
        return best;
    }
}
