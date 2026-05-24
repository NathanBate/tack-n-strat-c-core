package com.tackstrat.persistence;

import java.nio.file.Path;

/** One row in the in-game load/save list. */
public record SaveEntrySummary(Path path, String label, String statusLine, String whenLine, long sortTimeMillis) {}
