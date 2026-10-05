package com.github.guoyyit.georasterizerjava.tests;

import com.github.guoyyit.georasterizerjava.geometry.PolygonArea;
import com.github.guoyyit.georasterizerjava.model.ConfigBuilder;
import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.model.MergeSemantics;
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
 * Area verification for self-intersecting polygons. Every expected area
 * in this class is an independent analytic truth (triangle decomposition),
 * never a value copied from the implementation.
 */
class SelfIntersectionAreaTest {

    private static final double EPS = 1e-9;

    private static Polygon bowtie() {
        // (-2,-2) -> (2,2) -> (2,-2) -> (-2,2): two triangles that only
        // meet at the origin. Each triangle has base 4 and height 2.
        return Geometries.polygon(
                Geometries.ring(-2, -2, 2, 2, 2, -2, -2, 2));
    }

    private static ConfigBuilder config(Path dir, double cellSize, FillRule rule) {
        return new ConfigBuilder()
                .cellSize(cellSize)
                .extent(-2, -2, 2, 2)
                .fillRule(rule)
                .mergeSemantics(MergeSemantics.COUNT)
                .areaRelativeTolerance(0.01)
                .tileSize(64, 64)
                .workerThreads(3)
                .assembleCoverage(true)
                .checkpointDirectory(dir);
    }

    @Test
    void bowtieTheoreticalAreaIsAnalyticTruthUnderBothRules() {
        Polygon bowtie = bowtie();
        assertEquals(8.0, PolygonArea.theoreticalArea(bowtie, FillRule.EVEN_ODD), EPS);
        assertEquals(8.0, PolygonArea.theoreticalArea(bowtie, FillRule.NON_ZERO), EPS);
    }

    @Test
    void fineResolutionPassesDefaultToleranceUnderBothRules(@TempDir Path tempDir) {
        for (FillRule rule : FillRule.values()) {
            RasterizationResult result = new RasterizationService().rasterize(
                    config(tempDir.resolve("fine-" + rule), 0.02, rule).build(),
                    List.of(Geometries.feature(1, bowtie(), 1.0, 0)));
            assertTrue(result.areaReport().passed(),
                    "rule " + rule + " should pass at cellSize 0.02");
            assertEquals(8.0, result.areaReport().theoreticalArea(), EPS);
            assertTrue(result.areaReport().relativeError() <= 0.01);
        }
    }

    @Test
    void coarseResolutionStillFailsDefaultTolerance(@TempDir Path tempDir) {
        for (FillRule rule : FillRule.values()) {
            Path dir = tempDir.resolve("coarse-" + rule);
            AreaVerificationException ex = assertThrows(
                    AreaVerificationException.class,
                    () -> new RasterizationService().rasterize(
                            config(dir, 0.05, rule).build(),
                            List.of(Geometries.feature(1, bowtie(), 1.0, 0))),
                    "rule " + rule + " should fail at cellSize 0.05");
            assertFalse(ex.areaReport().passed());
            assertEquals(8.0, ex.areaReport().theoreticalArea(), EPS);
            assertTrue(ex.areaReport().relativeError() > 0.01);
            // Failure is explicit and no coverage is ever committed.
            assertFalse(Files.exists(dir.resolve("commit.dat")));
            try (CheckpointStore store = CheckpointStore.open(dir)) {
                assertFalse(store.isCommitted());
            }
        }
    }

    @Test
    void selfIntersectionInsideEdgesMatchesIndependentGeometry(@TempDir Path tempDir) {
        // Vertices (0,0),(4,0),(0,4),(4,4): edges B-C and D-A cross
        // properly at (2,2), strictly inside both edges. Independent
        // decomposition: triangles (0,0)(4,0)(2,2) and (0,4)(4,4)(2,2),
        // each area 4 -> truth 8.
        Polygon crossing = Geometries.polygon(
                Geometries.ring(0, 0, 4, 0, 0, 4, 4, 4));
        assertEquals(8.0, PolygonArea.theoreticalArea(crossing, FillRule.EVEN_ODD), EPS);
        assertEquals(8.0, PolygonArea.theoreticalArea(crossing, FillRule.NON_ZERO), EPS);

        for (FillRule rule : FillRule.values()) {
            RasterizationResult result = new RasterizationService().rasterize(
                    new ConfigBuilder()
                            .cellSize(0.02)
                            .extent(0, 0, 4, 4)
                            .fillRule(rule)
                            .mergeSemantics(MergeSemantics.COUNT)
                            .areaRelativeTolerance(0.01)
                            .tileSize(64, 64)
                            .workerThreads(2)
                            .checkpointDirectory(tempDir.resolve("cross-" + rule))
                            .build(),
                    List.of(Geometries.feature(7, crossing, 1.0, 0)));
            assertTrue(result.areaReport().passed());
            assertEquals(8.0, result.areaReport().theoreticalArea(), EPS);
        }
    }

    @Test
    void resumeAfterFineCheckpointIsPixelIdentical(@TempDir Path tempDir) {
        Path freshDir = tempDir.resolve("fresh");
        RasterizationResult fresh = new RasterizationService().rasterize(
                config(freshDir, 0.02, FillRule.EVEN_ODD).build(),
                List.of(Geometries.feature(1, bowtie(), 1.0, 0)));

        // A second job interrupted after some tiles, then resumed.
        Path resumeDir = tempDir.resolve("resume");
        ConfigBuilder resumeBuilder = config(resumeDir, 0.02, FillRule.EVEN_ODD);
        RasterizationException crash = assertThrows(RasterizationException.class,
                () -> new RasterizationService().rasterize(
                        resumeBuilder.tileSize(37, 37).build(),
                        List.of(Geometries.feature(1, bowtie(), 1.0, 0)),
                        (done, total) -> {
                            if (done == 2) {
                                throw new RasterizationException("simulated crash");
                            }
                        }));
        assertTrue(crash.getMessage().contains("simulated crash"));
        RasterizationResult resumed = new RasterizationService().resume(resumeDir);
        assertTrue(resumed.resumed());
        for (int row = 0; row < fresh.coverage().height(); row++) {
            for (int col = 0; col < fresh.coverage().width(); col++) {
                assertEquals(fresh.coverage().valueAt(col, row),
                        resumed.coverage().valueAt(col, row), 0.0);
                assertEquals(fresh.coverage().ownerAt(col, row),
                        resumed.coverage().ownerAt(col, row));
            }
        }
    }

    @Test
    void nonSelfIntersectingAreasAreUnchanged() {
        // Rectangle.
        Polygon rectangle = Geometries.polygon(
                Geometries.ring(0, 0, 5, 0, 5, 3, 0, 3));
        assertEquals(15.0, PolygonArea.theoreticalArea(rectangle, FillRule.NON_ZERO), EPS);

        // Polygon with a hole: 6x6 outer minus 2x2 inner = 32.
        Polygon withHole = Geometries.polygon(
                Geometries.ring(0, 0, 6, 0, 6, 6, 0, 6),
                Geometries.ring(2, 2, 2, 4, 4, 4, 4, 2));
        assertEquals(32.0, PolygonArea.theoreticalArea(withHole, FillRule.EVEN_ODD), EPS);
        assertEquals(32.0, PolygonArea.theoreticalArea(withHole, FillRule.NON_ZERO), EPS);

        // Repeated / explicit closing vertices.
        Ring duplicated = Geometries.ring(0, 0, 0, 0, 4, 0, 4, 4, 0, 4, 0, 0);
        assertEquals(16.0,
                PolygonArea.theoreticalArea(Geometries.polygon(duplicated),
                        FillRule.NON_ZERO), EPS);
    }
}
