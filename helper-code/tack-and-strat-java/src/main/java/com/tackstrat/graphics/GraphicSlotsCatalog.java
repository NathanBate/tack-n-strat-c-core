package com.tackstrat.graphics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
final class GraphicSlotsCatalog {

    private static final ObjectMapper JSON = new ObjectMapper();

    @com.fasterxml.jackson.annotation.JsonProperty("tackstratGraphicsCatalog")
    public int tackstratGraphicsCatalog;

    public List<SlotEntry> slots = List.of();

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class SlotEntry {
        public String id;
        public String label;
        public String assetKind;
        public String notes;
        /** Hard-coded instructions for what this slot’s art must satisfy (generation / import bundle). */
        public String imageReturnSpec;
        public Map<String, String> defaultFiles = Map.of();

        String pngDefault() {
            return defaultFiles != null ? nullIfBlank(defaultFiles.get("png")) : null;
        }

        String txtDefault() {
            return defaultFiles != null ? nullIfBlank(defaultFiles.get("txtSprite")) : null;
        }

        private static String nullIfBlank(String s) {
            return s == null || s.isBlank() ? null : s.trim();
        }
    }

    static GraphicSlotsCatalog load(Optional<Path> catalogPath) throws IOException {
        if (catalogPath.isEmpty()) {
            GraphicSlotsCatalog empty = new GraphicSlotsCatalog();
            empty.tackstratGraphicsCatalog = 1;
            empty.slots = List.of();
            return empty;
        }
        GraphicSlotsCatalog cat = JSON.readValue(catalogPath.get().toFile(), GraphicSlotsCatalog.class);
        if (cat.slots == null) {
            cat.slots = List.of();
        }
        if (cat.tackstratGraphicsCatalog != 1) {
            throw new IOException("Unsupported tackstratGraphicsCatalog version: "
                    + cat.tackstratGraphicsCatalog + " expected 1.");
        }
        return cat;
    }

    Map<String, SlotEntry> slotsById() {
        Map<String, SlotEntry> map = new LinkedHashMap<>();
        for (SlotEntry s : slots) {
            if (s.id != null && !s.id.isBlank()) {
                map.put(s.id.strip(), s);
            }
        }
        return Collections.unmodifiableMap(map);
    }
}
