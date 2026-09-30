package com.github.guoyyit.georasterizerjava.raster;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 单个分块的栅格结果：覆盖率、聚合值、获胜要素 id。
 */
public final class TileResult {

    private final int tileX;
    private final int tileY;
    private final int startCol;
    private final int startRow;
    private final int cols;
    private final int rows;
    private final double[] coverage;
    private final double[] values;
    private final int[] priorities;
    private final String[] winnerIds;

    public TileResult(int tileX, int tileY, int startCol, int startRow, int cols, int rows) {
        if (cols <= 0 || rows <= 0) {
            throw new IllegalArgumentException("tile dimensions must be positive");
        }
        this.tileX = tileX;
        this.tileY = tileY;
        this.startCol = startCol;
        this.startRow = startRow;
        this.cols = cols;
        this.rows = rows;
        int size = cols * rows;
        this.coverage = new double[size];
        this.values = new double[size];
        this.priorities = new int[size];
        this.winnerIds = new String[size];
    }

    public int index(int localCol, int localRow) {
        if (localCol < 0 || localCol >= cols || localRow < 0 || localRow >= rows) {
            throw new IndexOutOfBoundsException(
                    "local cell (" + localCol + "," + localRow + ") outside tile " + cols + "x" + rows);
        }
        return localRow * cols + localCol;
    }

    public int tileX() {
        return tileX;
    }

    public int tileY() {
        return tileY;
    }

    public int startCol() {
        return startCol;
    }

    public int startRow() {
        return startRow;
    }

    public int cols() {
        return cols;
    }

    public int rows() {
        return rows;
    }

    public double coverageAt(int index) {
        return coverage[index];
    }

    public double valueAt(int index) {
        return values[index];
    }

    public int priorityAt(int index) {
        return priorities[index];
    }

    public String winnerAt(int index) {
        return winnerIds[index];
    }

    public void setCell(int index, double cellCoverage, double cellValue, int priority,
                        String winnerId) {
        coverage[index] = cellCoverage;
        values[index] = cellValue;
        priorities[index] = priority;
        winnerIds[index] = winnerId;
    }

    /**
     * 覆盖率非零的像元数量。
     */
    public int filledCellCount() {
        int count = 0;
        for (double cellCoverage : coverage) {
            if (cellCoverage != 0.0) {
                count++;
            }
        }
        return count;
    }

    /**
     * 覆盖率总和（乘以像元面积即栅格化覆盖面积）。
     */
    public double totalCoverage() {
        double sum = 0.0;
        for (double cellCoverage : coverage) {
            sum += cellCoverage;
        }
        return sum;
    }

    /**
     * 确定性二进制序列化，用于检查点落盘。
     */
    public void encode(OutputStream output) throws IOException {
        DataOutputStream out = new DataOutputStream(output);
        out.writeInt(tileX);
        out.writeInt(tileY);
        out.writeInt(startCol);
        out.writeInt(startRow);
        out.writeInt(cols);
        out.writeInt(rows);
        for (int i = 0; i < coverage.length; i++) {
            out.writeDouble(coverage[i]);
            out.writeDouble(values[i]);
            out.writeInt(priorities[i]);
            String winnerId = winnerIds[i];
            out.writeBoolean(winnerId != null);
            if (winnerId != null) {
                out.writeUTF(winnerId);
            }
        }
        out.flush();
    }

    public static TileResult decode(InputStream input) throws IOException {
        DataInputStream in = new DataInputStream(input);
        int decodedTileX = in.readInt();
        int decodedTileY = in.readInt();
        int decodedStartCol = in.readInt();
        int decodedStartRow = in.readInt();
        int decodedCols = in.readInt();
        int decodedRows = in.readInt();
        TileResult result = new TileResult(decodedTileX, decodedTileY, decodedStartCol, decodedStartRow,
                decodedCols, decodedRows);
        for (int i = 0; i < result.coverage.length; i++) {
            double cellCoverage = in.readDouble();
            double cellValue = in.readDouble();
            int priority = in.readInt();
            String winnerId = in.readBoolean() ? in.readUTF() : null;
            result.setCell(i, cellCoverage, cellValue, priority, winnerId);
        }
        return result;
    }
}
