package com.github.guoyyit.georasterizerjava.tests;

import com.github.guoyyit.georasterizerjava.geometry.PolygonArea;
import com.github.guoyyit.georasterizerjava.geometry.PolygonClipper;
import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.model.OutOfRangePolicy;
import com.github.guoyyit.georasterizerjava.model.Point;
import com.github.guoyyit.georasterizerjava.model.Polygon;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolygonClipperTest {

    private static final double EPS = 1e-9;

    @Test
    void fullyInsideIsReturnedUnchanged() {
        Polygon square = Geometries.polygon(
                Geometries.ring(1, 1, 2, 1, 2, 2, 1, 2));
        Optional<Polygon> clipped = PolygonClipper.apply(
                square, 0, 0, 10, 10, OutOfRangePolicy.CLIP);
        assertTrue(clipped.isPresent());
        assertTrue(clipped.get().exterior().vertices().size() == 4);
    }

    @Test
    void fullyOutsideIsDroppedUnderBothPolicies() {
        Polygon square = Geometries.polygon(
                Geometries.ring(20, 20, 30, 20, 30, 30, 20, 30));
        assertTrue(PolygonClipper.apply(
                square, 0, 0, 10, 10, OutOfRangePolicy.CLIP).isEmpty());
        assertTrue(PolygonClipper.apply(
                square, 0, 0, 10, 10, OutOfRangePolicy.REJECT).isEmpty());
    }

    @Test
    void partiallyOutsideIsRejectedUnderRejectPolicy() {
        Polygon crossing = Geometries.polygon(
                Geometries.ring(-5, -5, 5, -5, 5, 5, -5, 5));
        assertTrue(PolygonClipper.apply(
                crossing, 0, 0, 10, 10, OutOfRangePolicy.REJECT).isEmpty());
    }

    @Test
    void partiallyOutsideIsClippedWithCorrectArea() {
        Polygon crossing = Geometries.polygon(
                Geometries.ring(-5, -5, 5, -5, 5, 5, -5, 5));
        Optional<Polygon> clipped = PolygonClipper.apply(
                crossing, 0, 0, 10, 10, OutOfRangePolicy.CLIP);
        assertTrue(clipped.isPresent());
        assertEquals(25.0,
                PolygonArea.theoreticalArea(clipped.get(), FillRule.NON_ZERO), EPS);
        for (Point p : clipped.get().exterior().vertices()) {
            assertTrue(p.x() >= 0.0 && p.x() <= 10.0
                    && p.y() >= 0.0 && p.y() <= 10.0);
        }
    }

    @Test
    void touchingBoundaryIsInside() {
        Polygon touching = Geometries.polygon(
                Geometries.ring(0, 0, 5, 0, 5, 5, 0, 5));
        Optional<Polygon> underReject = PolygonClipper.apply(
                touching, 0, 0, 5, 5, OutOfRangePolicy.REJECT);
        assertTrue(underReject.isPresent());
        assertEquals(25.0,
                PolygonArea.theoreticalArea(underReject.get(), FillRule.NON_ZERO), EPS);
    }

    @Test
    void clipRemovesDegenerateHoleOnBoundary() {
        Polygon withHoleOutside = Geometries.polygon(
                Geometries.ring(0, 0, 10, 0, 10, 10, 0, 10),
                Geometries.ring(20, 20, 30, 20, 30, 30, 20, 30));
        Optional<Polygon> clipped = PolygonClipper.apply(
                withHoleOutside, 0, 0, 10, 10, OutOfRangePolicy.CLIP);
        assertTrue(clipped.isPresent());
        assertTrue(clipped.get().holes().isEmpty());
        assertEquals(100.0,
                PolygonArea.theoreticalArea(clipped.get(), FillRule.NON_ZERO), EPS);
    }
}
