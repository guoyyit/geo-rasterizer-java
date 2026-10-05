package com.github.guoyyit.georasterizerjava.tests;

import com.github.guoyyit.georasterizerjava.model.ConfigBuilder;
import com.github.guoyyit.georasterizerjava.model.CoverageMap;
import com.github.guoyyit.georasterizerjava.model.Feature;
import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.model.MergeSemantics;
import com.github.guoyyit.georasterizerjava.model.OutOfRangePolicy;
import com.github.guoyyit.georasterizerjava.model.RasterConfig;
import com.github.guoyyit.georasterizerjava.model.RasterizationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The merged coverage must depend only on the input set, never on
 * submission order, thread count or tile size. Runs several random
 * permutations across several worker/tile configurations and compares
 * every cell value and owner against the ascending-id reference.
 */
class MergeDeterminismTest {

    private static final int[][] TILE_SIZES = {{1, 7}, {7, 1}, {3, 4}, {16, 16}};
    private static final int[] WORKERS = {1, 2, 5};

    /**
     * Overlapping small polygons inside a 0..4 extent at cell 0.2.
     * Two features deliberately share the maximal weight so MAX needs
     * an id tie-break; every cell is covered by several features so
     * COUNT needs a deterministic owner as well.
     */
    private static List<Feature> overlappingFeatures() {
        List<Feature> features = new ArrayList<>();
        Random random = new Random(99173L);
        for (int i = 0; i < 12; i++) {
            long id = 100 + i * 3L;
            double minX = 0.4 + random.nextDouble() * 1.2;
            double minY = 0.4 + random.nextDouble() * 1.2;
            double maxX = minX + 0.8 + random.nextDouble() * 1.6;
            double maxY = minY + 0.8 + random.nextDouble() * 1.6;
            // Half the features share weight 5.0 (the maximum); their
            // ids differ, so the smallest id among them must own cells.
            double weight = (i % 2 == 0) ? 5.0 : 1.0 + (i % 4);
            int priority = i % 3;
            features.add(Geometries.feature(id, Geometries.polygon(
                    Geometries.ring(minX, minY, maxX, minY, maxX, maxY, minX, maxY)),
                    weight, priority));
        }
        return features;
    }

    private static RasterConfig config(
            Path dir, MergeSemantics semantics, int workers, int tileW, int tileH
    ) {
        return new ConfigBuilder()
                .cellSize(0.2)
                .extent(0, 0, 4, 4)
                .fillRule(FillRule.NON_ZERO)
                .mergeSemantics(semantics)
                .outOfRangePolicy(OutOfRangePolicy.CLIP)
                .areaRelativeTolerance(0.20)
                .tileSize(tileW, tileH)
                .workerThreads(workers)
                .assembleCoverage(true)
                .checkpointDirectory(dir)
                .build();
    }

    private static void assertCellwiseEquals(CoverageMap expected, CoverageMap actual) {
        assertEquals(expected.width(), actual.width());
        assertEquals(expected.height(), actual.height());
        assertEquals(expected.coveredCellCount(), actual.coveredCellCount());
        for (int row = 0; row < expected.height(); row++) {
            for (int col = 0; col < expected.width(); col++) {
                assertEquals(expected.valueAt(col, row), actual.valueAt(col, row), 0.0,
                        "value mismatch at (" + col + "," + row + ")");
                assertEquals(expected.ownerAt(col, row), actual.ownerAt(col, row),
                        "owner mismatch at (" + col + "," + row + ")");
            }
        }
    }

    @Test
    void countOwnerIsSmallestCoveringIdRegardlessOfOrder(@TempDir Path tempDir) {
        List<Feature> ordered = overlappingFeatures();
        CoverageMap reference = new RasterizationServiceHelper()
                .run(config(tempDir.resolve("ref-count"),
                        MergeSemantics.COUNT, 1, 16, 16), ordered)
                .coverage();

        Random permutation = new Random(20261005L);
        int run = 0;
        for (int iter = 0; iter < 4; iter++) {
            List<Feature> shuffled = new ArrayList<>(ordered);
            Collections.shuffle(shuffled, permutation);
            for (int workers : WORKERS) {
                int[] tile = TILE_SIZES[(iter + workers) % TILE_SIZES.length];
                CoverageMap actual = new RasterizationServiceHelper()
                        .run(config(tempDir.resolve("count-" + (run++)),
                                MergeSemantics.COUNT, workers, tile[0], tile[1]), shuffled)
                        .coverage();
                assertCellwiseEquals(reference, actual);
            }
        }

        // Explicit semantic check: every covered cell's owner is the
        // minimum id among the features covering that very cell.
        for (int row = 0; row < reference.height(); row++) {
            for (int col = 0; col < reference.width(); col++) {
                long expectedOwner = -1L;
                long count = 0L;
                for (Feature feature : ordered) {
                    if (coversCellCenter(feature, col, row)) {
                        count++;
                        expectedOwner = expectedOwner < 0
                                ? feature.id() : Math.min(expectedOwner, feature.id());
                    }
                }
                if (count == 0L) {
                    assertEquals(-1L, reference.ownerAt(col, row));
                    assertEquals(0.0, reference.valueAt(col, row), 0.0);
                } else {
                    assertEquals((double) count, reference.valueAt(col, row), 0.0);
                    assertEquals(expectedOwner, reference.ownerAt(col, row));
                }
            }
        }
    }

    @Test
    void maxTieGoesToSmallerIdRegardlessOfOrder(@TempDir Path tempDir) {
        List<Feature> ordered = overlappingFeatures();
        CoverageMap reference = new RasterizationServiceHelper()
                .run(config(tempDir.resolve("ref-max"),
                        MergeSemantics.MAX, 1, 16, 16), ordered)
                .coverage();

        Random permutation = new Random(424242L);
        int run = 0;
        for (int iter = 0; iter < 4; iter++) {
            List<Feature> shuffled = new ArrayList<>(ordered);
            Collections.shuffle(shuffled, permutation);
            for (int workers : WORKERS) {
                int[] tile = TILE_SIZES[(iter * 2 + workers) % TILE_SIZES.length];
                CoverageMap actual = new RasterizationServiceHelper()
                        .run(config(tempDir.resolve("max-" + (run++)),
                                MergeSemantics.MAX, workers, tile[0], tile[1]), shuffled)
                        .coverage();
                assertCellwiseEquals(reference, actual);
            }
        }

        // Independent point-in-polygon recomputation of the tie rule.
        for (int row = 0; row < reference.height(); row++) {
            for (int col = 0; col < reference.width(); col++) {
                double bestWeight = Double.NEGATIVE_INFINITY;
                long bestId = -1L;
                for (Feature feature : ordered) {
                    if (coversCellCenter(feature, col, row)) {
                        if (feature.weight() > bestWeight
                                || (feature.weight() == bestWeight
                                && (bestId < 0 || feature.id() < bestId))) {
                            bestWeight = feature.weight();
                            bestId = feature.id();
                        }
                    }
                }
                if (bestId < 0) {
                    assertEquals(-1L, reference.ownerAt(col, row));
                } else {
                    assertEquals(bestWeight, reference.valueAt(col, row), 0.0);
                    assertEquals(bestId, reference.ownerAt(col, row));
                }
            }
        }
    }

    @Test
    void priorityRuleIsAlsoOrderIndependent(@TempDir Path tempDir) {
        List<Feature> ordered = overlappingFeatures();
        CoverageMap reference = new RasterizationServiceHelper()
                .run(config(tempDir.resolve("ref-prio"),
                        MergeSemantics.PRIORITY, 1, 16, 16), ordered)
                .coverage();

        List<Feature> shuffled = new ArrayList<>(ordered);
        Collections.shuffle(shuffled, new Random(7L));
        for (int workers : WORKERS) {
            CoverageMap actual = new RasterizationServiceHelper()
                    .run(config(tempDir.resolve("prio-" + workers),
                            MergeSemantics.PRIORITY, workers, 5, 3), shuffled)
                    .coverage();
            assertCellwiseEquals(reference, actual);
        }
    }

    /**
     * Independent point-in-rectangle test for the axis-aligned square
     * features used above: a cell center strictly inside the rectangle
     * is covered under cell-center sampling.
     */
    private static boolean coversCellCenter(Feature feature, int col, int row) {
        List<com.github.guoyyit.georasterizerjava.model.Point> vertices =
                feature.polygon().exterior().vertices();
        double xmin = Double.POSITIVE_INFINITY;
        double ymin = Double.POSITIVE_INFINITY;
        double xmax = Double.NEGATIVE_INFINITY;
        double ymax = Double.NEGATIVE_INFINITY;
        for (com.github.guoyyit.georasterizerjava.model.Point p : vertices) {
            xmin = Math.min(xmin, p.x());
            ymin = Math.min(ymin, p.y());
            xmax = Math.max(xmax, p.x());
            ymax = Math.max(ymax, p.y());
        }
        double cx = 0.0 + (col + 0.5) * 0.2;
        double cy = 0.0 + (row + 0.5) * 0.2;
        return cx > xmin && cx < xmax && cy > ymin && cy < ymax;
    }

    private static final class RasterizationServiceHelper {
        RasterizationResult run(
                RasterConfig config,
                List<Feature> features
        ) {
            return new com.github.guoyyit.georasterizerjava.raster.RasterizationService()
                    .rasterize(config, features);
        }
    }
}
