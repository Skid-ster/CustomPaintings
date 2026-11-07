package com.jay.custompaintings.client;

import com.jay.custompaintings.CustomPaintingsMod;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

import java.nio.ByteBuffer;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * SDL3 multi-file picker for Minecraft 26.3. SDL may invoke its callback on another
 * thread, so native strings are copied immediately and the result is posted to the client.
 * The callback and filter strings stay alive until SDL calls back (or opening fails).
 */
public final class ImageFileChooser implements AutoCloseable {
    // Native pointers do not keep their Java callback alive. Retain pending sessions even
    // when the screen closes before SDL delivers its callback.
    private static final Set<ImageFileChooser> PENDING = ConcurrentHashMap.newKeySet();
    private final Minecraft minecraft = Minecraft.getInstance();
    private final Consumer<Result> consumer;
    private final AtomicBoolean completed = new AtomicBoolean();
    private volatile boolean disposed;
    private SDL_DialogFileCallback callback;
    private SDL_DialogFileFilter.Buffer filters;
    private ByteBuffer filterName;
    private ByteBuffer filterPattern;

    public record Result(List<Path> files, String error, boolean cancelled) {
        public Result {
            files = List.copyOf(files);
        }
    }

    public ImageFileChooser(Consumer<Result> consumer) {
        this.consumer = consumer;
    }

    /** Must be called on Minecraft's client/render thread. */
    public void open() {
        if (completed.get() || disposed) {
            throw new IllegalStateException("File chooser has already been used");
        }
        PENDING.add(this);
        try {
            filterName = MemoryUtil.memUTF8("Painting images (PNG, JPG, JPEG)");
            filterPattern = MemoryUtil.memUTF8("png;jpg;jpeg");
            filters = SDL_DialogFileFilter.calloc(1);
            filters.get(0).name(filterName).pattern(filterPattern);
            callback = SDL_DialogFileCallback.create(this::nativeCallback);
            SDLDialog.SDL_ShowOpenFileDialog(callback, MemoryUtil.NULL, minecraft.getWindow().handle(),
                    filters, (ByteBuffer) null, true);
        } catch (RuntimeException | LinkageError exception) {
            completed.set(true);
            freeNativeResources();
            PENDING.remove(this);
            throw exception;
        }
    }

    private void nativeCallback(long userdata, long fileList, int filter) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        Result result;
        try {
            if (fileList == MemoryUtil.NULL) {
                String error = SDLError.SDL_GetError();
                result = new Result(List.of(), error == null || error.isBlank() ? "The native file picker failed" : error, false);
            } else {
                List<Path> paths = new ArrayList<>();
                long pointer;
                int index = 0;
                while ((pointer = MemoryUtil.memGetAddress(fileList + (long) index * Pointer.POINTER_SIZE)) != MemoryUtil.NULL) {
                    if (index++ >= 4096) {
                        throw new IllegalStateException("The file picker returned too many paths");
                    }
                    try {
                        paths.add(Path.of(MemoryUtil.memUTF8(pointer)));
                    } catch (InvalidPathException exception) {
                        CustomPaintingsMod.LOGGER.warn("Ignoring an invalid path from the file picker", exception);
                    }
                }
                result = paths.isEmpty() ? new Result(List.of(), null, true) : new Result(paths, null, false);
            }
        } catch (RuntimeException exception) {
            CustomPaintingsMod.LOGGER.warn("Could not read the native file picker result", exception);
            result = new Result(List.of(), "Could not read the selected files", false);
        } finally {
            freeNativeResources();
            PENDING.remove(this);
        }
        Result delivery = result;
        minecraft.execute(() -> {
            if (!disposed) {
                consumer.accept(delivery);
            }
        });
    }

    /** Closing the screen cancels delivery, but never frees memory SDL may still use. */
    @Override
    public void close() {
        disposed = true;
        if (completed.get()) {
            freeNativeResources();
        }
    }

    private synchronized void freeNativeResources() {
        if (callback != null) {
            callback.free();
            callback = null;
        }
        if (filters != null) {
            MemoryUtil.memFree(filters);
            filters = null;
        }
        if (filterName != null) {
            MemoryUtil.memFree(filterName);
            filterName = null;
        }
        if (filterPattern != null) {
            MemoryUtil.memFree(filterPattern);
            filterPattern = null;
        }
    }
}
