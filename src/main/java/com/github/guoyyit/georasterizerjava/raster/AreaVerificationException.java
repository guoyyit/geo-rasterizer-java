package com.github.guoyyit.georasterizerjava.raster;

import com.github.guoyyit.georasterizerjava.model.AreaReport;

/**
 * Thrown when rasterized area deviates from theoretical area beyond the
 * configured tolerance. The coverage is never marked committed.
 */
public class AreaVerificationException extends RasterizationException {

    private final transient AreaReport areaReport;

    public AreaVerificationException(AreaReport areaReport) {
        super("Area conservation check failed: " + areaReport);
        this.areaReport = areaReport;
    }

    public AreaReport areaReport() {
        return areaReport;
    }
}
