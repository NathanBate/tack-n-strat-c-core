package com.tackstrat.persistence;

import com.tackstrat.model.GameSnapshot;

/**
 * On-disk wrapper so we can show friendly titles and dates without exposing paths in the UI.
 * Legacy files without this wrapper are still readable (flat {@link GameSnapshot} root).
 */
public record SaveFileEnvelope(
        int envelopeVersion,
        SaveMetadata metadata,
        GameSnapshot snapshot,
        UiSaveState uiSaveState
) {
    public static final int ENVELOPE_VERSION = 2;

    public SaveFileEnvelope {
        if (uiSaveState == null) {
            uiSaveState = UiSaveState.EMPTY;
        }
    }
}
