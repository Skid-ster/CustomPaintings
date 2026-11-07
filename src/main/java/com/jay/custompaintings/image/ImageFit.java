package com.jay.custompaintings.image;

import java.util.Locale;

/** How a preserved-aspect master image is composed onto a painting canvas. */
public enum ImageFit {
    /** Show the whole image without distortion; unused canvas remains transparent. */
    FIT("fit"),
    /** Preserve proportions and fill the canvas, cropping excess edges. */
    FILL("fill"),
    /** Distort the image to exactly fill the canvas. */
    STRETCH("stretch");

    private final String id;

    ImageFit(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return "config.custompaintings.fit." + id;
    }

    public static ImageFit fromId(String value) {
        if (value != null) {
            String normalized = value.toLowerCase(Locale.ROOT);
            for (ImageFit fit : values()) {
                if (fit.id.equals(normalized)) {
                    return fit;
                }
            }
        }
        throw new IllegalArgumentException("Unknown image fit: " + value);
    }
}
