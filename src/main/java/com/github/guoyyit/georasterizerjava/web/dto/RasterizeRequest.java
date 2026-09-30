package com.github.guoyyit.georasterizerjava.web.dto;

import java.util.List;

/**
 * 栅格化请求。outputEnvelope 缺省时由所有要素包围盒推断；workDir 缺省时使用临时目录（不可续算）。
 */
public record RasterizeRequest(double cellSize,
                               double[] outputEnvelope,
                               String fillRule,
                               String mergeStrategy,
                               String outOfRangePolicy,
                               Double areaTolerance,
                               Integer tileCols,
                               Integer tileRows,
                               Integer parallelism,
                               String workDir,
                               List<FeatureDto> features) {
}
