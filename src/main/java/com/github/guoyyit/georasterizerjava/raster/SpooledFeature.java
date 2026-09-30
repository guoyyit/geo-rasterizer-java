package com.github.guoyyit.georasterizerjava.raster;

import com.github.guoyyit.georasterizerjava.model.Feature;

import java.io.Serializable;

/**
 * A feature persisted for re-reading during tile-at-a-time processing,
 * with its precomputed expected (post-policy) theoretical area and a
 * bounding box for per-tile culling.
 */
public record SpooledFeature(
        long sequence,
        Feature feature,
        double expectedArea,
        double minX,
        double minY,
        double maxX,
        double maxY
) implements Serializable {
}
