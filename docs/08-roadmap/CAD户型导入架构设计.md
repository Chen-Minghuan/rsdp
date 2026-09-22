# RSDP CAD 户型导入架构设计（DWG/DXF 精确解析）

> **版本：** v1.1
> **日期：** 2026-09-17
> **状态：** 设计稿（v1.1：与营销 Agent 解耦，CAD 导入作为独立能力先行交付）
> **前置文档：** `RSDP 户型驱动营销 Agent 架构设计.md`（§5.1 Parser 接口、§32 数据模型；Agent 集成为后续独立排期）
> **驱动需求：** 户型空间数据（房间划分 + 尺寸）必须**完全正确**，AI 视觉识别的概率性误差不接受

---

## 1. 目标与"完全正确"的边界

### 1.1 目标

上传 DWG/DXF 原始 CAD 文件，**不经视觉模型**，直接解析矢量数据，产出：

```text
房间清单：{ 名称/类型, 精确多边形, 开间mm, 进深mm, 面积㎡ }
尺寸来源：cad_geometry（几何实测量）/ cad_dimension（标注实体值）
误差：0（数据即图纸坐标，无估算、无标定、无比例换算）
```

### 1.2 "完全正确"的三个前提（必须向业务说清楚）

CAD 解析消除了**识别误差**和**比例误差**，但以下三类问题仍存在，靠质量门与人工确认兜底：

| 残余风险 | 说明 | 对策 |
|---|---|---|
| **图纸内容本身错** | 设计师画错/标注错/家具块拉伸 | 与图纸一致即"正确"；标注与几何矛盾时进人工复核清单 |
| **语义归属歧义** | "这段墙围合的区域叫什么"依赖文字标签关联，标签缺失/错位时需人工指定 | 未匹配区域单独列出，人工点名 |
| **文件结构脏乱** | 断线、重线、块嵌套、多图混排、老版本编码（GBK） | 解析容错 + 质量门报告 + 回退图片通道 |

**结论：交付口径 = "与 CAD 图纸完全一致 + 语义关联可人工校正"，而不是"图纸画错也能发现"。**

---

## 2. 总体架构

```text
web / website（上传 DWG/DXF/JPG/PNG/PDF）
        │
        ▼
FloorPlanController（入口不变，POST /api/v1/floor-plan/analyze）
        │
        ▼
FloorPlanParserRegistry（按文件类型路由）
        │
        ├─ VisionFloorPlanParser ──→ 现有 AI 链路（JPG/PNG/位图PDF，不动）
        │
        └─ CadFloorPlanParser ──→ HTTP ──→ rsdp-cad-parser（.NET 8 服务）
                                              │
                                              ├─ ACadSharp      （DWG/DXF 读取，MIT）
                                              └─ NetTopologySuite（几何运算，BSD）
                                              │
                                              ▼
                                    CadParseResult（JSON）
                                              │
        ▼─────────────────────────────────────┘
FloorPlanService.normalizeAndSave()
        │
        ▼
floor_plan_analysis + floor_plan_room（polygon/geometry_source 扩展，见 §7）
        │
        ▼
人工确认（现有编辑器，polygon 渲染模式）→ confirmed
        │
        ▼
后续业务消费（户型搭配 / 营销 Agent 等，统一读 floor_plan_room 事实数据）
```

**范围说明（v1.1）**：本方案只交付"CAD 文件 → 精确户型数据入库 + 人工确认"闭环。营销 Agent 集成（会话绑定、房间级约束等）为后续独立排期，本方案仅需保证数据模型与 Parser 接口对其兼容。

**关键决策：**

1. **独立 .NET 8 微服务**（`rsdp-cad-parser`），不进 Java 进程：
   - ACadSharp（MIT）是唯一能**直读 DWG**（R13~R2018）+ DXF 的成熟开源库，Java 生态无等价物；
   - 免 ODA 依赖（许可风险为零），免 Python sidecar（ezdxf 读不了 DWG，还要 ODA 转换器）；
   - NetTopologySuite（NTS，BSD 许可）提供 Polygonizer/吸附/面积/包含判断——工业级几何内核；
   - 进程隔离：解析崩溃/大文件内存峰值不波及主后端。
2. **Parser 接口隔离**：Java 侧只认 `FloorPlanParseResult`，CAD 服务对主后端是"上传字节、返回 JSON"的哑服务。
3. **人工确认环节保留**（营销 Agent 架构 §8 的既定前置条件），CAD 通道让确认从"修数据"变成"过目"。

---

## 3. rsdp-cad-parser 服务设计

### 3.1 项目结构

```text
rsdp-cad-parser/
├── rsdp-cad-parser.csproj        # .NET 8，Minimal API
├── Program.cs                    # 入口：Kestrel，监听 8090（容器内）
├── Api/
│   └── ParseEndpoint.cs          # POST /parse（multipart file）→ CadParseResult JSON
├── Parser/
│   ├── CadDocumentLoader.cs      # ACadSharp 载入（DWG/DXF 自动识别），GBK 编码处理
│   ├── EntityExtractor.cs        # 展开 INSERT 块引用→虚拟实体；过滤模型空间
│   ├── LayerClassifier.cs        # 图层归类：wall / door / window / furniture / dim / text / ignore
│   ├── WallExtractor.cs          # 墙线提取与预处理（合并/吸附/延伸）
│   ├── RoomPolygonizer.cs        # NTS Polygonizer → 候选闭合区域
│   ├── RoomLabelMatcher.cs       # 文字标签 → 多边形关联
│   ├── DimensionExtractor.cs     # DIMENSION 实体数值与关联
│   └── RoomGeometryMeasurer.cs   # 开间/进深/面积计算
├── Domain/
│   ├── CadParseResult.cs         # 输出契约（§3.4）
│   ├── RawEntities.cs
│   └── QualityIssue.cs           # 质量门问题项
└── tests/
    └── GoldenFiles/              # 真实图纸样本回归测试
```

### 3.2 解析流水线（7 步）

```
① 载入与规范化
   ACadSharp 读 DWG/DXF → modelspace 实体
   单位：读 INSUNITS（mm/cm/m），统一换算为毫米
   编码：老版 DWG 中文 GBK → UTF-8

② 块展开（BlockResolver）
   INSERT → 递归展开为虚拟实体（保留所在图层语义）
   家具块按块名/图层标记为 furniture（不参与墙体围合，但保留用于展示）

③ 图层归类（LayerClassifier）
   优先配置映射表（可运营维护）：墙/WALL/墙体/柱/COLUMN → wall；门/DOOR → door；
   窗/WINDOW → window；尺寸/DIM/标注 → dim；家具/FUR → furniture……
   未命中图层：按实体类型启发式（纯 TEXT 层→text；含 DIMENSION→dim）；
   仍未知 → unknown（默认视为非墙，记入质量门清单）
   【后续可扩展：把图层名清单发给 LLM 一次性归类，人工确认后入映射表】

④ 墙线预处理（WallExtractor）
   wall 层线/多段线 → 拆分为线段集
   端点吸附（容差默认 5mm，可配）；共线合并；短线（<50mm 非门窗洞口）清理
   平行成对检测 → 墙厚推算（100/120/200/240 常见值），洞口（门/窗块打断处）保留

⑤ 空间多边形化（RoomPolygonizer）
   NTS Polygonizer 对墙线段集求闭合环 → 候选多边形
   过滤：面积 ∈ [1㎡, 200㎡]；剔除最外包络；处理嵌套包含（阳台包在客厅内等）
   输出：每个候选区域的精确 polygon（毫米坐标）

⑥ 房间语义关联（RoomLabelMatcher）
   收集 TEXT/MTEXT，正则词典匹配房间名（客厅/主卧/次卧/卧室/餐厅/厨房/卫生间/卫/阳台/
   书房/玄关/过道/衣帽间……，词典可配置）
   标签点 → point-in-polygon（失败时试 buffer 10mm 膨胀、最近质心兜底）
   房间类型映射进现有 room_type 字典码
   无标签的闭合区域 → 进"待命名"清单（人工在确认页指定）

⑦ 尺寸实测与交叉验证（DimensionExtractor + RoomGeometryMeasurer）
   几何实测：polygon 的有向包围盒 → 开间（长轴）/进深（短轴）mm、面积㎡
   标注对照：收集 DIMENSION 实体 measurement（精确值），按位置关联到房间/轴线
   交叉验证：|几何值 − 标注值| > 2% → 记 qualityIssue（"图纸标注与几何不一致"），
   几何值为准（geometry 是线的真实坐标），标注值一并返回供人工判断
```

### 3.3 服务接口契约

```http
POST http://cad-parser:8090/parse
Content-Type: multipart/form-data
file=<DWG/DXF 字节>
```

响应：

```json
{
  "success": true,
  "units": "mm",
  "drawingBounds": { "minX": 0, "minY": 0, "maxX": 13670, "maxY": 14100 },
  "rooms": [
    {
      "label": "客厅",
      "roomType": "LIVING_ROOM",
      "polygon": [[x1,y1],[x2,y2],...],
      "labelPoint": { "x": 4200, "y": 3600 },
      "widthMm": 5200,
      "depthMm": 4300,
      "areaM2": 22.36,
      "dimensionSource": "cad_geometry",
      "dimensionCheck": { "annotated": "5200×4300", "consistent": true },
      "confidence": "high"
    }
  ],
  "unnamedRegions": [
    { "polygon": [...], "bbox": {...}, "widthMm": 3100, "depthMm": 2000,
      "areaM2": 6.2, "labelPoint": {"x": 6800, "y": 3600},
      "hint": "相邻标签：厨房（距离 320mm）" }
  ],
  "qualityIssues": [
    { "level": "warn", "code": "LAYER_UNKNOWN", "message": "图层 'S-配件' 未识别，已忽略" },
    { "level": "warn", "code": "DIM_MISMATCH", "message": "主卧：标注 4200 与几何 4360 偏差 3.8%" }
  ],
  "preview": {
    "format": "png",
    "width": 1357,
    "height": 1400,
    "bounds": { "minX": 0, "minY": 0, "maxX": 13670, "maxY": 14100 },
    "pngBase64": "<解析器渲染的规范预览图>"
  }
}
```

失败响应（`success: false` + `errorCode`）：`UNSUPPORTED_VERSION` / `NO_WALLS_FOUND` / `FILE_CORRUPT` → Java 侧提示用户"CAD 解析失败，请改用图片上传"。

### 3.4 预览图

编辑器需要底图来叠加房间框。CAD 服务顺带渲染一张 PNG（ACadSharp 无渲染器，使用扁平实体自绘墙线+家具轮廓，最长边 1400px），其 `preview.bounds` 与 polygon 的 `drawingBounds` 完全相同。Java 侧解码后把 PNG 独立存入 image_assets，并在写 raw_result 前清空 base64。前端只在该规范图上叠加；用户上传的外部阅览图独立展示为只读参考，不参与配准。

---

## 4. Java 侧集成（改动清单）

### 4.1 Parser 抽象（refactor，不动行为）

```java
public interface FloorPlanParser {
    boolean supports(FloorPlanFileType type);            // IMAGE / PDF / CAD
    FloorPlanParseResult parse(FloorPlanParseRequest req);
}
```

- `VisionFloorPlanParser`：包装现有 VisionService 两阶段链路（行为零变化）；
- `CadFloorPlanParser`：调 rsdp-cad-parser（RestClient，超时 120s），把 CadParseResult 归一化为 FloorPlanParseResult（毫米 polygon + bbox 由 polygon 外包络派生，兼容现有前端）；
- `FloorPlanParserRegistry`：`ImageUploadValidator` 放开 `.dwg/.dxf` 扩展名（≤20MB），按类型路由。

### 4.2 落库与状态

- `FloorPlanService.analyze`：CAD 解析为同步快调用（通常 <5s），仍走异步任务体系（复用现有进度轮询）；
- 结果直接 buildRooms 落库，`dimension_source = cad_geometry`，`dimension_confidence = high`；
- `unnamedRegions` 落成 roomType=OTHER + label="未命名空间 N"，确认页引导人工命名；
- `qualityIssues` 落 `floor_plan_analysis.raw_result` 备查，确认页顶部 banner 展示 warn 清单。

### 4.3 自动标定的关系

CAD 通道**跳过标定**（尺寸来自真实坐标）；编辑器检测到 `geometry_source=cad_geometry` 时隐藏标定与尺寸编辑入口。确认操作仅修改名称、类型和顺序，不覆盖 polygon、精确面积及 CAD 尺寸。

---

## 5. 前端配套（小改）

1. 上传组件 accept 加 `.dwg/.dxf`；
2. 编辑器支持 polygon 渲染模式（CAD 房间画多边形而非矩形框），规范图默认展示并与 polygon 同坐标叠加；
3. 原始阅览图作为独立只读参考页签，不提供人工平移/缩放配准；
4. 确认页：未命名空间命名、qualityIssues banner，CAD 宽深只读。

---

## 6. 部署

```yaml
# deploy/docker-compose.yml 追加
cad-parser:
  build: ../rsdp-cad-parser        # 或镜像
  container_name: rsdp-cad-parser
  restart: unless-stopped
  networks: [rsdp-net]             # 仅内网，不暴露端口
```

Java 侧配置：`rsdp.cad-parser.base-url: http://cad-parser:8090`（dev 默认 `http://localhost:8090`）。
本地开发：`dotnet run` 起服务即可；Windows 服务器部署走同一容器。

---

## 7. 数据模型（独立迁移，不并入营销 Agent 排期）

新增 Flyway `V11__floor_plan_room_cad_geometry.sql`（营销 Agent 的集成为后续 V12+）：

```sql
ALTER TABLE floor_plan_room
  ADD COLUMN IF NOT EXISTS label VARCHAR(128),
  ADD COLUMN IF NOT EXISTS polygon JSONB,
  ADD COLUMN IF NOT EXISTS centroid JSONB,
  ADD COLUMN IF NOT EXISTS geometry_source VARCHAR(32);  -- ai_vision / cad_geometry
```

（`database/schema/` 对应域文件 + `ops/reset_db.sql` 同步，遵守同步约定。）

---

## 8. 质量门与测试策略

### 8.1 金样本回归（核心）

- 收集 3~5 份真实设计师 DWG/DXF（不同公司/不同图层习惯），人工标注期望房间清单与尺寸；
- xunit 回归：解析结果与期望逐房间比对（面积差 <0.1%、polygon IoU ≈1）；
- 每次改解析规则跑全量金样本，防回归。

### 8.2 质量门运行时报表

每次解析输出 `qualityIssues`，分两级：
- `warn`（未知图层、标注几何不一致、未命名区域）→ 确认页展示，不阻断；
- `block`（无墙线、无闭合区域、单位缺失）→ 明确提示回退图片通道。

### 8.3 Java 侧

`CadFloorPlanParserTest`（WireMock 模拟解析服务）+ `FloorPlanService` CAD 路径用例。

---

## 9. 分期实施与工作量（独立交付，不绑定营销 Agent）

| 期 | 内容 | 预估 |
|---|---|---|
| ~~P0 Spike~~ | ✅ 已完成（2026-09-17，见 §12）：真实 DWG 可读、标签齐全、坐标即毫米；确认 HATCH 墙体识别 + 子图聚类两个技术调整 | — |
| **P1 解析核心** | rsdp-cad-parser 骨架 + 子图聚类拆分 + HATCH 墙体提取 + NTS 多边形化 + 金样本框架 | 4~6 天 |
| **P2 语义与尺寸** | 标签关联（MTEXT 格式码清洗+房间名词典）+ 几何实测/标注交叉验证 + 未命名区域 + qualityIssues | 2~3 天 |
| **P3 集成入库** | Java Parser 接口 refactor + CadFloorPlanParser + V11 字段 + 前端上传/确认页适配（含未命名空间命名） | 2~3 天 |
| **P4 编辑器 polygon 模式** | 多边形渲染（编辑可先降级为 bbox/整体拖动） | 1~2 天 |
| **P5 加固** | 多份真实图纸适配（图层/习惯差异）、性能、Docker 编排 | 持续 |

**验收标准（本方案范围内）**：
- 上传样本 DWG → 12 个空间全部识别、名称正确；
- 每个房间的开间/进深/面积与图纸标注完全一致（误差 0）；
- 未命名/低置信区域在确认页可见可人工指定；
- 确认后落库数据可直接被现有户型搭配链路消费（FloorPlanMatchingService 不需要改）。

---

## 10. 风险清单

| 风险 | 等级 | 应对 |
|---|---|---|
| 真实图纸图层习惯混乱 | 高 | P0 spike 先验证；图层映射表做成可配置 + 运营可维护 |
| 断线导致区域不闭合 | 中 | 端点吸附容差可调 + 短缝延伸；仍不闭合的区域进 unnamedRegions |
| 老 DWG 版本/编码（R12、GBK） | 中 | ACadSharp 覆盖 R13+；编码转换；不支持的版本明确报错回退 |
| 一张图多个户型/图框混排 | 中 | 按闭合区域簇分组，人工在确认页选择目标户型（一期可只取最大簇） |
| 设计师不愿给 DWG | 业务 | 图片通道长期并存；CAD 通道作为"设计师协作"卖点 |
| 块内墙线（墙体画在 BLOCK 里） | 中 | 块展开时保留图层语义覆盖（块实体 0 层→按 INSERT 层归类） |

---

## 11. 与现有资产的关系

- **AI 图片通道继续保留**：顾客/拍照/截图场景永远需要；CAD 通道服务设计师/有原文件的场景；
- **可视化编辑器、自动标定、对照面板全部复用**（CAD 通道下标定自动隐藏，其余照旧）；
- **下游业务零改动**：户型搭配（FloorPlanMatchingService）等消费方统一读 confirmed 的 floor_plan_room，不感知数据来源（`geometry_source` 只是溯源标记）；营销 Agent 集成为后续独立排期，届时直接获得精确数据红利；
- **输出契约统一**（FloorPlanParseResult）：未来矢量 PDF 可作为 rsdp-cad-parser 的第三个输入格式顺带支持。

---

## 12. P0 Spike 实测结论（2026-09-17，样本 `data/uploads/户型图.dwg`）

**结论：GO。** ACadSharp（.NET 7 SDK 本机验证）直读 DWG 2018（AC1032）成功，关键数据全部可提取。spike 程序与产出存 `logs/cad-spike/`。

### 12.1 利好消息

1. **房间标签齐全规范**（MTEXT，含格式码可清洗）：客厅/餐厅/玄关/主卧/次卧/小孩房/书房/衣帽间/主卫/公卫/生活阳台/休闲阳台——12 类空间全覆盖，带精确插入点坐标；
2. **坐标即真实毫米**：如客厅标签 @(91177, 89175)、总尺寸链 13670 等，无需标定、无比例误差；
3. **尺寸标注充足**：DIMENSION_LINEAR 757+62+132+64 条（含"家具尺寸"专属图层 62 条），交叉验证数据源充足；
4. **房间面积有现成文字**："地面铺贴图"图层上每个房间带"面积: 22.75㎡/周长: 26.83m"标注——可直接读数或做几何自检对照；
5. **家具/装饰线条独立图层**（"家具、装饰轮廓线条"1837 实体）——家具与墙体分离比预期容易；
6. 门有专属图层（DOOR【门】167 实体），门窗洞口后续可做。

### 12.2 工程难点（实测确认，设计已调整）

1. **墙体不在"墙体"图层**：8681 个实体堆在 0 层（含 5880 LINE），命名"墙体"图层为空 → **墙体验 Extractor 改为 HATCH 驱动**（0 层有 93 个 HATCH，正是图例中的"钢筋混凝土墙体"填充），线框兜底；
2. **多图混排**：全图跨度 463m×192m，含平面布置/拆墙/砌墙/天花/地面铺贴/开关立面等 5+ 张子图 → 解析前需**按坐标聚类拆分子图**（房间标签簇所在子图为目标）；
3. **块名为乱码**（gfgrtr/ewrf/A$C…匿名块）→ 家具语义不依赖块名，靠文字标签；
4. MTEXT 带格式码（`{\fFangSong|b0|i0|c134|p49;\C3;客厅}`）→ 需格式码清洗正则。

### 12.3 对设计的修正（已反映到 §3.2）

- ③图层归类：**新增"HATCH 填充即墙体"主判据**（装饰行业图纸墙体必填充），图层名归为辅判据；
- ①后新增**子图聚类拆分**步骤：按实体坐标密度聚类（间距 >10m 断开），取房间标签所在簇为解析目标；
- P1 工作量预估上调至 4~6 天。

---

## 13. 无房间标签图纸的增强策略（2026-09-21 实测补充）

后续收到的精简版 DWG 不含客厅/卧室名称，也没有面积文字；对应家具阅览 PNG 同样只有家具和尺寸。此类输入无法仅靠 CAD 恢复业务语义，双文件通道采用以下明确分工：

1. **CAD 决定几何**：polygon、宽深、面积、内部锚点和规范预览全部来自矢量图；线网法先连接近似共线、方向相反且距离不超过 `LineCloseGapMm`（默认 1800mm）的墙线端点，以封闭未画门扇的门洞；
2. **阅览图只补语义**：视觉模型输出 label/roomType/bbox；bbox 坐标先根据视觉房间并集与 CAD 主体纵横比消除图框、白边和标题栏，再映射到 CAD 坐标；
3. **匹配有保守边界**：普通空间全局一对一，避免重叠 polygon 抢占；面积显著占优的开放式主体空间允许聚合客厅/餐厅/厨房；未命中部分保持未命名，交人工确认；
4. **失败不污染几何**：视觉调用或配准失败只追加质量提示，绝不回写/覆盖 CAD 尺寸与 polygon。

实测精简版样本经门洞闭合后由 10 个区域改善为 13 个区域，原 57.63㎡ 跨房大区拆出 10.31㎡ 卧室、6.80㎡ 过道/门厅和 3.17㎡ 小空间，剩余 37㎡ 作为开放式客餐厨复合区。
