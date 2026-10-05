package com.github.guoyyit.georasterizerjava.tests;

import com.github.guoyyit.georasterizerjava.model.ConfigBuilder;
import com.github.guoyyit.georasterizerjava.model.CoverageMap;
import com.github.guoyyit.georasterizerjava.model.Feature;
import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.model.MergeSemantics;
import com.github.guoyyit.georasterizerjava.model.OutOfRangePolicy;
import com.github.guoyyit.georasterizerjava.model.RasterConfig;
import com.github.guoyyit.georasterizerjava.model.RasterizationResult;
import com.github.guoyyit.georasterizerjava.raster.RasterizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage values and owners must be identical for the same input set
 * regardless of submission order, worker count or tile size.
 */
class MergeOrderDeterminismTest {

    private static List<Feature> fullyOverlappingFeatures() {
        List<Feature> features = new ArrayList<>();
        // Features 30 and 50 tie on the largest weight 9.0 (MAX tie);
        // features 30 and 50 also tie on the largest priority 7
        // (PRIORITY tie). COUNT owner must be the smallest id (30).
        features.add(Geometries.feature(30, square(), 9.0, 7));
        features.add(Geometries.feature(50, square(), 9.0, 5));
        features.add(Geometries.feature(40, square(), 4.0, 7));
        features.add(Geometries.feature(80, square(), 2.0, 2));
        features.add(Geometries.feature(60, square(), 9.0, 1));
        return features;
    }

    private static com.github.guoyyit.georasterizerjava.model.Polygon square() {
        return Geometries.polygon(
                Geometries.ring(0, 0, 2, 0, 2, 2, 0, 2));
    }

    private static RasterConfig config(
            Path dir, MergeSemantics semantics, int workers, int tilePixels
    ) {
        return new ConfigBuilder()
                .cellSize(0.25)
                .extent(0, 0, 4, 4)
                .fillRule(FillRule.NON_ZERO)
                .mergeSemantics(semantics)
                .outOfRangePolicy(OutOfRangePolicy.CLIP)
                .areaRelativeTolerance(0.10)
                .tileSize(tilePixels, tilePixels)
                .workerThreads(workers)
                .assembleCoverage(true)
                .checkpointDirectory(dir)
                .build();
    }

    private static void assertPixelIdentical(CoverageMap expected, CoverageMap actual) {
        assertEquals(expected.width(), actual.width());
        assertEquals(expected.height(), actual.height());
        for (int row = 0; row < expected.height(); row++) {
            for (int col = 0; col < expected.width(); col++) {
                assertEquals(expected.valueAt(col, row), actual.valueAt(col, row), 0.0,
                        "value mismatch at col=" + col + " row=" + row);
                assertEquals(expected.ownerAt(col, row), actual.ownerAt(col, row),
                        "owner mismatch at col=" + col + " row=" + row);
            }
        }
    }

    private static List<List<Feature>> permutations(List<Feature> features) {
        List<List<Feature>> result = new ArrayList<>();
        Random random = new Random(987654321L);
        for (int trial = 0; trial < 8; trial++) {
            List<Feature> shuffled = new ArrayList<>(features);
            Collections.shuffle(shuffled, random);
            result.add(shuffled);
        }
        // The identity order is included by construction of the suite.
        result.add(new ArrayList<>(features));
        return result;
    }

    @Test
    void submissionOrderThreadsAndTilesCannotChangeCoverage(@TempDir Path tempDir) {
        int[] workerCounts = {1, 2, 5};
        int[] tileSizes = {3, 8, 17};
        for (MergeSemantics semantics : MergeSemantics.values()) {
            CoverageMap reference = null;
            int runIndex = 0;
            for (List<Feature> order : permutations(fullyOverlappingFeatures())) {
                for (int workers : workerCounts) {
                    for (int tilePixels : tileSizes) {
                        Path dir = tempDir.resolve(
                                semantics + "-run" + (runIndex++)
                                        + "-w" + workers + "-t" + tilePixels);
                        RasterizationResult result = new RasterizationService().rasterize(
                                config(dir, semantics, workers, tilePixels), order);
                        if (reference == null) {
                            reference = result.coverage();
                        } else {
                            assertPixelIdentical(reference, result.coverage());
                        }
                    }
                }
            }
            assertFixedTieRules(reference, semantics);
        }
    }

    private void assertFixedTieRules(CoverageMap coverage, MergeSemantics semantics) {
        // Cell centers strictly inside the unit square start at (0.125,
        // 0.125): columns/rows 0..7 are covered by all five features;
        // the remaining cells of the 16x16 grid are uncovered.
        for (int row = 0; row < 16; row++) {
            for (int col = 0; col < 16; col++) {
                boolean covered = col < 8 && row < 8;
                if (!covered) {
                    assertEquals(-1L, coverage.ownerAt(col, row),
                            semantics + " uncovered owner at col=" + col + " row=" + row);
                    assertEquals(0.0, coverage.valueAt(col, row), 0.0);
                    continue;
                }
                switch (semantics) {
                    case COUNT -> {
                        assertEquals(5.0, coverage.valueAt(col, row), 0.0);
                        assertEquals(30L, coverage.ownerAt(col, row));
                    }
                    case MAX -> {
                        assertEquals(9.0, coverage.valueAt(col, row), 0.0);
                        // Features 30, 50 and 60 share weight 9; the
                        // smallest id wins the tie.
                        assertEquals(30L, coverage.ownerAt(col, row));
                    }
                    case PRIORITY -> {
                        assertEquals(7.0, coverage.valueAt(col, row), 0.0);
                        // Features 30 and 40 share priority 7; smaller
                        // id wins the tie.
                        assertEquals(30L, coverage.ownerAt(col, row));
                    }
                }
            }
        }
        assertTrue(true);
    }
}
