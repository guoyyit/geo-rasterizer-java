package com.github.guoyyit.georasterizerjava.raster;

/**
 * Invoked after each tile checkpoint commit.
 */
@FunctionalInterface
public interface ProgressListener {
    void onTileCommitted(int completedTiles, int totalTiles);
}
