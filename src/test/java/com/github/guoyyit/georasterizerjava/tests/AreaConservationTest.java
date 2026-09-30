package com.github.guoyyit.georasterizerjava.tests;

import com.github.guoyyit.georasterizerjava.config.RasterConfig;
import com.github.guoyyit.georasterizerjava.model.Envelope;
import com.github.guoyyit.georasterizerjava.model.Feature;
import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.model.OutOfRangePolicy;
import com.github.guoyyit.georasterizerjava.service.AreaConservationException;
import com.github.guoyyit.georasterizerjava.service.GeometryRejectedException;
import com.github.guoyyit.georasterizerjava.service.RasterizationResult;
import com.github.guoyyit.georasterizerjava.service.RasterizationService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 面积守恒校验：精确输入零偏差通过；偏差超阈值显式失败；越界裁剪 / 拒绝策略。
 */
class AreaConservationTest {

    private Feature solidSquareFeature() {
        return new Feature("square", TestGeometries.squareCcw(0, 0, 4, 4), 1.0, 0);
    }

    @Test
    void exactCoverageConservesAreaAndPasses() {
        // 1.0 像元对齐 4x4 方块，理论与栅格面积应完全相等
        RasterConfig config = RasterConfig.builder(1.0)
                .outputEnvelope(new Envelope(0, 0, 4, 4))
                .fillRule(FillRule.WINDING)
                .areaTolerance(1.0e-9)
                .build();
        RasterizationResult result = new RasterizationService()
                .rasterize(List.of(solidSquareFeature()), config, null);
        assertEquals(16.0, result.areaReport().theoreticalArea(), 1.0e-12);
        assertEquals(16.0, result.areaReport().rasterizedArea(), 1.0e-12);
        assertEquals(0.0, result.areaReport().relativeDeviation(), 1.0e-12);
        assertTrue(result.areaReport().passed());
    }

    @Test
    void exceedingToleranceFailsExplicitlyAndProducesNoCoverage() {
        // 精确覆盖率引擎的偏差只来自浮点累加（~1e-16 量级）。
        // 用复杂非对齐几何 + 零阈值确定性地触发面积守恒失败，验证专用异常与报告。
        java.util.List<Feature> features = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            var ring = new com.github.guoyyit.georasterizerjava.model.LinearRing(java.util.List.of(
                    new com.github.guoyyit.georasterizerjava.model.Point(
                            0.1 + i * 0.01, 0.2),
                    new com.github.guoyyit.georasterizerjava.model.Point(
                            9.7 - i * 0.013, 0.3),
                    new com.github.guoyyit.georasterizerjava.model.Point(
                            9.5 - i * 0.011, 9.8),
                    new com.github.guoyyit.georasterizerjava.model.Point(
                            0.3 + i * 0.007, 9.6)));
            features.add(new Feature("strip-" + i,
                    new com.github.guoyyit.georasterizerjava.model.Polygon(ring,
                            java.util.List.of()), 1.0, 0));
        }
        RasterConfig config = RasterConfig.builder(0.13)
                .outputEnvelope(new Envelope(0, 0, 10, 10))
                .fillRule(FillRule.ODD_EVEN)
                .tileSize(7, 7)
                .parallelism(3)
                .areaTolerance(0.0)
                .build();
        AreaConservationException exception = assertThrows(AreaConservationException.class,
                () -> new RasterizationService().rasterize(features, config, null));
        assertFalse(exception.report().passed());
        assertTrue(exception.report().relativeDeviation() > 0.0);
        assertEquals(0.0, exception.report().tolerance(), 0.0);
    }

    @Test
    void generousToleranceAllowsBoundaryQuantization() {
        RasterConfig config = RasterConfig.builder(0.37)
                .outputEnvelope(new Envelope(0, 0, 1.0, 1.0))
                .fillRule(FillRule.WINDING)
                .areaTolerance(0.25)
                .build();
        Feature tinyFeature = new Feature("tiny",
                TestGeometries.squareCcw(0.1, 0.1, 0.9, 0.9), 1.0, 0);
        RasterizationResult result = new RasterizationService()
                .rasterize(List.of(tinyFeature), config, null);
        assertTrue(result.areaReport().passed());
    }

    @Test
    void rejectPolicyFailsWhenGeometryExtendsBeyondEnvelope() {
        RasterConfig config = RasterConfig.builder(1.0)
                .outputEnvelope(new Envelope(0, 0, 3, 3))
                .outOfRangePolicy(OutOfRangePolicy.REJECT)
                .build();
        GeometryRejectedException exception = assertThrows(GeometryRejectedException.class,
                () -> new RasterizationService()
                        .rasterize(List.of(solidSquareFeature()), config, null));
        assertEquals("square", exception.featureId());
    }

    @Test
    void clipPolicyRastersOnlyIntersectionAndRecordsStrategy() {
        RasterConfig config = RasterConfig.builder(1.0)
                .outputEnvelope(new Envelope(0, 0, 3, 3))
                .outOfRangePolicy(OutOfRangePolicy.CLIP)
                .build();
        RasterizationResult result = new RasterizationService()
                .rasterize(List.of(solidSquareFeature()), config, null);
        // 方块 0..4 与输出范围 0..3 的交集面积为 9
        assertEquals(9.0, result.areaReport().theoreticalArea(), 1.0e-9);
        assertEquals(9.0, result.areaReport().rasterizedArea(), 1.0e-9);
        assertEquals("CLIP", result.metadata().get("outOfRangePolicy"));
        assertTrue(result.metadata().containsKey("processingStrategy"));
    }

    @Test
    void metadataRecordsSemanticsForDownstreamTraceability() {
        RasterConfig config = RasterConfig.builder(2.0)
                .outputEnvelope(new Envelope(0, 0, 4, 4))
                .fillRule(FillRule.ODD_EVEN)
                .outOfRangePolicy(OutOfRangePolicy.CLIP)
                .tileSize(2, 2)
                .build();
        RasterizationResult result = new RasterizationService()
                .rasterize(List.of(solidSquareFeature()), config, null);
        assertEquals("ODD_EVEN", result.metadata().get("fillRule"));
        assertEquals("CLIP", result.metadata().get("outOfRangePolicy"));
        assertEquals(2.0, result.metadata().get("cellSize"));
        assertTrue(result.metadata().containsKey("processingStrategy"));
        assertTrue(((Map<?, ?>) result.metadata().get("areaConservation"))
                .get("passed").equals(Boolean.TRUE));
    }
}
