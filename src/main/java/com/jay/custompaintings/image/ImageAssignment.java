package com.jay.custompaintings.image;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure, deterministic assignment logic, independent of Minecraft and the GPU. */
public final class ImageAssignment {
    private static final Comparator<String> FILE_ORDER = String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    private ImageAssignment() {
    }

    public record Target(String id, int width, int height) {
        public Target {
            if (id == null || id.isBlank() || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("A painting target needs an id and positive dimensions");
            }
        }

        public String assetName() {
            int separator = id.indexOf(':');
            return separator < 0 ? id : id.substring(separator + 1);
        }
    }

    public record Assignment(String fileName, String targetId, int width, int height) {
    }

    /**
     * Exact filename matches win (kebab.png -> minecraft:kebab). Generic images
     * then replace the remaining canvases in order, repeating if necessary. If
     * every image is an exact match, those images also fill the remaining canvases.
     * More images than canvases are still loaded/listed, but have no unique slot.
     */
    public static Map<String, List<Assignment>> assign(List<String> fileNames, List<Target> targets) {
        List<String> files = fileNames.stream().distinct().sorted(FILE_ORDER).toList();
        List<Target> sortedTargets = targets.stream()
                .sorted(Comparator.comparing(Target::id))
                .toList();
        Map<String, List<Assignment>> result = new LinkedHashMap<>();
        for (String file : files) {
            result.put(file, new ArrayList<>());
        }
        if (files.isEmpty() || sortedTargets.isEmpty()) {
            return result;
        }

        Set<String> exactFiles = new LinkedHashSet<>();
        Set<String> assignedTargets = new LinkedHashSet<>();
        for (Target target : sortedTargets) {
            String match = files.stream()
                    .filter(file -> !exactFiles.contains(file))
                    .filter(file -> matches(stem(file), target))
                    .findFirst()
                    .orElse(null);
            if (match != null) {
                add(result, match, target);
                exactFiles.add(match);
                assignedTargets.add(target.id());
            }
        }

        List<String> pool = files.stream().filter(file -> !exactFiles.contains(file)).toList();
        if (pool.isEmpty()) {
            pool = files;
        }
        int index = 0;
        for (Target target : sortedTargets) {
            if (!assignedTargets.contains(target.id())) {
                add(result, pool.get(index++ % pool.size()), target);
            }
        }
        return result;
    }

    private static boolean matches(String stem, Target target) {
        return stem.equals(target.assetName().toLowerCase(Locale.ROOT))
                || stem.equals(target.id().toLowerCase(Locale.ROOT).replace(':', '_'));
    }

    private static void add(Map<String, List<Assignment>> result, String file, Target target) {
        result.get(file).add(new Assignment(file, target.id(), target.width(), target.height()));
    }

    public static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot < 0 ? fileName : fileName.substring(0, dot)).toLowerCase(Locale.ROOT);
    }
}
