package com.github.guoyyit.georasterizerjava.raster;

import com.github.guoyyit.georasterizerjava.model.AreaReport;
import com.github.guoyyit.georasterizerjava.model.RasterConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.DataInputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.DataOutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * On-disk checkpoint store for one rasterization job.
 *
 * <p>Layout under the job directory:
 * <ul>
 *   <li>{@code config.dat} – serialized job metadata</li>
 *   <li>{@code features.dat} – serialized accepted features (spool)</li>
 *   <li>{@code tile_NNNNNN.dat} – one atomically written tile</li>
 *   <li>{@code progress.dat} – number of committed tiles (atomic)</li>
 *   <li>{@code commit.dat} – final summary; its existence is the atomic
 *       commit marker. Without it the job is resumable, never partially
 *       visible as committed output.</li>
 * </ul>
 */
public final class CheckpointStore implements AutoCloseable {

    private static final String CONFIG_FILE = "config.dat";
    private static final String FEATURES_FILE = "features.dat";
    private static final String PROGRESS_FILE = "progress.dat";
    private static final String COMMIT_FILE = "commit.dat";
    private static final String TILE_PREFIX = "tile_";
    private static final String TILE_SUFFIX = ".dat";
    private static final String TMP_SUFFIX = ".tmp";

    private final Path directory;
    private JobMetadata metadata;
    private int acceptedFeatureCount;

    private CheckpointStore(Path directory) {
        this.directory = directory;
    }

    /**
     * Metadata describing a spooled job.
     */
    public record JobMetadata(
            RasterConfig config,
            int gridWidth,
            int gridHeight,
            int tilesX,
            int tilesY,
            int totalFeatures,
            int acceptedFeatures,
            List<Long> rejectedFeatureIds,
            double totalTheoreticalArea
    ) implements java.io.Serializable {
    }

    /**
     * One committed tile's payload.
     */
    public record TileData(
            double[] values,
            long[] owners,
            long unionCells,
            long incidenceCells
    ) implements java.io.Serializable {
    }

    /**
     * Final committed summary.
     */
    public record CommitSummary(
            long unionCells,
            long incidenceCells,
            AreaReport areaReport,
            boolean resumed
    ) implements java.io.Serializable {
    }

    /**
     * Opens a fresh store for single-pass streaming: features are written
     * first, then {@link #finalizeInitialization(JobMetadata)} stores the
     * metadata accumulated during that one pass. Memory stays bounded.
     */
    public static CheckpointStore openForStreaming(Path directory) {
        try {
            Files.createDirectories(directory);
            if (Files.isRegularFile(directory.resolve(COMMIT_FILE))
                    || Files.isRegularFile(directory.resolve(CONFIG_FILE))) {
                throw new RasterizationException(
                        "Checkpoint directory is not empty for a new job: " + directory);
            }
            return new CheckpointStore(directory);
        } catch (IOException e) {
            throw new RasterizationException(
                    "Failed to open streaming checkpoint at " + directory, e);
        }
    }

    /**
     * Creates a streaming feature writer. Exactly one writer may be used,
     * and it must be closed before {@link #finalizeInitialization}.
     */
    public SpooledFeatureSink featureWriter() {
        try {
            Path temp = directory.resolve(FEATURES_FILE + TMP_SUFFIX);
            ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(temp));
            return new SpooledFeatureSink(this, temp, out);
        } catch (IOException e) {
            throw new RasterizationException("Failed to open feature spool", e);
        }
    }

    private void publishFeatureSpool(Path temp) {
        try {
            try {
                Files.move(temp, featuresPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, featuresPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RasterizationException("Failed to publish feature spool", e);
        }
    }

    public void finalizeInitialization(JobMetadata finalizedMetadata) {
        try {
            this.metadata = finalizedMetadata;
            this.acceptedFeatureCount = finalizedMetadata.acceptedFeatures();
            writeAtomic(configPath(), out -> {
                try (ObjectOutputStream objectOut = new ObjectOutputStream(out)) {
                    objectOut.writeObject(finalizedMetadata);
                }
            });
            writeProgress(0);
        } catch (IOException e) {
            throw new RasterizationException("Failed to finalize checkpoint metadata", e);
        }
    }

    /**
     * Streaming sink for accepted features.
     */
    public static final class SpooledFeatureSink implements AutoCloseable {
        private final CheckpointStore store;
        private final Path tempFile;
        private ObjectOutputStream out;
        private boolean closed;

        private SpooledFeatureSink(CheckpointStore store, Path tempFile, ObjectOutputStream out) {
            this.store = store;
            this.tempFile = tempFile;
            this.out = out;
        }

        public void write(SpooledFeature feature) {
            if (closed) {
                throw new RasterizationException("Feature spool writer is already closed");
            }
            try {
                out.writeObject(feature);
            } catch (IOException e) {
                throw new RasterizationException("Failed to spool feature", e);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                out.writeObject(null);
                out.close();
                store.publishFeatureSpool(tempFile);
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Best-effort cleanup; original error is reported.
                }
                throw new RasterizationException("Failed to close feature spool", e);
            }
        }
    }

    public static CheckpointStore open(Path directory) {
        CheckpointStore store = new CheckpointStore(directory);
        if (!Files.isRegularFile(store.configPath())) {
            throw new RasterizationException("No checkpoint found at " + directory);
        }
        try (InputStream in = Files.newInputStream(store.configPath());
             ObjectInputStream objectIn = new ObjectInputStream(in)) {
            store.metadata = (JobMetadata) objectIn.readObject();
        } catch (IOException e) {
            throw new RasterizationException(
                    "Failed to read checkpoint config at " + directory, e);
        } catch (ClassNotFoundException e) {
            throw new RasterizationException("Corrupt checkpoint config at " + directory, e);
        }
        store.acceptedFeatureCount = store.readFeatureCount();
        return store;
    }

    public JobMetadata metadata() {
        return metadata;
    }

    public Iterator<SpooledFeature> featureIterator() {
        final Path featuresFile = featuresPath();
        return new Iterator<>() {
            private ObjectInputStream in;
            private int remaining;
            private boolean initialized;
            private boolean exhausted;
            private SpooledFeature pending;

            private void init() {
                if (!initialized) {
                    initialized = true;
                    try {
                        in = new ObjectInputStream(Files.newInputStream(featuresFile));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }

            @Override
            public boolean hasNext() {
                init();
                if (remaining == 0 && !exhausted) {
                    try {
                        SpooledFeature next = (SpooledFeature) in.readObject();
                        if (next == null) {
                            exhausted = true;
                            in.close();
                        } else {
                            pending = next;
                            remaining = 1;
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    } catch (ClassNotFoundException e) {
                        throw new RasterizationException("Corrupt feature spool", e);
                    }
                }
                return remaining > 0;
            }

            @Override
            public SpooledFeature next() {
                init();
                if (remaining <= 0) {
                    throw new NoSuchElementException();
                }
                remaining--;
                return pending;
            }
        };
    }

    public int acceptedFeatureCount() {
        return acceptedFeatureCount;
    }

    public int completedTileCount() {
        Path progress = progressPath();
        if (!Files.isRegularFile(progress)) {
            return 0;
        }
        try {
            try (DataInputStream dataIn = new DataInputStream(Files.newInputStream(progress))) {
                return dataIn.readInt();
            }
        } catch (IOException | NumberFormatException e) {
            throw new RasterizationException("Corrupt progress marker", e);
        }
    }

    public boolean isCommitted() {
        return Files.isRegularFile(commitPath());
    }

    public void commitTile(int tileIndex, TileData data) {
        try {
            int expected = completedTileCount();
            if (tileIndex != expected) {
                throw new RasterizationException(
                        "Out-of-order tile commit: expected " + expected + " but got " + tileIndex);
            }
            writeAtomic(tilePath(tileIndex), out -> {
                try (ObjectOutputStream objectOut = new ObjectOutputStream(out)) {
                    objectOut.writeObject(data);
                }
            });
            writeProgress(tileIndex + 1);
        } catch (IOException e) {
            throw new RasterizationException("Failed to commit tile " + tileIndex, e);
        }
    }

    public TileData readTile(int tileIndex) {
        try (InputStream in = Files.newInputStream(tilePath(tileIndex));
             ObjectInputStream objectIn = new ObjectInputStream(in)) {
            return (TileData) objectIn.readObject();
        } catch (IOException e) {
            throw new RasterizationException("Failed to read tile " + tileIndex, e);
        } catch (ClassNotFoundException e) {
            throw new RasterizationException("Corrupt tile " + tileIndex, e);
        }
    }

    public void markCommitted(CommitSummary summary) {
        try {
            writeAtomic(commitPath(), out -> {
                try (ObjectOutputStream objectOut = new ObjectOutputStream(out)) {
                    objectOut.writeObject(summary);
                }
            });
        } catch (IOException e) {
            throw new RasterizationException("Failed to write commit marker", e);
        }
    }

    public CommitSummary commitSummary() {
        try (InputStream in = Files.newInputStream(commitPath());
             ObjectInputStream objectIn = new ObjectInputStream(in)) {
            return (CommitSummary) objectIn.readObject();
        } catch (IOException e) {
            throw new RasterizationException("Failed to read commit marker", e);
        } catch (ClassNotFoundException e) {
            throw new RasterizationException("Corrupt commit marker", e);
        }
    }

    @Override
    public void close() {
        // Nothing to release; each reader opens a fresh stream.
    }

    private Path configPath() {
        return directory.resolve(CONFIG_FILE);
    }

    private Path featuresPath() {
        return directory.resolve(FEATURES_FILE);
    }

    private Path progressPath() {
        return directory.resolve(PROGRESS_FILE);
    }

    private Path commitPath() {
        return directory.resolve(COMMIT_FILE);
    }

    private Path tilePath(int tileIndex) {
        return directory.resolve(
                String.format("%s%06d%s", TILE_PREFIX, tileIndex, TILE_SUFFIX));
    }

    private void writeProgress(int count) throws IOException {
        writeAtomic(progressPath(), out -> {
            try (DataOutputStream dataOut = new DataOutputStream(out)) {
                dataOut.writeInt(count);
            }
        });
    }

    private int readFeatureCount() {
        return metadata.acceptedFeatures();
    }

    @FunctionalInterface
    private interface AtomicWriter {
        void write(OutputStream out) throws IOException;
    }

    private void writeAtomic(Path target, AtomicWriter writer) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + TMP_SUFFIX);
        try (OutputStream out = Files.newOutputStream(temp)) {
            writer.write(out);
        }
        try {
            Files.move(temp, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
