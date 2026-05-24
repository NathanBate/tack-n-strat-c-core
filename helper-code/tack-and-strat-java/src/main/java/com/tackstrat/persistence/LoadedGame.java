package com.tackstrat.persistence;

import com.tackstrat.model.GameSnapshot;

public record LoadedGame(GameSnapshot snapshot, UiSaveState uiSaveState) {

    public LoadedGame {
        uiSaveState = uiSaveState == null ? UiSaveState.EMPTY : uiSaveState;
    }
}
