package com.github.guoyyit.georasterizerjava.service;

import com.github.guoyyit.georasterizerjava.checkpoint.CheckpointStore;
import com.github.guoyyit.georasterizerjava.checkpoint.FileCheckpointStore;
import com.github.guoyyit.georasterizerjava.config.RasterConfig;
import com.github.guoyyit.georasterizerjava.engine.RasterizationEngine;
import com.github.guoyyit.georasterizerjava.model.Envelope;
import com.github.guoyyit.georasterizerjava.model.Feature;
import com.github.guoyyit.georasterizerjava.model.OutOfRangePolicy;
import com.github.guoyyit.georasterizerjava.raster.GridDefinition;
import com.github.guoyyit.georasterizerjava.raster.TileResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 矢量多边形栅格化服务入口：分块流式处理、有界并发合并、检查点恢复、面积守恒校验。
 *
 * <p>内存模型：作业按分块推进，已完成分块立即原子落盘到检查点，任意时刻在内存中的分块数
 * 不超过配置的并发度，因此总内存占用与全图尺寸无关，只与分块大小、并发度及要素数相关。
 * 失败后用同一工作目录再次调用即可从最近完成的分块继续。</p>
 *
 * <p>提交协议（两阶段，杜绝部分提交的覆盖图）：第一阶段所有分块完成计算、落盘并累加栅格化
 * 面积；第二阶段执行面积守恒校验，只有校验通过才把分块读出/回调发布为覆盖图。校验失败时抛出
 * {@link AreaConservationException}，调用方拿不到任何覆盖图数据；检查点保留在工作目录供排查。</p>
 */
public final class RasterizationService {

    private static final double AREA_FLOOR = 1.0e-12;

    /**
     * 一次性处理并在内存中组装完整结果（适用于中小作业；大批量请用流式接口）。
     */
    public RasterizationResult rasterize(List<Feature> features, RasterConfig config, Path workDir) {
        return rasterize(features, config, workDir, null);
    }

    /**
     * 一次性处理，支持进度/失败注入监听器。
     */
    public RasterizationResult rasterize(List<Feature> features, RasterConfig config, Path workDir,
                                         RasterizationProgressListener listener) {
        PreparedJob job = prepare(features, config, workDir);
        try {
            executeTiles(job, listener);
            return assemble(job);
        } catch (RuntimeException | Error failure) {
            job.store.close();
            throw failure;
        }
    }

    /**
     * 流式处理：面积校验通过后，按 (tileY, tileX) 顺序逐块回调；回调返回后分块即可被回收。
     * 适用于超大覆盖图，结果对象不常驻内存。
     */
    public AreaReport rasterizeStreaming(List<Feature> features, RasterConfig config, Path workDir,
                                         Consumer<TileResult> tileConsumer) {
        if (tileConsumer == null) {
            throw new RasterizationException("streaming tile consumer must not be null");
        }
        PreparedJob job = prepare(features, config, workDir);
        try {
            executeTiles(job, null);
            AreaReport report = verify(job);
            for (int tileY = 0; tileY < job.tileCountY; tileY++) {
                for (int tileX = 0; tileX < job.tileCountX; tileX++) {
                    tileConsumer.accept(job.store.readTile(tileX, tileY));
                }
            }
            return report;
        } finally {
            job.store.close();
        }
    }

    private PreparedJob prepare(List<Feature> features, RasterConfig config, Path workDir) {
        if (features == null) {
            throw new RasterizationException("features must not be null");
        }
        if (config == null) {
            throw new RasterizationException("raster config must not be null");
        }

        List<IndexedFeature> indexed = new ArrayList<>(features.size());
        for (int i = 0; i < features.size(); i++) {
            indexed.add(new IndexedFeature(i, features.get(i)));
        }
        indexed.sort(RasterizationService::compareFeatures);
        List<Feature> ordered = indexed.stream().map(item -> item.feature).toList();

        RasterizationEngine engine = new RasterizationEngine(config);
        Envelope featureEnvelope = engine.combinedEnvelope(ordered);
        Envelope validEnvelope = resolveEnvelope(config, featureEnvelope);
        GridDefinition grid = new GridDefinition(validEnvelope, config.cellSize());

        double theoreticalArea = computeTheoreticalArea(ordered, config, engine, validEnvelope);

        int tileCountX = (grid.cols() + config.tileCols() - 1) / config.tileCols();
        int tileCountY = (grid.rows() + config.tileRows() - 1) / config.tileRows();

        String configSignature = configSignature(config, validEnvelope);
        String jobId = "job-" + shortHash(configSignature + "|"
                + ordered.stream().map(Feature::id).reduce("", (acc, id) -> acc + "," + id));

        boolean temporaryWorkDir = workDir == null;
        Path effectiveWorkDir = temporaryWorkDir ? createTempDir() : workDir.resolve(jobId);

        CheckpointStore store = new FileCheckpointStore(effectiveWorkDir);
        store.initialize(jobId, configSignature, tileCountX, tileCountY);

        PreparedJob job = new PreparedJob();
        job.config = config;
        job.grid = grid;
        job.validEnvelope = validEnvelope;
        job.features = ordered;
        job.engine = engine;
        job.tileCountX = tileCountX;
        job.tileCountY = tileCountY;
        job.theoreticalArea = theoreticalArea;
        job.jobId = jobId;
        job.temporaryWorkDir = temporaryWorkDir;
        job.workDir = effectiveWorkDir;
        job.store = store;
        return job;
    }

    private static int compareFeatures(IndexedFeature first, IndexedFeature second) {
        int byId = first.feature.id().compareTo(second.feature.id());
        if (byId != 0) {
            return byId;
        }
        return Integer.compare(first.inputIndex, second.inputIndex);
    }

    private void executeTiles(PreparedJob job, RasterizationProgressListener listener) {
        int totalTiles = job.tileCountX * job.tileCountY;
        double[] coverageByTile = new double[totalTiles];
        boolean[] computedInRun = new boolean[totalTiles];
        AtomicLong completed = new AtomicLong(job.store.listCompletedTiles().size());

        int workers = Math.max(1, Math.min(job.config.parallelism(), totalTiles));
        ExecutorService executor = Executors.newFixedThreadPool(workers, new NamedThreadFactory());
        Semaphore permits = new Semaphore(workers);
        java.util.concurrent.atomic.AtomicReference<RuntimeException> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        try {
            for (int tileY = 0; tileY < job.tileCountY; tileY++) {
                List<Future<?>> rowFutures = new ArrayList<>(job.tileCountX);
                for (int tileX = 0; tileX < job.tileCountX; tileX++) {
                    if (job.store.isTileCompleted(tileX, tileY)) {
                        rowFutures.add(null);
                        continue;
                    }
                    if (failure.get() != null) {
                        break;
                    }
                    acquirePermit(permits);
                    int finalTileX = tileX;
                    int finalTileY = tileY;
                    rowFutures.add(executor.submit(() -> {
                        try {
                            if (failure.get() != null) {
                                return null;
                            }
                            RasterizationEngine.TileComputation computation =
                                    rasterizeOneTile(job, finalTileX, finalTileY);
                            TileResult tile = computation.mergedTile();
                            // 合并块与逐要素覆盖率和一起原子提交，保证恢复口径一致
                            job.store.storeTile(tile);
                            job.store.storeTileCoverageSum(finalTileX, finalTileY,
                                    computation.perFeatureCoverageSum());
                            int flatIndex = finalTileY * job.tileCountX + finalTileX;
                            coverageByTile[flatIndex] = computation.perFeatureCoverageSum();
                            computedInRun[flatIndex] = true;
                            int done = (int) completed.incrementAndGet();
                            if (listener != null) {
                                try {
                                    listener.onTileCompleted(done, totalTiles);
                                } catch (RuntimeException listenerFailure) {
                                    failure.compareAndSet(null, listenerFailure);
                                }
                            }
                            return null;
                        } finally {
                            permits.release();
                        }
                    }));
                }
                for (Future<?> future : rowFutures) {
                    if (future != null) {
                        awaitFuture(future);
                    }
                }
                if (failure.get() != null) {
                    throw failure.get();
                }
            }
        } finally {
            executor.shutdownNow();
        }

        double totalCoverage = 0.0;
        for (int tileY = 0; tileY < job.tileCountY; tileY++) {
            for (int tileX = 0; tileX < job.tileCountX; tileX++) {
                int flatIndex = tileY * job.tileCountX + tileX;
                totalCoverage += computedInRun[flatIndex]
                        ? coverageByTile[flatIndex]
                        : job.store.readTileCoverageSum(tileX, tileY).orElse(0.0);
            }
        }
        job.rasterizedArea = totalCoverage * job.grid.cellSize() * job.grid.cellSize();
    }

    private void acquirePermit(Semaphore permits) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RasterizationException("rasterization interrupted while scheduling tiles", e);
        }
    }

    private void awaitFuture(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RasterizationException("rasterization interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RasterizationException("rasterization failed", cause);
        }
    }

    private RasterizationEngine.TileComputation rasterizeOneTile(PreparedJob job, int tileX, int tileY) {
        int startCol = tileX * job.config.tileCols();
        int startRow = tileY * job.config.tileRows();
        int localCols = Math.min(job.config.tileCols(), job.grid.cols() - startCol);
        int localRows = Math.min(job.config.tileRows(), job.grid.rows() - startRow);
        return job.engine.rasterizeTile(job.features, job.grid,
                tileX, tileY, startCol, startRow, localCols, localRows);
    }

    private AreaReport verify(PreparedJob job) {
        AreaReport report = buildAreaReport(job.theoreticalArea, job.rasterizedArea,
                job.config.areaTolerance());
        job.areaReport = report;
        if (!report.passed()) {
            throw new AreaConservationException(report);
        }
        return report;
    }

    private RasterizationResult assemble(PreparedJob job) {
        AreaReport report = verify(job);
        List<TileResult> tiles = new ArrayList<>(job.tileCountX * job.tileCountY);
        for (int tileY = 0; tileY < job.tileCountY; tileY++) {
            for (int tileX = 0; tileX < job.tileCountX; tileX++) {
                tiles.add(job.store.readTile(tileX, tileY));
            }
        }

        Map<String, Object> metadata = buildMetadata(job);
        job.store.close();
        RasterizationResult result = new RasterizationResult(job.grid, report, metadata, tiles);
        if (job.temporaryWorkDir) {
            deleteRecursively(job.workDir);
        }
        return result;
    }

    private Envelope resolveEnvelope(RasterConfig config, Envelope featureEnvelope) {
        Envelope configured = config.outputEnvelope();
        if (configured != null) {
            return configured;
        }
        if (featureEnvelope == null) {
            throw new RasterizationException(
                    "no output envelope configured and no non-empty geometry to infer one");
        }
        return featureEnvelope;
    }

    private double computeTheoreticalArea(List<Feature> features, RasterConfig config,
                                          RasterizationEngine engine, Envelope validEnvelope) {
        double total = 0.0;
        for (Feature feature : features) {
            Envelope geometryEnvelope = feature.polygon().envelope();
            if (geometryEnvelope == null || !geometryEnvelope.intersects(validEnvelope)) {
                if (geometryEnvelope != null && config.outOfRangePolicy() == OutOfRangePolicy.REJECT) {
                    throw new GeometryRejectedException(feature);
                }
                continue;
            }
            if (config.outOfRangePolicy() == OutOfRangePolicy.REJECT
                    && !validEnvelope.contains(geometryEnvelope)) {
                throw new GeometryRejectedException(feature);
            }
            total += engine.theoreticalArea(feature.polygon(), validEnvelope);
        }
        return total;
    }

    private AreaReport buildAreaReport(double theoreticalArea, double rasterizedArea, double tolerance) {
        double absolute = Math.abs(rasterizedArea - theoreticalArea);
        double relative = absolute / Math.max(Math.abs(theoreticalArea), AREA_FLOOR);
        return new AreaReport(theoreticalArea, rasterizedArea, absolute, relative, tolerance,
                relative <= tolerance);
    }

    private Map<String, Object> buildMetadata(PreparedJob job) {
        RasterConfig config = job.config;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("jobId", job.jobId);
        metadata.put("cellSize", config.cellSize());
        metadata.put("gridCols", job.grid.cols());
        metadata.put("gridRows", job.grid.rows());
        metadata.put("outputEnvelope", envelopeMap(job.validEnvelope));
        metadata.put("fillRule", config.fillRule().name());
        metadata.put("mergeStrategy", config.mergeStrategy().name());
        metadata.put("outOfRangePolicy", config.outOfRangePolicy().name());
        metadata.put("areaTolerance", config.areaTolerance());
        metadata.put("tileSize", List.of(config.tileCols(), config.tileRows()));
        metadata.put("tileCountX", job.tileCountX);
        metadata.put("tileCountY", job.tileCountY);
        metadata.put("parallelism", config.parallelism());
        metadata.put("featureCount", job.features.size());
        metadata.put("featureIds", job.features.stream().map(Feature::id).toList());
        metadata.put("resumable", !job.temporaryWorkDir);
        metadata.put("processingStrategy",
                "streaming tiles, bounded in-flight count = parallelism; "
                        + "checkpointed per tile with atomic publish; two-phase commit after "
                        + "area conservation verification");
        metadata.put("areaConservation", Map.of(
                "theoreticalArea", job.areaReport.theoreticalArea(),
                "rasterizedArea", job.areaReport.rasterizedArea(),
                "absoluteDeviation", job.areaReport.absoluteDeviation(),
                "relativeDeviation", job.areaReport.relativeDeviation(),
                "tolerance", job.areaReport.tolerance(),
                "passed", job.areaReport.passed()));
        return metadata;
    }

    private Map<String, Object> envelopeMap(Envelope envelope) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("minX", envelope.minX());
        map.put("minY", envelope.minY());
        map.put("maxX", envelope.maxX());
        map.put("maxY", envelope.maxY());
        return map;
    }

    private String configSignature(RasterConfig config, Envelope envelope) {
        return String.join("|",
                Double.toString(config.cellSize()),
                Double.toString(envelope.minX()),
                Double.toString(envelope.minY()),
                Double.toString(envelope.maxX()),
                Double.toString(envelope.maxY()),
                config.fillRule().name(),
                config.mergeStrategy().name(),
                config.outOfRangePolicy().name(),
                Double.toString(config.areaTolerance()),
                Integer.toString(config.tileCols()),
                Integer.toString(config.tileRows()));
    }

    private String shortHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                builder.append(String.format("%02x", hash[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Path createTempDir() {
        try {
            return Files.createTempDirectory("geo-rasterizer-");
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create temporary checkpoint directory", e);
        }
    }

    private void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class IndexedFeature {

        private final int inputIndex;
        private final Feature feature;

        private IndexedFeature(int inputIndex, Feature feature) {
            this.inputIndex = inputIndex;
            this.feature = feature;
        }
    }

    private static final class PreparedJob {

        private RasterConfig config;
        private GridDefinition grid;
        private Envelope validEnvelope;
        private List<Feature> features;
        private RasterizationEngine engine;
        private int tileCountX;
        private int tileCountY;
        private double theoreticalArea;
        private double rasterizedArea;
        private AreaReport areaReport;
        private String jobId;
        private boolean temporaryWorkDir;
        private Path workDir;
        private CheckpointStore store;
    }

    private static final class NamedThreadFactory implements java.util.concurrent.ThreadFactory {

        private final AtomicLong counter = new AtomicLong();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "geo-rasterizer-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
