package com.github.guoyyit.georasterizerjava.model;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.List;

/**
 * Result of a rasterization job (fresh or resumed from a checkpoint).
 */
public record RasterizationResult(
        CoverageMap coverage,
        int gridWidth,
        int gridHeight,
        double cellSize,
        double xmin,
        double ymin,
        double effectiveXMax,
        double effectiveYMax,
        FillRule fillRule,
        MergeSemantics mergeSemantics,
        OutOfRangePolicy outOfRangePolicy,
        int totalFeatures,
        int acceptedFeatures,
        List<Long> rejectedFeatureIds,
        double unionCoveredArea,
        AreaReport areaReport,
        int tilesX,
        int tilesY,
        int tileWidthPixels,
        int tileHeightPixels,
        boolean resumed,
        boolean committed,
        Path checkpointDirectory
) implements Serializable {
}
