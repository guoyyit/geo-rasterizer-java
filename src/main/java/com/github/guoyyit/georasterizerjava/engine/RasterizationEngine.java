package com.github.guoyyit.georasterizerjava.engine;

import com.github.guoyyit.georasterizerjava.config.RasterConfig;
import com.github.guoyyit.georasterizerjava.model.Envelope;
import com.github.guoyyit.georasterizerjava.model.Feature;
import com.github.guoyyit.georasterizerjava.model.Polygon;
import com.github.guoyyit.georasterizerjava.raster.GridDefinition;
import com.github.guoyyit.georasterizerjava.raster.TileResult;

import java.util.List;

/**
 * 栅格化引擎：确定性的扫描线覆盖率计算 + 顺序无关合并。
 * 无状态（除配置外），可被服务层在流式分块/并发场景下安全复用。
 */
public final class RasterizationEngine {

    private final RasterConfig config;
    private final FeatureCoverageRasterizer featureRasterizer;

    public RasterizationEngine(RasterConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        this.featureRasterizer = new FeatureCoverageRasterizer(config);
    }

    /**
     * 所有要素包围盒的并集；要素为空时返回 null（由调用方决定默认范围）。
     */
    public Envelope combinedEnvelope(List<Feature> features) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        boolean found = false;
        for (Feature feature : features) {
            Envelope envelope = feature.polygon().envelope();
            if (envelope == null) {
                continue;
            }
            found = true;
            minX = Math.min(minX, envelope.minX());
            minY = Math.min(minY, envelope.minY());
            maxX = Math.max(maxX, envelope.maxX());
            maxY = Math.max(maxY, envelope.maxY());
        }
        return found ? new Envelope(minX, minY, maxX, maxY) : null;
    }

    /**
     * 单个多边形在给定裁剪框内按配置填充规则计算的理论填充面积。
     */
    public double theoreticalArea(Polygon polygon, Envelope clip) {
        return featureRasterizer.theoreticalArea(polygon, clip);
    }

    /**
     * 在一个分块内栅格化所有要素并按配置语义合并。
     * 各要素的部分结果相互独立（可并发计算），合并规则与提交顺序无关。
     *
     * @return 合并后的分块结果；该块与所有几何都不相交时返回空块（覆盖率全为零）
     */
    public TileComputation rasterizeTile(List<Feature> features, GridDefinition grid,
                                         int tileX, int tileY,
                                         int startCol, int startRow,
                                         int localCols, int localRows) {
        TileMerger merger = TileMerger.forTile(config.mergeStrategy(),
                tileX, tileY, startCol, startRow, localCols, localRows);
        double perFeatureCoverageArea = 0.0;
        for (Feature feature : features) {
            TileResult partial = featureRasterizer.rasterize(feature, grid,
                    tileX, tileY, startCol, startRow, localCols, localRows);
            if (partial != null) {
                // 面积守恒逐要素校验：无论合并语义如何，每个要素自身覆盖率之和必须守恒
                perFeatureCoverageArea += partial.totalCoverage();
            }
            merger.merge(partial);
        }
        return new TileComputation(merger.build(), perFeatureCoverageArea);
    }

    /**
     * 单块计算结果：合并后的覆盖块 + 该块内逐要素覆盖率之和（面积守恒校验用，
     * 不受 MAX/PRIORITY 重叠只保留一个胜者的影响）。
     */
    public record TileComputation(TileResult mergedTile, double perFeatureCoverageSum) {
    }
}
