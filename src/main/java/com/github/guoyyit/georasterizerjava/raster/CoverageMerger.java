package com.github.guoyyit.georasterizerjava.raster;

import com.github.guoyyit.georasterizerjava.model.MergeSemantics;

import java.util.BitSet;

/**
 * Merges per-feature covered-cell sets into one tile's coverage arrays.
 * The tie-breaking rules below are explicit, so the result does not
 * depend on the order in which features are merged:
 * <ul>
 *   <li>{@code COUNT} – value is the number of covering features and
 *       the owner is the smallest covering feature id;</li>
 *   <li>{@code MAX} – the largest weight wins and equal weights are
 *       broken by the smaller feature id;</li>
 *   <li>{@code PRIORITY} – the largest priority wins and equal
 *       priorities are broken by the smaller feature id.</li>
 * </ul>
 * Uncovered cells keep owner {@code -1}. Features are nevertheless fed
 * in ascending feature-id order by the service.
 */
public final class CoverageMerger {

    private final MergeSemantics semantics;
    private final Tile tile;
    private final double[] values;
    private final long[] owners;
    private final BitSet covered;
    private long unionCells;
    private long incidenceCells;

    public CoverageMerger(MergeSemantics semantics, Tile tile) {
        this.semantics = semantics;
        this.tile = tile;
        int cells = tile.width() * tile.height();
        this.values = new double[cells];
        this.owners = new long[cells];
        this.covered = new BitSet(cells);
        java.util.Arrays.fill(this.owners, -1L);
    }

    public void mergeFeature(
            long featureId, double weight, int priority, BitSet coveredLocalCells
    ) {
        for (int local = coveredLocalCells.nextSetBit(0);
             local >= 0;
             local = coveredLocalCells.nextSetBit(local + 1)) {
            incidenceCells++;
            boolean newlyCovered = !covered.get(local);
            if (newlyCovered) {
                covered.set(local);
                unionCells++;
                owners[local] = featureId;
                values[local] = semantics == MergeSemantics.COUNT ? 1.0
                        : semantics == MergeSemantics.MAX ? weight : priority;
            } else {
                switch (semantics) {
                    case COUNT -> {
                        values[local] += 1.0;
                        if (featureId < owners[local]) {
                            owners[local] = featureId;
                        }
                    }
                    case MAX -> {
                        if (weight > values[local]
                                || (weight == values[local] && featureId < owners[local])) {
                            values[local] = weight;
                            owners[local] = featureId;
                        }
                    }
                    case PRIORITY -> {
                        if (priority > values[local]
                                || (priority == values[local] && featureId < owners[local])) {
                            values[local] = priority;
                            owners[local] = featureId;
                        }
                    }
                }
            }
        }
    }

    public double[] values() {
        return values;
    }

    public long[] owners() {
        return owners;
    }

    public long unionCellCount() {
        return unionCells;
    }

    public long incidenceCellCount() {
        return incidenceCells;
    }
}
