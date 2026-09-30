package com.github.guoyyit.georasterizerjava.model;

import java.util.List;

/**
 * 多边形：一个外环与零或多个孔洞环，允许自相交与重复顶点。
 */
public final class Polygon {

    private final Envelope envelope;
    private final LinearRing exterior;
    private final List<LinearRing> holes;

    public Polygon(LinearRing exterior, List<LinearRing> holes) {
        if (exterior == null) {
            throw new IllegalArgumentException("polygon exterior ring must not be null");
        }
        this.exterior = exterior;
        this.holes = holes == null ? List.of() : List.copyOf(holes);
        this.envelope = computeEnvelope();
    }

    private Envelope computeEnvelope() {
        if (exterior.isEmpty()) {
            return null;
        }
        double minX = exterior.envelope().minX();
        double minY = exterior.envelope().minY();
        double maxX = exterior.envelope().maxX();
        double maxY = exterior.envelope().maxY();
        for (LinearRing hole : holes) {
            if (hole.isEmpty()) {
                continue;
            }
            minX = Math.min(minX, hole.envelope().minX());
            minY = Math.min(minY, hole.envelope().minY());
            maxX = Math.max(maxX, hole.envelope().maxX());
            maxY = Math.max(maxY, hole.envelope().maxY());
        }
        return new Envelope(minX, minY, maxX, maxY);
    }

    public LinearRing exterior() {
        return exterior;
    }

    public List<LinearRing> holes() {
        return holes;
    }

    public Envelope envelope() {
        return envelope;
    }
}
