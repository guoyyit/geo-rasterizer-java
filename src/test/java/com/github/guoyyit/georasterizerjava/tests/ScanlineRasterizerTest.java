package com.github.guoyyit.georasterizerjava.tests;

import com.github.guoyyit.georasterizerjava.geometry.ScanlineRasterizer;
import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.model.Polygon;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanlineRasterizerTest {

    private static final double CELL = 1.0;

    private static BitSet full(Polygon polygon, FillRule rule, int w, int h) {
        return ScanlineRasterizer.rasterizeTile(
                polygon, rule, w, h, CELL, 0, 0, 0, 0, w, h);
    }

    @Test
    void fillsSquareCompletely() {
        Polygon square = Geometries.polygon(
                Geometries.ring(0, 0, 4, 0, 4, 4, 0, 4));
        BitSet cells = full(square, FillRule.NON_ZERO, 4, 4);
        assertEquals(16, cells.cardinality());
    }

    @Test
    void fillsSquareWithHole() {
        Polygon withHole = Geometries.polygon(
                Geometries.ring(0, 0, 10, 0, 10, 10, 0, 10),
                Geometries.ring(2, 2, 2, 8, 8, 8, 8, 2));
        BitSet cells = full(withHole, FillRule.EVEN_ODD, 10, 10);
        // 10x10 minus 6x6 interior hole = 64 cells.
        assertEquals(64, cells.cardinality());
        assertTrue(cells.get(0));
        assertTrue(cells.get(9));
        // Hole cells.
        for (int row = 2; row < 8; row++) {
            for (int col = 2; col < 8; col++) {
                assertEquals(false, cells.get(row * 10 + col));
            }
        }
    }

    @Test
    void repeatedRunsAreBitIdentical() {
        Polygon messy = Geometries.polygon(
                Geometries.ring(0, 0, 0, 0, 7, 0, 7, 5, 3, 9, 0, 5));
        BitSet first = full(messy, FillRule.NON_ZERO, 10, 10);
        for (int run = 0; run < 5; run++) {
            assertEquals(first, full(messy, FillRule.NON_ZERO, 10, 10));
        }
    }

    @Test
    void windingAndEvenOddCanDifferOnSelfIntersect() {
        // Outer CCW square 0..8 and a second ring 2..6 also traversed
        // CCW: winding reaches 2 inside, so non-zero fills everything,
        // while even-odd treats the inner ring as a hole.
        Polygon wound = Geometries.polygon(
                Geometries.ring(0, 0, 8, 0, 8, 8, 0, 8),
                Geometries.ring(2, 2, 6, 2, 6, 6, 2, 6));
        assertEquals(48, full(wound, FillRule.EVEN_ODD, 8, 8).cardinality());
        assertEquals(64, full(wound, FillRule.NON_ZERO, 8, 8).cardinality());

        // A single self-intersecting ring traversed twice in the same
        // direction gives winding 2 in the overlap -> non-zero covers
        // it, even-odd does not.
        Polygon doubled = Geometries.polygon(
                Geometries.ring(
                        0, 0, 6, 0, 6, 6, 0, 6,
                        0, 0, 6, 0, 6, 6, 0, 6));
        int evenOdd = full(doubled, FillRule.EVEN_ODD, 6, 6).cardinality();
        int nonZero = full(doubled, FillRule.NON_ZERO, 6, 6).cardinality();
        assertEquals(0, evenOdd);
        assertEquals(36, nonZero);
        assertNotEquals(evenOdd, nonZero);
    }

    @Test
    void degenerateRingsProduceNoCells() {
        Polygon line = Geometries.polygon(
                Geometries.ring(0, 0, 0, 0, 5, 5, 5, 5));
        assertEquals(0, full(line, FillRule.NON_ZERO, 10, 10).cardinality());
    }

    @Test
    void edgesOnCellCentersAreDeterministic() {
        // Edges at x = 0.5 / 1.5 ... align exactly with cell centers.
        Polygon aligned = Geometries.polygon(
                Geometries.ring(0.5, 0.5, 3.5, 0.5, 3.5, 3.5, 0.5, 3.5));
        BitSet expected = full(aligned, FillRule.NON_ZERO, 4, 4);
        // Exactly the 2x2 interior cells (cols/rows 1 and 2).
        assertEquals(4, expected.cardinality());
        for (int row : new int[] {1, 2}) {
            for (int col : new int[] {1, 2}) {
                assertTrue(expected.get(row * 4 + col));
            }
        }
        for (int run = 0; run < 4; run++) {
            assertEquals(expected, full(aligned, FillRule.EVEN_ODD, 4, 4));
        }
    }

    @Test
    void regionRasterizationIsConsistentWithFullGrid() {
        Polygon polygon = Geometries.polygon(
                Geometries.ring(-3, -3, 13, -3, 13, 13, -3, 13),
                Geometries.ring(1, 1, 5, 1, 5, 5, 1, 5));
        int w = 10;
        int h = 10;
        BitSet full = full(polygon, FillRule.EVEN_ODD, w, h);
        // Re-render as 5x5 quadrant tiles and stitch.
        BitSet stitched = new BitSet(w * h);
        for (int ty = 0; ty < 2; ty++) {
            for (int tx = 0; tx < 2; tx++) {
                BitSet part = ScanlineRasterizer.rasterizeTile(
                        polygon, FillRule.EVEN_ODD, w, h, CELL, 0, 0,
                        tx * 5, ty * 5, 5, 5);
                for (int r = 0; r < 5; r++) {
                    for (int c = 0; c < 5; c++) {
                        if (part.get(r * 5 + c)) {
                            stitched.set((ty * 5 + r) * w + tx * 5 + c);
                        }
                    }
                }
            }
        }
        assertEquals(full, stitched);
    }
}
