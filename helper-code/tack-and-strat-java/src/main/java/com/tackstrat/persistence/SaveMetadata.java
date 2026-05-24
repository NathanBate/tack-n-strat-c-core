package com.tackstrat.persistence;

/** Display metadata stored next to the snapshot (not part of game rules). */
public record SaveMetadata(String label, long savedAtMillis) {}
