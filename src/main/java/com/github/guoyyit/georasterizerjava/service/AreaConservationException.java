package com.github.guoyyit.georasterizerjava.service;

/**
 * 面积守恒校验失败：输出覆盖面积与理论面积偏差超过可配置阈值。
 * 作业显式失败，已完成分块保留在检查点目录中供排查，但不返回覆盖图。
 */
public class AreaConservationException extends RasterizationException {

    private final AreaReport report;

    public AreaConservationException(AreaReport report) {
        super(String.format(
                "area conservation check failed: theoretical=%.12g, rasterized=%.12g, "
                        + "absoluteDeviation=%.3g, relativeDeviation=%.3g exceeds tolerance %.3g",
                report.theoreticalArea(), report.rasterizedArea(), report.absoluteDeviation(),
                report.relativeDeviation(), report.tolerance()));
        this.report = report;
    }

    public AreaReport report() {
        return report;
    }
}
