package com.tackstrat.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Axial coordinates (q, r) on a pointy-top hex grid. */
public final class HexCoord {

    private static final HexCoord[] NEIGHBOR_DELTAS = {
            new HexCoord(1, 0),
            new HexCoord(1, -1),
            new HexCoord(0, -1),
            new HexCoord(-1, 0),
            new HexCoord(-1, 1),
            new HexCoord(0, 1),
    };

    private final int q;
    private final int r;

    public HexCoord(int q, int r) {
        this.q = q;
        this.r = r;
    }

    public int q() {
        return q;
    }

    public int r() {
        return r;
    }

    public List<HexCoord> neighbors() {
        var list = new ArrayList<HexCoord>(6);
        for (var d : NEIGHBOR_DELTAS) {
            list.add(new HexCoord(q + d.q, r + d.r));
        }
        return list;
    }

    /** Cube distance on the hex grid. */
    public int distanceTo(HexCoord o) {
        int x1 = q;
        int z1 = r;
        int y1 = -q - r;
        int x2 = o.q;
        int z2 = o.r;
        int y2 = -o.q - o.r;
        return (Math.abs(x1 - x2) + Math.abs(y1 - y2) + Math.abs(z1 - z2)) / 2;
    }

    public HexCoord add(HexCoord d) {
        return new HexCoord(q + d.q, r + d.r);
    }

    /** All axial coords within {@code radius} of center (0,0), inclusive. */
    public static List<HexCoord> disk(int radius) {
        var out = new ArrayList<HexCoord>();
        for (int dq = -radius; dq <= radius; dq++) {
            int rMin = Math.max(-radius, -dq - radius);
            int rMax = Math.min(radius, -dq + radius);
            for (int dr = rMin; dr <= rMax; dr++) {
                out.add(new HexCoord(dq, dr));
            }
        }
        return out;
    }

    /**
     * Round fractional axial coords (from pixel hit-testing) to the nearest hex.
     * See <a href="https://www.redblobgames.com/grids/hexagons/">Red Blob Games</a>.
     */
    public static HexCoord hexRound(double fq, double fr) {
        double fs = -fq - fr;
        int q = (int) Math.round(fq);
        int r = (int) Math.round(fr);
        int s = (int) Math.round(fs);
        double qDiff = Math.abs(q - fq);
        double rDiff = Math.abs(r - fr);
        double sDiff = Math.abs(s - fs);
        if (qDiff > rDiff && qDiff > sDiff) {
            q = -r - s;
        } else if (rDiff > sDiff) {
            r = -q - s;
        }
        return new HexCoord(q, r);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        HexCoord hexCoord = (HexCoord) o;
        return q == hexCoord.q && r == hexCoord.r;
    }

    @Override
    public int hashCode() {
        return Objects.hash(q, r);
    }

    @Override
    public String toString() {
        return "HexCoord(" + q + "," + r + ")";
    }
}
