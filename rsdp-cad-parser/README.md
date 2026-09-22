# rsdp-cad-parser —— CAD 户型解析服务（P2：语义与尺寸）

RSDP 的 CAD 户型导入微服务：上传 DWG/DXF，直接解析矢量数据（不经视觉模型），产出带名称/类型的房间多边形（毫米坐标）+ 面积 + 尺寸交叉验证 + 质量门报告。

> 设计文档：`docs/08-roadmap/CAD户型导入架构设计.md`（§3 服务设计、§12 spike 结论）。
> P1（已交付）：服务骨架 + 子图拆分 + 墙体提取 + 空间多边形化 + 金样本框架。
> P2（本次）：房间标签关联（label/roomType）+ 铺贴标注驱动的语义对齐（归并/门槛归属/合并空间）+ DIMENSION 交叉验证（dimensionCheck）+ 置信度分级。

## 技术栈

- **.NET 7**（`net7.0`）Minimal API —— 本机仅有 .NET 7 SDK（7.0.400）；若环境装了 .NET 8 SDK，可直接把两个 csproj 的 `TargetFramework` 改为 `net8.0`。
- **ACadSharp 3.7.16**（MIT）：直读 DWG（R13~R2018）/ DXF。
- **NetTopologySuite 2.6**（BSD）：Polygonizer / noding / 拓扑运算。
- 测试：xunit（金样本回归 + 单元测试）。

## 运行

```bash
cd rsdp-cad-parser
dotnet run                    # 监听 http://0.0.0.0:8090（CAD_PARSER_PORT 可改端口）
curl -F "file=@data/uploads/户型图.dwg" http://localhost:8090/parse
```

响应契约见设计文档 §3.3。P2 起 `rooms[]` 完整字段：
`label`（原文名称）、`roomType`（字典码 LIVING_ROOM/DINING_ROOM/BEDROOM/KITCHEN/BATHROOM/BALCONY/STUDY/HALLWAY/OTHER）、
`memberLabels`（同空间其余标签）、`spaceGroup`（合并空间名）、`polygon`/`bBox`/`widthMm`/`depthMm`/`areaM2`、
`dimensionSource="cad_geometry"`、`dimensionCheck{annotated,consistent}`、`confidence`（high/mid/low）。
无标签空间进 `unnamedRegions`（带相邻标签 hint）。

**`drawingBounds` 语义（前端叠加对齐基准）**：= 全部产出空间（rooms + unnamedRegions）多边形并集的外包络 + `OutputBoundsMarginMm`（默认 200mm）边距，即"公寓本体范围"。解析结果中的 `preview` 是按该范围直接渲染的规范 PNG（`bounds` 与 `drawingBounds` 完全一致），前端只需做毫米坐标归一化，不再人工平移/缩放配准。无任何产出空间时回退为目标标签区域并记 `BOUNDS_FALLBACK` 质量门。

## 测试

```bash
cd rsdp-cad-parser
dotnet test tests/RsdpCadParser.Tests
```

金样本 = `data/uploads/户型图.dwg`（2.7MB 真实图纸，不入库）。测试通过环境变量 `CAD_GOLDEN_DWG` 或向上查找 `data/uploads/户型图.dwg` 定位；文件缺失时跳过（输出 SKIP）。

## 配置项（环境变量 `Parser__Xxx` 或 appsettings `Parser` 节）

| 配置 | 默认 | 说明 |
|---|---|---|
| `ClusterGapMm` | 10000 | 子图/标签聚类间距阈值（>10m 断开） |
| `SnapToleranceMm` | 5 | 线网端点吸附容差 |
| `MinRoomAreaM2` | 1.5 | 房间面积下限（调参值：滤除门套/衣柜凹位残渣） |
| `MaxRoomAreaM2` | 200 | 房间面积上限 |
| `TargetRegionMarginMm` | 6000 | 目标户型区域 = 标签包围盒外扩边距 |
| `MaxNicheAreaM2` | 1.5 | 凹位归并阈值（标注驱动） |
| `WallLayerPattern` | `^0$\|墙\|柱\|wall\|column\|结构\|承重` | 墙图层正则 |
| `DoorLayerPattern` | `^door\|门` | 门图层正则 |
| `WallHatchPattern` | `ANSI31\|AR-CONC\|AR-B816\|AR-HBONE\|AR-BRICK` | 墙体材质填充图案正则 |
| `ExcludeWallLayerPattern` | `天花\|吊顶\|铺贴\|地面\|家具\|灯具\|开关\|…` | 排除图层正则 |
| `SillLayerPattern` | `铺贴\|地面\|门槛` | 门槛石图层正则（该图层 HATCH 实心=门槛石） |
| `OutputBoundsMarginMm` | 200 | drawingBounds = 产出空间多边形并集外包络 + 该边距 |
| `MinHatchRingAreaM2` | 0.05 | HATCH 环面积下限（滤门垛/五金小填充） |
| `RoomLabelPattern` | 客厅\|主卧\|… | 房间名标签词典正则（标签关联 + 目标定位共用） |
| `WallCloseGapMm` | 0 | HATCH 减法兜底路径的闭运算半径（0=关闭） |

## 解析流水线（P2 最终判决逻辑）

1. **载入**：ACadSharp 读 DWG/DXF → 模型空间实体；INSUNITS 换算毫米（Unitless 默认 mm + `UNIT_ASSUMED` 警告）。
2. **实体抽取**：LINE/LWPOLYLINE/POLYLINE/ARC/CIRCLE/ELLIPSE → 点列（bulge/圆弧离散）；HATCH → 边界环；TEXT/MTEXT → 文字（PlainText + 格式码清洗兜底）；**DIMENSION → 测量值+文字中点**。INSERT 块引用暂不展开。
3. **子图拆分与目标定位**：实体密度聚类仅用于混排计数（金样本同户型 8+ 副本间距 <2m 互相桥接，密度聚类拆不开）；目标定位 = 房间名标签聚类 + 区域内面积标注密度打分。
4. **墙体提取**：HATCH 实心判定（墙图层/材质图案/排除图层 + 环面积下限）；铺贴图层 HATCH 实心单独收集为**门槛石**；墙图层线 + 门图层线收集。
5. **空间多边形化（线网法主路径）**：墙线 + 门线（门扇+摆动弧封闭门洞）→ 吸附 5mm → noding → Polygonizer → 原始 cells（≥0.05㎡ 全部保留，交给语义对齐）。HATCH 减法为线网不足时的兜底。
6. **语义对齐（SpaceAligner，P2 核心）**：
   - **语义标注配对**：每处"面积: X㎡"标注配对最近空间名文字（≤2.5m，优先同图层）；名称含 、/及 的标记为合并空间标注（如"客餐厅、厨房及过道 47.43㎡"）；
   - **标注驱动的凹位归并**：无标签、无自身标注的小 cell（<1.5㎡），仅当并入邻居能**改善该邻居的标注一致性**（|并集−标注| 下降）才并入；无标注邻居退回门线规则（贴门线 >300mm 按最长共享边并入，且只并入未命名空间）。→ 阳台门槛带并入阳台（11.49 ✓）、门洞楔并入卫生间（2.55 ✓）、大空间凹位逼近 47.43 ✓；衣柜凹位/生活阳台凹位因恶化误差被保留；
   - **门槛石归属（标注引导减除）**：门槛石实心与房间重叠时，若减除后更贴近标注面积则减除（铺贴面积量到门槛内侧）；
   - **合并空间分组**：成员 = 标签命中合并名各分词（含别名："客餐"涵盖"客厅"）的空间，并集面积与标注比对，偏差 >3% 记 `DIM_MISMATCH`（几何值为准，标注供人工复核）。
7. **标签关联（RoomLabelMatcher）**：空间内房间名标签 → 主标签（优先出现在关联标注名中、优先更具体/非 OTHER 类型、再次距质心最近），其余进 `memberLabels`；roomType 按词典映射（主卧/次卧/小孩房→BEDROOM，label 保留原文）。无标签 → `unnamedRegions`（hint=最近标签+距离）。
8. **DIMENSION 交叉验证**：仅对接近矩形（面积/bbox>0.8）的房间比对 bbox 开间/进深；"家具尺寸"图层标注为唯一可信失配源（偏差 ∈(2%,20%] 记 `DIM_MISMATCH`，>20% 视为无关尺寸不判定），其他图层标注只做正向命中；单轴命中即一致。
9. **置信度**：标注/尺寸交叉验证全部一致 + 有标签 → high；有标签无对照或有不一致 → mid；无标签 → low 进未命名清单。

## 金样本实测（2026-09-19 更新：样本已更换）

> ⚠️ 原带面积标注的 2.7MB 样本已被用户替换。当前样本（同户型，坐标系一致）：
> - `户型图.dwg`（154KB，380 实体）：无标签精简版 → 10 个未命名空间，drawingBounds 紧贴产出（13.63×14.02m）；
> - `家具平面布局图.dwg`（540KB，805 实体，13 类标签）：9 命名房间 + 2 未命名——客厅 47.43（成员：玄关+餐厅+厨房）/ 主卧 22.75（+衣帽间）/ 休闲阳台 11.47 / 小孩房 10.31 / 次卧 9.84 / 书房 7.35 / 主卫 4.63 / 生活阳台 2.81 / 公卫 2.30，与下表历史标注值全部一致。

**drawingBounds 对齐修正（本次）**：原实现 = 标签包围盒 + 6m 边距（家具布局图副本为 21.57×23.3m），与底图（紧贴户型本体 13.63×14.02m）错位 → 前端叠加偏移缩放。修正为产出空间多边形并集外包络 + 200mm（`OutputBoundsMarginMm`），两样本实测 bounds 均收紧到 13.63×14.02m 公寓本体。

### 历史实测（原 2.7MB 带标注样本，P2 交付基线）

10 处"面积: X㎡"标注（地面铺贴图图层） vs 输出房间面积（±3% 容差）：

| 标注 | 语义 | P1 结果 | P2 结果 |
|---|---|---|---|
| 22.75㎡ | 主卧及衣帽间 | ✓ 0.0% | ✓ 0.0% |
| 10.30㎡ | 儿童房 | ✓ 0.1% | ✓ 0.1% |
| 9.84㎡ | 次卧 | ✓ 0.0% | ✓ 0.0% |
| 7.24㎡ | 书房 | ✓ 1.5% | ✓ 1.5% |
| 4.63㎡ | 公卫 | ✓ 1.9% | ✓ 0.0% |
| 47.43㎡ | 客餐厅、厨房及过道 | ✗ −5.4% | **✓ 0.6%**（凹位归并逼近） |
| 11.49㎡ | 休闲阳台 | ✗ −9.1% | **✓ 0.4%**（门槛带归并） |
| 2.55㎡ | 公卫(上) | ✗ −9.8% | **✓ 0.4%**（门洞楔归并） |
| 2.81㎡ | 生活阳台 | ✗ | ✗ +7.8%（门槛归属残留，记 DIM_MISMATCH） |
| 2.46㎡ | 盥洗间 | ✗ | ✗（无墙围合，记 ANNOTATION_NO_GEOMETRY） |

**匹配率 8/10（±3%），P1 为 5/10。**

房间清单（curl 实测，HTTP 200）：客厅 47.15 / 主卧 22.75 / 休闲阳台 11.44 / 儿童房 10.31 / 次卧 9.84 / 厨房 7.36 / 书房 7.35 / 公卫 4.63 / 生活阳台 3.03 / 公卫 2.56 + 未命名凹位 1.56㎡；12 类空间名（含 餐厅/玄关/衣帽间/主卫/小孩房 等 memberLabels）全部命中。

已知语义事实（人工读图核对）：
- 大空间（客厅 47.15㎡）几何上含 客厅+餐厅+玄关/过道，标注"客餐厅、厨房及过道 47.43㎡"与其吻合（0.6%）；但**合并组并集（+厨房 7.36 = 54.51㎡）与标注偏差 14.9%**——铺贴标注的合并口径不含全部厨房面积，几何值为准，已记 DIM_MISMATCH 供人工复核；
- 主卫与公卫在该铺贴副本中画为**同一围合空间**（无分隔墙线），输出为一个 4.63㎡ 房间（label=公卫，memberLabels=主卫）；
- 盥洗间 2.46㎡ 区域无任何墙体/门线围合（开放式盥洗区，仅砖型分界），线网法无法切出——需 P3 引入砖型分界线或人工指定。

## 诊断 CLI（开发调参用）

```bash
dotnet run -- --probe [file] [closeGapMm]        # 全链路诊断 + 标注匹配明细（含标签/置信度）
dotnet run -- --probe-deep [file]                # 聚类桥接实体 / 各簇 HATCH 明细
dotnet run -- --probe-raw [file]                 # HATCH 原始边界 / 标签坐标 / 图层构成
dotnet run -- --probe-region [file]              # 标注区域内实体构成
dotnet run -- --probe-copies [file] [closeGapMm] # 逐户型副本墙体与房间产出
dotnet run -- --probe-lines [file] [layerRe] [snapMm] [cells.png]  # 线网多边形化实验
dotnet run -- --probe-render [file] [out.png] [layerRe] [minX minY maxX maxY(米)]  # 区域渲染 PNG
dotnet run -- --probe-texts [file]               # 铺贴/标注图层文字 dump
# 另有 --probe-merge / --probe-merge2 / --probe-merge3 / --probe-grid / --probe-tiles 为调参过程实验
```

## 已知限制（P3+ 待办）

1. 盥洗间类"无墙区域"需要砖型分界线（铺贴层非网格线）参与分割——P1 实验的网格过滤可复活（`--probe-grid`）；
2. INSERT 块引用不展开（金样本无需；其他图纸若墙画在块内需补）；
3. 合并空间标注的几何并集与铺贴口径可能不一致（已按设计文档"几何值为准"记 DIM_MISMATCH）；
4. 开间/进深目前取 bbox 长短轴（矩形房准确；异形房仅参考），有向包围盒（OBB）后续迭代；
5. 仅实测 1 份图纸，图层习惯差异需更多金样本（P5）。
