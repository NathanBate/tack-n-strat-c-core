package com.tackstrat.graphics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves per-slot graphics for the renderer + Settings. Storage layout:
 * <ul>
 *   <li>Catalog (read-only repo): {@code assets/graphics/catalog/slots.json}</li>
 *   <li>Bundled defaults (read-only repo): paths inside catalog entries (e.g. {@code assets/graphics/scout.png}).</li>
 *   <li>Uploaded overrides (repo-local): {@code assets/graphics/uploads/<slotId>/*.png}</li>
 *   <li>Active assignments (repo-local): {@code assets/graphics/graphics_assignments.json} ({@link GraphicAssignments}).</li>
 * </ul>
 */
public final class GraphicRuntime {

    public record SlotDescriptor(String id, String label, String assetKind, String imageReturnSpec) {}

    /**
     * One option in the per-slot library. {@link #id()} is the assignment token (e.g. {@code "default"} or
     * {@code "upload:scout_1234567890.png"}); {@link #label()} is the user-facing display name.
     */
    public record LibrarySetDescriptor(String id, String label) {}

    public static final String TOKEN_DEFAULT = GraphicAssignments.DEFAULT_TOKEN;

    private static volatile State state;

    private GraphicRuntime() {}

    /** Reload catalog and persisted assignments. Call after Settings save or after writing an upload. */
    public static void reloadFromDisk() {
        state = State.load();
    }

    public static List<SlotDescriptor> catalogSlotsInOrder() {
        return stateSnapshot().orderedDescriptors();
    }

    /** All upload options for {@code slotId} that exist on disk, newest first. Bundled default is NOT included. */
    public static List<LibrarySetDescriptor> librarySetsSupportingSlotNewestFirst(String slotId) {
        List<Path> uploads = GraphicSlotUploads.listSlotPngsNewestFirst(safeSlot(slotId));
        if (uploads.isEmpty()) {
            return List.of();
        }
        List<LibrarySetDescriptor> out = new ArrayList<>(uploads.size());
        for (Path p : uploads) {
            String filename = p.getFileName().toString();
            String label = labelForFile(filename);
            out.add(new LibrarySetDescriptor(GraphicAssignments.makeUploadToken(filename), label));
        }
        return List.copyOf(out);
    }

    /** Same listing as {@link #librarySetsSupportingSlotNewestFirst(String)} (ordering unchanged). */
    public static List<LibrarySetDescriptor> librarySetsSupportingSlot(String slotId) {
        return librarySetsSupportingSlotNewestFirst(slotId);
    }

    /** True when {@code token} is {@code default} or an upload PNG that exists on disk for {@code slotId}. */
    public static boolean librarySetMateriallyCoversSlot(String token, String slotId) {
        if (token == null || slotId == null) {
            return false;
        }
        if (TOKEN_DEFAULT.equalsIgnoreCase(token.strip())) {
            return resolveSlotWithToken(slotId, TOKEN_DEFAULT).png().isPresent()
                    || resolveSlotWithToken(slotId, TOKEN_DEFAULT).txtSprite().isPresent();
        }
        if (!GraphicAssignments.isUploadToken(token)) {
            return false;
        }
        Path p = GraphicSlotUploads.pathFor(slotId, GraphicAssignments.uploadFileNameFromToken(token));
        return Files.isRegularFile(p);
    }

    /** Active resolution for {@code slotId} (used by the map renderer). */
    public static GraphicResolvedAsset resolveSlot(String slotId) {
        return resolveSlotWithToken(slotId, assignmentToken(slotId));
    }

    /** Resolution for an arbitrary token (used by Settings card thumbnails / preview before save). */
    public static GraphicResolvedAsset resolveSlotWithToken(String slotId, String token) {
        return stateSnapshot().resolveWithToken(slotId, token);
    }

    /** Basename of the catalog default PNG (e.g. {@code scout.png}); empty when slot has no PNG. */
    public static Optional<String> catalogDefaultPngFileName(String slotId) {
        return stateSnapshot().defaultAssetBasename(slotId, true);
    }

    public static Optional<String> catalogDefaultTxtSpriteFileName(String slotId) {
        return stateSnapshot().defaultAssetBasename(slotId, false);
    }

    /** Persisted token for {@code slotId} ({@code default} when unset). */
    public static String assignmentToken(String slotId) {
        return GraphicAssignments.tokenFor(slotId);
    }

    public static Path assignmentsPersistencePath() {
        return GraphicAssignments.persistencePath();
    }

    public static boolean isUploadToken(String token) {
        return GraphicAssignments.isUploadToken(token);
    }

    public static String uploadFileNameFromToken(String token) {
        return GraphicAssignments.uploadFileNameFromToken(token);
    }

    public static String makeUploadToken(String fileName) {
        return GraphicAssignments.makeUploadToken(fileName);
    }

    /**
     * Bulk overwrite of all assignment tokens. Use this when persisting from Settings; for one-off updates use
     * {@link #setSlotAssignment(String, String)}.
     */
    public static void persistAssignmentTokens(Map<String, String> slotIdToToken) throws IOException {
        GraphicAssignments.saveAll(slotIdToToken == null ? Map.of() : slotIdToToken);
    }

    /** Updates exactly one slot's token, preserving the rest of the file. */
    public static void setSlotAssignment(String slotId, String token) throws IOException {
        GraphicAssignments.putOne(slotId, token);
    }

    /**
     * Writes a new uploaded PNG, returns the resulting assignment token (e.g.
     * {@code "upload:scout_1700000000.png"}). Caller should typically follow with {@link #setSlotAssignment} +
     * {@link #reloadFromDisk}.
     */
    public static String saveUploadAndMakeToken(String slotId, byte[] pngBytes) throws IOException {
        Path written = GraphicSlotUploads.saveUpload(slotId, pngBytes);
        return GraphicAssignments.makeUploadToken(written.getFileName().toString());
    }

    private static String safeSlot(String slotId) {
        return slotId == null ? "" : slotId.strip();
    }

    private static String labelForFile(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "Upload";
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static State stateSnapshot() {
        State s = state;
        if (s != null) return s;
        synchronized (GraphicRuntime.class) {
            if (state == null) {
                state = State.load();
            }
            return state;
        }
    }

    private static final class State {
        final GraphicSlotsCatalog catalog;
        final Map<String, GraphicSlotsCatalog.SlotEntry> slotsById;

        State(GraphicSlotsCatalog catalog) {
            this.catalog = catalog;
            this.slotsById = catalog.slotsById();
        }

        static State load() {
            try {
                GraphicSlotsCatalog cat = GraphicSlotsCatalog.load(RepoGraphicPaths.resolveCatalogSlotsFile());
                return new State(cat);
            } catch (IOException ex) {
                GraphicSlotsCatalog emptyCat = new GraphicSlotsCatalog();
                emptyCat.tackstratGraphicsCatalog = 1;
                emptyCat.slots = List.of();
                return new State(emptyCat);
            }
        }

        List<SlotDescriptor> orderedDescriptors() {
            List<SlotDescriptor> rows = new ArrayList<>();
            if (catalog.slots == null) return rows;
            for (GraphicSlotsCatalog.SlotEntry se : catalog.slots) {
                if (se.id == null || se.id.isBlank()) continue;
                String spec = se.imageReturnSpec == null ? "" : se.imageReturnSpec.strip();
                rows.add(new SlotDescriptor(
                        se.id.strip(),
                        se.label == null ? se.id : se.label.strip(),
                        se.assetKind == null ? "single_png" : se.assetKind.strip(),
                        spec));
            }
            return List.copyOf(rows);
        }

        GraphicResolvedAsset resolveWithToken(String slotId, String token) {
            String sid = safeSlot(slotId);
            var slot = slotsById.get(sid);
            if (slot == null) {
                return GraphicResolvedAsset.missing();
            }
            String t = token == null ? TOKEN_DEFAULT : token.strip();
            if (GraphicAssignments.isUploadToken(t)) {
                String fileName = GraphicAssignments.uploadFileNameFromToken(t);
                Path uploaded = GraphicSlotUploads.pathFor(sid, fileName);
                if (Files.isRegularFile(uploaded)) {
                    return new GraphicResolvedAsset(uploaded, null,
                            GraphicResolvedAsset.ResolvedSource.LIBRARY_SET_ART);
                }
            }
            Path pngDef = resolveOne(slot.pngDefault());
            Path txtDef = resolveOne(slot.txtDefault());
            if (pngDef == null && txtDef == null) {
                return GraphicResolvedAsset.missing();
            }
            return new GraphicResolvedAsset(pngDef, txtDef, GraphicResolvedAsset.ResolvedSource.APP_DEFAULT_ART);
        }

        private static Path resolveOne(String relative) {
            if (relative == null || relative.isBlank()) return null;
            return RepoGraphicPaths.firstExistingRegularFile(RepoGraphicPaths.candidates(relative)).orElse(null);
        }

        Optional<String> defaultAssetBasename(String slotId, boolean png) {
            String sid = safeSlot(slotId);
            GraphicSlotsCatalog.SlotEntry se = slotsById.get(sid);
            if (se == null) {
                return Optional.empty();
            }
            String rel = png ? se.pngDefault() : se.txtDefault();
            if (rel == null || rel.isBlank()) {
                return Optional.empty();
            }
            Path p = Path.of(rel.replace('\\', '/'));
            return Optional.of(p.getFileName().toString());
        }
    }

    /** Convenience: token map for all slots, with {@code default} for any unspecified. */
    public static Map<String, String> currentAssignmentSnapshot() {
        Map<String, String> file = new LinkedHashMap<>(GraphicAssignments.loadAll());
        Map<String, String> out = new LinkedHashMap<>();
        for (SlotDescriptor s : catalogSlotsInOrder()) {
            out.put(s.id(), file.getOrDefault(s.id(), TOKEN_DEFAULT));
        }
        return out;
    }
}
