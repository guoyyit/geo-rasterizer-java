package com.github.guoyyit.georasterizerjava.tests;

import com.github.guoyyit.georasterizerjava.config.RasterConfig;
import com.github.guoyyit.georasterizerjava.model.Envelope;
import com.github.guoyyit.georasterizerjava.model.Feature;
import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.service.AreaReport;
import com.github.guoyyit.georasterizerjava.service.AreaConservationException;
import com.github.guoyyit.georasterizerjava.service.RasterizationService;
import com.github.guoyyit.georasterizerjava.raster.TileResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式分块：逐块回调覆盖全部分块，顺序确定，面积校验失败时不发布任何块。
 */
class StreamingProcessingTest {

    private RasterConfig config(int tileCols, int tileRows) {
        return RasterConfig.builder(0.5)
                .outputEnvelope(new Envelope(0, 0, 6, 6))
                .fillRule(FillRule.ODD_EVEN)
                .tileSize(tileCols, tileRows)
                .parallelism(2)
                .build();
    }

    @Test
    void streamsEveryTileExactlyOnceInOrder() {
        List<Feature> features = List.of(
                new Feature("f", TestGeometries.squareWithCwHole(), 1.0, 0));
        List<String> visited = new ArrayList<>();
        AreaReport report = new RasterizationService()
                .rasterizeStreaming(features, config(3, 3), null, tile ->
                        visited.add(tile.tileX() + "," + tile.tileY()));

        // 12x12 网格、3x3 分块 => 4x4 = 16 块
        assertEquals(16, visited.size());
        assertEquals("0,0", visited.get(0));
        assertEquals("3,3", visited.get(visited.size() - 1));
        assertTrue(report.passed());

        // 顺序严格按 tileY 外、tileX 内
        for (int tileY = 0; tileY < 4; tileY++) {
            for (int tileX = 0; tileX < 4; tileX++) {
                assertEquals(tileX + "," + tileY,
                        visited.get(tileY * 4 + tileX));
            }
        }
    }

    @Test
    void streamingDoesNotPublishTilesWhenAreaCheckFails() {
        java.util.List<Feature> features = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            var ring = new com.github.guoyyit.georasterizerjava.model.LinearRing(java.util.List.of(
                    new com.github.guoyyit.georasterizerjava.model.Point(0.1 + i * 0.01, 0.2),
                    new com.github.guoyyit.georasterizerjava.model.Point(9.7 - i * 0.013, 0.3),
                    new com.github.guoyyit.georasterizerjava.model.Point(9.5 - i * 0.011, 9.8),
                    new com.github.guoyyit.georasterizerjava.model.Point(0.3 + i * 0.007, 9.6)));
            features.add(new Feature("s" + i,
                    new com.github.guoyyit.georasterizerjava.model.Polygon(ring, List.of()),
                    1.0, 0));
        }
        RasterConfig strict = RasterConfig.builder(0.13)
                .outputEnvelope(new Envelope(0, 0, 10, 10))
                .fillRule(FillRule.ODD_EVEN)
                .tileSize(7, 7)
                .areaTolerance(0.0)
                .build();

        List<TileResult> published = new ArrayList<>();
        assertThrows(AreaConservationException.class, () -> new RasterizationService()
                .rasterizeStreaming(features, strict, null, published::add));
        // 两阶段提交：面积校验在发布之前，失败时下游拿不到任何分块
        assertTrue(published.isEmpty(), "no tile may be published after area failure");
    }
}
