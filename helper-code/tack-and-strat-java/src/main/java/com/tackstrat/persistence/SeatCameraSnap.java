package com.tackstrat.persistence;

/** Per-seat map camera stored alongside saves so hot-seat zoom/pan survives load/continue. */
public record SeatCameraSnap(int seat, double scale, double offsetX, double offsetY) {}
