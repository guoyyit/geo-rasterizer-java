package com.github.guoyyit.georasterizerjava.geometry;

import com.github.guoyyit.georasterizerjava.model.OutOfRangePolicy;
import com.github.guoyyit.georasterizerjava.model.Point;
import com.github.guoyyit.georasterizerjava.model.Polygon;
import com.github.guoyyit.georasterizerjava.model.Ring;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Applies the configured out-of-range policy to a polygon against the
 * axis-aligned output extent.
 */
public final class PolygonClipper {

    private PolygonClipper() {
    }

    private static final double EPS = 1.0e-12;

    public static Optional<Polygon> apply(
            Polygon polygon,
            double xmin, double ymin, double xmax, double ymax,
            OutOfRangePolicy policy
    ) {
        double[] bounds = boundsOf(polygon);
        boolean outside = bounds[2] < xmin || bounds[0] > xmax
                || bounds[3] < ymin || bounds[1] > ymax;
        if (outside) {
            return Optional.empty();
        }
        boolean inside = bounds[0] >= xmin && bounds[2] <= xmax
                && bounds[1] >= ymin && bounds[3] <= ymax;
        if (policy == OutOfRangePolicy.REJECT) {
            return inside ? Optional.of(polygon) : Optional.empty();
        }
        if (inside) {
            return Optional.of(polygon);
        }

        List<Point> exterior = clipRing(polygon.exterior(), xmin, ymin, xmax, ymax);
        if (exterior.size() < 3) {
            return Optional.empty();
        }
        List<Ring> clippedHoles = new ArrayList<>();
        for (Ring hole : polygon.holes()) {
            List<Point> clipped = clipRing(hole, xmin, ymin, xmax, ymax);
            if (clipped.size() >= 3) {
                clippedHoles.add(new Ring(clipped));
            }
        }
        return Optional.of(new Polygon(new Ring(exterior), clippedHoles));
    }

    private static double[] boundsOf(Polygon polygon) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        List<Ring> rings = new ArrayList<>(polygon.holes().size() + 1);
        rings.add(polygon.exterior());
        rings.addAll(polygon.holes());
        for (Ring ring : rings) {
            for (Point p : ring.vertices()) {
                minX = Math.min(minX, p.x());
                minY = Math.min(minY, p.y());
                maxX = Math.max(maxX, p.x());
                maxY = Math.max(maxY, p.y());
            }
        }
        return new double[] {minX, minY, maxX, maxY};
    }

    private static List<Point> normalize(Ring ring) {
        List<Point> points = new ArrayList<>(ring.vertices().size());
        for (Point p : ring.vertices()) {
            if (points.isEmpty() || !samePoint(points.get(points.size() - 1), p)) {
                points.add(p);
            }
        }
        if (points.size() > 1 && samePoint(points.get(0), points.get(points.size() - 1))) {
            points.remove(points.size() - 1);
        }
        return points;
    }

    private static boolean samePoint(Point a, Point b) {
        return Math.abs(a.x() - b.x()) <= EPS && Math.abs(a.y() - b.y()) <= EPS;
    }

    private static List<Point> clipRing(
            Ring ring, double xmin, double ymin, double xmax, double ymax
    ) {
        List<Point> points = normalize(ring);
        points = clipHalfPlane(points, 0, xmin);
        points = clipHalfPlane(points, 2, xmax);
        points = clipHalfPlane(points, 1, ymin);
        points = clipHalfPlane(points, 3, ymax);
        return points;
    }

    /**
     * Sutherland-Hodgman against one half-plane.
     * side: 0 = x &gt;= bound, 1 = y &gt;= bound, 2 = x &lt;= bound, 3 = y &lt;= bound.
     */
    private static List<Point> clipHalfPlane(List<Point> points, int side, double bound) {
        List<Point> out = new ArrayList<>(points.size() + 4);
        int n = points.size();
        for (int i = 0; i < n; i++) {
            Point current = points.get(i);
            Point previous = points.get((i + n - 1) % n);
            boolean currentInside = inside(current, side, bound);
            boolean previousInside = inside(previous, side, bound);
            if (currentInside) {
                if (!previousInside) {
                    out.add(intersection(previous, current, side, bound));
                }
                out.add(current);
            } else if (previousInside) {
                out.add(intersection(previous, current, side, bound));
            }
        }
        return out;
    }

    private static boolean inside(Point p, int side, double bound) {
        return switch (side) {
            case 0 -> p.x() >= bound;
            case 1 -> p.y() >= bound;
            case 2 -> p.x() <= bound;
            case 3 -> p.y() <= bound;
            default -> throw new IllegalStateException();
        };
    }

    private static Point intersection(Point a, Point b, int side, double bound) {
        double t;
        if (side == 0 || side == 2) {
            t = (bound - a.x()) / (b.x() - a.x());
        } else {
            t = (bound - a.y()) / (b.y() - a.y());
        }
        if (!Double.isFinite(t)) {
            t = 0.5;
        }
        t = Math.max(0.0, Math.min(1.0, t));
        return new Point(
                a.x() + t * (b.x() - a.x()),
                a.y() + t * (b.y() - a.y())
        );
    }
}
