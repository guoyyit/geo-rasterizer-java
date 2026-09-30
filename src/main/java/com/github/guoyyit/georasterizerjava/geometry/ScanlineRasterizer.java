package com.github.guoyyit.georasterizerjava.geometry;

import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.model.Point;
import com.github.guoyyit.georasterizerjava.model.Polygon;
import com.github.guoyyit.georasterizerjava.model.Ring;

import java.util.BitSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Scanline polygon rasterizer. For each row it computes crossings at the
 * cell-center y, sorts them deterministically and fills cell-center spans
 * according to the fill rule. Results depend only on geometry and the
 * grid, never on processing order.
 */
public final class ScanlineRasterizer {

    private ScanlineRasterizer() {
    }

    /**
     * Rasterizes one polygon restricted to the given grid-cell region.
     * Returned bits are local indices {@code row * regionWidth + col}.
     */
    public static BitSet rasterizeTile(
            Polygon polygon,
            FillRule fillRule,
            int gridWidth,
            int gridHeight,
            double cellSize,
            double xmin,
            double ymin,
            int regionCol0,
            int regionRow0,
            int regionWidth,
            int regionHeight
    ) {
        int cellCount = regionWidth * regionHeight;
        BitSet cells = new BitSet(cellCount);

        List<Edge> edges = new ArrayList<>();
        collectEdges(polygon.exterior(), edges);
        for (Ring hole : polygon.holes()) {
            collectEdges(hole, edges);
        }
        if (edges.isEmpty()) {
            return cells;
        }

        List<Edge> sorted = new ArrayList<>(edges);
        sorted.sort(Comparator.comparingDouble(e -> e.yLow));
        List<Edge> active = new ArrayList<>();

        int nextEdge = 0;
        for (int localRow = 0; localRow < regionHeight; localRow++) {
            int row = regionRow0 + localRow;
            if (row >= gridHeight) {
                break;
            }
            double scanY = ymin + (row + 0.5) * cellSize;

            while (nextEdge < sorted.size() && sorted.get(nextEdge).yLow < scanY) {
                active.add(sorted.get(nextEdge));
                nextEdge++;
            }
            active.removeIf(edge -> edge.yHigh <= scanY);

            if (active.isEmpty()) {
                continue;
            }

            List<Crossing> crossings = new ArrayList<>(active.size());
            for (Edge edge : active) {
                double t = (scanY - edge.yLow) / (edge.yHigh - edge.yLow);
                double x = edge.xLow + t * (edge.xHigh - edge.xLow);
                crossings.add(new Crossing(x, edge.direction));
            }
            crossings.sort(Comparator
                    .comparingDouble((Crossing c) -> c.x)
                    .thenComparingInt(c -> c.direction));

            int winding = 0;
            int parity = 0;
            for (int i = 0; i < crossings.size() - 1; i++) {
                Crossing left = crossings.get(i);
                if (fillRule == FillRule.NON_ZERO) {
                    winding += left.direction;
                } else {
                    parity ^= 1;
                }
                boolean inside = fillRule == FillRule.NON_ZERO ? winding != 0 : parity != 0;
                if (!inside) {
                    continue;
                }
                double spanRight = crossings.get(i + 1).x;
                // Cell c is covered iff its center lies strictly inside
                // the span: left < xmin + (c + 0.5) * cell < right.
                int firstCol = (int) Math.floor(
                        (left.x - xmin - cellSize * 0.5) / cellSize) + 1;
                int lastCol = (int) Math.ceil(
                        (spanRight - xmin - cellSize * 0.5) / cellSize) - 1;
                firstCol = Math.max(firstCol, regionCol0);
                lastCol = Math.min(lastCol, Math.min(regionCol0 + regionWidth - 1, gridWidth - 1));
                for (int col = firstCol; col <= lastCol; col++) {
                    int localCol = col - regionCol0;
                    cells.set(localRow * regionWidth + localCol);
                }
            }
        }
        return cells;
    }

    private static void collectEdges(Ring ring, List<Edge> out) {
        List<Point> vertices = ring.vertices();
        int n = vertices.size();
        for (int i = 0; i < n; i++) {
            Point a = vertices.get(i);
            Point b = vertices.get((i + 1) % n);
            if (a.y() == b.y()) {
                continue;
            }
            Point low = a.y() < b.y() ? a : b;
            Point high = a.y() < b.y() ? b : a;
            int direction = a.y() < b.y() ? 1 : -1;
            out.add(new Edge(low.x(), low.y(), high.x(), high.y(), direction));
        }
    }

    private record Edge(double xLow, double yLow, double xHigh, double yHigh, int direction) {
    }

    private record Crossing(double x, int direction) {
    }
}
