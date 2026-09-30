# geo-rasterizer-java

矢量多边形栅格化服务：把平面上的多边形要素（可含孔洞、自相交、重复顶点）按指定
分辨率转换为栅格覆盖图，用于面积统计与空间分析。相同输入在任何时候都产生**确定、可复现、
逐像元一致**的结果。

基于 Java 17 + Spring Boot 3，无外部几何/JTS 依赖，核心算法在本仓库内实现。

---

## 1. 快速开始

```bash
mvn test          # 运行全部 29 个自动化测试
mvn spring-boot:run
```

健康检查：

```bash
curl http://localhost:8080/health
# {"status":"UP","service":"geo-rasterizer"}
```

栅格化请求示例：

```bash
curl -X POST http://localhost:8080/api/rasterize \
  -H 'Content-Type: application/json' \
  -d '{
    "cellSize": 1.0,
    "outputEnvelope": [0, 0, 4, 4],
    "fillRule": "WINDING",
    "mergeStrategy": "COUNT",
    "outOfRangePolicy": "CLIP",
    "areaTolerance": 1e-6,
    "tileCols": 64,
    "tileRows": 64,
    "parallelism": 4,
    "features": [
      {
        "id": "square",
        "value": 1.0,
        "priority": 0,
        "polygon": {
          "exterior": [[0,0],[4,0],[4,4],[0,4]],
          "holes": []
        }
      }
    ]
  }'
```

响应包含：

- `grid`：行列数、像元尺寸、原点与坐标轴约定（行自下而上，列自左而右）。
- `coverage`：每个像元的覆盖率矩阵 `coverage[row][col]`，取值 `[0,1]`；
  COUNT 合并下是各要素覆盖率之和，可能大于 1。
- `values`：每个像元的聚合值（含义随合并语义而变，见下）。
- `winnerFeatureIds`：每个像元的获胜要素 id。
- `areaConservation`：面积守恒校验结果。
- `metadata`：完整处理元信息，供下游追溯。

编程式入口是 `com.github.guoyyit.georasterizerjava.service.RasterizationService`。

---

## 2. 核心算法：为什么结果精确且确定

核心扫描线实现见
`src/main/java/com/github/guoyyit/georasterizerjava/engine/FillRuleSweep.java`。

1. 沿 y 方向把输出范围切成相邻水平窄条，窄条边界取：
   - 裁剪框上下边界；
   - 所有边端点的 y；
   - 每一对非竖直边在范围内的内部交点 y；
   - 像元行边界。
2. 因此每个窄条内部边的左右顺序恒定，窄条中线（水平射线）不会经过任何顶点或交叉点，
   也不会与竖直边共线。
3. 水平射线只与非竖直边（含竖直边）按半开区间 `[lowY, highY)` 求交；
   自左向右累计环绕数或奇偶翻转，得到该窄条内被填充的 x 区间。
4. 窄条高度 × 填充区间长度 = 精确填充面积；把 x 区间切到各像元列带得到逐像元覆盖率。

这套方法对孔洞、自相交、重复顶点、轴对齐退化都成立，且：

- **不是超采样近似**：覆盖率由解析积分得到，面积偏差只来自 double 浮点累加
  （实测复杂几何约 1e-16 量级）；
- 只依赖输入坐标，没有随机数、哈希迭代顺序或线程调度顺序；
- 要素在引擎内按 `(id, 输入下标)` 稳定排序，合并决胜规则确定，因此并发执行、
  打乱提交顺序、重复运行都逐像元一致。

---

## 3. 填充语义（`fillRule`）

- `ODD_EVEN`（奇偶规则）：射线穿越边界次数为奇数的像元区域属于多边形内部。
  不看环的方向，任何内环都表现为“孔洞”。
- `WINDING`（非零环绕规则）：累计有向环绕数，环绕数非零即为内部。
  反向孔洞（外环 CCW、孔洞 CW）环绕数相消，形成孔洞；同向内环环绕数叠加
  （孔内环绕数为 2，仍为非零覆盖，按覆盖面积语义计一次，不按重数翻倍）。

环顶点首尾点无需重复；连续重复顶点和闭合重复顶点会在
`LinearRing` 中被规范化去除。

---

## 4. 合并语义（`mergeStrategy`，多要素叠加到同一张覆盖图）

每个像元维护覆盖率、聚合值、获胜要素 id 与优先级。三种语义都满足
**可交换、可结合，与要素提交顺序无关**：

- `COUNT`：覆盖率与聚合值按要素覆盖率**累加**（“覆盖面积计数”）。
  同一像元被 N 个要素覆盖时覆盖率为 N；`values` 为各要素 `value × 覆盖率` 之和。
  获胜者 id 记录为字典序最小者，仅供追溯。
- `MAX`：保留 `value` 最大的要素；`value` 相同时按要素 id 字典序较小者决胜。
  像元覆盖率取获胜要素自身的覆盖率。
- `PRIORITY`：保留 `priority` 最大的要素；优先级相同时按要素 id 字典序较小者决胜。

各要素的部分覆盖块彼此独立，可以安全并发计算；合并只依赖
`(value / priority, id)`，因此结果不依赖线程调度或到达顺序。

---

## 5. 面积守恒校验（`areaTolerance`）

输出覆盖面积必须与几何理论面积一致，方法如下：

- 理论面积：对每个要素，用同一个扫描线引擎在“有效输出范围”内按填充规则做解析积分。
- 栅格面积：栅格化时对**每个要素单独**累加其逐像元覆盖率 × 像元面积。
  注意 MAX/PRIORITY 下重叠像元只保留一个获胜者，合并后矩阵的覆盖率之和天然不等于
  各要素面积之和；因此面积守恒是**逐要素**口径，不受合并语义影响。
  逐要素覆盖率之和随分块一起原子写入检查点 sidecar（`.area`）。
- 偏差：
  - 绝对偏差 = |栅格面积 − 理论面积|
  - 相对偏差 = 绝对偏差 / max(|理论面积|, 1e-12)
- 当相对偏差 > `areaTolerance`（默认 `1e-6`，可配置，可设为 `0`）时，作业抛出
  `AreaConservationException`（REST 返回 `422 AREA_CONSERVATION_FAILED` 并携带报告），
  **显式失败，绝不静默产出错误覆盖图**。

校验结果（理论面积、栅格面积、绝对/相对偏差、阈值、是否通过）随结果返回，并写入
`metadata.areaConservation`。

---

## 6. 分辨率、输出范围与越界策略

- `cellSize`：像元边长（>0，必填）。
- `outputEnvelope`：有效输出范围 `[minX,minY,maxX,maxY]`。缺省时由所有要素包围盒的并集推断。
- 栅格行列数按 `ceil(范围 / cellSize)` 计算；所有像元边界严格落在
  `min + n × cellSize` 上，末列/末行超出范围的部分被精确钳制到范围边界。
- `outOfRangePolicy`：
  - `CLIP`（默认）：几何超出范围时只栅格化与有效范围的交集；理论面积也按交集计算。
  - `REJECT`：任一要素包围盒不完全包含在有效范围内时，作业以
    `GeometryRejectedException` 显式拒绝（REST `422 GEOMETRY_OUT_OF_RANGE_REJECTED`）。

实际生效的范围、填充规则、合并策略、越界策略、阈值、分块参数、并发度、处理策略说明
都会写入结果 `metadata`，下游可完整追溯。

---

## 7. 分块处理：无缝拼合

- 栅格按 `tileCols × tileRows` 个像元切成块。像元不可分割，每个像元整体属于唯一一块。
- 相邻块**共享同一浮点边界坐标**，末尾块钳制到输出范围边界，因此任意相邻块拼合后与
  一次性整体处理逐像元一致：块边界两侧没有裂缝、漏像元或重叠像元。
- `TilingConsistencyTest` 用多种分块尺寸（含 1×1、质数尺寸）逐像元对比整块结果。

---

## 8. 有界内存、流式推进、检查点恢复

- 内存有界：作业按块推进，已完成块立即落盘，任意时刻在途块数不超过 `parallelism`，
  常驻内存只与分块大小、并发度、要素数有关，与全图尺寸无关。
- 流式接口 `RasterizationService.rasterizeStreaming(...)` 在面积校验通过后，按
  `(tileY, tileX)` 顺序逐块回调，回调返回后该块即可被回收，适合超大覆盖图。
- 检查点：提供 `workDir` 时，作业状态写入 `<workDir>/<jobId>/`：
  - `meta.properties`：作业 id 与配置签名（cellSize、范围、填充/合并/越界策略、阈值、
    分块尺寸）。配置签名不匹配时拒绝恢复，防止拼接旧的不兼容结果。
  - `tiles/tile-x-y.dat`：合并后的分块（确定性二进制编码）。
  - `tiles/tile-x-y.area`：该块逐要素覆盖率之和（面积守恒口径）。
- 原子提交：每个块先写 `.tmp` 再 `ATOMIC_MOVE` 发布；崩溃时未完成块只会残留
  `.tmp`，外部永远观察不到部分提交的覆盖图。
- 两阶段提交：第一阶段完成所有块计算/落盘并汇总面积，第二阶段执行面积守恒校验，
  只有校验通过才把块读出（同步接口）或逐块回调（流式接口）发布。校验失败时调用方
  拿不到任何覆盖图。
- 断点续算：用同一 `workDir` 再次调用即可跳过已完成块，从最近检查点继续。
  `RasterizationProgressListener` 可用于观测进度或在测试中注入中途失败。
- 不提供 `workDir` 时使用临时目录，作业结束自动清理（该模式不可续算）。

---

## 9. 主要代码结构

| 包 | 职责 |
| --- | --- |
| `model` | `Point`、`Envelope`、`LinearRing`、`Polygon`、`Feature` 与规则枚举 |
| `config` | `RasterConfig`（不可变配置 + Builder） |
| `raster` | `GridDefinition`、`TileResult`（含确定性序列化） |
| `engine` | `FillRuleSweep`（核心扫描线）、`FeatureCoverageRasterizer`、`TileMerger`、`RasterizationEngine` |
| `checkpoint` | `CheckpointStore`、`FileCheckpointStore`（原子提交、配置签名校验） |
| `service` | `RasterizationService`（编排、并发、两阶段提交、面积报告）、异常、结果与元信息 |
| `web` | REST 控制器、DTO、错误映射 |

---

## 10. 测试覆盖（`src/test/.../tests`）

- `FillRuleRasterizationTest`：奇偶/环绕、孔洞定向、自相交蝴蝶结、重复顶点、像元覆盖。
- `TilingConsistencyTest`：多种分块尺寸逐像元一致、无裂缝/漏像元/重叠。
- `MergeSemanticsTest`：COUNT/MAX/PRIORITY 语义、决胜规则、与提交顺序无关。
- `DeterminismTest`：不同顺序、不同并发度、重复运行逐像元一致。
- `AreaConservationTest`：精确守恒、超阈值显式失败、阈值放宽通过、裁剪/拒绝、元信息。
- `CheckpointResumeTest`：中途失败、原子块计数、无 `.tmp` 残留、跳过已完成块、
  恢复结果与一次完整运行一致。
- `StreamingProcessingTest`：每块恰好回调一次、顺序确定、面积失败时不发布任何块。
- `BoundedMemoryTest`：40000 像元大网格在小分块/低并发下流式处理且守恒。
- `RasterControllerWebTest`：REST 成功路径、越界拒绝、非法枚举、健康检查。
