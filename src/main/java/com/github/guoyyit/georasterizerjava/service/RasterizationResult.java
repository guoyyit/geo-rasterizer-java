package com.github.guoyyit.georasterizerjava.service;

import com.github.guoyyit.georasterizerjava.raster.GridDefinition;

import com.github.guoyyit.georasterizerjava.raster.TileResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 栅格化任务结果：栅格定义、面积报告、处理元信息与像元访问入口。
 */
public final class RasterizationResult {

    private final GridDefinition gridDefinition;
    private final AreaReport areaReport;
    private final Map<String, Object> metadata;
    private final List<TileResult> tiles;

    public RasterizationResult(GridDefinition gridDefinition, AreaReport areaReport,
                               Map<String, Object> metadata, List<TileResult> tiles) {
        this.gridDefinition = gridDefinition;
        this.areaReport = areaReport;
        this.metadata = Collections.unmodifiableMap(metadata);
        this.tiles = List.copyOf(tiles);
    }

    public GridDefinition grid() {
        return gridDefinition;
    }

    public AreaReport areaReport() {
        return areaReport;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public List<TileResult> tiles() {
        return tiles;
    }

    /**
     * 全局像元覆盖率（[0,1]；COUNT 语义下为各要素覆盖率之和，可能大于 1）。
     */
    public double coverageAt(int col, int row) {
        TileResult tile = locateTile(col, row);
        return tile.coverageAt(tile.index(col - tile.startCol(), row - tile.startRow()));
    }

    /**
     * 全局像元聚合值（COUNT：覆盖面积计数；MAX：最大要素值；PRIORITY：覆盖要素值）。
     */
    public double valueAt(int col, int row) {
        TileResult tile = locateTile(col, row);
        return tile.valueAt(tile.index(col - tile.startCol(), row - tile.startRow()));
    }

    /**
     * 全局像元获胜要素 id（COUNT 下为最小 id，仅供追溯）。
     */
    public String winnerAt(int col, int row) {
        TileResult tile = locateTile(col, row);
        return tile.winnerAt(tile.index(col - tile.startCol(), row - tile.startRow()));
    }

    private TileResult locateTile(int col, int row) {
        if (col < 0 || col >= gridDefinition.cols() || row < 0 || row >= gridDefinition.rows()) {
            throw new IndexOutOfBoundsException(
                    "cell (" + col + "," + row + ") outside grid "
                            + gridDefinition.cols() + "x" + gridDefinition.rows());
        }
        for (TileResult tile : tiles) {
            if (col >= tile.startCol() && col < tile.startCol() + tile.cols()
                    && row >= tile.startRow() && row < tile.startRow() + tile.rows()) {
                return tile;
            }
        }
        throw new IllegalStateException("no tile covers cell (" + col + "," + row + ")");
    }
}
