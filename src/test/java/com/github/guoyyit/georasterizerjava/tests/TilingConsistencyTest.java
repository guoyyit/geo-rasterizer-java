package com.github.guoyyit.georasterizerjava.tests;

import com.github.guoyyit.georasterizerjava.config.RasterConfig;
import com.github.guoyyit.georasterizerjava.model.Envelope;
import com.github.guoyyit.georasterizerjava.model.Feature;
import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.model.Polygon;
import com.github.guoyyit.georasterizerjava.service.RasterizationResult;
import com.github.guoyyit.georasterizerjava.service.RasterizationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分块一致性：不同分块大小逐像元一致，相邻块拼合与整体处理一致，无裂缝 / 漏像元 / 重叠。
 */
class TilingConsistencyTest {

    private static final Envelope ENVELOPE = new Envelope(-2, -2, 6, 6);

    private RasterizationResult rasterize(Polygon polygon, int tileCols, int tileRows) {
        RasterConfig config = RasterConfig.builder(0.7)
                .outputEnvelope(ENVELOPE)
                .fillRule(FillRule.ODD_EVEN)
                .tileSize(tileCols, tileRows)
                .parallelism(3)
                .build();
        return new RasterizationService()
                .rasterize(List.of(new Feature("f", polygon, 1.0, 0)), config, null);
    }

    private void assertSameCoverage(RasterizationResult expected, RasterizationResult actual) {
        assertEquals(expected.grid().cols(), actual.grid().cols());
        assertEquals(expected.grid().rows(), actual.grid().rows());
        int cols = expected.grid().cols();
        int rows = expected.grid().rows();
        int mismatches = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                double expectedCoverage = expected.coverageAt(col, row);
                double actualCoverage = actual.coverageAt(col, row);
                if (Math.abs(expectedCoverage - actualCoverage) > 1.0e-12) {
                    mismatches++;
                }
            }
        }
        assertEquals(0, mismatches, "cell-level mismatch count between tilings");
    }

    @Test
    void differentTileSizesProduceIdenticalCoverage() {
        Polygon polygon = TestGeometries.squareWithCwHole();
        RasterizationResult whole = rasterize(polygon, 1000, 1000);
        RasterizationResult oneByOne = rasterize(polygon, 1, 1);
        RasterizationResult threeByTwo = rasterize(polygon, 3, 2);
        RasterizationResult primeSizes = rasterize(polygon, 7, 5);

        assertSameCoverage(whole, oneByOne);
        assertSameCoverage(whole, threeByTwo);
        assertSameCoverage(whole, primeSizes);
    }

    @Test
    void tilingIsSeamlessAcrossSelfIntersectingGeometry() {
        RasterizationResult whole = rasterize(TestGeometries.bowtie(), 1000, 1000);
        RasterizationResult fragmented = rasterize(TestGeometries.bowtie(), 2, 3);
        assertSameCoverage(whole, fragmented);

        // 块拼合面积必须与理论面积一致（无裂缝漏算、无重叠重算）
        double tiledArea = fragmented.areaReport().rasterizedArea();
        double wholeArea = whole.areaReport().rasterizedArea();
        assertEquals(wholeArea, tiledArea, 1.0e-9);
        assertTrue(fragmented.areaReport().passed());
    }

    @Test
    void tilePartitionsHaveNoOverlapAndNoGaps() {
        RasterizationResult result = rasterize(TestGeometries.squareCcw(0, 0, 4, 4), 3, 3);
        long cellCount = result.tiles().stream()
                .mapToLong(tile -> (long) tile.cols() * tile.rows())
                .sum();
        assertEquals((long) result.grid().cols() * result.grid().rows(), cellCount,
                "tile cells must partition grid exactly (no overlap, no gap)");
    }
}
