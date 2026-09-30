package com.github.guoyyit.georasterizerjava.raster;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits the output grid into tiles of at most the configured size, in a
 * deterministic order (row-major from the lower-left).
 */
public final class TilePlanner {

    private final int gridWidth;
    private final int gridHeight;
    private final int tileWidth;
    private final int tileHeight;
    private final int tilesX;
    private final int tilesY;

    public TilePlanner(int gridWidth, int gridHeight, int tileWidth, int tileHeight) {
        if (gridWidth <= 0 || gridHeight <= 0 || tileWidth <= 0 || tileHeight <= 0) {
            throw new IllegalArgumentException("grid and tile dimensions must be positive");
        }
        this.gridWidth = gridWidth;
        this.gridHeight = gridHeight;
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        this.tilesX = (gridWidth + tileWidth - 1) / tileWidth;
        this.tilesY = (gridHeight + tileHeight - 1) / tileHeight;
    }

    public List<Tile> tiles() {
        List<Tile> tiles = new ArrayList<>(tilesX * tilesY);
        int index = 0;
        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int col0 = tx * tileWidth;
                int row0 = ty * tileHeight;
                int width = Math.min(tileWidth, gridWidth - col0);
                int height = Math.min(tileHeight, gridHeight - row0);
                tiles.add(new Tile(index++, tx, ty, col0, row0, width, height));
            }
        }
        return tiles;
    }

    public int tilesX() {
        return tilesX;
    }

    public int tilesY() {
        return tilesY;
    }
}
