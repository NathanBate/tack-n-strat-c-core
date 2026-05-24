package com.tackstrat.graphics;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphicRuntimeIntegrationTest {

    @Test
    void catalogVersionAndScoutSlotWhenCatalogOnClasspathLayout() throws IOException {
        Optional<java.nio.file.Path> catPath = RepoGraphicPaths.resolveCatalogSlotsFile();
        Assumptions.assumeTrue(catPath.isPresent(), "run from tack-and-strat/ or repo root with catalog");
        GraphicSlotsCatalog cat = GraphicSlotsCatalog.load(catPath);
        assertEquals(1, cat.tackstratGraphicsCatalog);
        assertTrue(
                cat.slots.stream().anyMatch(s -> GraphicSlotIds.UNIT_SCOUT.equals(s.id)),
                "catalog lists unit.scout");
    }

    @Test
    void catalogSlotsIncludeHardCodedImageReturnSpec() {
        Optional<java.nio.file.Path> catPath = RepoGraphicPaths.resolveCatalogSlotsFile();
        Assumptions.assumeTrue(catPath.isPresent(), "run from tack-and-strat/ or repo root with catalog");
        GraphicRuntime.reloadFromDisk();
        GraphicRuntime.SlotDescriptor scout = GraphicRuntime.catalogSlotsInOrder().stream()
                .filter(s -> GraphicSlotIds.UNIT_SCOUT.equals(s.id()))
                .findFirst()
                .orElseThrow();
        assertTrue(
                scout.imageReturnSpec() != null && scout.imageReturnSpec().length() > 20,
                "scout should carry imageReturnSpec from catalog");
    }

    @Test
    void resolveSlotWithTokenShowsBundledDefaultArtOnDisk() {
        Optional<java.nio.file.Path> catPath = RepoGraphicPaths.resolveCatalogSlotsFile();
        Assumptions.assumeTrue(catPath.isPresent(), "run from tack-and-strat/ or repo root with catalog");
        GraphicRuntime.reloadFromDisk();
        GraphicResolvedAsset r = GraphicRuntime.resolveSlotWithToken(GraphicSlotIds.CITY_BANNER, "default");
        assertTrue(
                r.png().filter(Files::isRegularFile).isPresent()
                        || r.txtSprite().filter(Files::isRegularFile).isPresent(),
                "app-default token should resolve to existing bundled art");
    }
}
