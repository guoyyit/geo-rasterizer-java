package com.github.guoyyit.georasterizerjava.tests;

import com.github.guoyyit.georasterizerjava.model.LinearRing;
import com.github.guoyyit.georasterizerjava.model.Point;
import com.github.guoyyit.georasterizerjava.model.Polygon;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用几何构造工具。
 */
final class TestGeometries {

    private TestGeometries() {
    }

    /** 逆时针轴对齐正方形（CCW）。 */
    static Polygon squareCcw(double minX, double minY, double maxX, double maxY) {
        return new Polygon(ring(List.of(
                new Point(minX, minY),
                new Point(maxX, minY),
                new Point(maxX, maxY),
                new Point(minX, maxY))), List.of());
    }

    /** 顺时针对应环。 */
    static LinearRing ringCw(double minX, double minY, double maxX, double maxY) {
        return ring(List.of(
                new Point(minX, minY),
                new Point(minX, maxY),
                new Point(maxX, maxY),
                new Point(maxX, minY)));
    }

    static LinearRing ringCcw(double minX, double minY, double maxX, double maxY) {
        return ring(List.of(
                new Point(minX, minY),
                new Point(maxX, minY),
                new Point(maxX, maxY),
                new Point(minX, maxY)));
    }

    /**
     * 蝴蝶结（四角星形）自相交多边形：(0,0)->(4,4)->(4,0)->(0,4)。
     * 面积（两种规则一致）为两个三角形之和 = 8。
     */
    static Polygon bowtie() {
        return new Polygon(ring(List.of(
                new Point(0, 0),
                new Point(4, 4),
                new Point(4, 0),
                new Point(0, 4))), List.of());
    }

    /** 外环 4x4 + 内孔 2x2（洞 CCW 与外环同向；ODD 下面积 12，WINDING 下面积 20）。 */
    static Polygon squareWithCcwHole() {
        return new Polygon(ringCcw(0, 0, 4, 4), List.of(ringCcw(1, 1, 3, 3)));
    }

    /** 外环 4x4 + 内孔 2x2（洞 CW 与外环反向；两种规则下面积均为 12）。 */
    static Polygon squareWithCwHole() {
        return new Polygon(ringCcw(0, 0, 4, 4), List.of(ringCw(1, 1, 3, 3)));
    }

    /** 含连续重复顶点与闭合重复顶点的 4x4 正方形。 */
    static Polygon squareWithDuplicateVertices() {
        List<Point> points = new ArrayList<>(List.of(
                new Point(0, 0),
                new Point(0, 0),
                new Point(4, 0),
                new Point(4, 4),
                new Point(4, 4),
                new Point(0, 4),
                new Point(0, 0)));
        return new Polygon(ring(points), List.of());
    }

    private static LinearRing ring(List<Point> points) {
        return new LinearRing(points);
    }
}
