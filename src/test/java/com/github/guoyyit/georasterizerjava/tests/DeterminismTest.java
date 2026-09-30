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
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeterminismTest {

    private static List<Feature> randomPolygons(int count) {
        Random random = new Random(20260930L);
        List<Feature> features = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double cx = random.nextDouble() * 40;
            double cy = random.nextDouble() * 40;
            double r = 1.0 + random.nextDouble() * 6.0;
            // Irregular concave quadrilateral, often self-touching.
            features.add(Geometries.feature(1000 + i, Geometries.polygon(
                    Geometries.ring(
                            cx - r, cy - r,
                            cx + r * (random.nextDouble()), cy - r * 0.5,
                            cx + r, cy + r,
                            cx - r * 0.5, cy + r * (0.5 + random.nextDouble()))),
                    random.nextDouble() * 10,
                    random.nextInt(5)));
        }
        return features;
    }

    private static RasterConfig config(Path dir, int workers, FillRule rule,
                                       MergeSemantics semantics) {
        return new ConfigBuilder()
                .cellSize(0.4)
                .extent(-5, -5, 45, 45)
                .fillRule(rule)
                .mergeSemantics(semantics)
                .outOfRangePolicy(OutOfRangePolicy.CLIP)
                .areaRelativeTolerance(0.10)
                .tileSize(13, 11)
                .workerThreads(workers)
                .assembleCoverage(true)
                .checkpointDirectory(dir)
                .build();
    }

    private static void assertCoverageEquals(CoverageMap a, CoverageMap b) {
        assertEquals(a.width(), b.width());
        assertEquals(a.height(), b.height());
        assertEquals(a.coveredCellCount(), b.coveredCellCount());
        for (int row = 0; row < a.height(); row++) {
            for (int col = 0; col < a.width(); col++) {
                assertEquals(a.valueAt(col, row), b.valueAt(col, row), 0.0);
                assertEquals(a.ownerAt(col, row), b.ownerAt(col, row));
            }
        }
    }

    @Test
    void concurrencyLevelAndFillRulesDoNotChangePixels(@TempDir Path tempDir) {
        List<Feature> features = randomPolygons(40);
        RasterizationService service = new RasterizationService();

        RasterizationResult base = service.rasterize(
                config(tempDir.resolve("w1"), 1, FillRule.EVEN_ODD, MergeSemantics.PRIORITY),
                features);
        for (int workers : new int[] {2, 4, 8}) {
            RasterizationResult other = service.rasterize(
                    config(tempDir.resolve("w" + workers),
                            workers, FillRule.EVEN_ODD, MergeSemantics.PRIORITY),
                    features);
            assertCoverageEquals(base.coverage(), other.coverage());
        }

        RasterizationResult nonZero = service.rasterize(
                config(tempDir.resolve("nz"), 4, FillRule.NON_ZERO, MergeSemantics.MAX),
                features);
        RasterizationResult evenOdd = service.rasterize(
                config(tempDir.resolve("eo"), 4, FillRule.EVEN_ODD, MergeSemantics.MAX),
                features);
        // Both rules must be stable within themselves; they are allowed
        // to differ, and each passes its own area conservation check.
        assertEquals(nonZero.areaReport().passed(), true);
        assertEquals(evenOdd.areaReport().passed(), true);
        assertCoverageEquals(nonZero.coverage(), service.rasterize(
                config(tempDir.resolve("nz2"), 1, FillRule.NON_ZERO, MergeSemantics.MAX),
                features).coverage());
    }
}
