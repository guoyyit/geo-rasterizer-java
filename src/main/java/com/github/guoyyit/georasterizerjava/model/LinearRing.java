package com.github.guoyyit.georasterizerjava.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 线性环：闭合的简单或自相交顶点序列。
 */
public final class LinearRing {

    private final Envelope envelope;
    private final List<Point> points;

    public LinearRing(List<Point> points) {
        List<Point> normalized = normalize(points);
        this.points = List.copyOf(normalized);
        this.envelope = computeEnvelope(this.points);
    }

    private static List<Point> normalize(List<Point> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Point> withoutConsecutive = new ArrayList<>(raw.size());
        for (Point point : raw) {
            if (point == null) {
                throw new IllegalArgumentException("ring points must not be null");
            }
            if (!withoutConsecutive.isEmpty() && equalPoint(withoutConsecutive.get(withoutConsecutive.size() - 1), point)) {
                continue;
            }
            withoutConsecutive.add(point);
        }
        if (withoutConsecutive.size() >= 2
                && equalPoint(withoutConsecutive.get(0), withoutConsecutive.get(withoutConsecutive.size() - 1))) {
            withoutConsecutive.remove(withoutConsecutive.size() - 1);
        }
        return withoutConsecutive;
    }

    private static boolean equalPoint(Point a, Point b) {
        return Double.compare(a.x(), b.x()) == 0 && Double.compare(a.y(), b.y()) == 0;
    }

    private static Envelope computeEnvelope(List<Point> pts) {
        if (pts.isEmpty()) {
            return null;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Point point : pts) {
            minX = Math.min(minX, point.x());
            minY = Math.min(minY, point.y());
            maxX = Math.max(maxX, point.x());
            maxY = Math.max(maxY, point.y());
        }
        return new Envelope(minX, minY, maxX, maxY);
    }

    public List<Point> points() {
        return points;
    }

    public int size() {
        return points.size();
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    public Envelope envelope() {
        return envelope;
    }
}
