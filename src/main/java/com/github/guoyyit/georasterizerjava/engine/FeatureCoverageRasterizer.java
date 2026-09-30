package com.github.guoyyit.georasterizerjava.engine;

import com.github.guoyyit.georasterizerjava.config.RasterConfig;
import com.github.guoyyit.georasterizerjava.model.Envelope;
import com.github.guoyyit.georasterizerjava.model.Feature;
import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.raster.GridDefinition;
import com.github.guoyyit.georasterizerjava.raster.TileResult;

/**
 * 单个要素在一个分块上的精确覆盖率栅格化（占位骨架）。
 */
final class FeatureCoverageRasterizer {

    private final FillRule fillRule;

    FeatureCoverageRasterizer(RasterConfig config) {
        this.fillRule = config.fillRule();
    }

    /**
     * 在指定分块内栅格化单个要素。
     *
     * @param grid      全图栅格定义
     * @param tileX     分块列号
     * @param tileY     分块行号
     * @param startCol  分块全局起始列
     * @param startRow  分块全局起始行
     * @param localCols 分块列数
     * @param localRows 分块行数
     * @return 该要素在此分块上的覆盖率结果；几何与分块不相交时返回 null
     */
    TileResult rasterize(Feature feature, GridDefinition grid,
                         int tileX, int tileY,
                         int startCol, int startRow,
                         int localCols, int localRows) {
        Envelope geometryEnvelope = feature.polygon().envelope();
        if (geometryEnvelope == null) {
            return null;
        }
        Envelope tileEnvelope = grid.tileEnvelope(startCol, startRow, localCols, localRows);
        if (!geometryEnvelope.intersects(tileEnvelope)) {
            return null;
        }

        TileResult tile = new TileResult(tileX, tileY, startCol, startRow, localCols, localRows);
        FillRuleSweep.sweep(feature.polygon(), fillRule, tileEnvelope,
                grid, startCol, startRow, localCols, localRows,
                (localCol, localRow, fraction, area) -> {
                    int index = tile.index(localCol, localRow);
                    // 同一像元可能被多个填充区间段（孔洞两侧）覆盖，按面积累加，不能覆盖
                    double combined = clampFraction(tile.coverageAt(index) + fraction);
                    tile.setCell(index, combined, feature.value(),
                            feature.priority(), feature.id());
                });
        return tile.filledCellCount() == 0 ? null : tile;
    }

    private static double clampFraction(double fraction) {
        if (fraction < 0.0) {
            return 0.0;
        }
        if (fraction > 1.0) {
            return 1.0;
        }
        return fraction;
    }

    /**
     * 要素在给定裁剪框内的理论填充面积（同扫描线引擎产出，保证面积校验口径一致）。
     */
    double theoreticalArea(com.github.guoyyit.georasterizerjava.model.Polygon polygon, Envelope clip) {
        return FillRuleSweep.areaWithin(polygon, fillRule, clip);
    }
}
