package com.github.guoyyit.georasterizerjava.service;

/**
 * 分块完成进度回调。每个分块原子提交到检查点后触发一次；
 * 监听器抛出异常会终止整个作业（用于模拟中途失败并验证断点恢复）。
 */
@FunctionalInterface
public interface RasterizationProgressListener {

    /**
     * @param completedTiles 已完成分块数（含本次恢复前已存在的检查点）
     * @param totalTiles     作业分块总数
     */
    void onTileCompleted(int completedTiles, int totalTiles);
}
