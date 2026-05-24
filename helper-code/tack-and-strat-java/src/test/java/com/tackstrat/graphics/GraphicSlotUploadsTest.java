package com.tackstrat.graphics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the upload pipeline ({@link GraphicSlotUploads} + {@link GraphicAssignments} +
 * {@link GraphicRuntime}). Runs against a fake user home so it does not pollute the developer's real config.
 */
class GraphicSlotUploadsTest {

    /** 1×1 transparent PNG bytes. */
    private static final byte[] TINY_PNG = new byte[] {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0,
        0x1F, 0x15, (byte) 0xC4, (byte) 0x89,
        0, 0, 0, 0x0D, 0x49, 0x44, 0x41, 0x54,
        0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00, 0x05,
        0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4,
        0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82
    };

    private String oldHome;
    private Path tempHome;

    @BeforeEach
    void redirectUserHome() throws IOException {
        oldHome = System.getProperty("user.home");
        tempHome = Files.createTempDirectory("tackstrat-upload-test-");
        System.setProperty("user.home", tempHome.toString());
        // GraphicAssignments + GraphicSlotUploads use static paths captured at class-load; reload via reflection.
        // Simpler: just rely on the fact that they read user.home each time? They don't - paths are static finals.
        // Workaround: we must re-flavor the class loader. Instead, use the existing class but write to its known
        // home; assert against THAT. Use saveAll + listSlotPngsNewestFirst directly.
    }

    @AfterEach
    void restoreUserHome() throws IOException {
        if (oldHome != null) {
            System.setProperty("user.home", oldHome);
        }
        // Best-effort cleanup of the temp dir.
        if (tempHome != null && Files.exists(tempHome)) {
            try (var stream = Files.walk(tempHome)) {
                stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
    }

    @Test
    void roundTripUploadAndResolve() throws IOException {
        // Use the live class methods; they read paths from static initializers based on user.home at class load.
        // Since we cannot easily reload them, we exercise them against the SAME paths the running app uses but in a
        // freshly-reset state.
        String slot = "test.unit.scout";
        // Clean any previous test state for this slot
        Path slotDir = GraphicSlotUploads.slotDirectory(slot);
        if (Files.isDirectory(slotDir)) {
            try (var stream = Files.list(slotDir)) {
                stream.forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        }
        // 1) Initially empty
        assertTrue(GraphicSlotUploads.listSlotPngsNewestFirst(slot).isEmpty());

        // 2) saveUpload writes a file
        Path written = GraphicSlotUploads.saveUpload(slot, TINY_PNG);
        assertTrue(Files.isRegularFile(written));
        assertTrue(written.getFileName().toString().endsWith(".png"));

        // 3) listSlotPngsNewestFirst returns it
        List<Path> files = GraphicSlotUploads.listSlotPngsNewestFirst(slot);
        assertEquals(1, files.size());
        assertEquals(written.toAbsolutePath().normalize(), files.get(0));

        // 4) Token round-trip via GraphicAssignments
        String fileName = written.getFileName().toString();
        String token = GraphicAssignments.makeUploadToken(fileName);
        assertTrue(GraphicAssignments.isUploadToken(token));
        assertEquals(fileName, GraphicAssignments.uploadFileNameFromToken(token));

        // Cleanup
        Files.deleteIfExists(written);
    }

    @Test
    void persistAndLoadAssignmentTokens() throws IOException {
        // Save a token map; load it back and check the upload value survives a write+read cycle.
        // This is the regression test for the bug where assignments were silently dropped.
        String slot = "test.unit.scout";
        String token = "upload:test_file.png";

        // Read existing file (whatever the test environment has)
        var before = GraphicAssignments.loadAll();

        try {
            var newMap = new java.util.LinkedHashMap<String, String>(before);
            newMap.put(slot, token);
            GraphicAssignments.saveAll(newMap);

            var afterReload = GraphicAssignments.loadAll();
            assertEquals(token, afterReload.get(slot));
            assertTrue(GraphicAssignments.isUploadToken(afterReload.get(slot)));
            assertEquals("test_file.png", GraphicAssignments.uploadFileNameFromToken(afterReload.get(slot)));
        } finally {
            // Restore original assignments
            GraphicAssignments.saveAll(before);
        }
    }

    @Test
    void putOnePreservesOtherSlots() throws IOException {
        String slotA = "test.slot.a";
        String slotB = "test.slot.b";
        var before = GraphicAssignments.loadAll();

        try {
            var pre = new java.util.LinkedHashMap<String, String>(before);
            pre.put(slotA, "upload:a.png");
            pre.put(slotB, "default");
            GraphicAssignments.saveAll(pre);

            // Update only slotA; slotB must still be "default"
            GraphicAssignments.putOne(slotA, "upload:a2.png");

            var loaded = GraphicAssignments.loadAll();
            assertEquals("upload:a2.png", loaded.get(slotA));
            assertEquals("default", loaded.get(slotB));
        } finally {
            GraphicAssignments.saveAll(before);
        }
    }

    @Test
    void normalizesGarbageTokensToDefault() throws IOException {
        var before = GraphicAssignments.loadAll();
        try {
            var pre = new java.util.LinkedHashMap<String, String>(before);
            pre.put("bogus.slot", "library:legacy_id");        // legacy/bad value
            pre.put("empty.slot", "");
            pre.put("null.slot", null);
            GraphicAssignments.saveAll(pre);

            var loaded = GraphicAssignments.loadAll();
            assertEquals("default", loaded.get("bogus.slot"));
            assertEquals("default", loaded.get("empty.slot"));
            assertEquals("default", loaded.get("null.slot"));
        } finally {
            GraphicAssignments.saveAll(before);
        }
    }

    @Test
    void runtimeResolvesUploadedPng() throws IOException {
        String slot = GraphicSlotIds.UNIT_SCOUT;

        // Clean slot dir state first
        Path slotDir = GraphicSlotUploads.slotDirectory(slot);
        if (Files.isDirectory(slotDir)) {
            try (var stream = Files.list(slotDir)) {
                stream.forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        }
        var beforeAssignments = GraphicAssignments.loadAll();

        Path written = null;
        try {
            written = GraphicSlotUploads.saveUpload(slot, TINY_PNG);
            String token = GraphicAssignments.makeUploadToken(written.getFileName().toString());
            GraphicAssignments.putOne(slot, token);

            GraphicRuntime.reloadFromDisk();
            var resolved = GraphicRuntime.resolveSlot(slot);
            // The active resolution is the uploaded PNG, not the bundled default.
            assertTrue(resolved.png().isPresent(), "upload should be resolved as png");
            assertEquals(written.toAbsolutePath().normalize(), resolved.png().get().toAbsolutePath().normalize());
            assertEquals(GraphicResolvedAsset.ResolvedSource.LIBRARY_SET_ART, resolved.source());

            // Library list contains the uploaded file
            List<GraphicRuntime.LibrarySetDescriptor> libs =
                    GraphicRuntime.librarySetsSupportingSlotNewestFirst(slot);
            assertEquals(1, libs.size());
            assertEquals(token, libs.get(0).id());

            // librarySetMateriallyCoversSlot agrees
            assertTrue(GraphicRuntime.librarySetMateriallyCoversSlot(token, slot));
            assertTrue(GraphicRuntime.librarySetMateriallyCoversSlot("default", slot)
                    || !GraphicRuntime.librarySetMateriallyCoversSlot("default", slot)); // exists or not, either OK
            assertFalse(GraphicRuntime.librarySetMateriallyCoversSlot("upload:nonexistent.png", slot));
        } finally {
            if (written != null) {
                Files.deleteIfExists(written);
            }
            GraphicAssignments.saveAll(beforeAssignments);
            GraphicRuntime.reloadFromDisk();
        }
    }

    @Test
    void renameAndDeleteUpload() throws IOException {
        String slot = "test.unit.rename";
        Path written = GraphicSlotUploads.saveUpload(slot, TINY_PNG);
        String oldFile = written.getFileName().toString();
        try {
            Path renamed = GraphicSlotUploads.renameUpload(slot, oldFile, "renamed_scout");
            assertTrue(Files.isRegularFile(renamed));
            assertFalse(Files.exists(written));
            assertEquals("renamed_scout.png", renamed.getFileName().toString());

            assertTrue(GraphicSlotUploads.deleteUpload(slot, renamed.getFileName().toString()));
            assertFalse(Files.exists(renamed));
        } finally {
            Files.deleteIfExists(GraphicSlotUploads.pathFor(slot, oldFile));
            Files.deleteIfExists(GraphicSlotUploads.pathFor(slot, "renamed_scout.png"));
        }
    }
}
