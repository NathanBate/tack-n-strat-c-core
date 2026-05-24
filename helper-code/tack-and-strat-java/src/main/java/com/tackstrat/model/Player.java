package com.tackstrat.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A seat at the table. {@code seat} is 0-based display order (stable for UI colors).
 * {@link #computer()} seats are driven by simple AI instead of the handoff gate.
 */
public record Player(int seat, String name, boolean computer) {

    public Player(int seat, String name) {
        this(seat, name, false);
    }

    public Player {
        if (seat < 0 || seat > 3) {
            throw new IllegalArgumentException("seat must be 0..3");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
    }

    /** Jackson: older saves omit {@code computer}. */
    @JsonCreator
    public static Player fromJson(
            @JsonProperty("seat") int seat,
            @JsonProperty("name") String name,
            @JsonProperty("computer") Boolean computer) {
        return new Player(seat, name, computer != null && computer);
    }
}
