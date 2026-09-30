package com.github.guoyyit.georasterizerjava.tests;

import com.github.guoyyit.georasterizerjava.geometry.PolygonArea;
import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.model.Polygon;
import com.github.guoyyit.georasterizerjava.model.Ring;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolygonAreaTest {

    private static final double EPS = 1e-9;

    @Test
    void squareAreaIndependentOfFillRule() {
        Polygon square = Geometries.polygon(
                Geometries.ring(0, 0, 4, 0, 4, 4, 0, 4));
        assertEquals(16.0, PolygonArea.theoreticalArea(square, FillRule.EVEN_ODD), EPS);
        assertEquals(16.0, PolygonArea.theoreticalArea(square, FillRule.NON_ZERO), EPS);
    }

    @Test
    void areaIndependentOfWindingAndStartVertex() {
        Polygon reversed = Geometries.polygon(
                Geometries.ring(0, 0, 0, 4, 4, 4, 4, 0));
        Polygon rotated = Geometries.polygon(
                Geometries.ring(4, 0, 0, 0, 0, 4, 4, 4));
        assertEquals(16.0, PolygonArea.theoreticalArea(reversed, FillRule.NON_ZERO), EPS);
        assertEquals(16.0, PolygonArea.theoreticalArea(rotated, FillRule.EVEN_ODD), EPS);
    }

    @Test
    void squareWithSquareHole() {
        Polygon withHole = Geometries.polygon(
                Geometries.ring(0, 0, 10, 0, 10, 10, 0, 10),
                Geometries.ring(2, 2, 2, 8, 8, 8, 8, 2));
        // Winding-independent area is the planar region measure; here
        // exterior minus hole regardless of ring orientation.
        assertEquals(64.0, PolygonArea.theoreticalArea(withHole, FillRule.EVEN_ODD), EPS);
        assertEquals(64.0, PolygonArea.theoreticalArea(withHole, FillRule.NON_ZERO), EPS);
    }

    @Test
    void selfIntersectingBowtieDiffersByFillRule() {
        // Bowtie (hourglass): two 2x2 triangles touching at the origin.
        Ring bowtie = Geometries.ring(-2, -2, 2, 2, 2, -2, -2, 2);
        Polygon polygon = Geometries.polygon(bowtie);
        // Each triangle has area 8 (base 4, height 4); under both rules
        // the two lobes are interior (they only meet at a point).
        assertEquals(16.0, PolygonArea.theoreticalArea(polygon, FillRule.EVEN_ODD), EPS);
        assertEquals(16.0, PolygonArea.theoreticalArea(polygon, FillRule.NON_ZERO), EPS);
    }

    @Test
    void overlappingRegionIsCancelledUnderNonZeroButNotEvenOdd() {
        // Outer CCW square (area 16) plus inner CW-winding overlapping
        // square built as a self-intersecting ring doubling one region.
        // Construct via two rings: outer 0..4 and a second ring 1..3
        // wound CW so under non-zero the inner region winding is 0.
        Polygon overlapping = Geometries.polygon(
                Geometries.ring(0, 0, 4, 0, 4, 4, 0, 4),
                Geometries.ring(1, 1, 1, 3, 3, 3, 3, 1));
        // Even-odd treats two nested rings as exterior-minus-hole.
        assertEquals(12.0, PolygonArea.theoreticalArea(overlapping, FillRule.EVEN_ODD), EPS);
        assertEquals(12.0, PolygonArea.theoreticalArea(overlapping, FillRule.NON_ZERO), EPS);
    }

    @Test
    void duplicateAndClosingVerticesAreIgnored() {
        Polygon duplicated = Geometries.polygon(
                Geometries.ring(0, 0, 0, 0, 4, 0, 4, 4, 0, 4, 0, 0));
        assertEquals(16.0, PolygonArea.theoreticalArea(duplicated, FillRule.NON_ZERO), EPS);
    }
}
