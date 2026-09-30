package com.github.guoyyit.georasterizerjava.tests;

import com.github.guoyyit.georasterizerjava.config.RasterConfig;
import com.github.guoyyit.georasterizerjava.model.Envelope;
import com.github.guoyyit.georasterizerjava.model.Feature;
import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.model.Polygon;
import com.github.guoyyit.georasterizerjava.service.AreaReport;
import com.github.guoyyit.georasterizerjava.service.RasterizationResult;
import com.github.guoyyit.georasterizerjava.service.RasterizationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 填充规则正确性：奇偶 / 环绕、孔洞定向、自相交、重复顶点。
 */
class FillRuleRasterizationTest {

    private static final Envelope ENVELOPE = new Envelope(0, 0, 4, 4);

    private RasterizationResult run(Polygon polygon, FillRule rule) {
        RasterConfig config = RasterConfig.builder(1.0)
                .outputEnvelope(ENVELOPE)
                .fillRule(rule)
                .tileSize(3, 3)
                .parallelism(2)
                .build();
        return new RasterizationService()
                .rasterize(List.of(new Feature("f", polygon, 1.0, 0)), config, null);
    }

    private void assertAreas(RasterizationResult result, double expected) {
        AreaReport report = result.areaReport();
        assertEquals(expected, report.theoreticalArea(), 1.0e-9, "theoretical area");
        assertEquals(expected, report.rasterizedArea(), 1.0e-9, "rasterized area");
        assertTrue(report.passed(), "area conservation must pass: " + report);
        assertEquals(0.0, report.relativeDeviation(), 1.0e-12);
    }

    @Test
    void solidSquareBothRules() {
        Polygon square = TestGeometries.squareCcw(0, 0, 4, 4);
        assertAreas(run(square, FillRule.ODD_EVEN), 16.0);
        assertAreas(run(square, FillRule.WINDING), 16.0);
    }

    @Test
    void holeOrientationDistinguishesRules() {
        // ODD 不看环方向：内环一律视为孔洞，面积 16-4=12（内孔 2x2）
        assertAreas(run(TestGeometries.squareWithCwHole(), FillRule.ODD_EVEN), 12.0);
        assertAreas(run(TestGeometries.squareWithCcwHole(), FillRule.ODD_EVEN), 12.0);
        // WINDING 看方向：反向孔洞（CW）环绕相消=12；同向内环（CCW）环绕叠加（孔内 winding=2，
        // 仍为非零覆盖），按覆盖面积语义记 16 而非按重数翻倍
        assertAreas(run(TestGeometries.squareWithCwHole(), FillRule.WINDING), 12.0);
        assertAreas(run(TestGeometries.squareWithCcwHole(), FillRule.WINDING), 16.0);
    }

    @Test
    void selfIntersectingBowtieBothRules() {
        assertAreas(run(TestGeometries.bowtie(), FillRule.ODD_EVEN), 8.0);
        assertAreas(run(TestGeometries.bowtie(), FillRule.WINDING), 8.0);
    }

    @Test
    void duplicateVerticesAreNormalized() {
        assertAreas(run(TestGeometries.squareWithDuplicateVertices(), FillRule.ODD_EVEN), 16.0);
        assertAreas(run(TestGeometries.squareWithDuplicateVertices(), FillRule.WINDING), 16.0);
    }

    @Test
    void interiorCellFullyCoveredForSolidSquare() {
        RasterizationResult result = run(TestGeometries.squareCcw(0, 0, 4, 4), FillRule.WINDING);
        // 1x1 像元完全落在正方形内部时覆盖率应为 1
        assertEquals(1.0, result.coverageAt(1, 1), 1.0e-12);
        assertEquals(1.0, result.coverageAt(2, 2), 1.0e-12);
    }
}
