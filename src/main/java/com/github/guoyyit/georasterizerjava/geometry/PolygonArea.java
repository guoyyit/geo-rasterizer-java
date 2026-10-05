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

    private static final double PARALLEL_EPS = 1.0e-12;
    private static final double INTERIOR_EPS = 1.0e-10;
    private static final double LEVEL_EPS = 1.0e-10;

    public static double theoreticalArea(Polygon polygon, FillRule fillRule) {
        List<Edge> edges = new ArrayList<>();
        collectEdges(polygon.exterior(), edges);
        for (Ring hole : polygon.holes()) {
            collectEdges(hole, edges);
        }
        if (edges.isEmpty()) {
            return 0.0;
        }

        // Split the plane into horizontal slabs whose crossing topology
        // is constant. Vertex y-coordinates alone are insufficient for
        // self-intersecting rings: a proper edge crossing can sit in the
        // middle of a slab, and when it lands exactly on the slab's
        // mid-height the duplicated crossing degenerates into a spurious
        // interior span (e.g. the bowtie polygon returning twice its
        // true area). Adding every proper intersection's y as a slab
        // boundary keeps every sample point away from the degeneracy.
        List<Double> levels = new ArrayList<>(edges.size() * 3);
        for (Edge edge : edges) {
            levels.add(edge.yLow);
            levels.add(edge.yHigh);
        }
        for (int i = 0; i < edges.size(); i++) {
            for (int j = i + 1; j < edges.size(); j++) {
                Double crossingY = properIntersectionY(edges.get(i), edges.get(j));
                if (crossingY != null) {
                    levels.add(crossingY);
                }
            }
        }
        levels.sort(Comparator.naturalOrder());

        double area = 0.0;
        for (int k = 0; k < levels.size() - 1; k++) {
            double y0 = levels.get(k);
            double y1 = levels.get(k + 1);
            if (nearlySameLevel(y0, y1)) {
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
     * Returns the y-coordinate of a proper intersection of the two
     * segments (strictly inside both parameter intervals), or
     * {@code null} when they do not cross properly. Edges sharing a
     * polygon vertex are excluded automatically because that meeting
     * point is an endpoint, not a proper crossing. Horizontal edges are
     * never collected, so collinear overlaps cannot occur here.
     */
    private static Double properIntersectionY(Edge first, Edge second) {
        double dx1 = first.xHigh - first.xLow;
        double dy1 = first.yHigh - first.yLow;
        double dx2 = second.xHigh - second.xLow;
        double dy2 = second.yHigh - second.yLow;
        double denominator = dx1 * dy2 - dy1 * dx2;
        if (Math.abs(denominator) <= PARALLEL_EPS) {
            return null;
        }
        double qpx = second.xLow - first.xLow;
        double qpy = second.yLow - first.yLow;
        double s = (qpx * dy2 - qpy * dx2) / denominator;
        double t = (qpx * dy1 - qpy * dx1) / denominator;
        if (s <= INTERIOR_EPS || s >= 1.0 - INTERIOR_EPS
                || t <= INTERIOR_EPS || t >= 1.0 - INTERIOR_EPS) {
            return null;
        }
        return first.yLow + s * dy1;
    }

    private static boolean nearlySameLevel(double a, double b) {
        return Math.abs(a - b) <= LEVEL_EPS * Math.max(1.0, Math.max(Math.abs(a), Math.abs(b)));
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
