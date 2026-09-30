package com.github.guoyyit.georasterizerjava.tests;

import com.github.guoyyit.georasterizerjava.config.RasterConfig;
import com.github.guoyyit.georasterizerjava.model.Envelope;
import com.github.guoyyit.georasterizerjava.model.Feature;
import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.model.MergeStrategy;
import com.github.guoyyit.georasterizerjava.model.Polygon;
import com.github.guoyyit.georasterizerjava.service.RasterizationResult;
import com.github.guoyyit.georasterizerjava.service.RasterizationService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 确定性：同一输入在不同要素顺序、不同并发度、重复执行下逐像元一致，可复现。
 */
class DeterminismTest {

    private RasterizationResult run(List<Feature> features, int parallelism) {
        RasterConfig config = RasterConfig.builder(0.6)
                .outputEnvelope(new Envelope(0, 0, 5, 5))
                .fillRule(FillRule.WINDING)
                .mergeStrategy(MergeStrategy.MAX)
                .tileSize(2, 2)
                .parallelism(parallelism)
                .build();
        return new RasterizationService().rasterize(features, config, null);
    }

    private List<Feature> features() {
        List<Feature> features = new ArrayList<>();
        features.add(new Feature("a", TestGeometries.bowtie(), 3.0, 1));
        Polygon hole = TestGeometries.squareWithCwHole();
        features.add(new Feature("b", hole, 9.0, 2));
        features.add(new Feature("c", TestGeometries.squareCcw(1, 1, 3, 3), 5.0, 3));
        return features;
    }

    @Test
    void sameInputReproducesAcrossOrderAndParallelism() {
        List<Feature> ordered = features();
        RasterizationResult serial = run(ordered, 1);
        RasterizationResult parallel = run(ordered, 8);

        List<Feature> shuffled = new ArrayList<>(ordered);
        Collections.shuffle(shuffled, new java.util.Random(42));
        RasterizationResult shuffledRun = run(shuffled, 4);

        assertIdentical(serial, parallel);
        assertIdentical(serial, shuffledRun);

        // 再次重复执行，结果仍一致
        RasterizationResult repeat = run(ordered, 8);
        assertIdentical(serial, repeat);
    }

    private void assertIdentical(RasterizationResult expected, RasterizationResult actual) {
        int cols = expected.grid().cols();
        int rows = expected.grid().rows();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                assertEquals(expected.coverageAt(col, row), actual.coverageAt(col, row), 0.0);
                assertEquals(expected.valueAt(col, row), actual.valueAt(col, row), 0.0);
                assertEquals(expected.winnerAt(col, row), actual.winnerAt(col, row));
            }
        }
    }
}
