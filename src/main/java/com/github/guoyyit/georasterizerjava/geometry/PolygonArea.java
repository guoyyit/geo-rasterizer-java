package com.github.guoyyit.georasterizerjava.geometry;

import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.model.Point;
import com.github.guoyyit.georasterizerjava.model.Polygon;
import com.github.guoyyit.georasterizerjava.model.Ring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Analytic planar area of a polygon under a given fill rule.
 * The polygon consists of the exterior ring and hole rings; winding
 * direction of individual rings is irrelevant to the result.
 */
public final class PolygonArea {

    private PolygonArea() {
    }

    public static double theoreticalArea(Polygon polygon, FillRule fillRule) {
        List<Edge> edges = new ArrayList<>();
        collectEdges(polygon.exterior(), edges);
        for (Ring hole : polygon.holes()) {
            collectEdges(hole, edges);
        }
        if (edges.isEmpty()) {
            return 0.0;
        }

        // Bands are split at every y where the interior topology changes:
        // edge endpoints and, for self-intersecting rings, proper edge
        // crossings lying in the interiors of both edges. Sampling each
        // band at its midpoint is then exact.
        List<Double> levels = new ArrayList<>(edges.size() * 2);
        for (Edge edge : edges) {
            levels.add(edge.yLow);
            levels.add(edge.yHigh);
            for (Edge other : edges) {
                if (other == edge) {
                    continue;
                }
                double crossingY = properIntersectionY(edge, other);
                if (!Double.isNaN(crossingY)) {
                    levels.add(crossingY);
                }
            }
        }
        levels.sort(Comparator.naturalOrder());

        double area = 0.0;
        for (int k = 0; k < levels.size() - 1; k++) {
            double y0 = levels.get(k);
            double y1 = levels.get(k + 1);
            if (!(y1 > y0)) {
                continue;
            }
            double ym = y0 + (y1 - y0) * 0.5;
            List<Crossing> crossings = crossingsAt(edges, ym);
            double measure = interiorMeasure(crossings, fillRule);
            area += measure * (y1 - y0);
        }
        return Math.abs(area);
    }

    /**
     * Y coordinate of a proper intersection between two non-horizontal
     * edges, i.e. one strictly inside both edge y-ranges. Vertex touches
     * are already band boundaries; collinear overlaps never change the
     * topology and are skipped. Returns NaN when there is no crossing.
     */
    private static double properIntersectionY(Edge a, Edge b) {
        double rx = a.xHigh - a.xLow;
        double ry = a.yHigh - a.yLow;
        double sx = b.xHigh - b.xLow;
        double sy = b.yHigh - b.yLow;
        double denominator = rx * sy - ry * sx;
        if (denominator == 0.0) {
            return Double.NaN;
        }
        double qpx = b.xLow - a.xLow;
        double qpy = b.yLow - a.yLow;
        double t = (qpx * sy - qpy * sx) / denominator;
        double u = (qpx * ry - qpy * rx) / denominator;
        if (t <= 0.0 || t >= 1.0 || u <= 0.0 || u >= 1.0) {
            return Double.NaN;
        }
        return a.yLow + t * ry;
    }

    private static void collectEdges(Ring ring, List<Edge> out) {
        List<Point> v = ring.vertices();
        int n = v.size();
        for (int i = 0; i < n; i++) {
            Point a = v.get(i);
            Point b = v.get((i + 1) % n);
            if (a.y() == b.y()) {
                continue;
            }
            Point low = a.y() < b.y() ? a : b;
            Point high = a.y() < b.y() ? b : a;
            // +1 when the edge crosses upward, -1 when downward.
            int direction = a.y() < b.y() ? 1 : -1;
            out.add(new Edge(low.x(), low.y(), high.x(), high.y(), direction));
        }
    }

    private static List<Crossing> crossingsAt(List<Edge> edges, double y) {
        List<Crossing> crossings = new ArrayList<>();
        for (Edge edge : edges) {
            if (y > edge.yLow && y < edge.yHigh) {
                double t = (y - edge.yLow) / (edge.yHigh - edge.yLow);
                double x = edge.xLow + t * (edge.xHigh - edge.xLow);
                crossings.add(new Crossing(x, edge.direction));
            }
        }
        crossings.sort(Comparator
                .comparingDouble((Crossing c) -> c.x)
                .thenComparingInt(c -> c.direction));
        return crossings;
    }

    private static double interiorMeasure(List<Crossing> crossings, FillRule fillRule) {
        double measure = 0.0;
        int winding = 0;
        int parity = 0;
        for (int i = 0; i + 1 < crossings.size(); i++) {
            Crossing left = crossings.get(i);
            if (fillRule == FillRule.NON_ZERO) {
                winding += left.direction;
            } else {
                parity ^= 1;
            }
            double right = crossings.get(i + 1).x();
            boolean inside = fillRule == FillRule.NON_ZERO ? winding != 0 : parity != 0;
            if (inside && right > left.x()) {
                measure += right - left.x();
            }
        }
        return measure;
    }

    private record Edge(double xLow, double yLow, double xHigh, double yHigh, int direction) {
    }

    private record Crossing(double x, int direction) {
    }
}
