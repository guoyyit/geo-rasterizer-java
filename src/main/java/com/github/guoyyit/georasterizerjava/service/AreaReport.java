package com.github.guoyyit.georasterizerjava.service;

/**
 * 面积守恒校验报告。
 *
 * @param theoreticalArea    几何理论填充面积（按填充规则、在有效输出范围内精确计算）
 * @param rasterizedArea     栅格覆盖率反推的覆盖面积
 * @param absoluteDeviation  |rasterized - theoretical|
 * @param relativeDeviation  |rasterized - theoretical| / max(|theoretical|, floor)
 * @param tolerance          判定阈值（相对偏差），可配置
 * @param passed             是否通过校验；false 时整个作业显式失败，不产出覆盖图
 */
public record AreaReport(double theoreticalArea,
                         double rasterizedArea,
                         double absoluteDeviation,
                         double relativeDeviation,
                         double tolerance,
                         boolean passed) {
}
