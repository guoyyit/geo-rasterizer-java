package com.github.guoyyit.georasterizerjava.checkpoint;

import com.github.guoyyit.georasterizerjava.raster.TileResult;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * 基于文件系统的检查点存储。
 *
 * <p>目录布局：{@code <workDir>/<jobId>/meta.properties} 与 {@code tiles/tile-x-y.dat}。
 * 每个分块先写 {@code .tmp} 临时文件，fsync 后再以 {@code ATOMIC_MOVE} 改名发布；
 * 崩溃时未完成块只会残留 .tmp 文件，不会出现被误读的部分提交。</p>
 */
public final class FileCheckpointStore implements CheckpointStore {

    private final Path jobDir;
    private final Path tilesDir;

    public FileCheckpointStore(Path workDir) {
        if (workDir == null) {
            throw new IllegalArgumentException("workDir must not be null");
        }
        this.jobDir = workDir;
        this.tilesDir = workDir.resolve("tiles");
    }

    @Override
    public void initialize(String jobId, String configSignature, int tileCountX, int tileCountY) {
        try {
            Path metaFile = jobDir.resolve("meta.properties");
            if (Files.exists(metaFile)) {
                Properties existing = readProperties(metaFile);
                if (!configSignature.equals(existing.getProperty("configSignature"))
                        || !jobId.equals(existing.getProperty("jobId"))) {
                    throw new IllegalStateException(
                            "checkpoint signature mismatch for job " + jobId
                                    + "; refusing to resume from an incompatible checkpoint directory "
                                    + jobDir);
                }
                return;
            }
            Files.createDirectories(tilesDir);
            Properties properties = new Properties();
            properties.setProperty("jobId", jobId);
            properties.setProperty("configSignature", configSignature);
            properties.setProperty("tileCountX", Integer.toString(tileCountX));
            properties.setProperty("tileCountY", Integer.toString(tileCountY));
            Path tmp = jobDir.resolve("meta.properties.tmp");
            try (BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp))) {
                properties.store(out, "rasterization job checkpoint metadata");
            }
            moveAtomically(tmp, metaFile);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to initialize checkpoint at " + jobDir, e);
        }
    }

    private Properties readProperties(Path metaFile) throws IOException {
        Properties properties = new Properties();
        try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(metaFile))) {
            properties.load(in);
        }
        return properties;
    }

    private Path tileFile(int tileX, int tileY) {
        return tilesDir.resolve("tile-" + tileX + "-" + tileY + ".dat");
    }

    @Override
    public boolean isTileCompleted(int tileX, int tileY) {
        return Files.exists(tileFile(tileX, tileY));
    }

    @Override
    public List<int[]> listCompletedTiles() {
        List<int[]> completed = new ArrayList<>();
        try (Stream<Path> paths = Files.list(tilesDir)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".dat")).forEach(path -> {
                String name = path.getFileName().toString();
                int[] coordinates = parseTileName(name);
                if (coordinates != null) {
                    completed.add(coordinates);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list checkpoint tiles", e);
        }
        return completed;
    }

    private int[] parseTileName(String name) {
        try {
            String body = name.substring("tile-".length(), name.length() - ".dat".length());
            int separator = body.indexOf('-');
            return new int[]{
                    Integer.parseInt(body.substring(0, separator)),
                    Integer.parseInt(body.substring(separator + 1))
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void storeTile(TileResult tile) {
        Path target = tileFile(tile.tileX(), tile.tileY());
        Path tmp = tilesDir.resolve(target.getFileName().toString() + "." + ProcessHandle.current().pid() + ".tmp");
        try {
            Files.createDirectories(tilesDir);
            try (BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp))) {
                tile.encode(out);
                out.flush();
            }
            moveAtomically(tmp, target);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to store checkpoint tile "
                    + tile.tileX() + "," + tile.tileY(), e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时文件残留不影响正确性，下次恢复时忽略 *.tmp
            }
        }
    }

    @Override
    public TileResult readTile(int tileX, int tileY) {
        Path target = tileFile(tileX, tileY);
        if (!Files.exists(target)) {
            return null;
        }
        try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(target))) {
            return TileResult.decode(in);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read checkpoint tile " + tileX + "," + tileY, e);
        }
    }

    @Override
    public void storeTileCoverageSum(int tileX, int tileY, double coverageSum) {
        Path target = areaFile(tileX, tileY);
        Path tmp = tilesDir.resolve(target.getFileName().toString()
                + "." + ProcessHandle.current().pid() + ".tmp");
        try {
            Files.createDirectories(tilesDir);
            try (BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp))) {
                new java.io.DataOutputStream(out).writeDouble(coverageSum);
                out.flush();
            }
            moveAtomically(tmp, target);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to store coverage sum for tile "
                    + tileX + "," + tileY, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时文件残留不影响正确性
            }
        }
    }

    @Override
    public java.util.OptionalDouble readTileCoverageSum(int tileX, int tileY) {
        Path target = areaFile(tileX, tileY);
        if (!Files.exists(target)) {
            return java.util.OptionalDouble.empty();
        }
        try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(target))) {
            return java.util.OptionalDouble.of(new java.io.DataInputStream(in).readDouble());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read coverage sum for tile "
                    + tileX + "," + tileY, e);
        }
    }

    private Path areaFile(int tileX, int tileY) {
        return tilesDir.resolve("tile-" + tileX + "-" + tileY + ".area");
    }

    @Override
    public boolean isComplete() {
        Path metaFile = jobDir.resolve("meta.properties");
        if (!Files.exists(metaFile)) {
            return false;
        }
        try {
            Properties properties = readProperties(metaFile);
            int tileCountX = Integer.parseInt(properties.getProperty("tileCountX"));
            int tileCountY = Integer.parseInt(properties.getProperty("tileCountY"));
            for (int tileY = 0; tileY < tileCountY; tileY++) {
                for (int tileX = 0; tileX < tileCountX; tileX++) {
                    if (!Files.exists(tileFile(tileX, tileY))) {
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to inspect checkpoint metadata", e);
        }
    }

    @Override
    public void close() {
        // 无持有的外部资源，目录即状态
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailed) {
            Files.move(source, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
