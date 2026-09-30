package com.github.guoyyit.georasterizerjava.tests;

import com.github.guoyyit.georasterizerjava.config.RasterConfig;
import com.github.guoyyit.georasterizerjava.model.Envelope;
import com.github.guoyyit.georasterizerjava.model.Feature;
import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.model.Polygon;
import com.github.guoyyit.georasterizerjava.service.RasterizationException;
import com.github.guoyyit.georasterizerjava.service.RasterizationResult;
import com.github.guoyyit.georasterizerjava.service.RasterizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检查点与中断恢复：中途失败后从最近完成的分块继续，恢复结果与一次完整运行逐像元一致，
 * 且失败时不产生已提交的覆盖图（无部分提交）。
 */
class CheckpointResumeTest {

    private static final Envelope ENVELOPE = new Envelope(0, 0, 6, 6);

    private RasterConfig config() {
        return RasterConfig.builder(0.5)
                .outputEnvelope(ENVELOPE)
                .fillRule(FillRule.ODD_EVEN)
                .tileSize(2, 2)
                .parallelism(1)
                .build();
    }

    private List<Feature> features() {
        Polygon polygon = TestGeometries.squareWithCwHole();
        return List.of(new Feature("resume-feature", polygon, 1.0, 0));
    }

    @Test
    void resumesAfterMidFlightFailureAndProducesIdenticalCoverage(@TempDir Path tempDir) throws Exception {
        Path workDir = tempDir.resolve("work");
        Files.createDirectories(workDir);
        RasterizationService service = new RasterizationService();

        // 第一次：在完成第 3 个分块后注入失败
        RasterizationException failure = assertThrows(RasterizationException.class, () ->
                service.rasterize(features(), config(), workDir,
                        (completed, total) -> {
                            if (completed >= 3) {
                                throw new RasterizationException("injected mid-flight failure");
                            }
                        }));
        assertTrue(failure.getMessage().contains("injected mid-flight failure"));

        // 检查点目录里只有部分（已原子提交的）分块，没有 .tmp 残留
        long tileFiles = countFiles(workDir, ".dat");
        long areaFiles = countFiles(workDir, ".area");
        long tmpFiles = countFiles(workDir, ".tmp");
        assertEquals(3, tileFiles, "exactly completed tiles must be atomically published");
        assertEquals(3, areaFiles, "each published tile carries its coverage-sum sidecar");
        assertEquals(0, tmpFiles, "no partial .tmp tile may be visible");

        // 第二次：无监听器，从检查点继续，必须成功
        RasterizationResult resumed = service.rasterize(features(), config(), workDir, null);

        // 对照组：独立工作目录一次性完整运行
        Path controlDir = tempDir.resolve("control");
        Files.createDirectories(controlDir);
        RasterizationResult control = service.rasterize(features(), config(), controlDir, null);

        assertCellwiseEqual(control, resumed);
        assertEquals(control.areaReport().rasterizedArea(),
                resumed.areaReport().rasterizedArea(), 1.0e-9);
        assertTrue(resumed.areaReport().passed());
        assertNotEquals(workDir, controlDir);
    }

    @Test
    void completedTilesAreSkippedOnResume(@TempDir Path tempDir) throws Exception {
        Path workDir = tempDir.resolve("work-skip");
        Files.createDirectories(workDir);
        RasterizationService service = new RasterizationService();

        assertThrows(RasterizationException.class, () ->
                service.rasterize(features(), config(), workDir,
                        (completed, total) -> {
                            if (completed >= 2) {
                                throw new RasterizationException("stop after two");
                            }
                        }));

        // 恢复时前两个已完成分块不应再触发完成回调
        int[] callbacksAfterResume = {0};
        service.rasterize(features(), config(), workDir, (completed, total) ->
                callbacksAfterResume[0]++);

        // 网格 12x12、分块 2x2 => 36 块；恢复后回调只覆盖剩余 34 块
        assertEquals(34, callbacksAfterResume[0]);
    }

    private long countFiles(Path workDir, String suffix) throws java.io.IOException {
        try (var paths = Files.walk(workDir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .count();
        }
    }

    private void assertCellwiseEqual(RasterizationResult expected, RasterizationResult actual) {
        int cols = expected.grid().cols();
        int rows = expected.grid().rows();
        assertEquals(cols, actual.grid().cols());
        assertEquals(rows, actual.grid().rows());
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                assertEquals(expected.coverageAt(col, row), actual.coverageAt(col, row), 1.0e-12,
                        "coverage mismatch at " + col + "," + row);
            }
        }
    }
}
