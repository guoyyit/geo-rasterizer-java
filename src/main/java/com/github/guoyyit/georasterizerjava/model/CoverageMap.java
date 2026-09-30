package com.github.guoyyit.georasterizerjava.model;

import java.util.BitSet;

/**
 * Final raster coverage: one aggregated value per cell plus the id of the
 * feature owning the cell for MAX/PRIORITY semantics ({@code -1} otherwise).
 * Row 0 is the bottom row; columns increase with x.
 */
public final class CoverageMap {

    private final int width;
    private final int height;
    private final double cellSize;
    private final double xmin;
    private final double ymin;
    private final double[] values;
    private final long[] owners;
    private final BitSet covered;

    public CoverageMap(int width, int height, double cellSize, double xmin, double ymin) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("coverage dimensions must be positive");
        }
        this.width = width;
        this.height = height;
        this.cellSize = cellSize;
        this.xmin = xmin;
        this.ymin = ymin;
        this.values = new double[width * height];
        this.owners = new long[width * height];
        this.covered = new BitSet(width * height);
        java.util.Arrays.fill(this.owners, -1L);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public double cellSize() {
        return cellSize;
    }

    public double xmin() {
        return xmin;
    }

    public double ymin() {
        return ymin;
    }

    private int index(int col, int row) {
        if (col < 0 || col >= width || row < 0 || row >= height) {
            throw new IndexOutOfBoundsException(
                    "cell (" + col + "," + row + ") outside grid " + width + "x" + height);
        }
        return row * width + col;
    }

    public double valueAt(int col, int row) {
        return values[index(col, row)];
    }

    public long ownerAt(int col, int row) {
        return owners[index(col, row)];
    }

    public void set(int col, int row, double value, long owner) {
        int idx = index(col, row);
        values[idx] = value;
        owners[idx] = owner;
        covered.set(idx);
    }

    public boolean isCovered(int col, int row) {
        return covered.get(index(col, row));
    }

    public long coveredCellCount() {
        return covered.cardinality();
    }
}
