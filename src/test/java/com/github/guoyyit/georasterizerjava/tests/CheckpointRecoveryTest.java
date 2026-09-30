package com.github.guoyyit.georasterizerjava.tests;

import com.github.guoyyit.georasterizerjava.model.ConfigBuilder;
import com.github.guoyyit.georasterizerjava.model.Feature;
import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.model.MergeSemantics;
import com.github.guoyyit.georasterizerjava.model.RasterizationResult;
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

class CheckpointRecoveryTest {

    private static List<Feature> features() {
        return List.of(
                Geometries.feature(1, Geometries.polygon(
                        Geometries.ring(-2, -2, 18, -2, 18, 18, -2, 18)), 1.0, 0),
                Geometries.feature(2, Geometries.polygon(
                        Geometries.ring(4, 4, 12, 4, 12, 12, 4, 12),
                        Geometries.ring(6, 6, 6, 10, 10, 10, 10, 6)), 2.0, 1)
        );
    }

    private static ConfigBuilder base(Path dir) {
        return new ConfigBuilder()
                .cellSize(0.5)
                .extent(0, 0, 16, 16)
                .fillRule(FillRule.EVEN_ODD)
                .mergeSemantics(MergeSemantics.COUNT)
                .areaRelativeTolerance(0.05)
                .tileSize(4, 4)
                .workerThreads(2)
                .assembleCoverage(true)
                .checkpointDirectory(dir);
    }

    @Test
    void resumesFromNearestCheckpointWithIdenticalResult(@TempDir Path tempDir) {
        Path dir = tempDir.resolve("job");
        RasterizationService service = new RasterizationService();

        // First attempt: fail after the 5th tile commit (16 tiles total).
        int failAfter = 5;
        RasterizationException failure = assertThrows(RasterizationException.class, () ->
                service.rasterize(base(dir).build(), features(), (done, total) -> {
                    if (done == failAfter) {
                        throw new RasterizationException("simulated crash after tile " + done);
                    }
                }));
        assertTrue(failure.getMessage().contains("simulated crash"));

        try (CheckpointStore store = CheckpointStore.open(dir)) {
            assertFalse(store.isCommitted());
            assertEquals(failAfter, store.completedTileCount());
        }
        assertFalse(Files.exists(dir.resolve("commit.dat")));

        // Resume; already committed tiles are skipped.
        int[] listenerTiles = new int[1];
        RasterizationResult resumed = service.resume(dir, (done, total) -> listenerTiles[0] = done);
        assertTrue(resumed.committed());
        assertTrue(resumed.resumed());
        // Listener reports the absolute committed tile count.
        assertEquals(64, listenerTiles[0]);

        // A fresh single-pass run must produce the exact same coverage.
        RasterizationResult fresh = service.rasterize(
                base(tempDir.resolve("fresh")).build(), features());
        assertEquals(fresh.coverage().coveredCellCount(),
                resumed.coverage().coveredCellCount());
        for (int row = 0; row < fresh.coverage().height(); row++) {
            for (int col = 0; col < fresh.coverage().width(); col++) {
                assertEquals(fresh.coverage().valueAt(col, row),
                        resumed.coverage().valueAt(col, row), 0.0);
            }
        }
    }

    @Test
    void resumingACommittedJobReturnsCommittedResult(@TempDir Path tempDir) {
        Path dir = tempDir.resolve("done");
        RasterizationService service = new RasterizationService();
        RasterizationResult first = service.rasterize(base(dir).build(), features());
        assertTrue(first.committed());
        RasterizationResult again = service.resume(dir);
        assertTrue(again.committed());
        assertTrue(again.areaReport().passed());
        assertEquals(first.coverage().coveredCellCount(),
                again.coverage().coveredCellCount());
    }
}
