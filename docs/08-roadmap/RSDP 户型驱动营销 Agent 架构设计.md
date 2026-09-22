# RSDP 户型驱动营销 Agent 架构设计

> **版本：** v1.0  
> **日期：** 2026-09-19  
> **状态：** 设计稿  
> **关联模块：** 户型图识别、营销 Agent、商品检索、方案搭配  
> **前置文档：** `10-营销Agent架构设计-方案C落地与Argus借鉴.md`

---

## 1. 背景

RSDP 当前已经分别具备两条能力链路。

### 1.1 户型图能力

当前已有：

- `FloorPlanController`
- `FloorPlanService`
- `FloorPlanMatchingService`
- `floor_plan_analysis`
- `floor_plan_room`
- 户型图异步识别
- 房间尺寸人工确认
- 基于房间尺寸的家具规则过滤
- 单空间 / 多空间方案生成

当前管理端已经支持：

```text
上传户型图
→ AI 识别空间
→ 人工确认空间尺寸
→ FloorPlanMatchingService
→ 自动生成 scheme
```

并且 `scheme.analysis_id` 已经能够追溯来源户型。

### 1.2 营销 Agent 能力

当前营销 Agent 已经实现：

```text
用户需求
→ RequirementPatch
→ 需求确认
→ ProductSearchNode
→ MarketingProductQueryService
→ RecommendNode
→ 用户反馈
→ 产品确认
→ agent_confirmed_item
```

营销 Agent 当前已经支持：

- SAA StateGraph 编排；
- SSE 流式交互；
- 会话恢复；
- 需求版本；
- 产品真实数据 Grounding；
- 产品尺寸硬过滤；
- 推荐批次与推荐条目；
- HITL 产品确认；
- 多轮反馈。

但目前 **户型链路与营销 Agent 链路仍然是两条相对独立的业务路径**。

---

# 2. 本次改造目标

本次改造后，RSDP 主业务流程调整为：

```text
选择 / 上传户型
        ↓
户型识别
        ↓
人工确认户型
        ↓
进入营销 Agent
        ↓
Agent 获取整个户型空间上下文
        ↓
选择目标房间
        ↓
根据房间尺寸 + 用户需求检索家具
        ↓
推荐真实 RSDP 产品
        ↓
客户反馈 / 修改要求
        ↓
确认产品
        ↓
继续选择同一空间其他家具
        ↓
切换下一个房间
        ↓
完成整屋产品确认
        ↓
生成方案
        ↓
报价 / 订单
```

最终目标不是：

> “根据户型一次生成一套家具。”

而是：

> **“以户型作为客户空间上下文，通过营销 Agent 和客户多轮互动，逐房间完成家具选品。”**

这样户型成为营销 Agent 的一个事实输入，而不是另一个独立推荐系统。

---

# 3. 核心架构原则

## 3.1 户型引擎只负责空间事实

户型模块负责：

```text
这个户型有哪些空间？
每个空间是什么类型？
房间多大？
开间是多少？
进深是多少？
面积是多少？
空间 Polygon 是什么？
后续可扩展：
门在哪里？
窗在哪里？
有哪些可用墙面？
```

户型模块**不负责最终决定买什么产品**。

---

## 3.2 商品中台只负责商品事实

商品事实仍然只能来自 RSDP：

```text
RSPU
Variant
RSKU
Supply
价格
尺寸
材质
颜色
风格
图片
交期
MOQ
```

营销 Agent 不允许生成不存在的商品参数。

---

## 3.3 Agent 负责决策编排

Agent 负责：

```text
理解客户想要什么
        +
理解当前空间条件
        +
调用商品检索
        +
生成推荐理由
        +
处理客户反馈
        +
维护确认清单
```

因此形成三个明确边界：

```text
┌──────────────────────────┐
│      Floor Plan Domain   │
│                          │
│      空间事实来源         │
└─────────────┬────────────┘
              │
              ▼
┌──────────────────────────┐
│     Marketing Agent      │
│                          │
│   需求理解 / 编排 / 推荐   │
└───────┬───────────┬──────┘
        │           │
        ▼           ▼
 Product Domain   Pricing / Order
 商品事实来源        交易能力
```

---

# 4. 总体系统架构

```text
                         RSDP Web
                            │
              ┌─────────────┴─────────────┐
              │                           │
          户型中心                     营销 Agent
              │                           │
              │                     SSE / REST
              │                           │
              ▼                           ▼
     FloorPlanController         MarketingAgentFacade
              │                           │
              ▼                           ▼
      FloorPlanService               SAA Graph
              │                           │
       ┌──────┴──────┐          ┌─────────┼─────────────┐
       │             │          │         │             │
       ▼             ▼          ▼         ▼             ▼
 Image Parser    CAD Parser   Room     Requirement   Recommendation
 JPG/PNG/PDF    DWG/DXF      Context      Node          Node
       │             │        Node         │             │
       │             │          │         ▼             │
       │      rsdp-cad-parser   │    ProductSearchNode  │
       │       .NET 8           │         │             │
       │      ACadSharp         │         ▼             │
       │   NetTopologySuite     │ MarketingProductQuery │
       │             │          │                       │
       └──────┬──────┘          └──────────┬────────────┘
              │                            │
              ▼                            ▼
     FloorPlanParseResult             RSDP 商品域
              │
              ▼
    floor_plan_analysis
    floor_plan_room
              │
              │
              └──────────────┐
                             ▼
                    FloorPlanContextService
                             │
                             ▼
                       Agent Session
```

---

# 5. 户型入口设计

## 5.1 支持文件

统一保留：

```http
POST /api/v1/floor-plan/analyze
```

支持：

```text
JPG
PNG
PDF
DWG
DXF
```

但内部不再全部走 VisionService。

增加：

```java
public interface FloorPlanParser {

    boolean supports(FloorPlanFileType type);

    FloorPlanParseResult parse(
        FloorPlanParseRequest request
    );
}
```

实现：

```text
VisionFloorPlanParser
    └── JPG / PNG / PDF

CadFloorPlanParser
    └── DWG / DXF
```

---

# 6. CAD 户型解析架构

DWG/DXF 不转换成图片后再让视觉模型识别。

采用：

```text
Spring Boot
      │
      ▼
rsdp-cad-parser
      │
      ├── .NET 8
      ├── ACadSharp
      └── NetTopologySuite
```

其中：

### ACadSharp

负责读取：

```text
LINE
ARC
POLYLINE
LWPOLYLINE
TEXT
MTEXT
DIMENSION
INSERT
BLOCK
LAYER
HATCH
```

### NetTopologySuite

负责：

```text
线段合并
端点吸附
Polygonize
Point-In-Polygon
面积计算
空间拓扑
```

该 CAD 解析链路不依赖 AutoCAD、ODA 商业 SDK 或收费 CAD 服务。

---

# 7. 户型标准结果

无论来源是：

```text
DWG
DXF
JPG
PNG
PDF
```

最后统一产生：

```json
{
  "analysisId": "FPA001",

  "rooms": [
    {
      "roomId": "ROOM001",
      "roomType": "LIVING_ROOM",
      "label": "客厅",

      "widthMm": 5200,
      "depthMm": 4300,
      "areaM2": 22.36,

      "polygon": [],

      "dimensionSource": "cad_geometry",
      "confidence": "high"
    }
  ]
}
```

Agent 不关心户型到底来自：

```text
DWG
图片
PDF
```

Agent 只消费：

```text
confirmed FloorPlan Room
```

---

# 8. 户型确认作为 Agent 前置条件

营销 Agent 不直接消费未经确认的户型识别结果。

必须满足：

```text
floor_plan_analysis.status = confirmed
```

用户流程：

```text
选择户型

↓

查看识别结果

↓

确认：
客厅 5200 × 4300
餐厅 3600 × 3200
主卧 4200 × 3800
次卧 3500 × 3200

↓

[进入智能选品]
```

点击进入智能选品后创建：

```text
Agent Session
```

并绑定：

```text
floor_plan_analysis_id
```

---

# 9. Agent Session 与户型绑定

当前：

```text
agent_session
```

增加：

```sql
floor_plan_analysis_id VARCHAR(64),
active_room_id VARCHAR(64)
```

含义：

### floor_plan_analysis_id

代表：

> 这场营销会话基于哪个户型。

### active_room_id

代表：

> 当前 Agent 正在帮助客户处理哪个房间。

例如：

```text
session = SES001

floorPlanAnalysisId = FPA001
activeRoomId = ROOM_LIVING
```

Agent 就知道：

> 目前是在给这个客户的客厅选产品。

---

# 10. 为什么必须有 activeRoom

一个户型可能包含：

```text
客厅
餐厅
主卧
次卧
书房
阳台
```

Agent 不应该把所有空间需求混到一起。

因此一个 Agent Session 对应：

```text
一个客户
+
一个户型
+
多个房间
```

而：

```text
activeRoom
```

是当前工作的空间。

例如：

```text
客户：
先把客厅配一下。

Agent：
好的，现在为 22.36㎡ 的客厅进行选品。
```

随后：

```text
activeRoom = 客厅
```

用户说：

> 客厅可以了，看看主卧。

则：

```text
activeRoom
客厅 → 主卧
```

无需新建聊天。

---

# 11. Agent 首页交互

进入营销 Agent 后，不应该直接要求客户重新描述户型。

Agent 首页可以直接展示：

```text
已加载户型

建筑空间：

[ 客厅 ]
22.36㎡
5200 × 4300

[ 餐厅 ]
11.52㎡
3600 × 3200

[ 主卧 ]
15.96㎡
4200 × 3800

[ 次卧 ]
11.20㎡
3500 × 3200
```

Agent：

> 我已经读取到这个户型。我们可以按空间逐步完成家具选品，你想先从哪个空间开始？

用户可以：

```text
点击客厅
```

也可以输入：

```text
先配客厅。
```

---

# 12. Graph 改造

当前 Graph：

```text
START
  ↓
RequirementPatchNode
  ↓
Router
  ├── Followup
  ├── ProductSearch
  ├── Chitchat
  └── ConfirmHint
```

改造后：

```text
START
  ↓
FloorPlanContextNode
  ↓
RequirementPatchNode
  ↓
Router
  ├── RoomSelect
  │
  ├── Followup
  │
  ├── ProductSearch
  │       ↓
  │   Recommend
  │
  ├── Chitchat
  │
  └── ConfirmHint
```

新增：

```text
FloorPlanContextNode
```

它不调用 LLM。

负责：

```text
sessionId
   ↓
agent_session.floor_plan_analysis_id
   ↓
FloorPlanContextService
   ↓
confirmed rooms
   ↓
activeRoom
   ↓
AgentRunContext
```

---

# 13. FloorPlanContextService

新增：

```java
@Service
public class FloorPlanContextService {

    public FloorPlanContext loadForSession(
        String sessionId
    );

    public RoomContext loadRoom(
        String sessionId,
        String roomId
    );
}
```

返回：

```java
public class RoomContext {

    private String roomId;

    private String roomType;

    private String label;

    private Integer widthMm;

    private Integer depthMm;

    private BigDecimal areaM2;

    private JsonNode polygon;
}
```

这里的数据全部来自：

```text
floor_plan_room
```

而不是 LLM。

---

# 14. 空间事实不能进入 RequirementPatch 修改

这是本次架构非常重要的边界。

例如：

```text
客厅面积 = 22.36㎡
客厅开间 = 5200mm
客厅进深 = 4300mm
```

这是：

```text
FloorPlan Fact
```

不能让 LLM 通过 RequirementPatch 改成：

```text
面积 = 30㎡
```

如果用户说：

> 我客厅应该是 25 平方。

Agent 应该提示用户：

> 当前确认户型中客厅面积为 22.36㎡，如户型数据有误，请修改户型确认结果。

也就是说：

```text
空间事实
→ FloorPlan Domain

用户偏好
→ Requirement Domain
```

严格分离。

---

# 15. 需求模型调整

最终一个选品条件由三部分组成。

```text
        用户全屋偏好
              │
              ▼
        房间选品需求
              │
              ▼
        户型硬约束
              │
              ▼
EffectiveProductSearchCriteria
```

例如：

### 用户全屋偏好

```json
{
  "style": "现代意式",
  "budgetMax": 100000
}
```

### 当前房间需求

```json
{
  "categoryCode": "沙发",
  "material": "真皮",
  "color": "米白"
}
```

### 客厅空间事实

```json
{
  "widthMm": 5200,
  "depthMm": 4300,
  "areaM2": 22.36
}
```

最后生成：

```text
ProductSearchCriteria
```

---

# 16. RequirementVersion 增加 Room Scope

当前：

```text
agent_requirement_version
```

是会话级需求版本。

建议增加：

```sql
room_id VARCHAR(64)
```

语义：

```text
room_id IS NULL
→ 整屋 / 全局需求

room_id = ROOM001
→ ROOM001 的房间需求
```

例如：

```text
全局需求
现代意式
总预算 10 万

客厅需求
真皮沙发
浅色
不需要 L 型

主卧需求
1.8m 床
软包
暖色
```

这样不用把不同房间的需求混在一起。

---

# 17. 有效约束合并规则

新增：

```text
EffectiveConstraintComposer
```

负责：

```text
Global Requirement
        +
Room Requirement
        +
FloorPlan Hard Constraint
        ↓
ProductSearchCriteria
```

约束优先级：

```text
空间物理硬约束
        >
用户主动设置的更严格约束
        >
用户偏好
        >
Agent 推测
```

例如：

空间最大推荐沙发宽：

```text
3200mm
```

用户要求：

```text
不要超过 2800mm
```

最终：

```text
maxWidthMm = 2800
```

而不是 3200。

---

# 18. ProductSearchCriteria 扩展

当前已经支持：

```text
category
style
material
color
budget
maxWidthMm
minWidthMm
keyword
```

户型场景建议继续增加：

```java
private Integer maxDepthMm;

private String roomType;

private BigDecimal roomAreaM2;
```

第一阶段真正进行 SQL / Java 硬过滤的仍然优先：

```text
maxWidthMm
maxDepthMm
```

`roomType / areaM2` 可以作为规则与推荐上下文使用。

---

# 19. 复用现有 RoomDimensionRules

当前 `FloorPlanMatchingService` 已经存在：

```text
RoomDimensionRules
```

并且已经实现：

```text
客厅
餐厅
卧室
```

的尺寸规则。

不应该在 Agent 里再重新实现一套。

建议把：

```text
FloorPlanMatchingService
```

中的：

```text
Room
→ Furniture Constraints
```

逐步抽成：

```text
RoomProductConstraintService
```

例如：

```java
SpaceConstraintProfile buildConstraints(
    FloorPlanRoom room,
    ProductCategory category
);
```

输出：

```json
{
  "maxWidthMm": 2800,
  "maxDepthMm": 1100,
  "excludeForms": [
    "L型"
  ]
}
```

然后：

```text
FloorPlanMatchingService
```

和：

```text
Marketing Agent
```

都调用它。

避免两套尺寸规则逐渐不一致。

---

# 20. 产品检索流程

最终 Agent 检索流程：

```text
用户：
“我想要一个现代意式的真皮沙发。”

                ↓

RequirementPatchNode

                ↓

RoomContext
客厅
5200×4300
22.36㎡

                ↓

RoomProductConstraintService

                ↓

得到空间限制：
沙发 maxWidth = 3000mm
maxDepth = 1100mm

                ↓

EffectiveConstraintComposer

                ↓

ProductSearchCriteria

category = SF
style = 现代意式
material = 真皮
maxWidth = 3000
maxDepth = 1100

                ↓

MarketingProductQueryService

                ↓

RSPU + Variant

                ↓

真实候选产品
```

尺寸过滤必须发生在：

```text
LLM 推荐之前
```

而不是让 LLM 自己判断：

> “这个沙发应该放得下。”

---

# 21. Recommendation Grounding 扩展

当前推荐 Grounding 主要是：

```text
Reason
→ evidenceRefs
→ Product Snapshot
```

接入户型以后建议升级成：

```text
Reason
  │
  ├── product.*
  │
  └── room.*
```

例如：

```json
{
  "text": "2.6m 宽度适合当前客厅空间，并符合现代意式偏好。",
  "evidenceRefs": [
    "product.sizeText",
    "room.widthMm",
    "room.areaM2",
    "requirement.style"
  ]
}
```

Java 校验：

```text
product.*
必须来源 Product Snapshot

room.*
必须来源 FloorPlan Context Snapshot

requirement.*
必须来源 Requirement Version
```

这样推荐理由可完整追溯。

---

# 22. 推荐批次必须记录 Room

当前：

```text
agent_recommend_batch
```

增加：

```sql
room_id VARCHAR(64),

room_context_snapshot JSONB
```

例如：

```json
{
  "roomId": "ROOM001",
  "roomType": "LIVING_ROOM",
  "widthMm": 5200,
  "depthMm": 4300,
  "areaM2": 22.36
}
```

这样即使之后用户修改户型：

```text
5200 → 5100
```

历史推荐仍然能够回答：

> 当时为什么推荐这个产品？

---

# 23. 已确认产品必须绑定 Room

当前：

```text
agent_confirmed_item
```

增加：

```sql
room_id VARCHAR(64)
```

否则整屋选品以后：

```text
沙发
餐桌
床
床头柜
茶几
椅子
```

无法判断分别属于哪个空间。

最终：

```text
SES001

├── 客厅 ROOM001
│   ├── 沙发 RSPU001
│   ├── 茶几 RSPU002
│   └── 电视柜 RSPU003
│
├── 餐厅 ROOM002
│   ├── 餐桌 RSPU010
│   └── 餐椅 RSPU011 × 6
│
└── 主卧 ROOM003
    ├── 床 RSPU020
    └── 床头柜 RSPU021 × 2
```

---

# 24. Agent 按主体产品逐步推进

不要进入一个房间以后一次推荐所有家具。

继续沿用现有：

```text
LV1 → LV2
```

思想。

例如客厅：

```text
客厅
 ↓
沙发
 ↓ 用户确认
茶几
 ↓ 用户确认
电视柜
 ↓ 用户确认
边几
```

卧室：

```text
卧室
 ↓
床
 ↓
床头柜
 ↓
斗柜
```

餐厅：

```text
餐厅
 ↓
餐桌
 ↓
餐椅
 ↓
餐边柜
```

Graph 决定：

```text
现在应该做哪个阶段
```

Skill 决定：

```text
这个品类应该怎么选
```

Tool 决定：

```text
去数据库拿哪些真实商品
```

仍然保持：

```text
Graph ≠ Skill ≠ Tool
```

---

# 25. 房间切换

增加接口：

```http
PUT /api/v1/agent/sessions/{sessionId}/active-room
```

请求：

```json
{
  "roomId": "ROOM003"
}
```

校验：

```text
session 存在
↓
绑定 analysis 存在
↓
room 属于 analysis
↓
room 未删除
↓
analysis 已 confirmed
```

成功：

```text
agent_session.active_room_id = ROOM003
```

然后发送系统消息：

```text
已切换到主卧（15.96㎡，4200 × 3800mm）。
```

---

# 26. Session 创建接口调整

创建 Agent Session 时允许：

```http
POST /api/v1/agent/sessions
```

请求：

```json
{
  "customerName": "张先生",
  "floorPlanAnalysisId": "FPA001"
}
```

如果从户型页进入：

```text
floorPlanAnalysisId 必传
```

如果未来保留纯聊天模式：

```text
floorPlanAnalysisId 可空
```

保证兼容当前营销 Agent。

---

# 27. 前端主流程

最终用户体验建议为：

```text
① 户型中心

上传户型
DWG / DXF / JPG / PNG / PDF

        ↓

② 户型确认

客厅
5200 × 4300
22.36㎡

餐厅
3600 × 3200
11.52㎡

主卧
4200 × 3800
15.96㎡

        ↓

[确认户型]

        ↓

[开始智能选品]

        ↓

③ Marketing Agent

左侧：
户型空间

● 客厅
○ 餐厅
○ 主卧
○ 次卧

中间：
聊天

右侧：
当前房间信息
+
已确认家具
```

---

# 28. Agent 页面建议布局

```text
┌─────────────────────────────────────────────────────────────┐
│ 客户项目 / 户型                                             │
├──────────────┬─────────────────────────┬────────────────────┤
│ 户型空间      │      Marketing Agent   │ 当前空间            │
│              │                         │                    │
│ ● 客厅        │ Agent：                │ 客厅               │
│   22.36㎡    │ 我已经读取到客厅……      │ 5200×4300          │
│              │                         │ 22.36㎡            │
│ ○ 餐厅        │ 用户：                  │                    │
│              │ 想找真皮沙发             │ 已确认：           │
│ ○ 主卧        │                         │ 沙发 ×1            │
│              │ [产品推荐卡片]           │                    │
│ ○ 次卧        │                         │                    │
│              │                         │                    │
├──────────────┴─────────────────────────┴────────────────────┤
│                       输入消息                              │
└─────────────────────────────────────────────────────────────┘
```

---

# 29. 方案生成职责调整

当前：

```text
FloorPlanController
POST /{analysisId}/scheme
```

可以直接调用：

```text
FloorPlanMatchingService
```

生成方案。

本次改造后，该入口不建议继续作为客户主链路。

定位调整为：

```text
快速自动搭配 / 管理端辅助功能
```

客户主流程改成：

```text
FloorPlan
    ↓
Marketing Agent
    ↓
agent_confirmed_item
    ↓
整屋确认
    ↓
SchemeAssembler
    ↓
scheme
+
scheme_item
```

即：

> **方案应该是用户和 Agent 多轮确认后的结果，而不是户型识别完成以后立即自动生成的结果。**

---

# 30. 最终 Scheme 生成

当用户点击：

```text
[完成选品并生成方案]
```

执行：

```text
AgentConfirmedItem
        ↓
按 Room 分组
        ↓
读取 RSKU / Supply
        ↓
价格与供给校验
        ↓
SchemeService
        ↓
scheme
        ↓
scheme_item
```

当前：

```text
scheme.analysis_id
```

继续保留。

同时建议：

```text
scheme_item
```

增加：

```sql
floor_plan_room_id VARCHAR(64)
```

原因是：

```text
space_tag = BEDROOM
```

无法区分：

```text
主卧
次卧
儿童房
```

而：

```text
floor_plan_room_id
```

能够精确说明产品属于哪个实际空间。

---

# 31. 数据模型改造

建议新增 Flyway：

```text
V11__floor_plan_agent_integration.sql
```

主要变更：

```sql
ALTER TABLE agent_session
ADD COLUMN floor_plan_analysis_id VARCHAR(64),
ADD COLUMN active_room_id VARCHAR(64);

ALTER TABLE agent_requirement_version
ADD COLUMN room_id VARCHAR(64);

ALTER TABLE agent_recommend_batch
ADD COLUMN room_id VARCHAR(64),
ADD COLUMN room_context_snapshot JSONB;

ALTER TABLE agent_confirmed_item
ADD COLUMN room_id VARCHAR(64);

ALTER TABLE scheme_item
ADD COLUMN floor_plan_room_id VARCHAR(64);
```

对应 FK 根据现有跨域 FK 规范统一补齐。

---

# 32. floor_plan_room 数据模型升级

当前：

```text
bbox
width_mm
depth_mm
area_m2
```

为支持 DWG 精确空间，建议增加：

```sql
label VARCHAR(128),

polygon JSONB,

centroid JSONB,

geometry_source VARCHAR(32)
```

最终：

```text
bbox
→ 快速显示 / 兼容图片识别

polygon
→ CAD 精确空间

width/depth/area
→ Agent 选品约束
```

---

# 33. 不建议第一期建设的内容

第一期不要同时做：

```text
3D 自动布置
家具碰撞检测
门窗净空精确规划
承重墙识别
水电识别
效果图自动生成
完整空间优化算法
Multi-Agent
PostGIS
复杂 CAD BIM 化
```

第一阶段解决的核心问题只有：

> **让营销 Agent 确切知道客户有哪些房间、房间多大，并基于这些事实从 RSDP 中选择真实家具。**

---

# 34. 第一阶段 MVP

第一阶段完整闭环：

```text
DWG/JPG/PDF
 ↓
户型识别
 ↓
空间确认
 ↓
绑定 Agent Session
 ↓
选择房间
 ↓
Agent 获取房间尺寸
 ↓
用户描述需求
 ↓
尺寸硬过滤
 ↓
真实商品推荐
 ↓
反馈
 ↓
确认商品
 ↓
切换空间
 ↓
继续选品
 ↓
整屋确认
 ↓
生成 scheme
```

验收标准：

```text
✓ Agent Session 能绑定 confirmed 户型

✓ Agent 能读取所有空间

✓ 可以切换当前房间

✓ 推荐批次能够追溯 room_id

✓ 推荐理由能够引用空间事实

✓ 产品尺寸不能超过空间硬约束

✓ Agent 不能修改户型事实

✓ 确认产品能够绑定具体 Room

✓ 一个 Session 可以完成多个房间选品

✓ 最终能够生成一个整屋 Scheme

✓ Scheme Item 可以追溯具体房间
```

---

# 35. 推荐代码结构

新增：

```text
com.rsdp.floorplan
├── parser
│   ├── FloorPlanParser
│   ├── FloorPlanParserRegistry
│   ├── VisionFloorPlanParser
│   └── CadFloorPlanParser
│
├── context
│   ├── FloorPlanContextService
│   ├── FloorPlanContext
│   └── RoomContext
│
└── constraint
    ├── RoomProductConstraintService
    └── SpaceConstraintProfile
```

Agent 新增：

```text
com.rsdp.agent
├── graph
│   └── nodes
│       ├── FloorPlanContextNode
│       └── RoomSelectNode
│
├── domain
│   └── EffectiveConstraintComposer
│
└── service
    └── AgentRoomService
```

CAD 独立：

```text
rsdp-cad-parser/
├── Api
├── Parser
│   ├── DwgParser
│   ├── EntityExtractor
│   ├── BlockResolver
│   ├── WallExtractor
│   ├── RoomPolygonizer
│   └── TextRoomMatcher
│
├── Domain
└── Program.cs
```

---

# 36. 最终架构

```text
                        Customer
                           │
                           ▼
                    Select Floor Plan
                           │
                           ▼
                Floor Plan Recognition
                           │
                           ▼
                    Human Confirm
                           │
                           ▼
                  Marketing Session
                           │
               floorPlanAnalysisId
                           │
                           ▼
                ┌─────────────────────┐
                │     SAA Graph       │
                │                     │
                │ FloorPlanContext    │
                │       ↓             │
                │ RequirementPatch    │
                │       ↓             │
                │ ProductSearch       │
                │       ↓             │
                │ Recommendation      │
                │       ↓             │
                │ HITL Confirm        │
                └─────────┬───────────┘
                          │
             ┌────────────┼────────────┐
             │            │            │
             ▼            ▼            ▼
        FloorPlan      Product       Requirement
          Facts          Facts          Facts
             │            │            │
             └────────────┼────────────┘
                          ▼
                  Confirmed Products
                          │
                    grouped by Room
                          │
                          ▼
                    Whole Scheme
                          │
                          ▼
                  Pricing / Order
```

---

# 37. 架构结论

RSDP 后续不应该存在两个互相竞争的选品流程：

```text
户型自动搭配
vs
营销 Agent 选品
```

建议统一成：

```text
FloorPlan
= 空间事实底座

Marketing Agent
= 面向客户的选品主入口

Product Platform
= 商品事实底座

Scheme
= Agent + 客户最终确认结果
```

因此新的完整业务链路为：

```text
商品数据中台
        │
        ├──────────────────────┐
        │                      │
        ▼                      ▼
 Floor Plan Domain      Product Domain
 空间数字化             商品数字化
        │                      │
        └──────────┬───────────┘
                   ▼
             Marketing Agent
                   │
          客户需求 + 空间约束
                   │
                   ▼
             智能家具选品
                   │
                   ▼
              整屋方案
                   │
                   ▼
             报价 / 订单
```

这比当前“户型识别后直接生成方案”和“营销 Agent 单独聊天选产品”两条平行路线更符合 RSDP 后续的业务定位，也能最大程度复用当前已经完成的 FloorPlan、Marketing Agent、ProductQuery 与 Scheme 能力。