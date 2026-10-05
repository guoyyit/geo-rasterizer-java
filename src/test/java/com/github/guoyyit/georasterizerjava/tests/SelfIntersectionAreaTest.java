package com.github.guoyyit.georasterizerjava.tests;

import com.github.guoyyit.georasterizerjava.geometry.PolygonArea;
import com.github.guoyyit.georasterizerjava.model.ConfigBuilder;
import com.github.guoyyit.georasterizerjava.model.Feature;
import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.model.MergeSemantics;
import com.github.guoyyit.georasterizerjava.model.Point;
import com.github.guoyyit.georasterizerjava.model.Polygon;
import com.github.guoyyit.georasterizerjava.model.RasterizationResult;
import com.github.guoyyit.georasterizerjava.model.Ring;
import com.github.guoyyit.georasterizerjava.raster.AreaVerificationException;
import com.github.guoyyit.georasterizerjava.raster.CheckpointStore;
import com.github.guoyyit.georasterizerjava.raster.RasterizationException;
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

/**
 * Area conservation for self-intersecting polygons. Every expected area
 * in this class is an independent analytic value derived directly from
 * the test geometry (see each test's derivation), never copied from the
 * implementation under test.
 */
class SelfIntersectionAreaTest {

    private static final double EPS = 1e-9;

    /** Bowtie (-2,-2),(2,2),(2,-2),(-2,2): two lobes meeting at the origin. */
    private static Polygon bowtie() {
        return Geometries.polygon(
                Geometries.ring(-2, -2, 2, 2, 2, -2, -2, 2));
    }

    @Test
    void bowtieTheoreticalAreaIsEightUnderBothRules() {
        // Independent decomposition: two triangles with base 4 (the
        // vertical edge on each side) and height 2 from the shared
        // origin, area 4*2/2 = 4 each; they touch only at the origin,
        // so the planar region measure is 8 under either fill rule.
        for (FillRule rule : FillRule.values()) {
            assertEquals(8.0, PolygonArea.theoreticalArea(bowtie(), rule), EPS);
        }
    }

    @Test
    void fineResolutionPassesDefaultToleranceUnderBothRules(@TempDir Path tempDir) {
        for (FillRule rule : FillRule.values()) {
            Path dir = tempDir.resolve("fine-" + rule);
            RasterizationResult result = new RasterizationService().rasterize(
                    new ConfigBuilder()
                            .cellSize(0.02)
                            .extent(-2, -2, 2, 2)
                            .fillRule(rule)
                            .mergeSemantics(MergeSemantics.COUNT)
                            .tileSize(64, 64)
                            .workerThreads(3)
                            .areaRelativeTolerance(0.01)
                            .checkpointDirectory(dir)
                            .build(),
                    List.of(Geometries.feature(7, bowtie(), 1.0, 0)));

            assertEquals(8.0, result.areaReport().theoreticalArea(), 0.0);
            assertTrue(result.areaReport().passed(),
                    "0.02 cells must pass the default 1% check under " + rule);
            assertTrue(result.areaReport().relativeError() <= 0.01);
        }
    }

    @Test
    void coarseResolutionStillFailsDefaultTolerance(@TempDir Path tempDir) {
        for (FillRule rule : FillRule.values()) {
            Path dir = tempDir.resolve("coarse-" + rule);
            AreaVerificationException ex = assertThrows(AreaVerificationException.class,
                    () -> new RasterizationService().rasterize(
                            new ConfigBuilder()
                                    .cellSize(0.05)
                                    .extent(-2, -2, 2, 2)
                                    .fillRule(rule)
                                    .mergeSemantics(MergeSemantics.COUNT)
                                    .tileSize(32, 32)
                                    .workerThreads(1)
                                    .areaRelativeTolerance(0.01)
                                    .checkpointDirectory(dir)
                                    .build(),
                            List.of(Geometries.feature(7, bowtie(), 1.0, 0))));

            // The check stays meaningful: ~1.9% shortfall exceeds 1%.
            assertFalse(ex.areaReport().passed());
            assertEquals(8.0, ex.areaReport().theoreticalArea(), 0.0);
            assertTrue(ex.areaReport().relativeError() > 0.01);
            // A failed job must not leave an observable committed map.
            assertFalse(Files.exists(dir.resolve("commit.dat")));
            try (CheckpointStore store = CheckpointStore.open(dir)) {
                assertFalse(store.isCommitted());
            }
        }
    }

    @Test
    void crossingInTheInteriorOfAnEdgeIsHandled() {
        // Four-vertex dart (0,0),(4,0),(0,4),(4,4): the diagonals
        // cross at (2,2), strictly inside both edges. It partitions
        // into four right triangles of leg 2, each area 2; the two
        // winding-0 lobes are exterior under non-zero, leaving two
        // interior lobes of area 2 each: planar truth 8 for both rules.
        Polygon dart = new Polygon(new Ring(List.of(
                new Point(0, 0), new Point(4, 0),
                new Point(0, 4), new Point(4, 4))), List.of());
        assertEquals(8.0, PolygonArea.theoreticalArea(dart, FillRule.EVEN_ODD), EPS);
        assertEquals(8.0, PolygonArea.theoreticalArea(dart, FillRule.NON_ZERO), EPS);
    }

    @Test
    void fillRulesDifferWhenWindingTwoRegionExists() {
        // Six-vertex ring
        //   (0,0)->(4,0)->(4,4)->(0,2)->(4,2)->(0,4)->close
        // with one proper crossing of the (4,4)-(0,2) and (4,2)-(0,4)
        // edges at (2,3). Independent horizontal-strip integration
        // (interior width x strip height):
        //   even-odd: 6 + 4 + 2 = 12
        //   non-zero: 6 + 4 + 4 = 14 (the y in (2,3) central span is
        //   winding 2: even-odd excludes it, non-zero keeps it).
        Polygon spiral = new Polygon(new Ring(List.of(
                new Point(0, 0), new Point(4, 0), new Point(4, 4),
                new Point(0, 2), new Point(4, 2), new Point(0, 4))), List.of());
        assertEquals(12.0, PolygonArea.theoreticalArea(spiral, FillRule.EVEN_ODD), EPS);
        assertEquals(14.0, PolygonArea.theoreticalArea(spiral, FillRule.NON_ZERO), EPS);
    }

    @Test
    void freshAndResumedRunsAgreeForTheBowtie(@TempDir Path tempDir) {
        RasterizationService service = new RasterizationService();
        List<Feature> features = List.of(Geometries.feature(7, bowtie(), 1.0, 0));

        Path interruptedDir = tempDir.resolve("interrupted");
        // 200x200 grid at 37-pixel tiles = 6x6 = 36 tiles; crash mid-way.
        assertThrows(RasterizationException.class, () -> service.rasterize(
                bowtieConfig(interruptedDir).build(), features, (done, total) -> {
                    if (done == 7) {
                        throw new RasterizationException("simulated crash");
                    }
                }));
        try (CheckpointStore store = CheckpointStore.open(interruptedDir)) {
            assertFalse(store.isCommitted());
            assertEquals(7, store.completedTileCount());
        }

        RasterizationResult resumed = service.resume(interruptedDir);
        RasterizationResult fresh = service.rasterize(
                bowtieConfig(tempDir.resolve("fresh")).build(), features);

        assertEquals(8.0, resumed.areaReport().theoreticalArea(), 0.0);
        assertEquals(fresh.coverage().width(), resumed.coverage().width());
        assertEquals(fresh.coverage().height(), resumed.coverage().height());
        for (int row = 0; row < fresh.coverage().height(); row++) {
            for (int col = 0; col < fresh.coverage().width(); col++) {
                assertEquals(fresh.coverage().valueAt(col, row),
                        resumed.coverage().valueAt(col, row), 0.0);
                assertEquals(fresh.coverage().ownerAt(col, row),
                        resumed.coverage().ownerAt(col, row));
            }
        }
    }

    private static ConfigBuilder bowtieConfig(Path dir) {
        return new ConfigBuilder()
                .cellSize(0.02)
                .extent(-2, -2, 2, 2)
                .fillRule(FillRule.NON_ZERO)
                .mergeSemantics(MergeSemantics.COUNT)
                .tileSize(37, 37)
                .workerThreads(2)
                .areaRelativeTolerance(0.01)
                .checkpointDirectory(dir);
    }
}
