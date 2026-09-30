package com.github.guoyyit.georasterizerjava.model;

import java.io.Serializable;

/**
 * Immutable rasterization configuration.
 */
public record RasterConfig(
        double cellSize,
        double xmin,
        double ymin,
        double xmax,
        double ymax,
        FillRule fillRule,
        MergeSemantics mergeSemantics,
        OutOfRangePolicy outOfRangePolicy,
        double areaRelativeTolerance,
        int tileWidthPixels,
        int tileHeightPixels,
        int workerThreads,
        boolean assembleCoverage,
        String checkpointDirectory
) implements Serializable {

    public java.nio.file.Path checkpointDirectoryPath() {
        return java.nio.file.Path.of(checkpointDirectory);
    }
}
