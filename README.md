# geo-rasterizer-java

矢量多边形栅格化服务：把平面多边形（可含孔洞、自相交、重复顶点）按给定分辨率
转换为确定性、可复现的栅格覆盖图，用于面积统计与空间分析。

## 快速开始

```java
RasterConfig config = new ConfigBuilder()
        .cellSize(0.5)                          // 像元边长（坐标单位）
        .extent(0, 0, 100, 100)                 // 输出范围 xmin,ymin,xmax,ymax
        .fillRule(FillRule.NON_ZERO)            // 填充规则
        .mergeSemantics(MergeSemantics.COUNT)   // 多要素合并语义
        .outOfRangePolicy(OutOfRangePolicy.CLIP)
        .areaRelativeTolerance(0.01)            // 面积相对误差阈值 1%
        .tileSize(256, 256)                     // 分块大小（像元）
        .workerThreads(4)
        .assembleCoverage(true)                 // 大数据量时可设为 false
        .checkpointDirectory(Path.of("/data/ckpt/job-001"))
        .build();

RasterizationResult result = new RasterizationService()
        .rasterize(config, features, (done, total) -> { });

result.areaReport().passed();   // 面积守恒是否达标
result.coverage().valueAt(c, r);
result.coverage().ownerAt(c, r);
```

中断后继续：

```java
RasterizationResult result = new RasterizationService().resume(jobDir);
```

## 栅格化模型

- 网格左下角为 `(xmin, ymin)`，行号自下而上；列号随 x 增大。
- 网格列数为 `ceil((xmax - xmin) / cellSize)`，最后一个像元可能略微越过
  `xmax/ymax`，实际覆盖范围由结果中的 `effectiveXMax/effectiveYMax` 给出。
- 像元归属采用**像元中心采样**：当且仅当像元中心落在多边形内部时该像元被覆盖。
  这保证了：
  - 同一几何无论按什么顺序、分多少块处理，逐像元结果完全一致；
  - 相邻块之间无裂缝、无漏像元、无重复像元（每个像元只属于一个瓦片）。

## 填充规则（FillRule）

支持两种规则，扫描线在每个像元中心高度求交点并排序后计算：

- `NON_ZERO`（默认）：从 -∞ 起累计环绕数（winding number），向上穿越 +1、
  向下穿越 -1；环绕数非零的区间为内部。
- `EVEN_ODD`：穿越次数奇偶翻转，奇数区间为内部。

孔洞以独立环表达。环的方向、起点顶点不影响结果；重复顶点与显式闭合顶点
会被规整。自相交多边形在两种规则下都能得到正确归属，同一输入多次执行结果
逐像元一致。

## 合并语义（MergeSemantics）

多个要素覆盖同一像元时：

- `COUNT`：像元值为覆盖该像元的要素数量；`owner` 为覆盖该像元的**最小要素 id**。
- `MAX`：像元值取覆盖要素的最大 `weight`，`owner` 记录获胜要素 id；
  `weight` 相同时以**较小的要素 id 获胜**。
- `PRIORITY`：像元值取最高 `priority`，`owner` 记录获胜要素 id；
  `priority` 相同时以**较小的要素 id 获胜**。

任何语义下，未被要素覆盖的像元值恒为 `0`、`owner` 恒为 `-1`。决胜规则完全
确定，与提交顺序无关：服务始终按要素 id（id 相同时按提交序号）顺序合并，
合并器自身也对 COUNT/MAX/PRIORITY 的平票显式取较小 id。因此同一要素集合无论
以何种顺序提交、配置多少线程、使用多大的分块，每个像元的值与 owner 都逐像元
一致。要素的栅格集合通过有界工作队列（窗口为 worker 数的常数倍）产生，内存
占用与要素总数无关。

## 越界处理（OutOfRangePolicy）

- `CLIP`（默认）：对每个环做 Sutherland–Hodgman 矩形裁剪，落在范围外的
  孔洞环若退化则丢弃。处理策略写入结果元信息
  （`RasterizationResult.outOfRangePolicy`）。
- `REJECT`：与输出范围不完全相容（bbox 超出或相交边界外侧）的要素整体拒绝，
  其 id 进入 `rejectedFeatureIds`，不参与栅格化与面积统计。

## 面积守恒校验

每个被接受的要素在裁剪之后计算**解析理论面积**：

- 按所有非水平边端点的 y 值，以及**任意两条边的严格内部交点**的 y 值切分
  水平条带，保证每个条带内部穿越拓扑恒定；
- 在条带中点求边交点，按当前填充规则（环绕数或奇偶）积分内部宽度。

自相交多边形必须在边的真实交点处切分：若只按顶点 y 切分，交点可能恰好落在
条带中线上（例如顶点 `(-2,-2),(2,2),(2,-2),(-2,2)` 的蝴蝶结，交点在原点），
中线上的重合穿越会退化成虚假的内部区间，把面积算成两倍。切分后理论面积就是
几何真值：该蝴蝶结由两个仅在原点相接的三角形组成，两种规则下面积均为 8；
交点位于边内部的一般自相交多边形同样按交点切分，结果与独立的三角形分解一致。
孔洞仍以独立环表达，`EVEN_ODD` 与 `NON_ZERO` 分别按奇偶/环绕数积分。

栅格面积按“要素 × 像元”关联计数（即 COUNT 视图）乘以 `cellSize²` 累加，
因此重叠要素的面积校验基于各要素面积之和，而不是并集。结果同时返回
`unionCoveredArea` 供并集统计使用。

`AreaReport` 包含理论面积、栅格面积、绝对/相对误差、阈值与 `passed`。
相对误差超过 `areaRelativeTolerance` 时抛出 `AreaVerificationException`：

- 提交标记（`commit.dat`）**不会**写入；
- 已完成的瓦片保留在检查点目录，修正配置（通常是提高分辨率或放宽阈值）后
  可重新运行；下游永远观察不到“部分提交”的覆盖图。

像元中心采样的误差随分辨率提高而收敛；阈值应按业务精度要求配置。

## 分块、流式处理与检查点

输出网格按 `tileSize` 切成瓦片，自左下角行优先编号。要素首先经策略处理后
**流式假脱机**到检查点目录（同一时刻只保留当前要素几何），随后逐瓦片处理：

1. 顺序读取假脱机要素，用 bbox 快速剔除与当前瓦片不相交的要素；
2. 有界线程池并发栅格化候选要素；
3. 按要素 id 顺序合并到该瓦片；
4. 瓦片通过临时文件 + 原子移动提交，并同步推进进度（同样原子写）。

检查点目录结构：

```text
config.dat     任务配置与网格/瓦片元信息
features.dat   已接受要素的流式假脱机
tile_NNNNNN.dat 各瓦片覆盖数据（原子写入）
progress.dat   已提交瓦片数（原子写入）
commit.dat     最终汇总；存在即唯一的原子提交标记
```

任意时刻崩溃：没有 `commit.dat` 就不算完成；`resume` 从最近已提交瓦片之后
继续，已提交瓦片不会重算或覆盖。全部瓦片完成且面积校验通过后才写入
`commit.dat`，覆盖图在此之前对外不可见。

`assembleCoverage=false` 时不在内存装配整张覆盖图（超大网格场景），结果中
`coverage` 为 `null`，瓦片数据保留在检查点目录供下游流式读取。

## 包结构

- `model`：几何（`Point/Ring/Polygon/Feature`）、配置与结果类型。
- `geometry`：`PolygonArea`（解析面积）、`PolygonClipper`（裁剪/拒绝）、
  `ScanlineRasterizer`（扫描线栅格化）。
- `raster`：`TilePlanner`、`CoverageMerger`、`CheckpointStore`、
  `RasterizationService`。

## 测试

```bash
mvn test
```

测试覆盖：两种填充规则、孔洞、自相交、重复顶点、裁剪/拒绝策略、
分块（含 1×N / N×1 瓦片）与整体处理逐像元一致、COUNT/MAX/PRIORITY 合并、
并发下提交顺序无关、面积超阈值显式失败且不提交，以及注入崩溃后的检查点
恢复与结果一致性。

其中自相交相关用例（`SelfIntersectionAreaTest`）的期望值全部来自独立解析
真值（蝴蝶结两三角形面积 4+4=8；内部交点情形的三角形分解），并覆盖：两种
填充规则下的理论面积、像元边长 0.02 在默认 1% 阈值下通过、0.05 下失败（证明
校验仍随分辨率收敛）、检查点中断后续跑与从头执行逐像元一致。

合并顺序无关用例（`MergeOrderDeterminismTest`）对同一要素集合做多次固定种子
随机排列，组合 1/2/5 线程与 3/8/17 像元的分块尺寸，逐像元比对值与 owner，并
显式断言：COUNT 的 owner 为最小覆盖要素 id（平票场景）、MAX 等权时较小 id
获胜、PRIORITY 等优先级时较小 id 获胜、未覆盖像元 owner 为 `-1`。
