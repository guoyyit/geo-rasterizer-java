package com.github.guoyyit.georasterizerjava.raster;

import com.github.guoyyit.georasterizerjava.geometry.PolygonArea;
import com.github.guoyyit.georasterizerjava.geometry.PolygonClipper;
import com.github.guoyyit.georasterizerjava.geometry.ScanlineRasterizer;
import com.github.guoyyit.georasterizerjava.model.AreaReport;
import com.github.guoyyit.georasterizerjava.model.CoverageMap;
import com.github.guoyyit.georasterizerjava.model.Feature;
import com.github.guoyyit.georasterizerjava.model.Point;
import com.github.guoyyit.georasterizerjava.model.Polygon;
import com.github.guoyyit.georasterizerjava.model.RasterConfig;
import com.github.guoyyit.georasterizerjava.model.RasterizationResult;
import com.github.guoyyit.georasterizerjava.model.Ring;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Orchestrates streaming, tiled, resumable polygon rasterization.
 *
 * <p>Features are clipped/rejected up front and spooled to the checkpoint
 * directory, so memory stays bounded regardless of input size. Tiles are
 * then processed one at a time: each tile rasterizes its features with a
 * bounded worker pool, merges results in feature-id order and is committed
 * atomically. The coverage becomes visible only after the area check
 * passes and the commit marker is written, so a crash never leaves a
 * partially committed coverage.
 */
public final class RasterizationService {

    private static final int MAX_PENDING_CHUNKS = 4;

    public RasterizationService() {
    }

    public RasterizationResult rasterize(RasterConfig config, List<Feature> features) {
        return rasterize(config, features, null);
    }

    public RasterizationResult rasterize(
            RasterConfig config,
            Iterable<Feature> features,
            ProgressListener listener
    ) {
        return runFresh(config, features, listener);
    }

    public RasterizationResult resume(Path checkpointDirectory) {
        return resume(checkpointDirectory, null);
    }

    public RasterizationResult resume(Path checkpointDirectory, ProgressListener listener) {
        try (CheckpointStore store = CheckpointStore.open(checkpointDirectory)) {
            if (store.isCommitted()) {
                return assembleResult(store, store.commitSummary(), true);
            }
            return processTiles(store, listener, true);
        }
    }

    private RasterizationResult runFresh(
            RasterConfig config, Iterable<Feature> input, ProgressListener listener
    ) {
        int gridWidth = gridSize(config.xmin(), config.xmax(), config.cellSize());
        int gridHeight = gridSize(config.ymin(), config.ymax(), config.cellSize());
        TilePlanner planner = new TilePlanner(
                gridWidth, gridHeight, config.tileWidthPixels(), config.tileHeightPixels());

        // Single streaming pass: clip, compute expected area and spool
        // each accepted feature immediately. Only the current geometry is
        // held in memory, regardless of stream length.
        int total = 0;
        int acceptedCount = 0;
        double totalArea = 0.0;
        List<Long> rejected = new ArrayList<>();
        int sequence = 0;

        CheckpointStore store = CheckpointStore.openForStreaming(
                config.checkpointDirectoryPath());
        try {
            CheckpointStore.SpooledFeatureSink writer = store.featureWriter();
            for (Feature feature : input) {
                total++;
                Optional<Polygon> clipped = PolygonClipper.apply(
                        feature.polygon(),
                        config.xmin(), config.ymin(), config.xmax(), config.ymax(),
                        config.outOfRangePolicy());
                if (clipped.isEmpty()) {
                    rejected.add(feature.id());
                    continue;
                }
                Polygon polygon = clipped.get();
                double area = PolygonArea.theoreticalArea(polygon, config.fillRule());
                double[] bounds = boundsOf(polygon);
                writer.write(new SpooledFeature(
                        sequence++, feature, area,
                        bounds[0], bounds[1], bounds[2], bounds[3]));
                acceptedCount++;
                totalArea += area;
            }
            writer.close();

            CheckpointStore.JobMetadata metadata = new CheckpointStore.JobMetadata(
                    config, gridWidth, gridHeight, planner.tilesX(), planner.tilesY(),
                    total, acceptedCount, List.copyOf(rejected), totalArea);
            store.finalizeInitialization(metadata);
            RasterizationResult result = processTiles(store, listener, false);
            store.close();
            return result;
        } catch (RuntimeException e) {
            store.close();
            throw e;
        }
    }

    private RasterizationResult processTiles(
            CheckpointStore store, ProgressListener listener, boolean resuming
    ) {
        CheckpointStore.JobMetadata metadata = store.metadata();
        RasterConfig config = metadata.config();
        TilePlanner planner = new TilePlanner(
                metadata.gridWidth(), metadata.gridHeight(),
                config.tileWidthPixels(), config.tileHeightPixels());
        List<Tile> tiles = planner.tiles();

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.max(1, config.workerThreads()));
        try {
            for (Tile tile : tiles) {
                if (tile.index() < store.completedTileCount()) {
                    continue;
                }
                CheckpointStore.TileData data = rasterizeTile(
                        store, metadata, tile, executor);
                store.commitTile(tile.index(), data);
                if (listener != null) {
                    listener.onTileCommitted(tile.index() + 1, tiles.size());
                }
            }
        } finally {
            executor.shutdown();
        }

        long unionCells = 0L;
        long incidenceCells = 0L;
        for (Tile tile : tiles) {
            CheckpointStore.TileData data = store.readTile(tile.index());
            unionCells += data.unionCells();
            incidenceCells += data.incidenceCells();
        }

        double cellArea = config.cellSize() * config.cellSize();
        AreaReport report = areaReport(
                incidenceCells * cellArea, metadata.totalTheoreticalArea(),
                config.areaRelativeTolerance());

        CheckpointStore.CommitSummary summary = new CheckpointStore.CommitSummary(
                unionCells, incidenceCells, report, resuming);
        if (!report.passed()) {
            throw new AreaVerificationException(report);
        }
        store.markCommitted(summary);
        return assembleResult(store, summary, resuming);
    }

    private CheckpointStore.TileData rasterizeTile(
            CheckpointStore store,
            CheckpointStore.JobMetadata metadata,
            Tile tile,
            ExecutorService executor
    ) {
        RasterConfig config = metadata.config();
        List<SpooledFeature> candidates = new ArrayList<>();
        store.featureIterator().forEachRemaining(feature -> {
            if (overlapsTile(feature, tile, config)) {
                candidates.add(feature);
            }
        });
        // Merge order is the feature id, never the submission order, so
        // permuting the input set cannot change values or owners.
        candidates.sort(java.util.Comparator
                .comparingLong((SpooledFeature f) -> f.feature().id())
                .thenComparingLong(SpooledFeature::sequence));

        CoverageMerger merger = new CoverageMerger(config.mergeSemantics(), tile);

        List<Future<RasterizedFeature>> pending = new ArrayList<>();
        int cursor = 0;
        long nextMerged = 0;
        int window = Math.max(MAX_PENDING_CHUNKS,
                Math.max(1, config.workerThreads()) * MAX_PENDING_CHUNKS);
        while (nextMerged < candidates.size()) {
            while (cursor < candidates.size() && pending.size() < window) {
                SpooledFeature feature = candidates.get(cursor++);
                pending.add(executor.submit(() -> rasterizeOne(feature, metadata, tile, config)));
            }
            List<RasterizedFeature> ready = drainInOrder(pending);
            for (RasterizedFeature rasterized : ready) {
                merger.mergeFeature(
                        rasterized.featureId(), rasterized.weight(),
                        rasterized.priority(), rasterized.cells());
                nextMerged++;
            }
        }

        return new CheckpointStore.TileData(
                merger.values(), merger.owners(),
                merger.unionCellCount(), merger.incidenceCellCount());
    }

    private RasterizedFeature rasterizeOne(
            SpooledFeature spooled,
            CheckpointStore.JobMetadata metadata,
            Tile tile,
            RasterConfig config
    ) {
        BitSet cells = ScanlineRasterizer.rasterizeTile(
                spooled.feature().polygon(),
                config.fillRule(),
                metadata.gridWidth(), metadata.gridHeight(),
                config.cellSize(), config.xmin(), config.ymin(),
                tile.col0(), tile.row0(), tile.width(), tile.height());
        return new RasterizedFeature(spooled.sequence(), spooled.feature().id(),
                spooled.feature().weight(), spooled.feature().priority(), cells);
    }

    private List<RasterizedFeature> drainInOrder(List<Future<RasterizedFeature>> pending) {
        List<RasterizedFeature> ready = new ArrayList<>();
        int i = 0;
        while (i < pending.size()) {
            try {
                ready.add(pending.get(i).get());
                i++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RasterizationException("Rasterization interrupted", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RasterizationException("Rasterization failed", cause);
            }
        }
        pending.clear();
        ready.sort(java.util.Comparator
                .comparingLong(RasterizedFeature::featureId)
                .thenComparingLong(RasterizedFeature::sequence));
        return ready;
    }

    private boolean overlapsTile(
            SpooledFeature feature,
            Tile tile,
            RasterConfig config
    ) {
        double cell = config.cellSize();
        double left = config.xmin() + tile.col0() * cell;
        double right = left + tile.width() * cell;
        // Rows are numbered bottom-up; scanline space has the same
        // orientation, so the tile's bottom row maps directly.
        double bottom = config.ymin() + tile.row0() * cell;
        double top = bottom + tile.height() * cell;
        return feature.maxX() > left
                && feature.minX() < right
                && feature.maxY() > bottom
                && feature.minY() < top;
    }

    private AreaReport areaReport(
            double rasterizedArea, double theoreticalArea, double relativeTolerance
    ) {
        double absolute = Math.abs(rasterizedArea - theoreticalArea);
        double relative = theoreticalArea > 0.0 ? absolute / theoreticalArea : 0.0;
        return new AreaReport(
                rasterizedArea, theoreticalArea, absolute, relative,
                relativeTolerance, relative <= relativeTolerance);
    }

    private RasterizationResult assembleResult(
            CheckpointStore store,
            CheckpointStore.CommitSummary summary,
            boolean resumed
    ) {
        CheckpointStore.JobMetadata metadata = store.metadata();
        RasterConfig config = metadata.config();
        double cell = config.cellSize();

        CoverageMap coverage = null;
        if (config.assembleCoverage()) {
            coverage = new CoverageMap(
                    metadata.gridWidth(), metadata.gridHeight(),
                    cell, config.xmin(), config.ymin());
            TilePlanner planner = new TilePlanner(
                    metadata.gridWidth(), metadata.gridHeight(),
                    config.tileWidthPixels(), config.tileHeightPixels());
            for (Tile tile : planner.tiles()) {
                CheckpointStore.TileData data = store.readTile(tile.index());
                for (int localRow = 0; localRow < tile.height(); localRow++) {
                    for (int localCol = 0; localCol < tile.width(); localCol++) {
                        int local = localRow * tile.width() + localCol;
                        if (data.owners()[local] >= 0L) {
                            coverage.set(
                                    tile.col0() + localCol, tile.row0() + localRow,
                                    data.values()[local], data.owners()[local]);
                        }
                    }
                }
            }
        }

        return new RasterizationResult(
                coverage,
                metadata.gridWidth(), metadata.gridHeight(),
                cell, config.xmin(), config.ymin(),
                config.xmin() + metadata.gridWidth() * cell,
                config.ymin() + metadata.gridHeight() * cell,
                config.fillRule(), config.mergeSemantics(), config.outOfRangePolicy(),
                metadata.totalFeatures(), metadata.acceptedFeatures(),
                metadata.rejectedFeatureIds(),
                summary.unionCells() * cell * cell,
                summary.areaReport(),
                metadata.tilesX(), metadata.tilesY(),
                config.tileWidthPixels(), config.tileHeightPixels(),
                resumed, true, config.checkpointDirectoryPath());
    }

    private static int gridSize(double min, double max, double cell) {
        return (int) Math.ceil((max - min) / cell);
    }

    private static double[] boundsOf(Polygon polygon) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        List<Ring> rings = new ArrayList<>(polygon.holes().size() + 1);
        rings.add(polygon.exterior());
        rings.addAll(polygon.holes());
        for (Ring ring : rings) {
            for (Point point : ring.vertices()) {
                minX = Math.min(minX, point.x());
                minY = Math.min(minY, point.y());
                maxX = Math.max(maxX, point.x());
                maxY = Math.max(maxY, point.y());
            }
        }
        return new double[] {minX, minY, maxX, maxY};
    }

    private record RasterizedFeature(
            long sequence,
            long featureId,
            double weight,
            int priority,
            BitSet cells
    ) {
    }
}
