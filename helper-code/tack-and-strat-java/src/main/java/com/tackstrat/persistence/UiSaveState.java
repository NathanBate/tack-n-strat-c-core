package com.tackstrat.persistence;

import java.util.List;

/** Client-side UI fields persisted in the save envelope (not part of core {@link com.tackstrat.model.GameSnapshot}). */
public record UiSaveState(List<SeatCameraSnap> seatCameras) {

    public static final UiSaveState EMPTY = new UiSaveState(List.of());

    public UiSaveState {
        seatCameras = seatCameras == null ? List.of() : List.copyOf(seatCameras);
    }
}
