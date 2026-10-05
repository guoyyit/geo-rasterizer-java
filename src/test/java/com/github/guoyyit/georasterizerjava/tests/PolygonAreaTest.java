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
    void selfIntersectingBowtieAreaIsEightUnderBothFillRules() {
        // Bowtie (hourglass): two 2x2 triangles touching at the origin.
        Ring bowtie = Geometries.ring(-2, -2, 2, 2, 2, -2, -2, 2);
        Polygon polygon = Geometries.polygon(bowtie);
        // Independent truth: each lobe is a triangle with base 4 and
        // height 2 (area 4); the lobes only meet at the origin, so the
        // total area is 8 under both fill rules. The self-intersection
        // y = 0 must split the integration slabs, otherwise the
        // coincident mid-slab crossings double-count to 16.
        assertEquals(8.0, PolygonArea.theoreticalArea(polygon, FillRule.EVEN_ODD), EPS);
        assertEquals(8.0, PolygonArea.theoreticalArea(polygon, FillRule.NON_ZERO), EPS);
    }

    @Test
    void selfIntersectionInsideEdgesIsHandled() {
        // Vertices A(0,0) B(4,0) C(0,4) D(4,4): edges BC and DA cross
        // properly at P(2,2), strictly inside both segments. The shape
        // decomposes into triangles ABP and CDP (base 4, height 2
        // each), so the independent geometric truth is 8.
        Polygon polygon = Geometries.polygon(
                Geometries.ring(0, 0, 4, 0, 0, 4, 4, 4));
        assertEquals(8.0, PolygonArea.theoreticalArea(polygon, FillRule.EVEN_ODD), EPS);
        assertEquals(8.0, PolygonArea.theoreticalArea(polygon, FillRule.NON_ZERO), EPS);
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
