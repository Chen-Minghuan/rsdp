# RSDP CAD Scene Parser 升级开发设计文档 V1.2.3 — Scene Revision 原子激活收口版

> 修订重点：在 V1.2.2 的 Revision 绑定与字段级 Fact Gate 基础上，补齐 Scene Revision 的实际落库和原子激活规则：Room/Furniture/Wall/Door/Window 必须显式归属 `analysisRevision`；工作 Revision 的人工编辑直接保存到该 Revision 下，不覆盖当前生效版；新增 Scene Activation 契约，只有整版 Revision 通过校验并显式激活后才切换 `activeAnalysisRevision`；现有 `confirmRooms()` 不再允许单独把整个 Analysis 标记为 confirmed，避免“新版家具 + 旧版房间”跨 Revision 拼接。补充激活后的写保护、逐版自动快照和存量数据回填契约。本文仍为设计目标，新增字段、接口和模块需按阶段实施。

## 1. 文档目的

本文用于明确 RSDP 当前 CAD 户型解析能力的现状、存在的问题，以及下一阶段从“户型房间识别”升级为“CAD 空间与家具结构化解析引擎”的技术方案。

本次升级不推翻现有 `rsdp-cad-parser`，而是在现有 DWG/DXF 解析、房间 Polygon、面积与尺寸校验能力之上，继续补充：

- 墙体结构化
- 门结构化
- 窗结构化
- 家具对象识别
- 家具尺寸识别
- CAD DIMENSION 与实际对象关联
- 家具与房间、墙、门、窗之间的空间关系
- CAD 识别结果人工确认与持续沉淀
- 为 Marketing Agent 提供真实空间约束

最终目标不是简单“把 CAD 画出来”，而是将 CAD 转换成可被业务系统直接使用的结构化空间事实数据，并让系统随着真实 CAD 的不断使用逐渐提升兼容性与稳定性。

---

## 2. 当前项目 CAD 能力现状

当前项目已经具备独立的：

```text
rsdp-cad-parser
```

解析服务。

当前总体链路已经形成：

```text
DWG / DXF
    ↓
ACadSharp
    ↓
CAD Entity Extraction
    ↓
墙体 / HATCH / 门线提取
    ↓
空间 Polygon 构建
    ↓
房间标签识别
    ↓
面积 / DIMENSION 校验
    ↓
CAD Preview
    ↓
Java Server
    ↓
数据库
    ↓
前端人工确认
```

目前已经能够完成：

- DWG / DXF 文件读取
- 单位转换为毫米
- LINE / POLYLINE / ARC / CIRCLE / ELLIPSE 解析
- HATCH 解析
- TEXT / MTEXT 解析
- DIMENSION 解析
- 墙体提取
- 门图层线提取
- 门槛 / 铺贴 HATCH 提取
- 房间 Polygon 构建
- 房间名称识别
- 房间面积计算
- 房间开间 / 进深计算
- 面积标注交叉验证
- DIMENSION 交叉验证
- CAD 预览图生成
- CAD Polygon 前端叠加
- 未命名区域人工确认
- CAD + Vision 语义补充

当前已经形成：

```text
上传
→ 自动解析
→ 空间结构化
→ 数据落库
→ 人工确认
→ 后续业务使用
```

完整业务链路。

下一阶段应该在此基础上进一步增加：

```text
人工确认
    ↓
保存“自动结果 vs 最终确认结果”
    ↓
识别差异沉淀
    ↓
形成新的 CAD 解析经验
    ↓
Parser 优化
    ↓
下一批 CAD 识别更稳定
```

---

## 3. 当前版本主要定位

当前 CAD Parser 更准确的定位是：

> 二维住宅户型 CAD 房间几何解析器。

它主要回答：

```text
这个 CAD 里面有哪些空间？
这些空间多大？
```

例如：

```text
客厅
面积：37.2㎡
宽度：5200mm
进深：4300mm
```

当前能力已经能够支撑：

- 户型识别
- 空间确认
- 按面积推荐商品
- 粗粒度尺寸过滤

但对于真正的“户型驱动 Marketing Agent”仍然不足。

---

## 4. 下一阶段目标

下一阶段不再只定义为：

> CAD 户型识别优化。

而应升级为：

> CAD 空间与家具结构化解析引擎。

推荐内部能力名称：

```text
CAD Scene Parser
```

它需要同时回答两个问题。

### 4.1 房子本身是什么样

包括：

```text
Room
Wall
Door
Window
Opening
```

例如：

```text
客厅：
5200 × 4300
面积：22.36㎡

南墙：
长度：5200
厚度：240

东墙：
窗户：3600

西墙：
门洞：900
```

### 4.2 房子里面已经放了什么

包括：

```text
Furniture
Dimension
Position
Rotation
Relation
```

例如：

```text
三人沙发
2850 × 1050
旋转：90°
所在房间：客厅

茶几
1350 × 750

电视柜
2400 × 450
```

两部分最终共同形成：

```text
CAD Scene
```

### 4.3 “识别各个家具”的能力边界

本阶段的最小输出单位是**一件家具实例**，不是一个家具类别，也不是一组图元。一张图中两把相同椅子必须输出两个独立对象；识别不确定的图形输出 `UNKNOWN` 候选供人工判断，不能悄悄丢弃，也不能强行归为沙发、桌子等类别。

识别分成三个层次，验收时分别统计：

1. **实例检测**：图中有多少件家具，每件在哪里、由哪些 CAD 图元组成。
2. **类别识别**：判断沙发、床、桌、椅、柜等大类；证据不足时保留 `UNKNOWN`。
3. **商品匹配**：根据确认后的类别、尺寸等检索 RSPU 候选；CAD 平面符号通常不足以证明具体款式、材质、颜色或 RSKU，不能自动宣称精确同款。

第一阶段优先支持模型空间内的二维家具平面图，包括独立 `INSERT`、嵌套 `INSERT` 和有限的已炸开图元。多张户型混排时必须明确选中目标户型，家具检测范围与房间解析使用同一目标区域；布局、立面、详图混排以及无法正确选区时标记质量问题并允许人工切换，不把其他视图的家具混入结果。

---

## 5. 目标总体架构

V1.2 不再把 Parser 简单理解为“Loader → Extractor → 各类识别器”的流水线，而是明确拆成 **输入校验、文档规范化、Canonical IR、目标场景选择、几何规范化、对象识别、关系关联、Scene 装配、质量门、人工确认** 十个阶段。

```text
                    DWG / DXF
                        │
                        ▼
               ① Input Validation
                        │
          File / Version / XREF / Limits
                        │
                        ▼
               ② Document Normalize
                        │
       Unit / Coordinate / Planarity
                        │
                        ▼
                 ③ Canonical IR
                        │
       EntityRef / Block Tree / Provenance
                        │
                        ▼
              ④ Target Scene Selector
                        │
            AUTO + MANUAL OVERRIDE
                        │
                        ▼
               ⑤ Geometry Normalize
                        │
                   Primitives
                        │
          ┌─────────────┼─────────────┐
          ▼             ▼             ▼
      Architecture   Furniture     Dimension
          │             │             │
          └─────────────┼─────────────┘
                        ▼
               ⑥ Association Layer
                        │
       Dimension / Room / Wall / Object
                        │
                        ▼
                ⑦ Scene Assembler
                        │
                        ▼
                  Initial Scene
                        │
                        ▼
               ⑧ Quality / Fact Gate
                        │
                        ▼
                Human Confirmation
                        │
                        ▼
                 Confirmed Scene
                   │           │
                   ▼           ▼
               Business     Feedback
                               │
                               ▼
                          Profile Evolution
```

### 5.1 三层事实模型必须分离

后续禁止继续把所有信息直接塞入一个不断膨胀的 `FlatEntity`。

必须区分：

```text
Source Entity
≠
Normalized Primitive
≠
Business Scene Object
```

推荐形成：

```text
ACadSharp Entity
        ↓
CadEntityRef
        ↓
CadPrimitive
        ↓
SceneObjectCandidate
        ↓
CadScene Object
```

例如一个沙发：

```text
INSERT handle=7A3
        ↓
内部 27 个 LINE / ARC / POLYLINE
        ↓
27 个 CadPrimitive
        ↓
1 个 FurnitureCandidate
        ↓
1 个 Furniture Object
```

这样才能回答：

> 这个 Furniture 为什么存在？它由哪些原始 CAD 图元组成？重新解析后还能不能稳定找到它？

---

## 6. Canonical CAD IR（新增 P0 核心能力）

### 6.1 为什么需要 Canonical IR

当前 `FlatEntity` 适合 P1/P2 房间解析，但随着 BLOCK、家具、门窗、DIMENSION 和反馈闭环加入，继续向其增加字段会逐渐形成巨型数据结构，并让所有识别算法直接依赖 ACadSharp 的具体实体实现。

V1.2 建议增加统一中间事实层。

### 6.2 CadEntityRef

表示原始 CAD 实体身份和溯源信息，不直接表达业务语义。

建议至少包含：

```text
sourceHandle
entityType
layer
sourceSpace
ownerHandle
blockDefinitionName
insertPath
attributes
transform
sourceIndex
```

其中：

- `sourceHandle`：原始 CAD Handle。
- `insertPath`：从顶层 INSERT 到当前实体的完整实例路径。
- `sourceSpace`：MODEL_SPACE / PAPER_SPACE / BLOCK_DEFINITION。
- `transform`：从当前局部坐标到目标 Scene 统一坐标的完整变换矩阵。
- `attributes`：块实例属性值，保留实例级事实。

### 6.3 CadPrimitive

表示统一毫米坐标系中的规范几何：

```text
LinePrimitive
CurvePrimitive
PolygonPrimitive
TextPrimitive
DimensionPrimitive
PointPrimitive
```

每个 Primitive 必须保留：

```text
primitiveId
sourceHandles[]
insertPath
geometry
effectiveLayer
transformApplied
qualityFlags[]
```

### 6.4 SceneObjectCandidate

业务对象识别从 Primitive 层开始：

```text
WallCandidate
DoorCandidate
WindowCandidate
FurnitureCandidate
RoomCandidate
DimensionCandidate
```

Candidate 可以：

```text
CONFIRMED_BY_RULE
NEEDS_REVIEW
UNKNOWN
REJECTED
```

候选层和最终 Scene Object 必须分离，避免算法一旦猜测就直接成为业务事实。

---

## 7. 单位、坐标与平面性契约（新增 P0）

### 7.1 UnitResolver

当前 `INSUNITS=Unitless` 时直接按毫米处理只适合早期房间解析。家具尺寸、门宽、墙厚将直接参与商品过滤，因此 V1.2.1 明确区分：

> **“为了统一计算而采用的毫米坐标”** 与 **“已经确认的真实物理毫米”** 不是一回事。

必须引入完整的 `UnitResolution`：

```json
{
  "rawUnit": "UNITLESS",
  "declaredUnit": null,
  "canonicalUnit": "mm",
  "scaleToMm": 1.0,
  "scaleSource": "ASSUMED",
  "scaleAssumed": true,
  "confirmed": false,
  "confidence": "low"
}
```

字段语义：

- `rawUnit`：CAD 原始单位状态，例如 `MILLIMETER / CENTIMETER / INCH / UNITLESS`；
- `declaredUnit`：文件头明确声明的单位，没有则为 `null`；
- `canonicalUnit`：Parser 内部统一计算坐标单位，固定为 `mm`；
- `scaleToMm`：**本次实际采用的**原始坐标到 Canonical mm 的换算系数；
- `scaleSource`：换算系数来自哪里；
- `scaleAssumed=true`：表示当前毫米值只是按某种假设归一化，并未证明真实物理单位就是毫米；
- `confirmed`：该单位是否已经可以作为真实尺寸事实使用。

`scaleSource`：

```text
HEADER
SOURCE_PROFILE
DIMENSION_INFERENCE
MANUAL
ASSUMED
```

建议增加单位状态：

```text
CONFIRMED
INFERRED
ASSUMED
INVALID
```

例如 `INSUNITS=Unitless` 且暂按 1.0 处理时：

```text
canonicalUnit = mm
scaleToMm = 1.0
scaleSource = ASSUMED
confirmed = false
```

这意味着坐标仍可以进入统一几何计算，但：

```text
geometryWidthMm = 2850
```

只能理解为：

> “按当前假定 scale 归一化后得到 2850 的 Canonical 数值”。

不能直接向业务解释成：

> “家具真实宽度已确认是 2850mm”。

因此当 `unitResolution.confirmed=false` 时：

- `geometryWidthMm / geometryDepthMm` 可以保存并展示，但前端必须显示“单位待确认”；
- `effectiveWidthMm / effectiveDepthMm` 默认不得作为硬尺寸事实，可保持 `null`，或仅在人工明确确认单位后生成；
- `Scene Fact Gate.usableForHardConstraint=false`；
- 不得进入严格商品尺寸过滤；
- 不得向 Marketing Agent 输出“确定放得下/确定放不下”的结论；
- 人工确认单位后必须根据**比例是否改变**区分两种情况：

### 7.1.1 仅确认“当前比例正确”

例如：

```text
原状态：
rawUnit = UNITLESS
scaleToMm = 1.0
scaleAssumed = true
confirmed = false

人工确认：
这张图确实就是毫米
```

此时：

```text
scaleToMm 仍然 = 1.0
```

几何坐标没有发生变化。

允许：

- 不重新执行完整几何解析；
- 仅产生新的 Unit Confirmation / Confirmation Revision；
- 更新 Fact Gate，使相关尺寸字段从“假定尺度”提升为“已确认尺度”；
- 保留原 Analysis Revision。

### 7.1.2 单位确认导致 `scaleToMm` 改变

例如：

```text
原先假定：
scaleToMm = 1.0

人工确认：
原图实际单位为 cm

新的：
scaleToMm = 10.0
```

这会改变：

```text
Room Polygon
Wall Geometry
Door / Window
Furniture Footprint
DIMENSION Measurement
drawing/scene/preview bounds
所有 width/depth/area
对象之间的距离
```

因此必须：

```text
创建新的 Analysis Revision
↓
使用新的 UnitResolution
↓
从 Document Normalize / Canonical IR 开始重新计算
↓
重新执行所有受尺度影响的下游 Stage
↓
产生新的 Initial Scene
```

禁止只修改：

```text
widthMm / depthMm
```

或仅更新 Confirmation Revision。

旧 Analysis Revision 必须保留。

因此规则明确为：

```text
scaleToMm unchanged
→ Confirmation Revision

scaleToMm changed
→ New Analysis Revision + Reparse
```

任何单位修正都不得静默覆盖旧结果。

`UNIT_UNCONFIRMED` 本身不代表整次解析失败，它通常对应 `PARTIAL` 或 `NEEDS_REVIEW`。

### 7.2 Coordinate Contract

正式定义统一坐标契约：

```text
Canonical Scene Coordinate
单位：mm
轴：X/Y
角度：degree
```

所有 BLOCK 需要正确处理：

```text
BasePoint
InsertPoint
Rotation
ScaleX / ScaleY
Mirror
Nested Transform
```

同时保留原始坐标及变换链用于调试。

### 7.3 PlanarityCheck

不能默认所有 3D/高程实体都可以静默压平。

建议：

```text
Z range <= tolerance
→ PLANAR

Z range > tolerance
→ NON_PLANAR_GEOMETRY
```

对于明显存在多高程或立面/三维信息的目标区域：

```text
MULTI_ELEVATION_GEOMETRY
```

输出质量问题并限制后续二维空间事实使用。

---

## 8. Target Scene Selector（新增 P0）

真实室内 CAD 常见：

- 多套户型混排；
- 平面 + 立面 + 节点 + 图框混排；
- 多个 ModelSpace 副本；
- PaperSpace / Layout；
- 同户型多个设计阶段。

因此目标区域不能继续只是 Parser 内部临时计算结果，而应该成为一级对象：

```text
CadTargetScene

targetId
sourceSpace
bounds
selectionSource
selectionConfidence
selectedAt
selectedBy
```

`selectionSource`：

```text
AUTO
MANUAL
SOURCE_PROFILE
```

推荐流程：

```text
CAD
↓
TargetSceneCandidates[]
↓
Parser 自动推荐目标 Scene
↓
用户可切换
↓
为新的 Analysis Revision 重跑下游阶段
```

人工切换 Target Scene **不重新上传 DWG**，但必须产生新的 Analysis Revision，不能直接覆盖原自动结果。

### 8.1 Target Scene Candidate 读取契约

前端在提交 `targetId` 前，必须能够读取 Parser 已发现的目标场景候选。

建议在分析详情中直接返回：

```json
{
  "analysisId": "A-001",
  "analysisRevision": 3,
  "targetScene": {
    "targetId": "SCENE-001",
    "selectionSource": "AUTO"
  },
  "targetSceneCandidates": [
    {
      "targetId": "SCENE-001",
      "sourceSpace": "MODEL_SPACE",
      "bounds": {
        "minX": 0,
        "minY": 0,
        "maxX": 13630,
        "maxY": 14020
      },
      "preview": {
        "format": "png",
        "previewImageId": "IMG-SCENE-001",
        "previewUrl": "/api/v1/images/IMG-SCENE-001",
        "bounds": {
          "minX": 0,
          "minY": 0,
          "maxX": 13630,
          "maxY": 14020
        }
      },
      "score": 0.93,
      "reasons": [
        "ROOM_LABEL_DENSITY",
        "AREA_ANNOTATION_DENSITY"
      ],
      "recommended": true
    }
  ]
}
```

如果候选预览体积较大，也可拆成独立接口：

```http
GET /api/v1/floor-plan/{analysisId}/target-scenes?analysisRevision=3
```

返回候选列表、范围、来源空间、推荐分、识别证据和预览引用。`previewImageId` 必须属于本分析及对应的 Analysis Revision；图片读取权限在现有 `ImageService` 归属校验上扩展到候选预览，不能只因图片 ID 存在就放行。`previewUrl` 是后端生成的可访问地址，不能由前端猜测对象存储路径；候选无预览时显式返回 `null` 并仍允许查看范围和证据。

候选读取必须满足：

- `targetId` 在同一 `analysisRevision` 内稳定；
- 前端不得自行根据 bounds 生成新的 `targetId`；
- `PUT target-scene` 只接受当前 Revision 已返回的合法候选 ID；
- 如果用户需要手工框选一个全新区域，则使用独立的 `MANUAL_REGION` 流程生成新的 Target Scene Candidate，再切换到该 Candidate。

### 8.2 Target Scene 切换接口

业务层建议提供：

```http
PUT /api/v1/floor-plan/{analysisId}/target-scene
```

请求：

```json
{
  "baseAnalysisRevision": 3,
  "targetId": "SCENE-002"
}
```

语义：

1. 校验当前用户对 `analysisId` 的归属与编辑权限；
2. 校验 `baseAnalysisRevision`，不一致返回 `409 CONFLICT`；
3. 从原始 CAD 文件引用重新读取，不要求客户端重新上传；
4. 创建新的 `analysisRevision = base + 1`；
5. 记录：

```text
parentAnalysisRevision
trigger = TARGET_SCENE_SWITCH
targetId
parserVersion
parserConfigHash
sourceProfileVersion
```

6. 新 Revision 的自动解析快照不可变；
7. 旧 Revision 不删除、不覆盖。

如果切换需要异步执行，接口返回：

```http
202 Accepted
```

例如：

```json
{
  "analysisId": "A-001",
  "analysisRevision": 4,
  "jobId": "JOB-889",
  "status": "PROCESSING"
}
```

### 8.3 旧确认结果如何处理

切换 Target Scene 后，旧确认结果必须保留为历史事实：

```text
Revision 3
Initial Scene
Confirmed Scene
Confirmation Revision
```

全部继续可追溯。

新的 Revision 4：

```text
confirmationStatus = UNCONFIRMED
```

不得直接把 Revision 3 的家具确认结果自动当成 Revision 4 的业务事实。

如 `sourceHandle / insertPath / sourceIdentityKey` 能稳定匹配，可产生：

```text
matchedFromObjectId
```

作为“建议继承”的证据，但必须经过人工确认后才能成为新 Revision 的 Current Confirmed Object。

### 8.4 工作 Revision、对象落库与当前业务事实分离

V1.2.3 明确：

> **工作 Revision 不是只存在于 `raw_result` 或内存中的临时结果，而是一个可以独立编辑、独立确认、但尚未生效的完整 Scene 版本。**

例如：

```text
Revision 3
状态：ACTIVE_CONFIRMED

Revision 4
状态：EDITING
```

此时必须允许：

```text
Revision 4 Room 编辑
Revision 4 Furniture 编辑
Revision 4 Dimension Association 编辑
```

但这些修改全部只写入：

```text
analysis_id = A-001
analysis_revision = 4
```

对应的数据。

不得修改 Revision 3 的任何对象。

#### 8.4.1 所有 Scene Object 必须带 Analysis Revision

以下对象表后续都必须具有：

```text
analysis_id
analysis_revision
object_id
```

至少包括：

```text
floor_plan_room
floor_plan_furniture
floor_plan_wall
floor_plan_door
floor_plan_window
floor_plan_dimension_relation
```

对象逻辑键建议理解为：

```text
(analysis_id, analysis_revision, object_id)
```

即使 `object_id` 当前采用全局唯一 ID，也不能省略 `analysis_revision` 作为查询和写入条件。

任何：

```text
UPDATE
DELETE
SELECT FOR EDIT
```

都必须同时限定：

```text
analysis_id
+
analysis_revision
```

禁止继续仅使用：

```text
WHERE analysis_id = ?
```

更新“当前所有房间/家具”。

#### 8.4.2 Working Revision 的人工编辑存在哪里

工作版的人工编辑直接存入**该 Revision 自己的对象记录**。

例如：

```text
A-001 / Revision 3
Room R-01
Furniture F-01

A-001 / Revision 4
Room R-21
Furniture F-31
```

Revision 4 的人工修改：

```text
新增家具
删除误检家具
拆分/合并
房间改名
房间重新归属
尺寸确认
```

全部写入 Revision 4。

不设计：

```text
一张“全局当前家具表”
+
另一张“工作区家具临时表”
```

这种双模型。

统一规则为：

> **Scene Object 表保存 revision-scoped object；哪一版对业务生效，由 `activeAnalysisRevision` 指针决定。**

这样 Working Scene 和 Active Scene 使用相同数据模型，只是生命周期状态不同。

#### 8.4.3 Analysis 头表只保存 Revision 指针

`floor_plan_analysis` 建议增加：

```text
active_analysis_revision
working_analysis_revision
latest_analysis_revision
```

语义：

```text
active_analysis_revision
= 当前业务正式使用的完整 Scene Revision

working_analysis_revision
= 当前用户正在编辑/确认的 Revision，可空

latest_analysis_revision
= 已创建的最大 Revision
```

例如：

```text
analysis_id = A-001

active_analysis_revision = 3
working_analysis_revision = 4
latest_analysis_revision = 4
```

表示：

```text
业务继续使用 Revision 3
用户正在校正 Revision 4
```

#### 8.4.4 增加 Scene Revision 头表

建议新增：

```text
floor_plan_scene_revision
```

至少包含：

```text
analysis_id
analysis_revision

parent_analysis_revision
trigger

target_scene_id

parse_status
revision_status

room_revision
furniture_revision
wall_revision
door_revision
window_revision

parser_version
parser_config_hash
source_profile_version
unit_resolution

raw_result                 # 本 Revision 的自动解析 JSON（不含预览 PNG base64）
preview_image_id           # 本 Revision 的规范预览
quality_issues             # 本 Revision 的质量问题
target_scene_candidates    # 本 Revision 发现的候选及预览引用
drawing_bounds
preview_bounds

created_by
created_at
ready_at
confirmed_at
activated_at
activated_by
```

`revision_status` 建议：

```text
PROCESSING
READY
EDITING
READY_TO_ACTIVATE
ACTIVE
FAILED
SUPERSEDED
```

其中：

- `PROCESSING`：正在重新解析；
- `READY`：自动结果已产生，尚未人工编辑；
- `EDITING`：存在人工修订；
- `READY_TO_ACTIVATE`：必要确认条件已满足；
- `ACTIVE`：当前整版业务事实；
- `FAILED`：该 Revision 解析失败；
- `SUPERSEDED`：历史已激活版本，被新版本替代，但继续可审计。

`raw_result`、预览和质量问题属于**该 Revision 的自动结果快照**。解析任务可在 `PROCESSING` 阶段组装结果；转为 `READY` 时一次性写入并冻结，此后人工确认只修改该 Revision 的对象记录和确认元数据，不修改自动快照。预览二进制存 `image_assets`/文件存储，Revision 仅保存 `preview_image_id`。候选预览同样保存受归属校验的图片引用。`floor_plan_analysis.raw_result/preview_image_id/quality_issues` 只保留为旧接口兼容字段；新 CAD Scene Revision 不向这些分析级字段写入“最新结果”，防止它们覆盖历史或成为第二套事实源。

#### 8.4.5 Active Scene 查询规则

业务层、Marketing Agent 和正式报价链路读取 Scene 时，默认只能读取：

```text
floor_plan_analysis.active_analysis_revision
```

例如：

```sql
SELECT *
FROM floor_plan_furniture
WHERE analysis_id = :analysisId
  AND analysis_revision = :activeAnalysisRevision
  AND deleted_at IS NULL;
```

不得：

```text
取每张对象表各自“最新一条”
```

否则会出现：

```text
Room Revision 3
Furniture Revision 4
Door Revision 2
```

这种跨 Revision 拼接。

#### 8.4.6 激活后的写保护与锁顺序

Room、Furniture、Dimension Association 等编辑接口只接受 `floor_plan_analysis.working_analysis_revision` 指向的 Revision，且状态为 `READY / EDITING / READY_TO_ACTIVATE`。一次编辑开始时，先锁定 `floor_plan_analysis`，再锁定目标 `floor_plan_scene_revision`，重新校验工作指针、状态和对应对象的乐观版本，然后写入该 Revision 的对象及新对象版本；编辑 `READY_TO_ACTIVATE` 后状态回到 `EDITING`，须重新经过激活校验。`ACTIVE / SUPERSEDED / FAILED / PROCESSING` 一律不可原地编辑，过期页面提交返回 `409 CONFLICT`。

Scene Activation 也遵守**先锁 Analysis、再锁目标 Scene Revision** 的顺序，并在同一事务内校验所有 `expected*Revision` 后切换 active 指针。这样编辑与激活不能交错提交：先提交的编辑会使激活的 expected 版本失效；先完成的激活会使后到的编辑因工作指针或状态变化而被拒绝。

要修改已生效 Scene，先从 active Revision 创建 `trigger=MANUAL_EDIT` 的新工作 Revision：复制当前已确认对象作为编辑起点，复制原 Revision 的自动解析 JSON 快照与预览/质量问题引用，并记录父 Revision；这些新 Revision 快照写入后同样不可变。若仅修改确认值且单位、目标区域和几何不变，无需重新调用 Parser。新 Revision 经人工校正和显式 Activation 后才生效。历史 ACTIVE 对象不得被就地改写。

### 8.5 Scene 原子激活规则

Scene 生效必须是一个**显式、整版、原子事务**。

建议新增：

```http
POST /api/v1/floor-plan/{analysisId}/revisions/{analysisRevision}/activate
```

请求：

```json
{
  "baseActiveAnalysisRevision": 3,
  "expectedRoomRevision": 5,
  "expectedFurnitureRevision": 8
}
```

后续 Wall/Door/Window 进入人工编辑后，可继续增加对应 expected revision。

激活事务必须依次完成：

```text
1. 锁定 floor_plan_analysis，再锁定目标 floor_plan_scene_revision（与编辑接口顺序相同）
2. 校验 baseActiveAnalysisRevision
3. 校验目标 analysisRevision 存在
4. 校验目标是当前 working_analysis_revision，状态为 READY_TO_ACTIVATE，且不存在仍在执行的解析任务
5. 校验 Room/Furniture 等对象 Revision 未发生并发变化
6. 运行 Scene Activation Validator
7. 将旧 ACTIVE Revision → SUPERSEDED
8. 将目标 Revision → ACTIVE
9. 更新 floor_plan_analysis.active_analysis_revision
10. 清理/更新 working_analysis_revision
11. 更新 analysis.status = confirmed
12. 写 Audit Log
13. COMMIT
```

上述步骤必须在**同一数据库事务**中完成。

任何一步失败：

```text
ROLLBACK
```

不得出现只切换家具、没有切换房间的中间状态。

#### 8.5.1 Scene Activation Validator

P0-B 至少检查：

```text
目标 Revision parseStatus != FAILED
Target Scene 已确定
Unit 状态已达到当前业务允许级别
Room 确认完成
Furniture 确认状态满足 P0 要求
不存在未解决的版本冲突
对象均属于目标 analysisRevision
```

随着后续阶段增加：

```text
Wall
Door
Window
Dimension Association
```

再逐步增加相应激活条件。

注意：

> “某个能力尚未实现”与“该能力已实现但存在阻塞问题”必须区分。

例如 P0 阶段还没有完整 Window Object：

```text
不应因为 Window 未实现而禁止激活 P0 Scene。
```

激活条件必须根据当前：

```text
sceneSchemaVersion
+
capability contract
```

确定。

Room/Furniture 等必要对象完成确认后，服务层按当前 `sceneSchemaVersion + capability contract` 执行准备校验；通过时把工作 Revision 从 `READY/EDITING` 转为 `READY_TO_ACTIVATE`，未通过则保留可编辑状态并返回待处理项。激活接口再次在事务内运行 Validator，不能仅信任此前的状态。

#### 8.5.2 激活不是复制对象

推荐激活方式：

```text
切换 active_analysis_revision 指针
```

而不是：

```text
把 Revision 4 的对象复制覆盖到“当前表”
```

理由：

- 激活事务更小；
- 回滚容易；
- 历史 Scene 保留完整；
- 可以快速查看 Revision 3/4 差异；
- 不需要大量 UPDATE/COPY；
- 避免复制过程产生部分成功。

#### 8.5.3 激活后的历史版本

激活 Revision 4 后：

```text
active_analysis_revision = 4
working_analysis_revision = null
```

Revision 3：

```text
revision_status = SUPERSEDED
```

但 Revision 3 的：

```text
Rooms
Furniture
Confirmed Scene
Feedback
Diff
```

全部保留。

如果以后需要回退，也应该通过新的：

```text
Activation
```

操作显式切回历史 Revision，而不是删除 Revision 4。


### 8.6 前端如何取得异步结果

`PUT target-scene` 返回 `202 + analysisRevision + jobId` 后，前端使用统一分析查询接口：

```http
GET /api/v1/floor-plan/{analysisId}?analysisRevision=4
```

返回：

```text
PROCESSING
READY
FAILED
```

`READY` 后返回该 Revision 的新 `Initial Scene / Target Scene / qualityIssues`。

如果项目已有 SSE 事件通道，可以额外发送：

```text
CAD_ANALYSIS_REVISION_READY
```

用于减少轮询；但 `GET` 查询仍作为最终一致性的基础契约。

### 8.7 对象 ID 与跨 Revision 映射

`furnitureId / wallId / doorId / windowId` 首先保证**单个 Analysis Revision 内稳定**。

跨 Revision 不依赖顺序编号直接复用 ID，而使用：

```text
sourceIdentityKey
matchedFromObjectId
supersedesObjectId
```

建立可解释映射。

Target Scene 改变导致来源实体集合明显变化时，应允许：

```text
NO_MATCH
```

不能为了“ID 稳定”错误继承旧对象。

---

## 9. BLOCK / INSERT 为什么必须优先解决

当前代码的重要限制之一是：

```text
INSERT 暂未递归展开
```

只识别房间时还能通过 `HATCH / LINE / POLYLINE` 进行部分补偿，但家具大量以 BLOCK 形式存在。

例如：

```text
INSERT
 └── Sofa_Block
      ├── LINE
      ├── ARC
      ├── POLYLINE
      └── INSERT
           └── Cushion_Block
```

因此需要增加：

```text
BlockResolver
```

支持：

```text
INSERT
↓
BlockDefinition
↓
Nested INSERT
↓
Primitive Geometry
```

同时保留：

```text
blockName
blockHandle
parentBlock
rotation
scaleX
scaleY
positionX
positionY
transformMatrix
```

BLOCK 不仅影响家具，同时会影响墙、门、窗、标注和设备，因此属于基础能力。

`BlockResolver` 必须同时输出**块实例树**与供现有空间算法使用的扁平几何。每个 `INSERT` 节点保留独立实例身份，再递归读取块定义；展开后的每个图元仍保留 `sourceHandle`、`insertPath`（顶层至当前层的 INSERT 句柄序列）、`blockDefinitionName`、有效图层和变换信息。不能把块内图元展开后仅按空间距离重新拼装，因为相邻椅子可能因此被合为一件。

坐标变换按块基点、插入点、旋转、缩放逐级组合，覆盖嵌套、镜像与非等比缩放；块属性使用当前**实例**的属性值。块定义递归引用、缺失定义或展开数量超限时停止该分支并输出诊断，不能阻断其他家具和房间的解析。对于匿名/动态块，保留原始实例信息与可读名称，无法获得可靠语义时退回几何候选。

---

## 10. 家具识别方案

家具识别采用分层策略，而不是全部依赖 AI。

### 10.1 BLOCK 识别

优先读取：

```text
BlockName
BlockAttribute
```

例如：

```text
SOFA_3P
客厅沙发
DINING_TABLE
```

这是高可信语义来源。

识别对象以**家具语义对应的 INSERT 实例**为单位；同一个家具块定义被插入三次，应输出三个家具实例。如果顶层块代表整套户型、布局或家具组，需要下钻到其内部家具节点；如果父块本身是一件沙发，内部坐垫、五金等子块不得再单独计数。先排除门窗、卫浴洁具、灯具、尺寸标注、图框和设备等非家具块，再综合名称、属性、有效图层、几何和房间位置给类别打分。名称如 `BLOCK1` 或重复使用的通用块名不得视为高可信。组合家具块可先作为一个候选，人工确认界面允许拆分。

### 10.2 图层识别

例如：

```text
家具
活动家具
沙发
床
柜体
FURN
FURNITURE
```

但图层只能作为证据之一，不能作为唯一判断依据。

### 10.3 几何聚类

对于 EXPLODE 后的家具，通过 `FurnitureGeometryClusterer` 根据以下特征恢复 Furniture Candidate：

- 几何连通性
- 空间距离
- Layer
- 重复结构
- 外轮廓
- 尺寸范围
- 图元组合

实施顺序：先从目标户型中移除已归属墙/门/窗/标注及已归属 INSERT 的图元；再按图层和空间索引形成候选图元集；用线段连通、闭合轮廓、重复形状及尺寸范围生成候选；最后对相邻候选做拆分检查，并与 INSERT 候选去重。距离阈值只能按图纸单位和局部线宽/尺度配置，不能单独决定归属。图元跨房间、与墙重叠严重或两件家具无法拆分时，输出 `UNKNOWN`/待确认质量问题。

不得把“有图元”直接等同于“有家具”：每个候选都必须通过非家具排除规则，并记录纳入与排除的证据。候选可由人工新增、删除、拆分、合并。

### 10.4 Vision 语义补充

只有 CAD 本身无法明确家具类别时，才使用局部 Preview + Vision 补充类别语义。

Vision 可以判断：

```text
SOFA
BED
TABLE
CHAIR
CABINET
```

但不能决定家具真实尺寸。

局部裁图必须由家具候选的 CAD 毫米坐标映射到同一 `preview.bounds` 的像素坐标，保留裁图与候选 ID 的对应关系。Vision 只补类别证据，不创建没有 CAD 候选或人工确认支撑的确定家具实例；它返回的类别与 CAD 证据冲突时保留双方证据并交人工确认。

### 10.5 候选去重与未知类别

同一对象可能同时被块实例、炸开图元聚类和视觉裁图命中。优先按共享来源图元归并，再比较轮廓重叠和中心距离；归并后只输出一个实例并保留全部证据。类别、边界或数量拿不准时分别标记 `UNKNOWN_CATEGORY`、`UNCERTAIN_FOOTPRINT`、`POSSIBLE_SPLIT_OR_MERGE`，不能用一个笼统的 `confidence=low` 掩盖原因。

---

## 11. 家具尺寸识别

家具尺寸建议同时保留：

```text
Geometry Dimension
+
Annotated Dimension
```

不要只保存一个最终值。

先从家具几何得到平面占地尺寸与朝向，再尝试关联 DIMENSION。存在 DIMENSION 不代表它属于该家具：必须先确认标注的端点、方向、距离和候选对象关系，再读取测量值并与显示文字、几何值核对。关联不确定或数值冲突时，保留原始标注与冲突原因，不自动覆盖几何尺寸。

`geometryWidthMm/geometryDepthMm` 表示 CAD 符号的**平面占地外廓**，不必然等于商品档案中的标称宽深。弧形沙发、圆桌、组合柜、打开的门扇或抽屉以及带装饰线的块应显示量测口径，并允许人工调整有效尺寸。`effectiveWidthMm/effectiveDepthMm` 只有在来源明确或人工确认后才能用于严格的商品尺寸过滤。

---

## 12. DimensionAssociationEngine

当前逻辑偏向：

```text
DIMENSION
↓
房间尺寸校验
```

未来应调整为：

```text
DIMENSION
↓
DimensionAssociationEngine
↓
Object
```

例如：

```text
2850 → Sofa.Width
1050 → Sofa.Depth
900  → Door.Width
240  → Wall.Thickness
5200 → Room.Width
```

这也是下一阶段最核心的新能力之一。

关联采用候选评分：先过滤同目标户型、同局部区域和可比方向的对象，再评估尺寸延长线端点到家具轮廓/墙/门的距离、平行或垂直关系、标注图层与数值一致性；最高分与次高分不够分离时返回“未关联”，不强行指派。一个标注可记录多个候选及评分，但自动确认的宽/深关联只能有一个目标。房间尺寸校验改为消费已判定指向房间的标注，避免现有“家具尺寸”图层被房间优先占用。

---

## 13. Dimension 数据模型

当前仅有 `Measure / TextPos / Layer` 信息不足。

建议增加：

```text
DimensionEntity

handle
layer
dimensionType
measurement
definitionPoint1
definitionPoint2
extensionPoint1
extensionPoint2
dimensionLinePoint
textPoint
rotation
blockPath
displayText
measurementSource
unitScale
```

然后根据尺寸线、延长线和目标几何的位置关系进行关联。

`measurement` 是 CAD 实测值；`displayText` 是图上实际显示的文字，可能存在人工覆盖、前后缀和样式比例。两者不一致时保留原值并生成质量提示。不同 DIMENSION 子类型的定义点不一致，提取器应按类型建立统一的“测量轴 + 端点”语义；缺少必要端点的标注只能作为未关联原始标注。

---

## 14. Dimension Metric Role（新增）

仅完成：

```text
Dimension → Object
```

仍不足以支持真实家具尺寸解析。

同一家具附近的标注可能分别表示：

```text
2850 → 沙发总宽
300  → 扶手宽
1050 → 沙发总深
450  → 沙发与茶几间距
```

因此正式模型应升级为：

```text
Dimension
↓
Target Object
↓
Target Feature
↓
Metric Role
```

建议：

```json
{
  "dimensionId": "DIM-103",
  "targetObjectId": "F-001",
  "targetFeature": "FOOTPRINT",
  "metricRole": "OVERALL_WIDTH",
  "measurementMm": 2850,
  "associationScore": 0.96,
  "associationStatus": "AUTO_CONFIRMED"
}
```

第一阶段至少支持：

```text
OVERALL_WIDTH
OVERALL_DEPTH
DIAMETER
WALL_THICKNESS
OPENING_WIDTH
CLEARANCE
LOCAL_DETAIL
UNKNOWN
```

最高分与次高分不够分离时：

```text
associationStatus = NEEDS_REVIEW
```

不得强制映射为家具总宽/总深。

---

## 15. 家具几何尺寸与 OBB

没有 DIMENSION 时：

```text
Furniture Geometry
↓
OBB
↓
Width / Depth / Rotation
```

家具不能单纯使用 AABB。旋转家具的世界坐标 BBox 可能严重失真，因此需要 Oriented Bounding Box，或者基于 BLOCK 局部坐标计算实际尺寸。优先采用可靠块外轮廓的局部坐标尺寸；炸开家具可计算最小面积 OBB，但 OBB 只能描述外廓，不天然给出“正面”和宽深语义。正面不明时记录长轴/短轴和 `rotationDeg=null` 或候选朝向，由人工确认。

建议最终保留：

```json
{
  "geometryWidthMm": 2848,
  "geometryDepthMm": 1049,
  "annotatedWidthMm": 2850,
  "annotatedDepthMm": 1050,
  "effectiveWidthMm": 2850,
  "effectiveDepthMm": 1050,
  "dimensionSource": "cad_dimension",
  "dimensionConsistency": true
}
```

如果几何尺寸与标注尺寸冲突，生成 `FURNITURE_DIM_MISMATCH`，交给人工确认。

### 15.1 OBB 不是最终家具尺寸模型

OBB 只是通用外廓量测方法，不等于家具业务尺寸语义。

例如：

```text
圆桌
→ 更适合 Diameter

L 型沙发
→ OverallWidth + OverallDepth + ChaiseLength

组合柜
→ OverallWidth + SegmentDimensions
```

建议预留：

```text
footprintType

RECTANGLE
ROUND
L_SHAPE
IRREGULAR
COMPOSITE
```

以及：

```text
overallWidthMm
overallDepthMm
diameterMm
segmentDimensions[]
```

P1 可以只实现矩形/圆形的基础量测，但 Scene Schema 不应把所有家具永久锁死为 `width × depth`。

---

## 16. 家具高度

二维平面 CAD 通常能够获得 Width / Depth，但不能天然获得 Height。

高度只能来自：

- 立面图
- 剖面图
- BLOCK Attribute
- DIMENSION
- 明确文字
- 已关联产品
- 人工输入

否则：

```text
heightMm = null
```

不允许模型猜测。

---

## 17. 墙、门、窗结构化

家具解析如果比建筑结构解析更细，后续 `Spatial Constraint Model` 会失去可靠基础。因此 Wall / Door / Window 必须定义为完整 Scene Object，而不是仅作为房间 Polygon 的辅助几何。

### 17.1 Wall Object

建议 Wall 至少包含：

```text
wallId
axis
polygon
thicknessMm
startNode
endNode
faceA
faceB
adjacentRoomIds[]
openingIds[]
qualityFlags[]
```

其中：

- `axis`：墙体中心轴/主轴；
- `polygon`：墙体实体范围；
- `faceA/faceB`：面向两侧空间的墙面；
- `startNode/endNode`：进入 Wall Graph 的拓扑节点；
- `openingIds`：门、窗、洞口。

后续 `UsableWallSegment` 应基于**面向房间的 Wall Face** 计算，而不是直接使用整墙长度。

### 17.2 Door Object

Door 至少识别：

- 门洞位置；
- 门洞宽度；
- 门扇；
- 开启方向；
- 所属 Wall；
- 连接 Room；
- clearance / swing area；
- Dimension 证据。

### 17.3 Window Object

Window 至少识别：

- 所属 Wall；
- 平面位置；
- 宽度；
- 几何范围；
- Dimension 证据。

平面图没有窗高、窗台高度时保持 null，不允许推测。

### 17.4 Opening 作为统一抽象

推荐 Door / Window 之上增加：

```text
Opening
```

统一处理墙体缺口：

```text
DOOR
WINDOW
PASSAGE
UNKNOWN_OPENING
```

这样 Wall Graph、有效墙面和房间连通关系不需要分别为门窗写两套逻辑。

---

## 18. CAD Scene 数据模型

```text
CadScene

├── metadata
├── rooms[]
├── walls[]
├── doors[]
├── windows[]
├── furniture[]
├── dimensions[]
├── relations[]
├── qualityIssues[]
├── parseStatus
└── capabilities
```

`parseStatus` 不再只有 true/false，建议：

```text
SUCCESS
PARTIAL
NEEDS_REVIEW
FAILED
```

同时返回能力级状态：

```json
{
  "capabilities": {
    "rooms": "SUCCESS",
    "walls": "PARTIAL",
    "doors": "PARTIAL",
    "windows": "NEEDS_REVIEW",
    "furniture": "PARTIAL",
    "dimensions": "PARTIAL"
  }
}
```

这样“文件成功打开”与“家具已经可靠识别”不会混为一谈。

### 18.1 `parseStatus` 与旧 `success` / HTTP 状态兼容映射

当前 Java 适配链路仍以 `success=false` 判断整次解析失败，.NET `/parse` 也根据 `success` 返回 `200/422`。因此 V1.2.1 不直接废弃 `success`，而定义如下兼容契约：

| parseStatus | success | HTTP | 是否保留自动结果 | 前端行为 |
|---|---:|---:|---|---|
| `SUCCESS` | `true` | 200 | 是 | 可进入确认页 |
| `PARTIAL` | `true` | 200 | 是 | 进入确认页，展示缺失能力/警告 |
| `NEEDS_REVIEW` | `true` | 200 | 是 | 必须进入人工确认，不允许直接作为硬约束事实 |
| `FAILED` | `false` | 422 | 仅保存失败元数据/诊断，不生成当前 Scene 对象事实 | 展示解析失败 |
| 请求本身非法 | 无 | 400 | 否 | 请求错误 |

因此：

> **只有 `FAILED` 才保持旧语义中的 `success=false`。**

这样现有 Java Adapter 在升级前仍不会把 `PARTIAL / NEEDS_REVIEW` 当成异常抛出；升级后的 Java DTO 再显式读取 `parseStatus` 与 `capabilities`。

### 18.2 哪些情况属于部分成功

以下情况原则上不应把整次解析判为 `FAILED`：

```text
UNIT_UNCONFIRMED
XREF_UNRESOLVED（但仍有可用主图几何）
部分门窗识别失败
家具全部/部分 UNKNOWN
部分 DIMENSION 未关联
存在待人工选择的候选对象
部分 Unsupported Entity
```

根据影响范围进入：

```text
PARTIAL
或
NEEDS_REVIEW
```

### 18.3 哪些情况才属于 FAILED

典型情况：

```text
CAD 文件损坏或完全无法读取
格式无法打开且没有任何可恢复数据
目标 Scene 无法形成且没有可供人工选择的有效候选
解析过程中触发不可恢复异常
资源限制触发后无法产生任何可用 Initial Scene
```

如果达到资源上限但已经得到可展示的部分 Scene，可根据结果完整性返回 `PARTIAL / NEEDS_REVIEW`，并保留对应 `qualityIssues`。

### 18.4 非 FAILED 结果如何落库

对于：

```text
SUCCESS
PARTIAL
NEEDS_REVIEW
```

都保存：

```text
Raw Parse Snapshot
parser/version metadata
Target Scene
qualityIssues
capabilities
Initial Scene 中已经产生的对象
```

但只有通过 Fact Gate / Human Confirmation 的对象和字段才进入当前业务事实。

`FAILED` 仍应保存：

```text
analysis metadata
inputHash
parserVersion
errorCode
errorMessage
diagnostics
```

用于问题追踪，但不创建新的 Current Confirmed Object。


家具对象建议至少包含：

```json
{
  "furnitureId": "F-7A3",
  "sourceKind": "INSERT",
  "sourceHandles": ["7A3"],
  "insertPath": ["7A3"],
  "category": "SOFA",
  "semanticLabel": "三人沙发",
  "roomId": "ROOM-LIVING",
  "blockName": "SOFA_3P",
  "position": {"x": 82560, "y": 63420},
  "rotationDeg": 90,
  "footprintPolygon": [[82035.5, 61996], [83084.5, 61996], [83084.5, 64844], [82035.5, 64844], [82035.5, 61996]],
  "geometryWidthMm": 2848,
  "geometryDepthMm": 1049,
  "annotatedWidthMm": 2850,
  "annotatedDepthMm": 1050,
  "effectiveWidthMm": 2850,
  "effectiveDepthMm": 1050,
  "heightMm": null,
  "dimensionSource": "cad_dimension",
  "semanticSource": "cad_block",
  "evidence": ["block_name", "layer", "geometry", "dimension"],
  "qualityFlags": [],
  "detectionConfidence": "high",
  "categoryConfidence": "high"
}
```

上例数值只演示字段形态。`position` 约定为占地轮廓内部锚点；所有几何与 `drawingBounds/preview.bounds` 使用同一毫米坐标系。`footprintPolygon` 是用于点选和空间碰撞的外轮廓，`geometryWidthMm/geometryDepthMm` 是其局部轴尺寸；尺寸冲突时 `effective*` 可以为 `null`。`heightMm` 默认 `null`。

`furnitureId` 是**分析批次内**的对象 ID，不能使用按输出顺序重编的编号作为持久关联键。INSERT 候选由目标图纸标识 + 家具节点的完整 `insertPath` 稳定生成；炸开候选由排序后的来源句柄集合生成。重新解析的图元集合变化时，保留旧确认结果并进入人工重匹配，不能用新序号覆盖旧对象。人工新增对象由服务端生成新 ID。`sourceHandles` 与 `insertPath` 只用于溯源，不暴露为 RSPU/RSKU 主键。

### 18.5 候选状态与类别词表

`category` 第一阶段固定为 `SOFA/BED/TABLE/CHAIR/CABINET/OTHER/UNKNOWN`，后续与 RSDP 品类字典建立显式映射；`OTHER` 表示确认是家具但不在当前词表，`UNKNOWN` 表示连家具类别也不确定。`sourceKind` 为 `INSERT/EXPLODED/MANUAL`。对象同时记录 `detectionConfidence` 与 `categoryConfidence`，避免“检测到对象”被误当成“类别正确”。质量问题至少包含 `UNRESOLVED_BLOCK`、`UNKNOWN_CATEGORY`、`POSSIBLE_SPLIT_OR_MERGE`、`UNCERTAIN_FOOTPRINT`、`FURNITURE_DIM_MISMATCH`。

### 18.6 解析器至业务系统的兼容契约

现有 `POST /parse` 的 `success/units/drawingBounds/preview/rooms/unnamedRegions/qualityIssues` 保持原语义；新增可选 `sceneVersion`、`parserVersion`、`furniture[]`、`dimensions[]`。旧 Java 消费者可忽略新字段，但新 Java DTO 必须显式接收并校验对象 ID、坐标有限值、轮廓闭合、尺寸非负和枚举值，不能因为 `@JsonIgnoreProperties` 忽略新结果。解析器对不支持的图纸返回可读质量问题和空候选，而不是伪造完整家具清单。

业务侧 `GET /api/v1/floor-plan/{analysisId}` 增加 `furniture[]` 与 `furnitureRevision`，每件包含解析值、确认值/状态与质量问题；房间原有字段不变。新增受现有分析归属校验保护的：

```http
PUT /api/v1/floor-plan/{analysisId}/furniture
```

家具编辑必须同时绑定**目标 Analysis Revision**和该 Revision 自己的 Furniture Revision：

```json
{
  "analysisRevision": 4,
  "baseFurnitureRevision": 7,
  "furniture": [...]
}
```

语义约束：

- `analysisRevision`：明确本次编辑的是哪一版自动解析结果；
- `baseFurnitureRevision`：该 `analysisRevision` 下当前家具确认列表的乐观锁版本；
- 禁止使用一个全局 `baseRevision` 在“activeConfirmedRevision=3、workingAnalysisRevision=4”并存时隐式决定写入目标；
- 服务端必须验证 `analysisId + analysisRevision` 存在且属于当前分析；
- 再校验该 Revision 下的 `baseFurnitureRevision`；
- 任一版本不一致返回 `409 CONFLICT`；
- 不允许把 Revision 4 的人工修正写入 Revision 3 的家具表；
- 每个 Analysis Revision 拥有独立的 `furnitureRevision` 序列。
- Revision 4 处于 `EDITING` 时，其家具修改直接持久化到 `(analysis_id=A-001, analysis_revision=4)`；
- Furniture PUT **不会**修改 `active_analysis_revision`；
- Furniture PUT **不会**把整个 `floor_plan_analysis.status` 改为 `confirmed`；
- 只有 Scene Activation 接口能够切换整版生效状态。


提交仍为**该 Analysis Revision 下的完整最终家具列表**：

```text
已有 ID → 更新
缺席 ID → 软删
无 ID → 新增
```

支持类别、轮廓、尺寸、房间校正，以及人工拆分/合并后的来源关系。

响应建议：

```json
{
  "analysisRevision": 4,
  "furnitureRevision": 8,
  "furniture": [...]
}
```

幂等规则：

- 相同 `analysisRevision`
- 相同 `baseFurnitureRevision`
- 相同请求内容

重复提交返回第一次结果，不产生重复家具。

响应体继续使用项目统一 `{code,message,data}`，接口落地时同步更新 `docs/02-architecture/04-API设计.md`。

### 18.7 Room 人工确认接口必须同步 Revision 化

现有 `confirmRooms()` 的“整体替换房间 + 立即将 analysis 标记 confirmed”语义，在进入 P0-B Revision 模型后必须调整。

推荐新接口：

```http
PUT /api/v1/floor-plan/{analysisId}/rooms
```

请求：

```json
{
  "analysisRevision": 4,
  "baseRoomRevision": 5,
  "rooms": [...]
}
```

它只负责：

```text
修改 Revision 4 的 Room Object
↓
roomRevision 5 → 6
↓
revision_status = EDITING
```

不得：

```text
analysis.status = confirmed
```

不得：

```text
active_analysis_revision = 4
```

不得因为“房间确认完成”就隐式确认家具、门窗或整个 Scene。

P0-B 完成后，现有：

```java
confirmRooms(String analysisId, FloorPlanConfirmRequest request)
```

应：

1. 改造成 Revision-aware Room 编辑服务；或
2. 作为旧接口 Compatibility Wrapper，只允许 legacy 数据路径使用，并内部明确绑定旧 Revision。

新 CAD Scene 链路不得继续调用其旧的“确认房间即确认整个分析”语义。


### 18.8 房间归属与视图展示

先按家具轮廓内部锚点确定房间，再以轮廓与房间的重叠比例复核；跨房间、落在开放空间边界或目标区域外时令 `roomId=null` 并提示人工确认。前端在现有 CAD 规范预览上逐件高亮、点选与编辑，支持新增、删除、拆分、合并；列表与画布使用同一 `furnitureId`。仅有参考图而没有规范预览时，不声称毫米坐标可精确点选。

---

## 19. Relation 数据模型

需要逐步建立：

```text
FURNITURE_IN_ROOM
DOOR_ON_WALL
WINDOW_ON_WALL
FURNITURE_NEAR_WALL
DIMENSION_OF_OBJECT
ROOM_ADJACENT_TO_ROOM
DOOR_CONNECTS_ROOM
```

关系模型是后续空间约束计算的重要基础。

### 坐标范围拆分

V1.2 不再要求 `drawingBounds == preview.bounds` 永久保持一致。

建议拆分：

```text
coordinateBounds
sceneBounds
previewBounds
```

其中：

- `coordinateBounds`：统一 Scene 坐标基准；
- `sceneBounds`：目标户型/目标 Scene 的事实范围；
- `previewBounds`：实际渲染范围，可为了展示 DIMENSION、家具标签等适当外扩。

三者使用同一毫米坐标系，但允许范围不同。

原因是家具 DIMENSION、说明文字经常位于房间 Polygon 外部，如果 Preview 永远裁到“房间外包络 + 200mm”，会丢失尺寸证据。

---

## 20. 与 Marketing Agent 的关系

以前：

```text
用户想要现代沙发
↓
查现代沙发
↓
推荐
```

以后：

```text
用户想要现代沙发
↓
CAD Scene
↓
客厅空间约束
↓
可摆放宽度
↓
产品尺寸过滤
↓
风格 / 预算 / 供应条件
↓
推荐
```

从“语义推荐”升级成“空间事实约束 + 语义推荐”。

---

## 21. CAD 原方案反向选品

识别 CAD 家具后，可以实现：

```text
设计师 CAD
↓
识别已有家具
↓
Sofa 2850 × 1050
↓
家具外观 / 类别
↓
RSDP 商品检索
↓
尺寸过滤
↓
视觉相似检索
↓
供应链条件过滤
↓
替代商品
```

CAD 提供空间事实，商品系统提供商品事实，模型负责语义理解，三者继续保持分离。

---

## 22. 推荐新增模块

```text
BlockResolver.cs
EntityClassifier.cs
FurnitureExtractor.cs
FurnitureGeometryClusterer.cs
FurnitureGeometryMeasurer.cs
DoorExtractor.cs
WindowExtractor.cs
WallStructureExtractor.cs
RawDimensionExtractor.cs
DimensionAssociationEngine.cs
DimensionResolver.cs
RelationBuilder.cs
CadSceneAssembler.cs

SceneActivationValidator.cs
```

业务 Server 侧建议新增：

```text
FloorPlanRevisionService
FloorPlanSceneActivationService
FloorPlanRoomRevisionService
FloorPlanFurnitureRevisionService
```

其中只有：

```text
FloorPlanSceneActivationService
```

拥有切换 `active_analysis_revision` 和写入整体 `confirmed` 状态的权限。

现有模块继续复用：

```text
CadDocumentLoader
EntityExtractor
WallExtractor
RoomPolygonizer
SpaceAligner
```

---

## 23. 现有“家具尺寸图层”规则调整

当前代码中，“家具尺寸”图层会优先参与房间尺寸验证。

随着家具识别能力加入以后，该规则需要调整。

未来：

```text
Layer = 家具尺寸
```

只能表示：

> 这个尺寸更可能与家具相关。

最终关联必须根据：

```text
Layer
+
Dimension Geometry
+
Object Geometry
+
空间位置
+
方向
```

共同决定。

---

## 24. XREF、实体支持矩阵与资源安全（新增）

### 24.1 XREF

第一阶段不要求完整解析所有外部参照，但必须能够检测：

```text
XREF_PRESENT
XREF_UNRESOLVED
```

如果目标 Scene 的墙、门窗或家具依赖未提供 XREF：

```text
sceneStatus = NEEDS_REVIEW
```

前端必须提示：

> 当前 CAD 引用了未提供的外部图纸，场景对象可能不完整。

不能把“未加载 XREF”静默解释为“图中没有家具/门窗”。

### 24.2 Entity Support Matrix

建立正式支持矩阵：

| Entity | 支持级别 | 第一阶段策略 |
|---|---|---|
| LINE | FULL | 直接规范化 |
| LWPOLYLINE / POLYLINE | FULL | 保留弧段语义或离散 |
| ARC / CIRCLE / ELLIPSE | FULL | 规范化 Curve |
| HATCH | FULL/PARTIAL | 边界 + Pattern |
| TEXT / MTEXT | FULL | 文本 + 位置 |
| DIMENSION | FULL/PARTIAL | 按子类型提取 |
| INSERT | FULL | 实例树 + 展开几何 |
| SPLINE | PARTIAL | 曲线采样 + 质量标记 |
| MLINE | PARTIAL | 规范化或显式降级 |
| REGION | PARTIAL | 尽量提取边界 |
| XREF | DETECT | 首期检测与质量门 |
| 其他 | BBOX/UNSUPPORTED | 不静默丢失 |

不支持实体必须进入：

```text
unsupportedEntitySummary
```

而不是只记录一条模糊 diagnostics。

### 24.3 Parser Resource Guard

当前 `/parse` 为同步内存解析模式。加入 BLOCK 展开、家具聚类和拓扑计算后，需要从一开始设置资源上限：

```text
MaxBlockDepth
MaxExpandedEntities
MaxTotalPrimitives
MaxGeometryVertices
MaxParseTime
MaxPreviewPixels
```

并支持：

```text
CancellationToken
```

超限时输出：

```text
BLOCK_EXPANSION_LIMIT
ENTITY_LIMIT
GEOMETRY_COMPLEXITY_LIMIT
PARSER_TIMEOUT
```

不能让异常 CAD 导致 Parser 进程 OOM 或长时间占满 CPU。

---

## 25. CAD Benchmark 的重新定位

Benchmark 不应该一次性建立完成。

本项目不需要在开发初期人工准备 30～50 张 CAD 才能继续开发。

RSDP 更合理的方式是：

> 在真实使用过程中持续沉淀 CAD 样本。

流程：

```text
使用 CAD
↓
自动识别
↓
人工确认
↓
保存最终正确结果
↓
记录自动结果与正确结果之间的差异
↓
形成新的已确认 CAD Case
↓
后续 Parser 优化
↓
加入回归验证
```

因此 Benchmark 应该被理解成：

> 随生产使用不断增长的 CAD 已确认案例中，挑选出来的一组稳定回归案例。

而不是：

> 项目上线前必须人工准备的大规模测试数据集。

---

## 26. “越用越顺手”的实现方式

用户每处理一个新的 CAD：

```text
DWG
↓
Parser
↓
Initial Scene
↓
前端人工校正
↓
Confirmed Scene
```

系统同时保存：

```text
Auto Result
Confirmed Result
Recognition Diff
```

例如：

```text
自动：Block = UNKNOWN
人工：SOFA
```

或者：

```text
自动：Dimension 2850 → Room
人工：Dimension 2850 → Sofa
```

这些差异才是真正有价值的沉淀。

---

## 27. CAD 识别闭环

```text
                    CAD 上传
                       │
                       ▼
                  Parser 识别
                       │
                       ▼
                 Initial Scene
                       │
                       ▼
                  人工确认
                       │
                       ▼
                Confirmed Scene
                       │
             ┌─────────┴─────────┐
             ▼                   ▼
          业务使用            Scene Diff
                                 │
                                 ▼
                       Recognition Feedback
                                 │
               ┌─────────────────┼─────────────────┐
               ▼                 ▼                 ▼
          Block Mapping      Layer Profile      Error Case
               │                 │                 │
               └─────────────────┼─────────────────┘
                                 ▼
                         CAD Knowledge Base
                                 │
                                 ▼
                           Parser 优化
                                 │
                                 ▼
                         Regression Testing
```

这才是真正意义上的“越用越顺手”。

---

## 28. 需要沉淀的数据

### 28.1 Confirmed Scene

人工确认后的正确结构：

```text
rooms
walls
doors
windows
furniture
dimensions
relations
```

这是最重要的 Ground Truth。

### 28.2 Recognition Diff

记录 Parser 原结果和人工结果之间的差异。

例如：

```json
{
  "object": "F-001",
  "field": "category",
  "predicted": "UNKNOWN",
  "confirmed": "SOFA"
}
```

### 28.3 Block Dictionary

如果同一类 Block 多次被人工确认成 SOFA，可以沉淀为 Block 语义证据，并记录来源、置信度和使用次数。

### 28.4 Layer Profile

例如某批 CAD 经人工确认发现：

```text
A-FURN → Furniture
A-WALL → Wall
A-GLAZ → Window
DIM-FURN → Furniture Dimension
```

系统逐渐形成 LayerProfile，并记录 Entity 类型分布、HATCH 模式、Block 类型、来源设计单位等信息。

### 28.5 Source Profile

不同设计师或设计单位通常有自己的 CAD 习惯。

可逐渐形成：

```text
CAD Source Profile
```

识别来源后优先加载对应解析 Profile，再进入通用 Parser。

### 28.6 Error Case Library

典型异常，例如：

```text
Nested BLOCK 导致家具未识别
旋转家具尺寸计算错误
开放式厨房未识别
门被识别成家具
窗户图层异常
家具 EXPLODE
```

修复以后应沉淀为回归案例。

---

## 29. Case Library 与 Regression Benchmark 的区别

### CAD Case Library

所有经过确认、有价值的真实 CAD 案例，随着业务自然增长。

它属于：

> 生产沉淀数据。

### CAD Regression Benchmark

从 Case Library 中挑选出来的一小部分：

```text
典型
稳定
已确认
有代表性
```

用于每次代码修改自动跑测试。

正确关系：

```text
真实使用
↓
Case Library 持续增长
↓
挑选典型 Case
↓
Regression Benchmark
```

---

## 30. 初期不需要很多 CAD

项目现在完全可以用现有几张 CAD 开始：

```text
Case 001：无房间标签户型
Case 002：家具平面布局
Case 003：601-含尺寸标注.dwg
```

先形成几个 Confirmed Scene 即可。

后续每新增一个真实 CAD：

```text
识别
↓
确认
↓
沉淀
```

Case Library 自然增加。

不是每一个生产 CAD 都必须进入 Regression Benchmark。生产案例应先进入 Case Library，再经过脱敏、去重、代表性筛选后选择是否进入回归集。

---

## 31. 自动沉淀不等于自动学习

系统保存人工确认结果，并不代表 Parser 自动变强。

真正变强需要：

```text
Feedback
↓
统计
↓
发现规律
↓
更新 Profile / Dictionary / Algorithm
↓
Regression
↓
部署
```

第一阶段优先通过可解释规则和 Profile 演进。

### Profile 作用域与治理

“越用越顺手”不能等于“所有人工修正自动写进全局规则”。

Profile 必须区分作用域：

```text
Built-in Rule
        ↓
Global Profile
        ↓
Tenant Profile
        ↓
Source Profile
        ↓
Analysis Override
```

建议 Profile 至少包含：

```text
profileId
scope
scopeId
version
status
evidenceCount
createdBy
approvedBy
createdAt
```

`status`：

```text
DRAFT
APPROVED
DISABLED
```

某设计公司的 `A-FURN → FURNITURE` 只能优先作用于该 Source/Tenant，除非经过审核提升为 Global Profile。

所有 Profile 更新必须：

```text
可审计
可版本化
可回滚
可做 Regression
```

只有当 Case Library 累积到数百甚至数千份高质量 Confirmed Scene 后，再考虑训练：

```text
Furniture Classifier
Layer Classifier
Dimension Target Classifier
```

---

## 32. 当前 601 CAD 的定位

`601-含尺寸标注(1).dwg` 更适合作为当前真实开发过程中沉淀下来的一个 Confirmed CAD Case。

完成：

```text
房间确认
墙确认
门窗确认
家具确认
家具尺寸确认
Dimension 关联确认
```

后生成：

```text
601.confirmed-scene.json
```

由于它具备“家具 + 尺寸标注”特征，后续可进一步成为 Furniture Dimension Regression Case。

---

## 33. 建议增加的数据结构

建议未来对 CAD Analysis 增加：

```text
parser_result
confirmed_result
confirmation_status
parser_version
confirmed_at
confirmed_by
```

现有 `floor_plan_analysis.raw_result` 已留原始识别结果，`confirmed_rooms` 只保存房间确认，现有确认接口仅处理房间。新链路的自动家具快照保存在 `floor_plan_scene_revision.raw_result`，人工确认家具保存在对应 Revision 的 `floor_plan_furniture`；两者以 `analysis_id + analysis_revision + furniture_id` 对齐。确认版本、操作者和时间随每次提交记录。重新解析不能覆盖旧快照，人工确认内容也不能写回 CAD 原文件；是否当前生效由 active 指针决定。

同时生成：

```text
Result Diff
```

以后可以统计当前 Parser 最常识别错误的类型，让开发优先级由真实数据驱动。

可以增加类似：

```text
cad_analysis_feedback
```

保存：

```text
analysis_id
parser_version
object_type
object_id
field_name
predicted_value
confirmed_value
feedback_type
created_at
```

Diff 需要覆盖字段变更之外的 `ADD/DELETE/SPLIT/MERGE/REASSIGN_DIMENSION`；拆分和合并记录原对象 ID 与目标对象 ID 列表。反馈是可追溯的训练/规则改进材料，不能在下一次上传时未经审核自动写入全局 Block Dictionary。

---

## 34. 版本、回放与可重复解析（新增）

为了让 Feedback、Benchmark 和线上问题真正可追溯，同一份 CAD 的一次解析必须能够回答：

> 使用了哪个 Parser 版本、哪套参数、哪个 Source Profile、哪个 Scene Schema 得到这个结果？

建议保存：

```text
inputHash
sceneSchemaVersion
parserVersion
parserBuildCommit
parserConfigHash
sourceProfileVersion
visionModelVersion
analysisRevision
confirmationRevision
parsedAt
```

定义：

- `inputHash`：原始 CAD SHA-256；
- `sceneSchemaVersion`：Scene JSON 契约版本；
- `parserVersion`：Parser 发布版本；
- `parserBuildCommit`：对应代码 Commit；
- `parserConfigHash`：ParserOptions/规则配置 Hash；
- `sourceProfileVersion`：来源 Profile 版本；
- `analysisRevision`：同一分析的 Scene 版本序号；目标区域/单位变化会触发重新解析，`MANUAL_EDIT` 可复制已确认 Scene 而不重新调用 Parser；
- `confirmationRevision`：人工确认版本。

重新解析必须产生新的 Analysis Revision，不能覆盖旧自动结果。

---

## 35. 推荐开发优先级

V1.2.1 对阶段范围进行统一，避免“人工能力、自动识别能力、验收范围”互相跨阶段。

### P0-A：Parser 核心事实契约

先完成：

```text
Canonical IR
UnitResolver
Coordinate Contract
PlanarityCheck
TargetScene Candidate Read + Target Scene Revision Flow
Provenance / Stable ID
Version Metadata
parseStatus / success Compatibility
Scene Revision Persistence
Atomic Scene Activation
Active Revision Write Protection
Per-Revision Parse Snapshot + Legacy Backfill
Resource Guard
```

目标不是先增加识别功能，而是保证后续所有家具、门窗、DIMENSION 都建立在统一事实模型上。

### P0-B：第一张 CAD 垂直闭环

P0-B 只要求**以 INSERT 家具为主完成可人工校正的闭环**：

```text
人工逐件基准
↓
BlockResolver 基础展开
↓
INSERT Furniture Candidate
↓
Parser / Java DTO
↓
规范 Preview 逐件点选
↓
人工新增 / 删除 / 拆分 / 合并 / 修改（绑定 analysisRevision + baseFurnitureRevision）
↓
Initial / Confirmed / Diff
```

P0 的 `Split / Merge` 定义为：

> **人工编辑能力**，用于修正 Parser 输出的一件拆多件、两件合一件等错误。

P0 **不要求** Parser 自动判断炸开图元应该如何拆分/合并。

此阶段要求：

- 同一 Block 多实例分别输出；
- UNKNOWN 不丢失；
- Stable ID 在当前 Revision 内稳定；
- 人工可新增漏检家具；
- 人工可删除误检家具；
- 人工可拆分/合并候选；
- 重复提交幂等；
- 自动结果不可变；
- 确认结果可版本化；
- Target Scene 切换产生新 Analysis Revision；
- Room/Furniture 编辑均限定到该 Analysis Revision；
- 独立对象确认不得直接激活整个 Scene；
- 完整 Scene 通过 Activation Validator 后原子切换生效。

### P1：块家具与尺寸能力

P1 聚焦 **BLOCK / INSERT 家具的完整性与尺寸事实**：

```text
Nested INSERT
Rotation / Mirror / Non-uniform Scale
Furniture Geometry / OBB
Furniture Category
Dimension → ObjectFeature / MetricRole
Wall / Door / Window 最小对象化
Opening
XREF Detection
Entity Support Matrix
```

P1 仍以：

```text
INSERT / Nested INSERT 家具
```

作为自动识别主范围。

P1 不把“炸开家具的自动恢复”作为完成条件。

### P2：炸开家具自动识别与泛化

P2 才正式实现：

```text
Exploded Furniture Automatic Detection
FurnitureGeometryClusterer
自动候选拆分 / 合并判断
INSERT 与 EXPLODED 去重
Layer / Source Profile
Weak Boundary
Wall Graph
Relation Engine
Profile Governance
Regression Benchmark
```

此阶段的 `Split / Merge` 指：

> **Parser 自动识别中的 split/merge detection 与候选恢复能力**。

它与 P0 的“人工拆分/合并编辑能力”不是同一个范围。

### P3：空间约束与供应链

```text
UsableWallSegment
Door / Window Clearance
Circulation
PlaceableArea
Spatial Constraint Model
CAD Furniture → RSDP Product Candidate
```

不要把 `Spatial Constraint Model` 提前建立在不稳定的墙、门、窗几何上。

---

## 36. Weak Boundary

继续解决：

```text
开放厨房
客餐厅
过道
开放阳台
盥洗空间
```

等没有完整实体墙的问题。

边界来源分级：

```text
Strong Boundary：墙
Medium Boundary：门槛 / 挡水
Weak Boundary：铺贴 / 材质 / 吊顶
Semantic Boundary：文字 / Annotation
```

---

## 37. Scene Fact Gate（字段级依赖模型）

`CAD Scene` 不能直接成为 Marketing Agent 的硬约束事实，但 Fact Gate 也不能采用“场景里任何一项不完整就全部禁止”的一刀切方式。

V1.2.2 明确：

> **Fact Gate 按字段自身的依赖图判断，而不是按整个 Scene 的最低置信度判断。**

每个事实字段至少记录：

```text
factStatus
factSource
dependencies[]
confirmed
usableForHardConstraint
qualityFlags[]
```

例如一件已确认沙发的自身宽度：

```json
{
  "objectId": "F-001",
  "field": "effectiveWidthMm",
  "value": 2850,
  "factSource": "CAD_DIMENSION",
  "dependencies": [
    "UNIT_SCALE",
    "FURNITURE_IDENTITY",
    "DIMENSION_ASSOCIATION"
  ],
  "confirmed": true,
  "usableForHardConstraint": true
}
```

它**不依赖**：

```text
WINDOW_COMPLETENESS
DOOR_COMPLETENESS
WALL_USABLE_SEGMENT
```

因此即使窗户尚未完整识别，只要：

```text
单位可靠
家具对象可靠
尺寸关联可靠
```

该家具自身宽度仍然可以作为硬事实。

### 37.1 字段依赖示例

#### 家具自身宽度

```text
Furniture.effectiveWidthMm
依赖：
UNIT_SCALE
FURNITURE_IDENTITY
FOOTPRINT_OR_DIMENSION
DIMENSION_ASSOCIATION（如果来源是 DIMENSION）
```

不依赖门窗完整性。

#### 房间面积

```text
Room.areaM2
依赖：
UNIT_SCALE
ROOM_POLYGON
```

不依赖家具识别结果。

#### 门洞宽度

```text
Door.widthMm
依赖：
UNIT_SCALE
DOOR_IDENTITY
DOOR_GEOMETRY / DIMENSION_ASSOCIATION
```

#### 有效可摆墙面

```text
UsableWallSegment.lengthMm
依赖：
UNIT_SCALE
WALL_GEOMETRY
ROOM_WALL_RELATION
DOOR_COMPLETENESS
WINDOW_COMPLETENESS
OPENING_COMPLETENESS
```

因此门窗不完整时，应阻止：

```text
UsableWallSegment
PlaceableArea
部分 Circulation 结论
```

但不应连带阻止与门窗无关的家具自身宽度、房间面积等事实。

#### 家具“是否放得下”

```text
FurnitureFitDecision
```

依赖：

```text
产品尺寸
UNIT_SCALE
目标摆放区域
WALL / OPENING
DoorClearance
WindowClearance
Circulation Rules
PlaceableArea
```

这是高依赖度复合事实，任何关键依赖不可靠时：

```text
usableForHardConstraint = false
```

### 37.2 Fact Dependency Graph

建议在代码中不要写成大量散落的：

```text
if (windowIncomplete) ...
```

而是形成：

```text
FactKey
↓
RequiredDependencies[]
↓
Dependency Status
↓
FactStatus
```

例如：

```text
FURNITURE_WIDTH
→ UNIT_SCALE
→ FURNITURE_IDENTITY
→ DIMENSION_ASSOCIATION

USABLE_WALL_SEGMENT
→ UNIT_SCALE
→ WALL_GEOMETRY
→ DOOR_COMPLETENESS
→ WINDOW_COMPLETENESS
→ OPENING_COMPLETENESS
```

这样以后新增：

```text
CEILING_HEIGHT
SOCKET_LOCATION
TV_WALL
```

可以继续扩展依赖关系，而不是重新改整套判断代码。

### 37.3 单位与 Fact Gate

当：

```text
canonicalUnit = mm
但
unitResolution.confirmed = false
```

时，`geometryWidthMm` 等字段虽然处于 Canonical mm 坐标系中，也只是**按当前 scale 假设归一化后的数值**。

因此凡依赖：

```text
UNIT_SCALE
```

的硬尺寸事实：

```text
usableForHardConstraint = false
```

但诸如：

```text
同一 Scene 内相对左右关系
Furniture in Room
对象相交/不相交
```

如果其判断不依赖真实物理尺度，可以继续作为相对几何事实使用。

### 37.4 场景级状态与字段级 Fact Gate 的关系

`parseStatus = PARTIAL / NEEDS_REVIEW` 不代表所有字段都不可用。

例如：

```text
parseStatus = NEEDS_REVIEW

Furniture.width = HARD_FACT
Room.area = HARD_FACT
Window completeness = UNKNOWN
UsableWallSegment = NOT_HARD_FACT
```

因此下游必须读取字段级 Fact Gate，而不能只根据整个 Scene 的 `parseStatus` 决定所有数据是否可用。

---

## 38. 有效墙面与 Spatial Constraint Model

Marketing Agent 真正需要的不是 Wall Length，而是 UsableWallSegment。

例如：

```text
墙 5000
窗户占 1800
门洞占 900

最终连续可用墙段：
1700
600
```

后续建立：

```text
RoomConstraint
UsableWall
DoorClearance
WindowClearance
FurnitureFootprint
CirculationArea
PlaceableArea
```

真正解决：

```text
这个家具实际上能不能放进去？
```

---

## 39. 测试指标

Case Library 和 Regression Benchmark 成熟以后，可以逐渐统计：

### Room

```text
Room Recall
Room Precision
Polygon IoU
Area Error
Dimension Error
```

### Furniture

```text
Furniture Instance Precision / Recall（逐件匹配，不按图元数）
Furniture Count Error（每张图漏检数、误检数）
Footprint IoU（候选外廓与人工确认外廓）
Category Accuracy（只在正确检测且已确认类别的实例上统计）
Unknown Rate（含被人工确认后可分类的 UNKNOWN 比例）
Width Error
Depth Error
Rotation Error
Room Association Accuracy
Split / Merge Error（两件合一件、一件拆多件）
```

匹配评估应先按来源实例与外廓重叠做一对一配对，再分别计算检测、类别和尺寸指标，避免把一件沙发拆成多个候选后仍得到好看的类别准确率。无家具图纸也纳入回归，以验证非家具误检。启动阶段用现有少量真实 CAD 加人工确认结果作为开发基线；扩大适用来源后再设跨来源量化上线阈值，不能用单张图的结果承诺“可识别所有 CAD 家具”。

### Architecture

```text
Wall Accuracy
Door Recall
Door Width Error
Window Recall
Window Width Error
```

### Dimension

```text
Dimension Extraction Accuracy
Dimension Association Accuracy
```

这些指标从真实 Confirmed Scene 中自然计算，而不是另外重新人工做一套数据。

### Benchmark 不能替代算法单元测试

除真实 CAD Regression Case 外，Parser 必须针对核心算法维护小型确定性测试：

```text
Nested Block Transform
Mirror / Non-uniform Scale
Unit Resolution
Planarity
Dimension Endpoint
Dimension Ambiguity
OBB
Stable ID
Split / Merge
XREF Detection
Target Scene Selection
Resource Limits
```

这些测试不依赖生产 CAD，可以使用人工构造的最小 DWG/DXF 或纯几何对象，确保算法行为可预测。

---

## 40. 推荐实际开发过程

```text
确认一张含家具的目标 CAD 与目标区域
↓
逐件标出人工基准
↓
BlockResolver + 家具实例候选
↓
解析器/Java/前端结果契约
↓
预览逐件点选与人工校正
↓
保存 Initial Scene / Confirmed Scene / Diff
↓
炸开图元候选、尺寸关联和更多 CAD 回归

真实 CAD 继续进入
↓
Case Library 自然增长
↓
不断发现新异常
↓
Parser 修复
↓
代表案例进入 Regression Benchmark
```

业务使用和解析能力升级应该同时发生，而不是先准备大量 CAD，再开发，最后上线。

---

## 41. 数据库长期建议

现有房间数据继续保留。

第一阶段不再把 `floor_plan_furniture` 定义为“只保存当前生效对象”，而是定义为：

> **按 Analysis Revision 保存的 Scene Object 表。**

建议：

```text
floor_plan_room
floor_plan_furniture
```

在 P0 即增加：

```text
analysis_id
analysis_revision
object_id

object_revision
confirmation_status

created_at
updated_at
deleted_at
```

查询/写入索引至少覆盖：

```text
(analysis_id, analysis_revision)
(analysis_id, analysis_revision, object_id)
```

如果 `object_id` 全局唯一，仍然必须把 `analysis_revision` 作为业务约束和 SQL 条件，防止跨 Revision 误更新。

同时新增：

```text
floor_plan_scene_revision
```

负责保存：

```text
analysis_id
analysis_revision
parent_analysis_revision
trigger
target_scene_id
parse_status
revision_status

room_revision
furniture_revision

parser_version
parser_config_hash
source_profile_version
unit_resolution

raw_result
preview_image_id
quality_issues
target_scene_candidates
drawing_bounds
preview_bounds

created_at
ready_at
confirmed_at
activated_at
activated_by
```

`floor_plan_analysis` 增加：

```text
active_analysis_revision
working_analysis_revision
latest_analysis_revision
```

逐版自动快照以 `floor_plan_scene_revision.raw_result` 为唯一存储位置，`preview_image_id` 指向已有图片存储，`quality_issues` 和 bounds 是本 Revision 的查询投影；它们不得在后续人工编辑时修改。原 `floor_plan_analysis.raw_result/preview_image_id/quality_issues/confirmed_rooms` 在迁移期仅服务旧接口与历史读取，新 Revision 不把结果回写到这些列。业务详情按指定 `analysisRevision` 读取对应快照；正式业务按 `active_analysis_revision` 读取。

因此 Working Revision 的人工编辑和 Active Revision 的正式业务数据**都在同一套 revision-scoped 对象表中**。

区别仅由：

```text
floor_plan_analysis.active_analysis_revision
```

决定。

不再设计“当前家具表覆盖旧版”的写法。


后续根据实际能力逐步增加：

```text
floor_plan_wall
floor_plan_door
floor_plan_window
```

另外增加 CAD 分析反馈能力：

```text
cad_analysis_feedback
```

以及必要的：

```text
parser_version
confirmed_scene
```

不建议一开始就建非常复杂的“CAD 知识库数据库”。

### 数据事实源约束

必须避免 `raw_result`、`confirmed_scene JSON`、`floor_plan_furniture` 同时成为可修改业务事实。

建议明确：

```text
Raw Parse Snapshot
→ 不可变自动结果

Revision-scoped Scene Object Tables
→ 保存每个 Analysis Revision 的 Room / Furniture / Wall / Door / Window

floor_plan_analysis.active_analysis_revision
→ 唯一决定当前业务正式 Scene

Confirmation Revision / Audit
→ 各 Revision 内的人工确认历史

Feedback Diff
→ 算法改进材料
```

其中：

- `floor_plan_scene_revision.raw_result` 在该 Revision 进入 `READY` 后不可修改；分析级旧 `raw_result` 保留原值；
- `floor_plan_room / furniture / wall / door / window` 保存各 Analysis Revision 的对象，不以“是否当前生效”决定是否存在；
- `confirmed_scene` 如保留，应作为版本化快照/导出，不作为第二套可编辑事实源；
- Feedback 只记录变化，不直接反向修改原始结果。


### 现有 `FloorPlanService.confirmRooms()` 的迁移要求

当前实现会：

```text
按 analysis_id 查询全部房间
↓
原地 update / soft delete
↓
analysis.status = confirmed
```

这与 V1.2.3 Revision 模型不兼容。

进入 P0-B 前必须完成以下迁移：

```text
现有：
room WHERE analysis_id = ?

改为：
room WHERE analysis_id = ?
       AND analysis_revision = ?
```

并把：

```text
confirmRooms()
```

从：

> “确认房间并确认整个分析”

调整为：

> “编辑某个 Analysis Revision 下的 Room 对象集合”。

最终 `STATUS_CONFIRMED` 只能由：

```text
SceneActivationService.activate(...)
```

统一写入。

Room/Furniture 任一独立编辑接口都不得直接修改整个 Analysis 的 confirmed 状态。

否则会产生：

```text
active Revision 3 Rooms
+
working Revision 4 Furniture
```

或者反过来的跨版本 Scene，必须从数据模型层禁止。

### 存量数据回填与切换

迁移时为**每条现有** `floor_plan_analysis` 建立 `analysis_revision=1` 的 `floor_plan_scene_revision`，并将其已有 `floor_plan_room` 行回填为 Revision 1；不能只回填 CAD 记录，否则同一房间表的非 CAD 行会失去合法 Revision。把当时可见的 `raw_result`、规范预览 ID、质量问题和 bounds 复制到 Revision 1 快照；历史若曾被旧重试覆盖，只能标记为 `LEGACY_IMPORTED` 的**当前可得快照**，不能伪造不存在的早期 Revision。

已确认分析：`active_analysis_revision=1`、`working_analysis_revision=null`；待确认分析：`active_analysis_revision=null`、`working_analysis_revision=1`；处理中/失败分析按旧任务状态建立 Revision 1，不凭空激活。所有分析的 `latest_analysis_revision` 从 1 开始。确认过的房间保持当前可见值，原 `confirmed_rooms` 作为迁移前记录留档。

新 CAD Scene 写路径切换前，先让读路径兼容 Revision 1 和旧字段；再切换解析、Room/Furniture 编辑与 Activation 写路径，最后移除“房间确认即整版 confirmed”的调用。已有异步任务在切换期间必须走旧兼容路径完成，或等待任务结束后再启用新写路径；不得让旧任务继续覆盖新 Revision 快照。迁移完成后回归检查历史详情、预览、房间数量与归属，以及 active/working 指针一致性。


优先把：

```text
解析结果
最终确认结果
差异
```

保存好。

---

## 42. 分阶段验收目标

### 42.1 P0 验收：INSERT 家具 + 人工校正闭环

在一张**已人工逐件标注**、主要家具可由 INSERT 定位的真实家具平面 CAD 上必须做到：

```text
每个可识别 INSERT 家具实例各输出一个候选
同块多次插入为多个对象
每个候选在规范预览中可单独高亮、点选，并与列表双向定位
能查看类别/UNKNOWN、来源证据、房间、轮廓、基础几何尺寸
漏检可人工新增
误检可人工删除
合并错误可人工拆分
拆分错误可人工合并
保存 Initial Scene、Confirmed Scene、ADD/DELETE/UPDATE/SPLIT/MERGE Diff
重开分析后确认值与当前 Revision 对象 ID 不丢失
重复提交不产生重复对象
Target Scene Candidate 可被前端读取并预览，targetId 来源明确
Target Scene 切换产生新 Analysis Revision，旧确认结果仍可追溯
家具编辑请求显式携带 analysisRevision + baseFurnitureRevision，不能跨 Revision 误写

Room 编辑请求显式携带 analysisRevision + baseRoomRevision
工作 Revision 的 Room/Furniture 修改不会改变 activeAnalysisRevision
Scene Activation 在同一事务内切换整版 Revision
Activation 前后不允许出现新版家具配旧版房间
仅 Scene Activation 能把 analysis 整体状态改为 confirmed
激活后旧页面提交编辑返回 409，不改变已生效对象
每个 Revision 的 raw_result/preview/qualityIssues 可独立读取，旧快照不被新解析覆盖
存量分析与房间回填 Revision 1 后历史详情仍可读取

```

P0 的拆分/合并只验收**人工编辑和 Diff 记录**，不验收炸开家具的自动 split/merge 推断。

### 42.2 P1 验收：块家具完整性与尺寸

P1 验收：

```text
Nested INSERT
旋转块
镜像块
非等比缩放块
家具 OBB / 局部坐标尺寸
DIMENSION → ObjectFeature / MetricRole
家具尺寸与房间尺寸同时存在时不串绑
门窗不误归为家具
单位未确认时，所有依赖 UNIT_SCALE 的字段由 Fact Gate 禁止硬尺寸过滤
单位确认若改变 scaleToMm，必须产生新 Analysis Revision 并重跑几何
单位确认若不改变 scaleToMm，允许只产生新的确认版本
```

P1 的自动家具识别范围仍以 `INSERT / Nested INSERT` 为主。

建议至少覆盖：

```text
两把相邻但独立 INSERT 的相同椅子
一个组合家具 Block
旋转/镜像家具
家具尺寸 + 房间尺寸同时存在
无家具图纸
```

### 42.3 P2 验收：炸开家具自动识别

P2 才要求对 EXPLODED 家具建立自动恢复能力。

回归案例至少覆盖：

```text
两把相邻已炸开椅子不能自动合成一件
一个已炸开家具不能被错误拆成多件
INSERT + EXPLODED 混合图
家具与墙线/门线重叠
相邻家具图元距离很近
自动 split / merge 后仍可人工纠正
```

并记录：

```text
Furniture Instance Precision / Recall
Furniture Count Error
Split / Merge Error
Footprint IoU
Unknown Rate
```

### 42.4 后续完整 CAD Scene 目标

完整 CAD Scene 后续输出：

```text
房间
墙
门
窗
家具
家具长宽/直径/组合尺寸
家具位置
家具旋转
家具所在房间
原始 DIMENSION
Dimension → ObjectFeature / MetricRole
```

确认以后系统保存：

```text
Initial Scene
+
Confirmed Scene
+
Scene Diff
```

完成：

```text
识别 → 校正 → 沉淀
```

闭环。

---

## 43. 最终架构定位

整个能力最终演进为：

```text
DWG
 ↓
CAD Scene Parser
 ↓
Initial CAD Scene
 ↓
Human Confirmation
 ↓
Confirmed CAD Scene
 ├──────────────→ Business
 │
 └──────────────→ Feedback
                     ↓
               CAD Knowledge
                     ↓
               Parser Evolution
```

然后业务侧：

```text
Confirmed CAD Scene
↓
Spatial Constraint Model
↓
Marketing Agent
↓
RSDP Product Retrieval
↓
整屋选品
```

---


## 44. Scene Revision 必须满足的不变量

进入 P0-B 后，以下约束应视为数据库和服务层共同保证的 **Invariant**：

```text
Invariant 1
一次业务 Scene 查询只能来自一个 analysisRevision。

Invariant 2
Room / Furniture / Wall / Door / Window 不允许跨 Revision 拼装。

Invariant 3
任何对象编辑必须显式指定 analysisRevision。

Invariant 4
对象自己的 optimistic revision 与 analysisRevision 是两个不同维度，不能混用。

Invariant 5
Working Revision 可以编辑但不自动生效。

Invariant 6
只有 Scene Activation 可以切换 activeAnalysisRevision。

Invariant 7
Scene Activation 必须是单事务原子操作。

Invariant 8
旧 Active Revision 在新版本成功激活之前始终保持可用。

Invariant 9
单独确认 Room 或 Furniture 不得把整个 Analysis 标记 confirmed。

Invariant 10
ACTIVE / SUPERSEDED / FAILED Revision 的对象不可原地编辑；修改生效版须派生新的工作 Revision。

Invariant 11
历史 Revision 不删除、不覆盖，可审计、可比较、可回放。

Invariant 12
每个 Revision 拥有自己的不可变自动解析快照、预览与质量问题；旧分析级 raw_result 不承担多版本存储。
```

这些不变量应直接进入后续数据库 Migration、Service 单元测试和 API 集成测试。

---

## 45. 最终结论

当前技术路线：

```text
ACadSharp
+
NetTopologySuite
+
独立 rsdp-cad-parser
+
Java Adapter
+
人工确认
```

继续保持。

下一阶段重点：

```text
1. Canonical IR、单位/坐标、Target Scene Candidate/Revision Flow、Provenance 与版本契约
2. parseStatus / success / HTTP / Java Adapter 兼容契约
3. P0：Revision-scoped Room/Furniture 落库 + INSERT 家具候选 + 预览点选 + 基于对象 Revision 的人工校正
4. P0：Scene Activation Validator + active/working Revision 原子切换；P1 再进入 Nested/旋转/镜像 BLOCK 家具 + OBB + Dimension → ObjectFeature / MetricRole
5. P1：Wall / Door / Window / Opening 最小对象化 + XREF / Unsupported Entity / Resource Guard
6. P2：EXPLODED 家具自动恢复 + 自动 split/merge + Profile / Regression
7. 字段依赖型 Fact Gate、Relation Engine 与 Spatial Constraint Model
8. Continuous CAD Feedback + Profile Governance + 供应链候选匹配
```

Benchmark 不再被理解成：

> 开发之前先人工准备几十张 CAD。

而应该被理解成：

> 真实业务使用过程中不断积累的、已经经过人工确认的 CAD Case 中，挑选出来的一组稳定回归案例。

整个演进过程应该是：

```text
第 1 张 CAD
↓
识别
↓
人工确认
↓
沉淀

第 2 张 CAD
↓
识别
↓
人工确认
↓
沉淀

第 N 张 CAD
↓
不断形成新的 Case

典型 Case
↓
进入 Regression Benchmark

发现新问题
↓
修改 Parser

历史 Case
↓
自动回归

Parser
↓
越来越稳定
```

因此 RSDP CAD 能力真正应该建立的是：

> **Continuous CAD Feedback Loop**

也就是：

> **每一次真实识别都可以成为下一次识别能力提升的数据基础。**

最终用户体验应逐渐从：

```text
第一次：
系统识别 + 人工修改较多
```

演进为：

```text
同来源 CAD 越来越多
↓
系统逐渐掌握其 Layer / Block / Dimension / 绘图习惯
↓
人工修改越来越少
```

这比一开始人为准备大量 Benchmark，更符合 RSDP 当前阶段以及未来企业实际使用方式。
