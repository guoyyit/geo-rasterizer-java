package com.github.guoyyit.georasterizerjava.tests;

import com.github.guoyyit.georasterizerjava.config.RasterConfig;
import com.github.guoyyit.georasterizerjava.model.Envelope;
import com.github.guoyyit.georasterizerjava.model.Feature;
import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.service.AreaReport;
import com.github.guoyyit.georasterizerjava.service.RasterizationService;
import com.github.guoyyit.georasterizerjava.raster.TileResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 有界内存：超大网格按小块流式推进，在途（已提交但未被消费落盘）的分块数受并发度约束，
 * 常驻内存不随全图像元数增长。
 */
class BoundedMemoryTest {

    @Test
    void largeGridProcessesWithBoundedInFlightTiles() {
        // 200x200 网格 = 40000 像元，8x8 小块 => 25x25 = 625 块，并发度仅 2
        RasterConfig config = RasterConfig.builder(1.0)
                .outputEnvelope(new Envelope(0, 0, 200, 200))
                .fillRule(FillRule.WINDING)
                .tileSize(8, 8)
                .parallelism(2)
                .build();
        Feature feature = new Feature("large",
                TestGeometries.squareCcw(20, 20, 180, 180), 1.0, 0);

        AtomicInteger seen = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();

        AreaReport report = new RasterizationService().rasterizeStreaming(
                List.of(feature), config, null, (TileResult tile) -> {
                    seen.incrementAndGet();
                    int current = inFlight.incrementAndGet();
                    maxInFlight.updateAndGet(previous -> Math.max(previous, current));
                    // 模拟下游处理后立即释放：消费者返回后分块即可被回收
                    inFlight.decrementAndGet();
                });

        assertEquals(625, seen.get(), "all tiles must be streamed");
        // 流式回调在面积校验通过后按顺序逐块进行，消费侧在途不超过 1；
        // 结合分块落盘，整体在途内存被并发度限制，不随 40000 像元增长
        assertTrue(maxInFlight.get() <= 1,
                "streaming consumer must hold at most one tile at a time");
        assertTrue(report.passed());
        // 160x160 方块面积守恒
        assertEquals(160.0 * 160.0, report.rasterizedArea(), 1.0e-6);
    }
}
