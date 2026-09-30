package com.github.guoyyit.georasterizerjava.model;

import java.nio.file.Path;

/**
 * Convenience builder for {@link RasterConfig} with documented defaults.
 */
public final class ConfigBuilder {

    private double cellSize = Double.NaN;
    private double xmin = Double.NaN;
    private double ymin = Double.NaN;
    private double xmax = Double.NaN;
    private double ymax = Double.NaN;
    private FillRule fillRule = FillRule.NON_ZERO;
    private MergeSemantics mergeSemantics = MergeSemantics.COUNT;
    private OutOfRangePolicy outOfRangePolicy = OutOfRangePolicy.CLIP;
    private double areaRelativeTolerance = 0.01;
    private int tileWidthPixels = 256;
    private int tileHeightPixels = 256;
    private int workerThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
    private boolean assembleCoverage = true;
    private String checkpointDirectory;

    public ConfigBuilder() {
    }

    public RasterConfig build() {
        if (Double.isNaN(cellSize) || cellSize <= 0.0 || !Double.isFinite(cellSize)) {
            throw new IllegalArgumentException("cellSize must be a finite positive number");
        }
        if (Double.isNaN(xmin) || Double.isNaN(xmax) || Double.isNaN(ymin) || Double.isNaN(ymax)
                || !Double.isFinite(xmin) || !Double.isFinite(ymin)
                || !Double.isFinite(xmax) || !Double.isFinite(ymax)) {
            throw new IllegalArgumentException("extent bounds must all be finite");
        }
        if (!(xmax > xmin) || !(ymax > ymin)) {
            throw new IllegalArgumentException("extent must have positive width and height");
        }
        if (areaRelativeTolerance < 0.0) {
            throw new IllegalArgumentException("areaRelativeTolerance must be >= 0");
        }
        if (tileWidthPixels <= 0 || tileHeightPixels <= 0) {
            throw new IllegalArgumentException("tile dimensions must be positive");
        }
        if (workerThreads <= 0) {
            throw new IllegalArgumentException("workerThreads must be positive");
        }
        long gridCols = (long) Math.ceil((xmax - xmin) / cellSize);
        long gridRows = (long) Math.ceil((ymax - ymin) / cellSize);
        if (gridCols <= 0L || gridRows <= 0L) {
            throw new IllegalArgumentException("extent is smaller than one cell");
        }
        if (gridCols > Integer.MAX_VALUE || gridRows > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("extent contains more than "
                    + Integer.MAX_VALUE + " rows or columns");
        }
        if (checkpointDirectory == null) {
            throw new IllegalArgumentException("checkpointDirectory is required");
        }
        return new RasterConfig(
                cellSize, xmin, ymin, xmax, ymax,
                fillRule, mergeSemantics, outOfRangePolicy, areaRelativeTolerance,
                tileWidthPixels, tileHeightPixels, workerThreads, assembleCoverage,
                checkpointDirectory
        );
    }

    public ConfigBuilder cellSize(double cellSize) {
        this.cellSize = cellSize;
        return this;
    }

    public ConfigBuilder extent(double xmin, double ymin, double xmax, double ymax) {
        this.xmin = xmin;
        this.ymin = ymin;
        this.xmax = xmax;
        this.ymax = ymax;
        return this;
    }

    public ConfigBuilder fillRule(FillRule fillRule) {
        this.fillRule = fillRule;
        return this;
    }

    public ConfigBuilder mergeSemantics(MergeSemantics mergeSemantics) {
        this.mergeSemantics = mergeSemantics;
        return this;
    }

    public ConfigBuilder outOfRangePolicy(OutOfRangePolicy outOfRangePolicy) {
        this.outOfRangePolicy = outOfRangePolicy;
        return this;
    }

    public ConfigBuilder areaRelativeTolerance(double tolerance) {
        this.areaRelativeTolerance = tolerance;
        return this;
    }

    public ConfigBuilder tileSize(int widthPixels, int heightPixels) {
        this.tileWidthPixels = widthPixels;
        this.tileHeightPixels = heightPixels;
        return this;
    }

    public ConfigBuilder workerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
        return this;
    }

    public ConfigBuilder assembleCoverage(boolean assembleCoverage) {
        this.assembleCoverage = assembleCoverage;
        return this;
    }

    public ConfigBuilder checkpointDirectory(Path checkpointDirectory) {
        this.checkpointDirectory = checkpointDirectory.toString();
        return this;
    }

    public ConfigBuilder checkpointDirectory(String checkpointDirectory) {
        this.checkpointDirectory = checkpointDirectory;
        return this;
    }
}
