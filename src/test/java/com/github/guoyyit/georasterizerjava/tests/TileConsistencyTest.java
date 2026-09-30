package com.github.guoyyit.georasterizerjava.tests;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TileConsistencyTest {

    private static RasterConfig config(Path dir, int tileW, int tileH,
                                       MergeSemantics semantics) {
        return new com.github.guoyyit.georasterizerjava.model.ConfigBuilder()
                .cellSize(0.5)
                .extent(0, 0, 10, 10)
                .fillRule(FillRule.NON_ZERO)
                .mergeSemantics(semantics)
                .outOfRangePolicy(OutOfRangePolicy.CLIP)
                .areaRelativeTolerance(0.05)
                .tileSize(tileW, tileH)
                .workerThreads(1)
                .assembleCoverage(true)
                .checkpointDirectory(dir)
                .build();
    }

    private List<Feature> features() {
        // Several polygons straddling tile boundaries, including one
        // with a hole and one crossing the diagonal corner region.
        return List.of(
                Geometries.feature(1, Geometries.polygon(
                        Geometries.ring(-1, -1, 3, -1, 3, 3, -1, 3)), 1.0, 1),
                Geometries.feature(2, Geometries.polygon(
                        Geometries.ring(2, 2, 8, 2, 8, 8, 2, 8),
                        Geometries.ring(4, 4, 4, 6, 6, 6, 6, 4)), 2.0, 2),
                Geometries.feature(3, Geometries.polygon(
                        Geometries.ring(6, 0, 11, 0, 11, 5, 6, 5)), 3.0, 3),
                Geometries.feature(4, Geometries.polygon(
                        Geometries.ring(0, 7, 9, 7, 9, 11, 0, 11)), 4.0, 4)
        );
    }

    @Test
    void tilingMatchesSinglePass(@TempDir Path tempDir) {
        RasterizationService service = new RasterizationService();

        RasterizationResult single = service.rasterize(
                config(tempDir.resolve("single"), 20, 20, MergeSemantics.COUNT),
                features());
        RasterizationResult tiled = service.rasterize(
                config(tempDir.resolve("tiled"), 3, 3, MergeSemantics.COUNT),
                features());

        assertTrue(single.areaReport().passed());
        assertTrue(tiled.areaReport().passed());
        CoverageMap a = single.coverage();
        CoverageMap b = tiled.coverage();
        assertEquals(a.width(), b.width());
        assertEquals(a.height(), b.height());
        assertEquals(a.coveredCellCount(), b.coveredCellCount());
        for (int row = 0; row < a.height(); row++) {
            for (int col = 0; col < a.width(); col++) {
                assertEquals(a.valueAt(col, row), b.valueAt(col, row), 0.0,
                        "value mismatch at " + col + "," + row);
            }
        }
    }

    @Test
    void differentTileShapesGiveIdenticalCoverage(@TempDir Path tempDir) {
        RasterizationService service = new RasterizationService();
        RasterizationResult tall = service.rasterize(
                config(tempDir.resolve("tall"), 1, 20, MergeSemantics.MAX),
                features());
        RasterizationResult wide = service.rasterize(
                config(tempDir.resolve("wide"), 20, 1, MergeSemantics.MAX),
                features());

        CoverageMap a = tall.coverage();
        CoverageMap b = wide.coverage();
        assertEquals(a.coveredCellCount(), b.coveredCellCount());
        for (int row = 0; row < a.height(); row++) {
            for (int col = 0; col < a.width(); col++) {
                assertEquals(a.valueAt(col, row), b.valueAt(col, row), 0.0);
                assertEquals(a.ownerAt(col, row), b.ownerAt(col, row));
            }
        }
    }
}
