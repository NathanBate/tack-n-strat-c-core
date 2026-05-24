package com.tackstrat.graphics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * One-folder-per-catalog-slot store for user-uploaded PNGs. Files live under
 * {@code assets/graphics/uploads/<sanitizedSlotId>/} so uploads and selections are versioned in-repo for now.
 */
public final class GraphicSlotUploads {

    private GraphicSlotUploads() {}

    /** Top-level uploads directory; created on demand by {@link #saveUpload}. */
    public static Path uploadsRoot() {
        return RepoGraphicPaths.graphicsDataRoot().resolve("uploads");
    }

    /** Per-slot directory; safe to call even if it does not yet exist. */
    public static Path slotDirectory(String slotId) {
        return uploadsRoot().resolve(sanitizeSlotId(slotId));
    }

    /**
     * Lists PNG files in the slot directory, newest first by mtime (filename break-tie). Absolute paths.
     */
    public static List<Path> listSlotPngsNewestFirst(String slotId) {
        Path dir = slotDirectory(slotId);
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                if (Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
                    files.add(p.toAbsolutePath().normalize());
                }
            }
        } catch (IOException ignored) {
            return Collections.emptyList();
        }
        files.sort(Comparator.comparing(GraphicSlotUploads::lastModifiedMs).reversed()
                .thenComparing(p -> p.getFileName().toString()));
        return Collections.unmodifiableList(files);
    }

    /** Resolves an absolute path for {@code fileName} under the slot folder; does not require the file to exist. */
    public static Path pathFor(String slotId, String fileName) {
        return slotDirectory(slotId).resolve(fileName).toAbsolutePath().normalize();
    }

    /**
     * Writes {@code bytes} as a new PNG and returns the file. Creates the slot folder if needed. Filename is generated
     * to avoid collisions: {@code <slotId>_<epochMs>.png}.
     */
    public static Path saveUpload(String slotId, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("Empty PNG bytes.");
        }
        Path dir = slotDirectory(slotId);
        Files.createDirectories(dir);
        String slug = sanitizeSlotId(slotId);
        long ts = System.currentTimeMillis();
        Path target = dir.resolve(slug + "_" + ts + ".png");
        int suffix = 1;
        while (Files.exists(target)) {
            target = dir.resolve(slug + "_" + ts + "_" + suffix + ".png");
            suffix++;
        }
        Files.write(target, bytes);
        return target.toAbsolutePath().normalize();
    }

    /** Deletes one upload file, ignoring missing files. */
    public static boolean deleteUpload(String slotId, String fileName) {
        try {
            Path target = pathFor(slotId, fileName);
            if (!target.startsWith(slotDirectory(slotId).toAbsolutePath().normalize())) {
                return false;
            }
            return Files.deleteIfExists(target);
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * Renames one upload file within the same slot folder.
     *
     * @return absolute path of the renamed file
     * @throws IOException when source is missing, target exists, or rename fails
     */
    public static Path renameUpload(String slotId, String oldFileName, String newBaseName) throws IOException {
        if (oldFileName == null || oldFileName.isBlank()) {
            throw new IOException("Missing source filename.");
        }
        String cleanBase = sanitizeFileBaseName(newBaseName);
        if (cleanBase.isEmpty()) {
            throw new IOException("New name is empty.");
        }
        Path src = pathFor(slotId, oldFileName);
        Path dir = slotDirectory(slotId).toAbsolutePath().normalize();
        if (!src.startsWith(dir) || !Files.isRegularFile(src)) {
            throw new IOException("Upload file not found.");
        }
        String ext = oldFileName.toLowerCase(Locale.ROOT).endsWith(".png") ? ".png" : "";
        Path dst = dir.resolve(cleanBase + ext).normalize();
        if (!dst.startsWith(dir)) {
            throw new IOException("Invalid rename target.");
        }
        if (Files.exists(dst)) {
            throw new IOException("A file with that name already exists.");
        }
        return Files.move(src, dst);
    }

    private static long lastModifiedMs(Path p) {
        try {
            FileTime ft = Files.getLastModifiedTime(p);
            return ft.toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String sanitizeFileBaseName(String base) {
        if (base == null) {
            return "";
        }
        String b = base.trim();
        if (b.toLowerCase(Locale.ROOT).endsWith(".png")) {
            b = b.substring(0, b.length() - 4);
        }
        StringBuilder sb = new StringBuilder(b.length());
        for (int i = 0; i < b.length(); i++) {
            char c = b.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_'
                    || c == '-'
                    || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString().strip();
    }

    private static String sanitizeSlotId(String slotId) {
        if (slotId == null) {
            return "_";
        }
        StringBuilder sb = new StringBuilder(slotId.length());
        for (int i = 0; i < slotId.length(); i++) {
            char c = slotId.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_'
                    || c == '-'
                    || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.length() == 0 ? "_" : sb.toString();
    }
}
