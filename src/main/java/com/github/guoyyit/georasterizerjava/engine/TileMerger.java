package com.github.guoyyit.georasterizerjava.engine;

import com.github.guoyyit.georasterizerjava.model.Feature;
import com.github.guoyyit.georasterizerjava.model.MergeStrategy;
import com.github.guoyyit.georasterizerjava.raster.TileResult;

/**
 * 多要素分块结果的顺序无关合并。
 *
 * <p>每个像元维护覆盖率、聚合值、获胜要素 id 与获胜要素优先级：</p>
 * <ul>
 *   <li>COUNT：覆盖率与聚合值按要素覆盖率累加（覆盖面积计数）；可交换、可结合。</li>
 *   <li>MAX：保留最大 {@link Feature#value()} 的要素，value 相同时取 id 字典序较小者。</li>
 *   <li>PRIORITY：保留最高 {@link Feature#priority()} 的要素，优先级相同时取 id 字典序较小者。</li>
 * </ul>
 * <p>MAX 与 PRIORITY 的选择只依赖 (值/优先级, id)，与到达顺序无关；最终像元覆盖率取获胜要素的覆盖率。</p>
 */
final class TileMerger {

    private final TileResult accumulated;
    private final MergeStrategy strategy;

    TileMerger(MergeStrategy strategy) {
        this(strategy, null);
    }

    TileMerger(MergeStrategy strategy, TileResult seed) {
        this.strategy = strategy;
        this.accumulated = seed;
    }

    /**
     * 在指定分块尺寸上创建合并器（用于流式处理的新块）。
     */
    static TileMerger forTile(MergeStrategy strategy, int tileX, int tileY,
                              int startCol, int startRow, int cols, int rows) {
        return new TileMerger(strategy,
                new TileResult(tileX, tileY, startCol, startRow, cols, rows));
    }

    void merge(TileResult partial) {
        if (partial == null) {
            return;
        }
        if (accumulated.startCol() != partial.startCol() || accumulated.startRow() != partial.startRow()
                || accumulated.cols() != partial.cols() || accumulated.rows() != partial.rows()) {
            throw new IllegalArgumentException("tile geometry must match when merging");
        }
        for (int index = 0; index < accumulated.cols() * accumulated.rows(); index++) {
            double partialCoverage = partial.coverageAt(index);
            if (partialCoverage == 0.0) {
                continue;
            }
            switch (strategy) {
                case COUNT -> mergeCount(index, partial);
                case MAX -> mergeMax(index, partial);
                case PRIORITY -> mergePriority(index, partial);
                default -> throw new IllegalStateException("unknown merge strategy: " + strategy);
            }
        }
    }

    private void mergeCount(int index, TileResult partial) {
        double combinedCoverage = accumulated.coverageAt(index) + partial.coverageAt(index);
        double combinedValue = accumulated.valueAt(index) + partial.valueAt(index);
        String combinedWinner = accumulated.winnerAt(index) == null
                ? partial.winnerAt(index)
                : minId(accumulated.winnerAt(index), partial.winnerAt(index));
        accumulated.setCell(index, combinedCoverage, combinedValue, 0, combinedWinner);
    }

    private void mergeMax(int index, TileResult partial) {
        if (prefer(index, partial.valueAt(index), partial.winnerAt(index),
                accumulated.valueAt(index), accumulated.winnerAt(index))) {
            accumulated.setCell(index, partial.coverageAt(index), partial.valueAt(index),
                    partial.priorityAt(index), partial.winnerAt(index));
        }
    }

    private void mergePriority(int index, TileResult partial) {
        if (prefer(index, partial.priorityAt(index), partial.winnerAt(index),
                accumulated.priorityAt(index), accumulated.winnerAt(index))) {
            accumulated.setCell(index, partial.coverageAt(index), partial.valueAt(index),
                    partial.priorityAt(index), partial.winnerAt(index));
        }
    }

    private boolean prefer(int index, double candidateKey,
                           String candidateId, double currentKey, String currentId) {
        if (accumulated.coverageAt(index) == 0.0) {
            return true;
        }
        if (candidateKey != currentKey) {
            return candidateKey > currentKey;
        }
        return candidateId.compareTo(currentId) < 0;
    }

    private static String minId(String first, String second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.compareTo(second) <= 0 ? first : second;
    }

    TileResult build() {
        return accumulated;
    }
}
