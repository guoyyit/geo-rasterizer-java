package com.github.guoyyit.georasterizerjava.engine;

import com.github.guoyyit.georasterizerjava.model.Envelope;
import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.model.LinearRing;
import com.github.guoyyit.georasterizerjava.model.Point;
import com.github.guoyyit.georasterizerjava.model.Polygon;
import com.github.guoyyit.georasterizerjava.raster.GridDefinition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 基于水平扫描线的填充规则面积 / 覆盖率计算。
 *
 * <p>沿 y 方向把裁剪框切成相邻窄条，窄条边界取裁剪框上下边界、所有边端点 y、
 * 每对非竖直边在裁剪范围内的交点 y、以及像元行边界。于是每个窄条内部：边的左右顺序恒定，
 * 窄条中线上的水平射线不会经过任何顶点或交叉点，也不会与竖直边共线。射线只与非竖直边相交，
 * 按半开端点规则求交后，自左而右累计环绕数（WINDING）或奇偶翻转次数（ODD_EVEN），
 * 得到该窄条内被填充的 x 区间。窄条高乘以区间长即精确填充面积，把区间切到各像元列带
 * 得到逐像元覆盖率。</p>
 *
 * <p>对自相交、孔洞、重复顶点与轴对齐退化均成立；只依赖输入坐标，无随机或顺序因素，
 * 相同输入逐像元一致。</p>
 */
final class FillRuleSweep {

    @FunctionalInterface
    interface CellConsumer {
        void accept(int localCol, int localRow, double fraction, double area);
    }

    private FillRuleSweep() {
    }

    static double areaWithin(Polygon polygon, FillRule fillRule, Envelope clip) {
        return sweep(polygon, fillRule, clip, null, -1, -1, 0, 0, null);
    }

    static double sweep(Polygon polygon, FillRule fillRule, Envelope clip,
                        GridDefinition grid, int startCol, int startRow,
                        int tileCols, int tileRows, CellConsumer consumer) {
        if (clip.width() <= 0.0 || clip.height() <= 0.0) {
            return 0.0;
        }
        List<Edge> edges = collectEdges(polygon, clip);
        if (edges.isEmpty()) {
            return 0.0;
        }
        double[] breaks = buildBreakpoints(edges, clip, grid, startRow, tileRows);
        double totalArea = 0.0;

        for (int slab = 0; slab < breaks.length - 1; slab++) {
            double y0 = breaks[slab];
            double y1 = breaks[slab + 1];
            double slabHeight = y1 - y0;
            if (!(slabHeight > 0.0)) {
                continue;
            }
            double midY = y0 + slabHeight / 2.0;

            List<Crossing> crossings = crossingsAt(edges, midY);
            List<double[]> intervals = filledIntervals(crossings, fillRule, clip);
            for (double[] interval : intervals) {
                totalArea += integrateInterval(interval[0], interval[1], slabHeight,
                        grid, clip, startCol, startRow, tileCols, tileRows, midY, consumer);
            }
        }
        return totalArea;
    }

    private static List<Edge> collectEdges(Polygon polygon, Envelope clip) {
        List<Edge> edges = new ArrayList<>();
        addRingEdges(polygon.exterior(), edges, clip);
        for (LinearRing hole : polygon.holes()) {
            addRingEdges(hole, edges, clip);
        }
        return edges;
    }

    private static void addRingEdges(LinearRing ring, List<Edge> edges, Envelope clip) {
        if (ring == null || ring.size() < 2) {
            return;
        }
        List<Point> points = ring.points();
        for (int i = 0; i < points.size(); i++) {
            Point a = points.get(i);
            Point b = points.get((i + 1) % points.size());
            // 水平边与水平射线平行，永不相交，排除；竖直边必须保留（提供穿越）
            if (Double.compare(a.y(), b.y()) == 0) {
                continue;
            }
            // 水平射线沿 +x：边从射线下方穿到上方（b 在 a 之上）为 +1，否则为 -1
            int direction = b.y() >= a.y() ? 1 : -1;
            edges.add(new Edge(a, b, direction));
        }
    }

    private static double[] buildBreakpoints(List<Edge> edges, Envelope clip,
                                             GridDefinition grid, int startRow, int tileRows) {
        List<Double> breaks = new ArrayList<>();
        breaks.add(clip.minY());
        breaks.add(clip.maxY());
        for (Edge edge : edges) {
            addIfWithin(edge.a.y(), breaks, clip);
            addIfWithin(edge.b.y(), breaks, clip);
        }
        addIntersectionOrdinates(edges, breaks, clip);
        if (grid != null) {
            // 块起始行边界也强制入断点，保证窄条不会跨越块/行边界
            addIfWithin(grid.rowBottom(startRow), breaks, clip);
            for (int k = 1; k < tileRows; k++) {
                addIfWithin(grid.rowBottom(startRow + k), breaks, clip);
            }
        }
        double[] sorted = new double[breaks.size()];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = breaks.get(i);
        }
        Arrays.sort(sorted);
        int unique = 0;
        for (double value : sorted) {
            if (unique == 0 || value != sorted[unique - 1]) {
                sorted[unique++] = value;
            }
        }
        return Arrays.copyOf(sorted, unique);
    }

    private static void addIfWithin(double y, List<Double> breaks, Envelope clip) {
        if (y >= clip.minY() && y <= clip.maxY()) {
            breaks.add(y);
        }
    }

    /**
     * 所有非竖直边对在裁剪 y 范围内的内部交点纵坐标。加入后窄条中线不会落在交叉点上。
     */
    private static void addIntersectionOrdinates(List<Edge> edges, List<Double> breaks, Envelope clip) {
        for (int i = 0; i < edges.size(); i++) {
            for (int j = i + 1; j < edges.size(); j++) {
                Double intersectionY = intersectionOrdinate(edges.get(i), edges.get(j));
                if (intersectionY != null) {
                    addIfWithin(intersectionY, breaks, clip);
                }
            }
        }
    }

    private static Double intersectionOrdinate(Edge first, Edge second) {
        double x1 = first.a.x();
        double y1 = first.a.y();
        double x2 = first.b.x();
        double y2 = first.b.y();
        double x3 = second.a.x();
        double y3 = second.a.y();
        double x4 = second.b.x();
        double y4 = second.b.y();

        double denominator = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (denominator == 0.0) {
            return null;
        }
        double tNumerator = (x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4);
        double uNumerator = (x1 - x3) * (y1 - y2) - (y1 - y3) * (x1 - x2);
        double t = tNumerator / denominator;
        double u = uNumerator / denominator;
        if (t <= 0.0 || t >= 1.0 || u <= 0.0 || u >= 1.0) {
            return null;
        }
        return y1 + t * (y2 - y1);
    }

    private static List<Crossing> crossingsAt(List<Edge> edges, double midY) {
        List<Crossing> crossings = new ArrayList<>(edges.size());
        for (Edge edge : edges) {
            Double crossingX = crossingX(edge, midY);
            if (crossingX != null) {
                crossings.add(new Crossing(crossingX, edge.direction));
            }
        }
        crossings.sort((left, right) -> {
            int byX = Double.compare(left.x, right.x);
            if (byX != 0) {
                return byX;
            }
            return Integer.compare(left.direction, right.direction);
        });
        return crossings;
    }

    /**
     * 水平射线 y=midY 与非竖直边的交点 x，半开端点规则：按 y 增大方向取 [lowY, highY)，
     * 低端点含、高端点不含，使顶点处两条邻边恰好计一次。返回 null 表示不相交。
     */
    private static Double crossingX(Edge edge, double midY) {
        double lowY = Math.min(edge.a.y(), edge.b.y());
        double highY = Math.max(edge.a.y(), edge.b.y());
        // 竖直边与斜边都必须满足半开 y 范围，几何外窄条才不会被误判为内部
        if (!(midY >= lowY && midY < highY)) {
            return null;
        }
        if (Double.compare(edge.a.x(), edge.b.x()) == 0) {
            return edge.a.x();
        }
        double ratio = (midY - edge.a.y()) / (edge.b.y() - edge.a.y());
        return edge.a.x() + (edge.b.x() - edge.a.x()) * ratio;
    }

    private static List<double[]> filledIntervals(List<Crossing> crossings, FillRule fillRule,
                                                  Envelope clip) {
        List<double[]> intervals = new ArrayList<>();
        int winding = 0;
        boolean odd = false;
        boolean inside = false;
        double segmentStart = clip.minX();
        for (Crossing crossing : crossings) {
            boolean wasInside = inside;
            if (fillRule == FillRule.WINDING) {
                winding += crossing.direction;
                inside = winding != 0;
            } else {
                odd = !odd;
                inside = odd;
            }
            if (!wasInside && inside) {
                segmentStart = crossing.x;
            } else if (wasInside && !inside) {
                double left = Math.max(segmentStart, clip.minX());
                double right = Math.min(crossing.x, clip.maxX());
                if (right > left) {
                    intervals.add(new double[]{left, right});
                }
            }
        }
        return intervals;
    }

    private static double integrateInterval(double left, double right, double slabHeight,
                                            GridDefinition grid, Envelope clip,
                                            int startCol, int startRow,
                                            int tileCols, int tileRows, double midY,
                                            CellConsumer consumer) {
        double width = right - left;
        if (width <= 0.0) {
            return 0.0;
        }
        if (grid == null) {
            return width * slabHeight;
        }
        double cellSize = grid.cellSize();
        double tileLeft = grid.columnLeft(startCol);
        double tileBottom = grid.rowBottom(startRow);
        int localRow = (int) Math.floor((midY - tileBottom) / cellSize);
        localRow = Math.max(0, Math.min(localRow, tileRows - 1));

        double delivered = 0.0;
        int localCol = (int) Math.floor((left - tileLeft) / cellSize);
        localCol = Math.max(0, Math.min(localCol, tileCols - 1));
        double cursor = left;
        while (cursor < right) {
            double bandLeft = grid.columnLeft(startCol + localCol);
            double bandRight = localCol + 1 == tileCols
                    ? clip.maxX()
                    : grid.columnLeft(startCol + localCol + 1);
            double overlap = Math.min(right, bandRight) - Math.max(cursor, bandLeft);
            if (overlap > 0.0) {
                double area = overlap * slabHeight;
                double fraction = Math.max(0.0, Math.min(1.0, area / (cellSize * cellSize)));
                if (fraction > 0.0) {
                    consumer.accept(localCol, localRow, fraction, area);
                    delivered += area;
                }
            }
            cursor = bandRight;
            localCol++;
        }
        return delivered;
    }

    private record Edge(Point a, Point b, int direction) {
    }

    private record Crossing(double x, int direction) {
    }
}
