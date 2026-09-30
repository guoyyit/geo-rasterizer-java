package com.github.guoyyit.georasterizerjava.tests;

import com.github.guoyyit.georasterizerjava.model.ConfigBuilder;
import com.github.guoyyit.georasterizerjava.model.Feature;
import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.model.MergeSemantics;
import com.github.guoyyit.georasterizerjava.model.OutOfRangePolicy;
import com.github.guoyyit.georasterizerjava.model.RasterizationResult;
import com.github.guoyyit.georasterizerjava.raster.RasterizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingRasterizationTest {

    private static Iterable<Feature> streamingFeatures(int count) {
        AtomicInteger produced = new AtomicInteger();
        return () -> new Iterator<>() {
            @Override
            public boolean hasNext() {
                return produced.get() < count;
            }

            @Override
            public Feature next() {
                int i = produced.getAndIncrement();
                if (i >= count) {
                    throw new NoSuchElementException();
                }
                int base = i * 2;
                return Geometries.feature(i + 1, Geometries.polygon(
                        Geometries.ring(base, 0, base + 1, 0, base + 1, 1, base, 1)),
                        1.0, 0);
            }
        };
    }

    @Test
    void consumesSinglePassIterableWithoutAssemblingCoverage(@TempDir Path tempDir) {
        Path dir = tempDir.resolve("stream");
        RasterizationResult result = new RasterizationService().rasterize(
                new ConfigBuilder()
                        .extent(0, 0, 30, 4)
                        .fillRule(FillRule.NON_ZERO)
                        .mergeSemantics(MergeSemantics.COUNT)
                        .outOfRangePolicy(OutOfRangePolicy.CLIP)
                        .areaRelativeTolerance(0.05)
                        .tileSize(8, 8)
                        .workerThreads(3)
                        .assembleCoverage(false)
                        .checkpointDirectory(dir)
                        .cellSize(0.25)
                        .build(),
                streamingFeatures(10),
                null);

        assertTrue(result.committed());
        assertTrue(result.areaReport().passed());
        assertEquals(10, result.acceptedFeatures());
        // assembleCoverage=false => no in-memory grid; tiles live on disk.
        assertNull(result.coverage());
        // 10 unit squares, no overlaps: 160 cells x 0.0625 = area 10.
        assertEquals(10.0, result.unionCoveredArea(), 0.0);
        assertEquals(10.0, result.areaReport().rasterizedArea(), 0.0);

        // Result metadata records the processing policy for downstream.
        assertEquals(OutOfRangePolicy.CLIP, result.outOfRangePolicy());
        assertNotNull(result.effectiveXMax());
    }
}
