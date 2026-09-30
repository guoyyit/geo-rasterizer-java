package com.github.guoyyit.georasterizerjava.raster;

import com.github.guoyyit.georasterizerjava.model.Envelope;

/**
 * 输出栅格定义：原点、像元尺寸、行列数。
 */
public final class GridDefinition {

    private final Envelope envelope;
    private final double cellSize;
    private final int cols;
    private final int rows;

    public GridDefinition(Envelope envelope, double cellSize) {
        if (envelope == null) {
            throw new IllegalArgumentException("grid envelope must not be null");
        }
        if (!(cellSize > 0.0)) {
            throw new IllegalArgumentException("cellSize must be positive");
        }
        this.envelope = envelope;
        this.cellSize = cellSize;
        // 列/行数按精确浮点除法上取整；实际覆盖范围可能略大于 envelope，
        // 但所有边界严格落在 min + n*cell 上，保证分块间坐标完全一致、无浮点裂缝。
        // 末列/末行超出 envelope 的部分由 tileEnvelope 钳制到 envelope 边界。
        this.cols = (int) Math.ceil(envelope.width() / cellSize);
        this.rows = (int) Math.ceil(envelope.height() / cellSize);
        if (this.cols <= 0 || this.rows <= 0) {
            throw new IllegalArgumentException("grid must contain at least one cell in each dimension");
        }
    }

    public Envelope envelope() {
        return envelope;
    }

    public double cellSize() {
        return cellSize;
    }

    /**
     * 第 col 列左边界 x。
     */
    public double columnLeft(int col) {
        // 先按到 minX 的索引计算，保证相邻块共享完全相同的浮点坐标（避免长距离累加漂移）
        return envelope.minX() + (col * cellSize);
    }

    /**
     * 第 row 行下边界 y（行索引自下而上递增）。
     */
    public double rowBottom(int row) {
        return envelope.minY() + row * cellSize;
    }

    /**
     * 指定行列区间的精确包围盒：块间共享边界坐标，末尾块钳制到输出范围，保证块拼接无缝。
     */
    public Envelope tileEnvelope(int startCol, int startRow, int localCols, int localRows) {
        double minX = columnLeft(startCol);
        double maxX = startCol + localCols >= cols ? envelope.maxX() : columnLeft(startCol + localCols);
        double minY = rowBottom(startRow);
        double maxY = startRow + localRows >= rows ? envelope.maxY() : rowBottom(startRow + localRows);
        return new Envelope(minX, minY, maxX, maxY);
    }

    public int cols() {
        return cols;
    }

    public int rows() {
        return rows;
    }
}
