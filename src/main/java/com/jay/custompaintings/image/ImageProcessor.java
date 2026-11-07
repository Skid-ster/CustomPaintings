package com.jay.custompaintings.image;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.Locale;

/** Bounded ImageIO decoding and high-quality Graphics2D resizing on worker threads. */
public final class ImageProcessor {
    public static final long MAX_FILE_BYTES = 32L * 1024 * 1024;
    public static final long MAX_SOURCE_PIXELS = 16_777_216L;
    public static final long MAX_OUTPUT_PIXELS = 1_048_576L;

    private ImageProcessor() {
    }

    public record Pixels(int width, int height, int[] argb) {
        public Pixels {
            if (width <= 0 || height <= 0 || argb.length != (long) width * height) {
                throw new IllegalArgumentException("Pixel buffer does not match its dimensions");
            }
        }
    }

    public static boolean isSupported(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    public static BufferedImage read(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw new IOException("Only regular, non-symlink image files are supported");
        }
        if (!isSupported(file.getFileName().toString())) {
            throw new IOException("Use a PNG, JPG, or JPEG image");
        }
        if (attributes.size() > MAX_FILE_BYTES) {
            throw new IOException("Image exceeds the 32 MiB file-size limit");
        }
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            return readStream(input, file.getFileName().toString());
        }
    }

    /** Bounded decoding for bytes received from a remote client or read from the canonical library. */
    public static BufferedImage readBytes(byte[] bytes, String fileName) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Image exceeds the 32 MiB file-size limit");
        }
        try (InputStream input = new java.io.ByteArrayInputStream(bytes)) {
            return readStream(input, fileName);
        }
    }

    private static BufferedImage readStream(InputStream input, String fileName) throws IOException {
        try (ImageInputStream imageInput = new MemoryCacheImageInputStream(input)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new IOException("Unsupported or damaged image (PNG/JPG required)");
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                String name = fileName.toLowerCase(Locale.ROOT);
                boolean png = name.endsWith(".png");
                boolean matchesExtension = png ? format.equals("png")
                        : format.equals("jpeg") || format.equals("jpg");
                if (!matchesExtension) {
                    throw new IOException("Image contents do not match the PNG/JPG file extension");
                }
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_SOURCE_PIXELS) {
                    throw new IOException("Image dimensions exceed the 16,777,216-pixel safety limit");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IOException("Image decoder returned no image");
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    /** Stretch to the exact canvas size, retaining PNG alpha and making JPEG pixels opaque. */
    public static Pixels resize(BufferedImage source, int width, int height) {
        if (source == null || width <= 0 || height <= 0 || (long) width * height > MAX_OUTPUT_PIXELS) {
            throw new IllegalArgumentException("Invalid source or target dimensions (maximum 1,048,576 output pixels)");
        }
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return new Pixels(width, height, output.getRGB(0, 0, width, height, null, 0, width));
    }

    public static Pixels load(Path file, int width, int height) throws IOException {
        return resize(read(file), width, height);
    }
}
