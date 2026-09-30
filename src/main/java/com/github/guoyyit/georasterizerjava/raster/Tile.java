package com.github.guoyyit.georasterizerjava.raster;

/**
 * A rectangular region of the output grid, in cell coordinates.
 */
public record Tile(
        int index,
        int tx,
        int ty,
        int col0,
        int row0,
        int width,
        int height
) {
}
