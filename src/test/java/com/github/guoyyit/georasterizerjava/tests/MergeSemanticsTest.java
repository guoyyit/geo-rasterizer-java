package com.github.guoyyit.georasterizerjava.tests;

import com.github.guoyyit.georasterizerjava.config.RasterConfig;
import com.github.guoyyit.georasterizerjava.model.Envelope;
import com.github.guoyyit.georasterizerjava.model.Feature;
import com.github.guoyyit.georasterizerjava.model.MergeStrategy;
import com.github.guoyyit.georasterizerjava.model.Polygon;
import com.github.guoyyit.georasterizerjava.service.RasterizationResult;
import com.github.guoyyit.georasterizerjava.service.RasterizationService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多要素并发合并：COUNT / MAX / PRIORITY 语义正确，且结果与提交顺序无关。
 */
class MergeSemanticsTest {

    private static final Envelope ENVELOPE = new Envelope(0, 0, 4, 4);

    private RasterizationResult run(List<Feature> features, MergeStrategy strategy) {
        RasterConfig config = RasterConfig.builder(1.0)
                .outputEnvelope(ENVELOPE)
                .mergeStrategy(strategy)
                .tileSize(2, 2)
                .parallelism(4)
                .build();
        return new RasterizationService().rasterize(features, config, null);
    }

    private Feature feature(String id, double minX, double minY, double maxX, double maxY,
                            double value, int priority) {
        Polygon polygon = TestGeometries.squareCcw(minX, minY, maxX, maxY);
        return new Feature(id, polygon, value, priority);
    }

    private List<Feature> overlappingFeatures() {
        List<Feature> features = new ArrayList<>();
        features.add(feature("a", 0, 0, 3, 3, 10.0, 1));
        features.add(feature("b", 1, 1, 4, 4, 5.0, 5));
        features.add(feature("c", 0, 0, 2, 2, 7.0, 3));
        return features;
    }

    @Test
    void countSumsCoverageRegardlessOfOrder() {
        List<Feature> features = overlappingFeatures();
        RasterizationResult first = run(features, MergeStrategy.COUNT);

        List<Feature> shuffled = new ArrayList<>(features);
        Collections.reverse(shuffled);
        RasterizationResult second = run(shuffled, MergeStrategy.COUNT);

        // 重叠像元 (1,1) 同时被 a,b,c 三个要素覆盖，COUNT 覆盖率应为 3
        assertEquals(3.0, first.coverageAt(1, 1), 1.0e-12);
        // 仅被 a,b 覆盖的像元 (2,2) 覆盖率为 2
        assertEquals(2.0, first.coverageAt(2, 2), 1.0e-12);
        assertCellwiseEqual(first, second);
    }

    @Test
    void maxPicksHighestValueRegardlessOfOrder() {
        List<Feature> features = overlappingFeatures();
        RasterizationResult ascending = run(features, MergeStrategy.MAX);
        List<Feature> reversed = new ArrayList<>(features);
        Collections.reverse(reversed);
        RasterizationResult descending = run(reversed, MergeStrategy.MAX);

        // (1,1) 被 a(10),b(5),c(7) 覆盖，MAX 值为 10，获胜者 a
        assertEquals(10.0, ascending.valueAt(1, 1), 1.0e-12);
        assertEquals("a", ascending.winnerAt(1, 1));
        // (3,3) 只被 b(5) 覆盖
        assertEquals(5.0, ascending.valueAt(3, 3), 1.0e-12);
        assertCellwiseEqual(ascending, descending);
    }

    @Test
    void priorityOverridesByPriorityThenIdRegardlessOfOrder() {
        List<Feature> features = overlappingFeatures();
        RasterizationResult ascending = run(features, MergeStrategy.PRIORITY);
        List<Feature> reversed = new ArrayList<>(features);
        Collections.reverse(reversed);
        RasterizationResult descending = run(reversed, MergeStrategy.PRIORITY);

        // (1,1) 被 a(prio1),b(prio5),c(prio3) 覆盖，最高优先级为 b
        assertEquals("b", ascending.winnerAt(1, 1));
        assertEquals(5.0, ascending.valueAt(1, 1), 1.0e-12);
        // (0,0) 被 a(prio1),c(prio3) 覆盖，c 优先
        assertEquals("c", ascending.winnerAt(0, 0));
        assertCellwiseEqual(ascending, descending);
    }

    @Test
    void maxTieBreaksDeterministicallyByFeatureId() {
        List<Feature> features = List.of(
                feature("zeta", 0, 0, 2, 2, 9.0, 1),
                feature("alpha", 0, 0, 2, 2, 9.0, 9));
        RasterizationResult result = run(features, MergeStrategy.MAX);
        // value 相同，id 字典序较小者 alpha 获胜，与输入顺序无关
        assertEquals("alpha", result.winnerAt(0, 0));
        assertEquals(9.0, result.valueAt(0, 0), 1.0e-12);
    }

    private void assertCellwiseEqual(RasterizationResult expected, RasterizationResult actual) {
        int cols = expected.grid().cols();
        int rows = expected.grid().rows();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                assertEquals(expected.coverageAt(col, row), actual.coverageAt(col, row), 1.0e-12,
                        "coverage mismatch at " + col + "," + row);
                assertEquals(expected.valueAt(col, row), actual.valueAt(col, row), 1.0e-12,
                        "value mismatch at " + col + "," + row);
                assertEquals(expected.winnerAt(col, row), actual.winnerAt(col, row),
                        "winner mismatch at " + col + "," + row);
            }
        }
        assertTrue(true);
    }
}
