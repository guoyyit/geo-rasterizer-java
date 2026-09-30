package com.github.guoyyit.georasterizerjava.tests;

import com.github.guoyyit.georasterizerjava.model.ConfigBuilder;
import com.github.guoyyit.georasterizerjava.model.Feature;
import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.model.MergeSemantics;
import com.github.guoyyit.georasterizerjava.model.OutOfRangePolicy;
import com.github.guoyyit.georasterizerjava.model.RasterizationResult;
import com.github.guoyyit.georasterizerjava.raster.AreaVerificationException;
import com.github.guoyyit.georasterizerjava.raster.CheckpointStore;
import com.github.guoyyit.georasterizerjava.raster.RasterizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaVerificationTest {

    private static ConfigBuilder base(Path dir) {
        return new ConfigBuilder()
                .extent(0, 0, 100, 100)
                .fillRule(FillRule.NON_ZERO)
                .mergeSemantics(MergeSemantics.COUNT)
                .tileSize(16, 16)
                .workerThreads(2)
                .checkpointDirectory(dir);
    }

    private static List<Feature> bigSquare() {
        return List.of(Geometries.feature(1, Geometries.polygon(
                Geometries.ring(10, 10, 90, 10, 90, 90, 10, 90)), 1.0, 0));
    }

    @Test
    void fineResolutionPassesAreaCheck(@TempDir Path tempDir) {
        RasterizationResult result = new RasterizationService().rasterize(
                base(tempDir.resolve("fine")).cellSize(1.0).areaRelativeTolerance(0.02).build(),
                bigSquare());
        assertTrue(result.areaReport().passed());
        // 80x80 theoretical; rasterized cell-center area must be close.
        assertEquals(6400.0, result.areaReport().theoreticalArea(), 0.0);
        assertEquals(6400.0, result.areaReport().rasterizedArea(), 0.0);
        assertEquals(0.0, result.areaReport().relativeError(), 1e-12);
    }

    @Test
    void coarseResolutionExceedingToleranceFailsExplicitly(@TempDir Path tempDir) {
        Path dir = tempDir.resolve("coarse");
        // Cell size 7: 80 / 7 = 11.43 -> 12 cells, only 11 cell centers
        // per axis are inside, a ~16% area shortfall.
        AreaVerificationException ex = assertThrows(AreaVerificationException.class,
                () -> new RasterizationService().rasterize(
                        base(dir).cellSize(7.0).areaRelativeTolerance(0.05).build(),
                        bigSquare()));
        assertFalse(ex.areaReport().passed());
        assertTrue(ex.areaReport().relativeError() > 0.05);
        // The failed job must never be committed.
        try (CheckpointStore store = CheckpointStore.open(dir)) {
            assertFalse(store.isCommitted());
        }
        assertFalse(Files.exists(dir.resolve("commit.dat")));
    }

    @Test
    void clippedGeometryAreaIsComputedPostClip(@TempDir Path tempDir) {
        // Half of this square is outside the extent. Under CLIP the
        // theoretical area becomes 50*80 = 4000, not 6400.
        List<Feature> crossing = List.of(Geometries.feature(1, Geometries.polygon(
                Geometries.ring(-30, 10, 50, 10, 50, 90, -30, 90)), 1.0, 0));
        RasterizationResult result = new RasterizationService().rasterize(
                base(tempDir.resolve("clip"))
                        .cellSize(1.0)
                        .outOfRangePolicy(OutOfRangePolicy.CLIP)
                        .areaRelativeTolerance(0.01)
                        .build(),
                crossing);
        assertTrue(result.areaReport().passed());
        assertEquals(4000.0, result.areaReport().theoreticalArea(), 1e-9);
    }

    @Test
    void rejectedGeometryIsExcludedFromAreaCheck(@TempDir Path tempDir) {
        List<Feature> features = List.of(
                Geometries.feature(1, Geometries.polygon(
                        Geometries.ring(10, 10, 20, 10, 20, 20, 10, 20)), 1.0, 0),
                Geometries.feature(2, Geometries.polygon(
                        Geometries.ring(200, 200, 300, 200, 300, 300, 200, 300)), 1.0, 0));
        RasterizationResult result = new RasterizationService().rasterize(
                base(tempDir.resolve("reject"))
                        .cellSize(1.0)
                        .outOfRangePolicy(OutOfRangePolicy.REJECT)
                        .areaRelativeTolerance(0.01)
                        .build(),
                features);
        assertTrue(result.areaReport().passed());
        assertEquals(List.of(2L), result.rejectedFeatureIds());
        assertEquals(2, result.totalFeatures());
        assertEquals(1, result.acceptedFeatures());
    }
}
