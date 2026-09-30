package com.github.guoyyit.georasterizerjava.tests;

import com.github.guoyyit.georasterizerjava.model.Feature;
import com.github.guoyyit.georasterizerjava.model.Point;
import com.github.guoyyit.georasterizerjava.model.Polygon;
import com.github.guoyyit.georasterizerjava.model.Ring;

import java.util.ArrayList;
import java.util.List;

/**
 * Test geometry factory helpers.
 */
final class Geometries {

    private Geometries() {
    }

    static Ring ring(double... xy) {
        if (xy.length % 2 != 0) {
            throw new IllegalArgumentException("coordinates must be pairs");
        }
        List<Point> points = new ArrayList<>(xy.length / 2);
        for (int i = 0; i < xy.length; i += 2) {
            points.add(new Point(xy[i], xy[i + 1]));
        }
        return new Ring(points);
    }

    static Polygon polygon(Ring exterior, Ring... holes) {
        return new Polygon(exterior, List.of(holes));
    }

    static Feature feature(long id, Polygon polygon, double weight, int priority) {
        return new Feature(id, polygon, weight, priority);
    }
}
