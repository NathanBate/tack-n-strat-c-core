package com.tackstrat.graphics;

import java.nio.file.Path;
import java.util.Optional;

public record GraphicResolvedAsset(Path pngPath, Path txtSpritePath, ResolvedSource source) {

    public enum ResolvedSource {
        APP_DEFAULT_ART,
        LIBRARY_SET_ART,
        MISSING_FALLBACK_EMPTY
    }

    public static GraphicResolvedAsset missing() {
        return new GraphicResolvedAsset(null, null, ResolvedSource.MISSING_FALLBACK_EMPTY);
    }

    public Optional<Path> png() {
        return Optional.ofNullable(pngPath);
    }

    public Optional<Path> txtSprite() {
        return Optional.ofNullable(txtSpritePath);
    }
}
