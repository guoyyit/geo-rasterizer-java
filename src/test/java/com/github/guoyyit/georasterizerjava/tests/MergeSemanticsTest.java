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

import static org.junit.jupiter.api.Assertions.assertEquals;

class MergeSemanticsTest {

    private static RasterConfig config(Path dir, MergeSemantics semantics, int workers) {
        return new ConfigBuilder()
                .cellSize(0.25)
                .extent(0, 0, 4, 4)
                .fillRule(FillRule.NON_ZERO)
                .mergeSemantics(semantics)
                .outOfRangePolicy(OutOfRangePolicy.CLIP)
                .areaRelativeTolerance(0.05)
                .tileSize(2, 2)
                .workerThreads(workers)
                .assembleCoverage(true)
                .checkpointDirectory(dir)
                .build();
    }

    private static List<Feature> overlappingFeatures() {
        // Three identical 2x2 squares; cell centers 0.125..1.875 are
        // covered, giving 8x8 = 64 overlapping cells.
        return List.of(
                Geometries.feature(10, Geometries.polygon(
                        Geometries.ring(0, 0, 2, 0, 2, 2, 0, 2)), 5.0, 1),
                Geometries.feature(20, Geometries.polygon(
                        Geometries.ring(0, 0, 2, 0, 2, 2, 0, 2)), 9.0, 3),
                Geometries.feature(30, Geometries.polygon(
                        Geometries.ring(0, 0, 2, 0, 2, 2, 0, 2)), 7.0, 3)
        );
    }

    @Test
    void countSemanticsCountsCoveringFeatures(@TempDir Path tempDir) {
        RasterizationResult result = new RasterizationService().rasterize(
                config(tempDir.resolve("count"), MergeSemantics.COUNT, 4),
                overlappingFeatures());
        CoverageMap coverage = result.coverage();
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                assertEquals(3.0, coverage.valueAt(col, row), 0.0);
            }
        }
        assertEquals(64, coverage.coveredCellCount());
    }

    @Test
    void maxSemanticsSelectsLargestWeight(@TempDir Path tempDir) {
        RasterizationResult result = new RasterizationService().rasterize(
                config(tempDir.resolve("max"), MergeSemantics.MAX, 4),
                overlappingFeatures());
        CoverageMap coverage = result.coverage();
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                assertEquals(9.0, coverage.valueAt(col, row), 0.0);
                assertEquals(20L, coverage.ownerAt(col, row));
            }
        }
    }

    @Test
    void prioritySemanticsTieBreaksBySmallerFeatureId(@TempDir Path tempDir) {
        RasterizationResult result = new RasterizationService().rasterize(
                config(tempDir.resolve("prio"), MergeSemantics.PRIORITY, 4),
                overlappingFeatures());
        CoverageMap coverage = result.coverage();
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                assertEquals(3.0, coverage.valueAt(col, row), 0.0);
                // Features 20 and 30 tie on priority 3; smaller id wins.
                assertEquals(20L, coverage.ownerAt(col, row));
            }
        }
    }

    @Test
    void resultIsIndependentOfSubmissionOrder(@TempDir Path tempDir) {
        List<Feature> shuffled = new ArrayList<>(overlappingFeatures());
        Collections.shuffle(shuffled, new java.util.Random(42));

        RasterizationResult a = new RasterizationService().rasterize(
                config(tempDir.resolve("ordered"), MergeSemantics.PRIORITY, 8),
                overlappingFeatures());
        RasterizationResult b = new RasterizationService().rasterize(
                config(tempDir.resolve("shuffled"), MergeSemantics.PRIORITY, 8),
                shuffled);

        for (int row = 0; row < a.coverage().height(); row++) {
            for (int col = 0; col < a.coverage().width(); col++) {
                assertEquals(a.coverage().valueAt(col, row),
                        b.coverage().valueAt(col, row), 0.0);
                assertEquals(a.coverage().ownerAt(col, row),
                        b.coverage().ownerAt(col, row));
            }
        }
    }
}
