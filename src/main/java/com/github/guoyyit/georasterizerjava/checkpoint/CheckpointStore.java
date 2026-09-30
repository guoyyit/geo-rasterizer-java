package com.github.guoyyit.georasterizerjava.checkpoint;

import com.github.guoyyit.georasterizerjava.raster.TileResult;

/**
 * 检查点存储：分块级断点续算与原子提交。
 *
 * <p>一个作业由 {@code jobId + configSignature} 唯一标识；恢复时若签名不匹配必须拒绝，
 * 防止把不同配置的旧检查点错误拼接。每个分块只有在完整合并写入并原子落盘后才标记完成，
 * 因此外部永远观察不到部分提交的覆盖图。</p>
 */
public interface CheckpointStore extends AutoCloseable {

    void initialize(String jobId, String configSignature, int tileCountX, int tileCountY);

    boolean isTileCompleted(int tileX, int tileY);

    java.util.List<int[]> listCompletedTiles();

    void storeTile(TileResult tile);

    /**
     * 保存与某分块同时提交的逐要素覆盖率之和（面积守恒校验口径，独立于合并语义）。
     */
    void storeTileCoverageSum(int tileX, int tileY, double coverageSum);

    /**
     * 读取分块逐要素覆盖率之和；不存在时返回 null（旧检查点或未提交）。
     */
    java.util.OptionalDouble readTileCoverageSum(int tileX, int tileY);

    TileResult readTile(int tileX, int tileY);

    /**
     * 是否所有分块均已提交（作业完成）。
     */
    boolean isComplete();

    @Override
    void close();
}
