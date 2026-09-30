package com.github.guoyyit.georasterizerjava.model;

import java.io.Serializable;

/**
 * Area conservation verification. {@code rasterizedArea} counts every
 * feature-cell incidence (COUNT view), while {@code theoreticalArea} is the
 * analytic area of the accepted geometry under the active fill rule.
 */
public record AreaReport(
        double rasterizedArea,
        double theoreticalArea,
        double absoluteError,
        double relativeError,
        double relativeTolerance,
        boolean passed
) implements Serializable {
}
