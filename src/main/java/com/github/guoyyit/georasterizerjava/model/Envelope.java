package com.github.guoyyit.georasterizerjava.model;

/**
 * 轴对齐包围盒（最小/最大坐标）。
 */
public record Envelope(double minX, double minY, double maxX, double maxY) {

    public Envelope {
        if (Double.isNaN(minX) || Double.isNaN(minY) || Double.isNaN(maxX) || Double.isNaN(maxY)) {
            throw new IllegalArgumentException("envelope coordinates must not be NaN");
        }
        if (minX > maxX || minY > maxY) {
            throw new IllegalArgumentException("envelope minimum must not exceed maximum");
        }
    }

    public boolean contains(Envelope other) {
        return other.minX >= minX && other.maxX <= maxX
                && other.minY >= minY && other.maxY <= maxY;
    }

    public boolean intersects(Envelope other) {
        return other.minX <= maxX && other.maxX >= minX
                && other.minY <= maxY && other.maxY >= minY;
    }

    public double width() {
        return maxX - minX;
    }

    public double height() {
        return maxY - minY;
    }
}
