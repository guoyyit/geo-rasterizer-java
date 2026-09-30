package com.github.guoyyit.georasterizerjava.config;

import com.github.guoyyit.georasterizerjava.model.Envelope;
import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.model.MergeStrategy;
import com.github.guoyyit.georasterizerjava.model.OutOfRangePolicy;

/**
 * 栅格化任务配置：分辨率、输出范围、填充/合并/越界策略、面积阈值与分块大小。
 */
public final class RasterConfig {

    private final double cellSize;
    private final Envelope outputEnvelope;
    private final FillRule fillRule;
    private final MergeStrategy mergeStrategy;
    private final OutOfRangePolicy outOfRangePolicy;
    private final double areaTolerance;
    private final int tileCols;
    private final int tileRows;
    private final int parallelism;

    private RasterConfig(Builder builder) {
        if (!(builder.cellSize > 0.0) || Double.isNaN(builder.cellSize)
                || Double.isInfinite(builder.cellSize)) {
            throw new IllegalArgumentException("cellSize must be a finite positive number");
        }
        if (!(builder.areaTolerance >= 0.0) || Double.isNaN(builder.areaTolerance)) {
            throw new IllegalArgumentException("areaTolerance must be non-negative");
        }
        if (builder.tileCols <= 0 || builder.tileRows <= 0) {
            throw new IllegalArgumentException("tile dimensions must be positive");
        }
        if (builder.parallelism <= 0) {
            throw new IllegalArgumentException("parallelism must be positive");
        }
        this.cellSize = builder.cellSize;
        this.outputEnvelope = builder.outputEnvelope;
        this.fillRule = builder.fillRule;
        this.mergeStrategy = builder.mergeStrategy;
        this.outOfRangePolicy = builder.outOfRangePolicy;
        this.areaTolerance = builder.areaTolerance;
        this.tileCols = builder.tileCols;
        this.tileRows = builder.tileRows;
        this.parallelism = builder.parallelism;
    }

    public static Builder builder(double cellSize) {
        return new Builder(cellSize);
    }

    public double cellSize() {
        return cellSize;
    }

    public Envelope outputEnvelope() {
        return outputEnvelope;
    }

    public FillRule fillRule() {
        return fillRule;
    }

    public MergeStrategy mergeStrategy() {
        return mergeStrategy;
    }

    public OutOfRangePolicy outOfRangePolicy() {
        return outOfRangePolicy;
    }

    public double areaTolerance() {
        return areaTolerance;
    }

    public int tileCols() {
        return tileCols;
    }

    public int tileRows() {
        return tileRows;
    }

    public int parallelism() {
        return parallelism;
    }

    public static final class Builder {

        private final double cellSize;
        private Envelope outputEnvelope;
        private FillRule fillRule = FillRule.WINDING;
        private MergeStrategy mergeStrategy = MergeStrategy.COUNT;
        private OutOfRangePolicy outOfRangePolicy = OutOfRangePolicy.CLIP;
        private double areaTolerance = 1.0e-6;
        private int tileCols = 256;
        private int tileRows = 256;
        private int parallelism = Math.max(1, Runtime.getRuntime().availableProcessors());

        private Builder(double cellSize) {
            this.cellSize = cellSize;
        }

        public Builder outputEnvelope(Envelope outputEnvelope) {
            this.outputEnvelope = outputEnvelope;
            return this;
        }

        public Builder fillRule(FillRule fillRule) {
            this.fillRule = fillRule;
            return this;
        }

        public Builder mergeStrategy(MergeStrategy mergeStrategy) {
            this.mergeStrategy = mergeStrategy;
            return this;
        }

        public Builder outOfRangePolicy(OutOfRangePolicy outOfRangePolicy) {
            this.outOfRangePolicy = outOfRangePolicy;
            return this;
        }

        public Builder areaTolerance(double areaTolerance) {
            this.areaTolerance = areaTolerance;
            return this;
        }

        public Builder tileSize(int cols, int rows) {
            this.tileCols = cols;
            this.tileRows = rows;
            return this;
        }

        public Builder parallelism(int parallelism) {
            this.parallelism = parallelism;
            return this;
        }

        public RasterConfig build() {
            if (fillRule == null) {
                throw new IllegalArgumentException("fillRule must not be null");
            }
            if (mergeStrategy == null) {
                throw new IllegalArgumentException("mergeStrategy must not be null");
            }
            if (outOfRangePolicy == null) {
                throw new IllegalArgumentException("outOfRangePolicy must not be null");
            }
            return new RasterConfig(this);
        }
    }
}
