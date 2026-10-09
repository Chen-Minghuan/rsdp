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
无标签空间进 `unnamedRegions`（带相邻标签 hint；P1 起也分配稳定 `roomId`，与命名房间同一 ROOM-### 序列续编，参与家具归属）。

> P1（Scene-aware Block Fallback，2026-10-04）：
> - **块角色分类（BlockRoleClassifier）**：INSERT 实例树节点分为 SCENE_CONTAINER（整图/方案级容器：跨度 >8m 且图元 ≥100）/ ARCHITECTURE / FURNITURE / ANNOTATION / UNKNOWN；
> - **家具分支**：SCENE_CONTAINER 与绝对超大块（跨度 >32m）不作为单件家具输出，下钻子块找真家具（修复深化图"整户方案块被当成一件 150m 家具"的误判）；
> - **建筑几何回退**：主路径（模型空间直接实体）围合不出任何可输出空间时，用块展开建筑几何重试（剔除 FURNITURE 角色节点与家具/机电/淡显等图层；块内 EffectiveLayer 仍为 "0" 的图元不参与——方案容器内墙线/家具线混在 0 层无法区分）；产出记 `ROOMS_FROM_BLOCK_FALLBACK` 质量门；
> - **堆叠标签降级（LABELS_STACKED）**：半径 500mm 内 ≥3 个不同房间名标签判为堆叠（深化图整户标签块位置塌缩的典型形态），不参与房间指派，降级为所在未命名空间的"候选名：X/Y/Z" hint。

> P1-A/B/C（家具语义线，2026-10-05）：
> - **候选清洗（P1-A）**：无有效几何或包围盒最大边 <100mm 的候选标记 `candidateStatus=REJECTED` + `FURNITURE_SIZE_TOO_SMALL`，仅标记不删除；
> - **去重分组（P1-B）**：同块定义+几何指纹（`geometryFingerprint`，局部坐标量化哈希）+局部尺寸+旋转+位置网格（50mm）的多实例成组，输出 `duplicateGroupId/duplicateStatus(PRIMARY|DUPLICATE)/duplicateCount/sourceInstances`，副本抑制展示不删除（Scene 修正后可恢复）——不同位置的合法阵列（栏杆/餐椅）不算重复；
> - **视觉语义分组（P1-C）**：`visionGroupId`（同块+同尺寸+同指纹，跨位置/旋转共享一次视觉识别）；请求 `withIsolation=true` 时响应带 `isolationImages`（visionGroupId → 块局部坐标白底线稿 PNG，旋转已归一化），由后端配 context 环境裁剪调 qwen3-vl-plus 回填 category/semanticLabel/visionConfidence。

> P1-E（栅格空间重建，2026-10-05）：深化图断线墙体的最终回退——矢量两阶段（直接实体/块展开回退）均无产出时，
> **RasterRoomReconstructor**：墙/门线渲染位图（默认 50mm/px）→ 方形膨胀 RasterWallDilateMm（默认 450mm，封 ≤900mm 门/窗洞断口）→
> 边框泛洪得室外 → 4-连通分量分房（贴边丢弃、面积过滤、填充率 <RasterMinFillRatio 0.15 的环形/细条残渣丢弃）→
> **室内/室外多源 BFS 回填**（原始墙线不可穿越，最多推进 2 倍封口半径；封口带中有可见房间名但无核心的小空间可补种）→
> 扫描线矩形并集 + Douglas-Peucker 简化 → 毫米多边形。另支持 WIPEOUT/SOLID 抽取为实心墙身（深化图常用 WIPEOUT 填充墙身）。
> 产出记 `ROOMS_FROM_RASTER_REBUILD` 质量门且房间 confidence=low（需人工复核），标签指派/堆叠隔离照常。
> 栅格重建的空间能力与 parseStatus 均标为 `NEEDS_REVIEW`；只产出一个命名房间不能证明整户完整。
> 其他路径只要仍有未命名空间或堆叠标签，空间能力标为 `PARTIAL`。
> 调试：`RASTER_DEBUG_PNG=path` 环境变量可导出封口位图。

> 601 样本房间恢复（2026-10-05）：跳过 CAD 标记为不可见的图元，中文标签字距空格归一后把可见块中文字纳入目标选择；避免无标签碎片阻断回退。栅格空间的面积门槛在墙带回填后判断；多个可见房间名落在同一连通区时按标签位置沿横竖方向生成临时分区，并在标签覆盖不足时比较 450/600/650mm 封口半径。`ROOMS_FROM_LABEL_PARTITION` 的分界为估计线，`ROOM_LABEL_SNAPPED` 为近墙标签就近指派，均须人工确认；`ROOM_LABELS_WITHOUT_GEOMETRY` 列出仍没有空间轮廓的可见房间名。601 当前默认参数的 12 个房间合计面积由 78.50㎡ 增至 96.45㎡，轮廓更贴近墙线，但“公卫”仍未稳定分出，不可据此认定真实总面积。

> P2-S（房间轮廓贴合墙体内侧面，2026-10-05）：Room Polygon 不再独立成边，统一 Snap 到 Wall Inner Face——
> **WallInnerFaceExtractor** 按优先级构建墙体内侧边线：① 完成面类图层线（`InnerFaceLayerPattern`，如 601 的「W装饰完成面」，是现成的内侧完成面几何）；
> ② 实心墙身并集（WallUnion）边界环（墙体两侧边线即实心体边界，房间侧为内侧面）；③ 双线墙线配对（平行且间距 ∈ [WallPairMinThickMm, WallPairMaxThickMm] 的墙线配成两侧边线，未配对降优先级兜底）。
> **RoomWallSnapper**：每条房间边在 `SnapMaxDistMm`（默认 250）内找方向一致（夹角 ≤`SnapMaxAngleDeg` 10°）的内侧面线段，命中即改用内侧面所在直线（同一组几何坐标，共线、无间隙、不平行偏移）；
> 相邻两条已吸附边的顶点 = 两条内侧面直线的交点（roomVertex = wallInnerFaceIntersection）；候选评分 = 来源优先级 → 垂直距离 → 室内侧位移优先，并用"室内侧探针不得落在墙实心内"剔除同一堵墙的外侧面/邻室内侧面；
> 两侧已吸附、总长 ≤`DoorStraightenMaxLenMm` 的未命中边链（门洞虚拟封口折线）拉直为一条边；吸附后共线连续边精确合并，消除同一直墙上的轻微偏移碎段。
> 硬不变量：不得新增与墙实心内部的相交（贴边共线允许）、面积变化超 `SnapMaxAreaChangeRatio`（默认 ±15%）或退化时整体回退原轮廓并记 `WALL_SNAP_REVERTED`；未命中内侧面的边（开放区/无墙侧）保持原样记 `WALL_FACE_NOT_FOUND`，不虚构边界。
> 三条空间链路（线网/块回退/栅格）的最终空间统一过 snap；废弃候选路径的质量门不并入输出。总开关 `WallSnapEnabled`。
> 实测：金样本保持 10 命名+4 未命名（命名总面积 100.59→100.60㎡，房间顶点数 10~29 → 5~11）；601 保持 12 命名+0 未命名 + `NEEDS_REVIEW`（完成面线 5 实体 → 31 段参与吸附，命名总面积 96.45→99.25㎡，门洞拉直 25 处、回退 1 间）。

**`drawingBounds` 语义（前端叠加对齐基准）**：= 全部产出空间（rooms + unnamedRegions）多边形并集的外包络 + `OutputBoundsMarginMm`（默认 200mm）边距，即"公寓本体范围"。解析结果中的 `preview` 是按该范围直接渲染的规范 PNG（`bounds` 与 `drawingBounds` 完全一致），前端只需做毫米坐标归一化，不再人工平移/缩放配准。无任何产出空间时回退为目标标签区域并记 `BOUNDS_FALLBACK` 质量门；回退时会再扩展覆盖全部家具候选包围盒（P1-C：标签堆叠/残缺时标签区域常偏出家具主体，不扩展则规范预览与视觉 context 裁剪都够不到家具）。

校对用规范预览只绘制可确认的建筑线（模型空间建筑线及建筑角色/图层的块图元），不展开绘制家具、家电、标注块的内部细线；家具 isolation 小图仍保留原始细节。若图纸没有任何可确认的建筑线，预览回退为原始图元，避免空白。此筛选只影响预览 PNG，不改变房间/家具解析结果或 `preview.bounds`。

**参考图校对的预览墙带**：双线墙不一定全段是实墙。`Parser/preview_wall_profiles.json` 保存经原始 PDF 核对的例外：以 DWG SHA-256 精确限定输入，以 CAD 实体句柄限定墙线对，`yIntervals` 为允许显示深色墙带的 CAD 毫米纵坐标区间；空数组表示只保留原始细线。`solidRectangles` 是 PDF 配准后补绘的实墙矩形，坐标为 `[minX,minY,maxX,maxY]`（CAD 毫米）；601 的电视墙凹位上沿与左右侧缺口由这些矩形恢复，上沿中间开口保留；玄关左侧、下沿及向储物间下探的 L 形墙带也按原始 PDF 校准补回。601 右侧外墙对应参考 PDF 第 1 页的两段黑墙，中间和阳台侧保留留白。该配置仅影响规范预览像素，不改房间几何与面积；其他文件仍使用通用规则。新增校对项前须核对原始图并补充像素回归，不能用家具轮廓猜测墙体。

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
| `MaxFurnitureSpanMm` | 8000 | 家具跨度上限（配合图元数判 SCENE_CONTAINER；>4× 即 32m 无条件剔除出家具） |
| `SceneContainerMinPrimitives` | 100 | 方案级容器判定的展开图元数下限 |
| `FallbackExcludeLayerPattern` | 家具\|洁具\|家电\|淡显\|^k\|… | 块展开回退的排除图层正则 |
| `StackedLabelRadiusMm` / `StackedLabelMinCount` | 500 / 3 | 堆叠标签判定半径与数量下限 |
| `BlockGeometryFallbackEnabled` | true | 块展开建筑几何回退开关 |
| `WallSnapEnabled` | true | 房间轮廓贴合墙体内侧面总开关（P2-S） |
| `InnerFaceLayerPattern` | `完成面\|饰面\|面层\|inner.?face\|finish.?line` | 墙体内侧完成面图层正则（最高优先级 Inner Face 来源） |
| `SnapMaxDistMm` | 250 | 房间边吸附到内侧面的最大垂直距离 |
| `SnapMaxAngleDeg` | 10 | 房间边与内侧面方向夹角上限 |
| `SnapMaxAreaChangeRatio` | 0.15 | 吸附后面积变化容忍比例（超阈值回退原轮廓） |
| `DoorStraightenMaxLenMm` | 2000 | 门洞虚拟封口折线拉直的链长上限 |
| `WallPairMinThickMm` / `WallPairMaxThickMm` | 60 / 500 | 双线墙配对厚度窗口 |

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
7b. **墙体内侧面吸附（RoomWallSnapper，P2-S）**：每条链路的最终空间统一把边吸附到 Wall Inner Face 所在直线（完成面图层线 > 墙实心边界环 > 双线墙配对边线）；相邻已吸附边的顶点 = 两条内侧面直线交点；门洞虚拟封口折线拉直；共线碎段合并。硬不变量：不新增压墙相交、面积变化 ≤15%，违反回退原轮廓记 `WALL_SNAP_REVERTED`；未命中边保持原样记 `WALL_FACE_NOT_FOUND`。
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
dotnet run -- --probe-rooms [file] [on|off]      # 房间产出对比（off=关闭墙体内侧面吸附做前后对比）
# 另有 --probe-merge / --probe-merge2 / --probe-merge3 / --probe-grid / --probe-tiles 为调参过程实验
```

`--probe` 不传 `closeGapMm` 时与 `/parse` 使用相同的默认解析参数；显式传入数字才覆盖门洞闭合参数。

## 已知限制（P3+ 待办）

1. 盥洗间类"无墙区域"需要砖型分界线（铺贴层非网格线）参与分割——P1 实验的网格过滤可复活（`--probe-grid`）；
2. INSERT 块引用不展开（金样本无需；其他图纸若墙画在块内需补）；
3. 合并空间标注的几何并集与铺贴口径可能不一致（已按设计文档"几何值为准"记 DIM_MISMATCH）；
4. 开间/进深目前取 bbox 长短轴（矩形房准确；异形房仅参考），有向包围盒（OBB）后续迭代；
5. 仅实测 1 份图纸，图层习惯差异需更多金样本（P5）。
