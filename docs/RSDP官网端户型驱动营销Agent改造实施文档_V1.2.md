# RSDP 官网端户型驱动 Marketing Agent 改造实施文档 V1.2

> **适用仓库：** `Chen-Minghuan/rsdp`  
> **目标端：** `website/` 官网端  
> **后端：** `server/`  
> **数据库迁移基线：** 当前仓库已到 `V17`，本次改造从 `V18` 开始  
> **版本变化：** V1.2 在 V1.1 基础上正式加入 **Shortlist / 喜好清单 / 备选清单** 层，将“喜欢商品”和“最终确定商品”拆开。  
> **文档定位：** 可直接用于后续 Codex / Claude / 开发人员实施。

---

# 1. 改造目标

本次改造不是给官网简单增加一个聊天框，也不是重做现有户型识别，而是将项目已有的：

```text
户型识别
+
Marketing Agent
+
商品数据中台
+
Scheme
+
Quote
```

真正串成一条面向客户的完整整屋选品链路。

最终用户流程：

```text
上传户型图
  ↓
户型识别 / CAD 解析
  ↓
人工确认空间
  ↓
整体偏好
  ↓
进入 Marketing Agent
  ↓
按房间推荐主体产品
  ↓
用户将喜欢的商品加入喜好清单
  ↓
围绕“搭配参考商品”继续推荐配套产品
  ↓
形成分房间备选清单
  ↓
统一进入方案整理
  ↓
从备选中确定最终商品 + Variant + 数量
  ↓
生成整屋方案
  ↓
生成正式报价
  ↓
后续订单
```

本版最重要的原则：

> **Marketing Agent 阶段负责“发现和收集喜欢的商品”，方案整理阶段才负责“最终确定商品”。**

因此用户可以同时喜欢：

```text
沙发 A
沙发 B
```

两款都加入客厅沙发备选，而不是在 Agent 阶段被迫二选一。

---

# 2. 最终业务状态模型

商品在整个流程中的状态正式拆成四层：

```text
RECOMMENDED
    ↓
SHORTLISTED
    ↓
CONFIRMED
    ↓
QUOTED
```

含义如下：

| 状态 | 含义 |
|---|---|
| `RECOMMENDED` | Agent 给用户推荐过 |
| `SHORTLISTED` | 用户喜欢，加入当前项目/房间的备选清单 |
| `CONFIRMED` | 用户在方案整理阶段最终采用 |
| `QUOTED` | 已进入正式报价快照 |

对应数据库关系：

```text
agent_recommend_item
        ↓
agent_shortlist_item
        ↓
agent_confirmed_item
        ↓
scheme_item / agent_quote
```

禁止继续采用：

```text
推荐
→ 直接 Confirm
→ 最终方案
```

---

# 3. 为什么必须增加 Shortlist

家具选品属于：

```text
高客单价
+
强审美
+
多商品组合
+
需要比较
```

用户真实决策通常是：

```text
看
→ 喜欢
→ 保留
→ 比较
→ 搭配
→ 最终确定
```

而不是：

```text
看到推荐
→ 立即最终确认
```

例如 Agent 推荐 4 款沙发：

```text
沙发 A
沙发 B
沙发 C
沙发 D
```

用户完全可能：

```text
喜欢 A
喜欢 B
```

此时应该得到：

```text
客厅 / 沙发备选

A
B
```

而不是：

```text
确认 A
→ B 被放弃
```

因此 V1.2 增加独立的：

```text
agent_shortlist_item
```

---

# 4. Shortlist 与现有 user_favorite 的区别

项目已有：

```text
user_favorite
```

但不要直接拿它作为 Marketing Agent 的备选清单。

二者语义不同。

## user_favorite

表达：

> 我长期喜欢这个产品。

特点：

```text
用户级
跨项目
跨户型
生命周期长
```

## agent_shortlist_item

表达：

> 在这次户型、这个 Session、这个房间里，我正在考虑这个商品。

特点：

```text
Session 级
Room 级
项目上下文强
用于后续方案决策
```

未来可以允许：

```text
[加入选品清单]
[同时收藏到心愿单]
```

但必须是两个独立动作。

---

# 5. 当前项目基础

官网位于：

```text
website/
```

后端：

```text
server/
```

Marketing Agent：

```text
server/src/main/java/com/rsdp/agent/
```

当前官网主要户型入口：

```text
website/pages/ai-match.vue
```

已有能力继续复用：

```text
JPG / PNG / PDF
DWG / DXF
CAD Parser
异步户型识别
房间尺寸
Polygon
人工确认
FloorPlanMatchingService
MarketingProductQueryService
SSE
RRF
Skill
AgentQuoteService
AgentSchemeExportService
```

本次重点是重新组织它们之间的业务关系。

---

# 6. 最终官网信息架构

V1.2 建议正式拆成 5 个阶段：

```text
/ai-match
    ↓
/ai-match/preferences?analysisId=xxx
    ↓
/agent/{sessionId}
    ↓
/selection/{sessionId}
    ↓
/solution/{sessionId}
```

对应：

| 页面 | 职责 |
|---|---|
| `/ai-match` | 上传、识别、确认户型 |
| `/ai-match/preferences` | 设置整体偏好 |
| `/agent/{sessionId}` | 智能推荐 + 收集喜好商品 |
| `/selection/{sessionId}` | 整理备选并最终确定商品 |
| `/solution/{sessionId}` | 整屋方案与报价 |

其中：

```text
Agent = 帮用户找东西
Selection = 帮用户做决定
Solution = 输出正式结果
```

不要把三种职责混在一个聊天页面里。

---

# 7. `/ai-match` 户型确认

继续保留现有能力：

```text
上传
→ 解析
→ 房间识别
→ 尺寸校正
→ Polygon
→ 人工确认
```

户型确认后提供：

```text
[快速自动搭配]

[开始智能选品]
```

## 快速自动搭配

继续使用现有：

```text
FloorPlanMatchingService
```

用途：

```text
游客体验
快速 Demo
一次性参考方案
```

## 开始智能选品

进入：

```text
持久户型
→ 整体偏好
→ Marketing Agent
```

---

# 8. Marketing Agent 建议要求登录

正式 Marketing Agent 涉及：

```text
多轮需求
房间状态
喜好清单
最终方案
报价
订单
```

因此推荐：

```text
快速自动搭配
→ 可匿名

Marketing Agent
→ 登录后使用
```

普通客户登录后需要能使用：

```text
agent:use
```

但不要因此把后台通用：

```text
scheme:create
quote:generate
```

直接完整开放给 USER。

Agent 自服务接口通过：

```text
当前用户
+
Session ownership
```

控制。

---

# 9. 整体偏好页

新增：

```text
website/pages/ai-match/preferences.vue
```

采集：

```text
整体风格
综合色调
材质倾向
预算模式
价格倾向
家庭情况
```

例如：

```text
现代意式
暖灰
真皮 / 实木
预算暂未设置
价格品质均衡
有宠物
```

整体偏好通过确定性 API 保存。

不要经过 LLM。

---

# 10. 无预算模式

预算继续保持非必填。

```text
EXACT
RANGE
UNSET
```

如果：

```text
budgetMode = UNSET
```

Agent 正常推荐。

但由于 V1.2 允许用户同时收藏多个同品类候选，因此 Agent 页面**不再把所有喜好商品简单求和作为整屋金额**。

Agent 阶段主要显示：

```text
喜好清单 6 件
```

而不是：

```text
整屋累计 ¥xxxxx
```

只有进入最终方案确认、确定实际采用商品后，才计算真实整屋总价。

---

# 11. 三层约束模型保持不变

最终搜索条件仍然由：

```text
Global Requirement
+
Room Requirement
+
FloorPlan Fact
```

组成。

## Global Requirement

整屋偏好：

```text
风格
色调
价格倾向
整体材质倾向
预算模式
家庭标签
```

## Room Requirement

当前房间需求：

```text
品类
材质
颜色
尺寸要求
造型
用户自然语言补充
```

## FloorPlan Fact

只读空间事实：

```text
roomId
roomType
widthMm
depthMm
areaM2
polygon
```

LLM 不能修改 FloorPlan Fact。

---

# 12. Marketing Agent 最终页面

新增：

```text
website/pages/agent/[sessionId].vue
```

三栏布局：

```text
┌────────────────┬────────────────────────────┬────────────────────┐
│ 户型空间        │ Marketing Agent            │ 需求档案            │
│                │                            │                    │
│ ● 客厅         │ 对话                       │ 整体偏好            │
│ 22.36㎡        │                            │                    │
│ 初选中·6件备选 │ 推荐商品                   │ 客厅需求            │
│                │                            │                    │
│ ○ 餐厅         │ [加入清单]                 │ 喜好清单            │
│ 未开始         │                            │ 沙发 2              │
│                │ 用户反馈                   │ 茶几 3              │
│ ○ 主卧         │                            │ 休闲椅 1            │
│                │                            │                    │
│ ○ 次卧         │                            │ [查看全部清单]      │
└────────────────┴────────────────────────────┴────────────────────┘
```

右侧继续叫：

```text
需求档案
```

不要改成“当前空间”。

---

# 13. 左侧房间状态文案调整

V1.1：

```text
客厅
已选 2/4
```

容易让用户理解为：

> 已经最终确认 2 件。

V1.2 改为：

```text
客厅
22.36㎡
初选中 · 6件备选
```

或：

```text
客厅
✓ 已完成初选
```

只有进入最终方案后，才使用：

```text
已确定 4 件
```

---

# 14. 右侧需求档案调整

最终结构：

```text
需求档案

整体偏好
────────────
现代意式
暖灰
品质价格均衡
预算暂未设置

客厅需求
────────────
当前品类：沙发
材质：真皮
颜色：米白
形式：一字型
尺寸：≤2800mm

喜好清单
────────────
沙发      2
茶几      3
休闲椅    1

共 6 件备选

[查看全部清单]
```

删除 Agent 页里的：

```text
已确认产品
```

统一改成：

```text
喜好清单 / 备选清单
```

---

# 15. Agent 商品卡交互调整

V1.1：

```text
[查看详情] [确认]
```

V1.2：

```text
[查看详情] [♡ 加入清单]
```

加入后：

```text
[✓ 已加入清单]
```

再次点击可移除。

示例：

```text
┌────────────────────────────┐
│ 产品图                     │
│                            │
│ 云栖 · 真皮沙发            │
│ 现代意式 · 真皮 · 米白     │
│                            │
│ 可适配规格                 │
│ 2600 / 2800mm              │
│                            │
│ ¥10,800 起                 │
│                            │
│ 推荐理由：                 │
│ 适合当前客厅空间           │
│ 符合整体风格与颜色偏好     │
│                            │
│ [查看详情] [♡ 加入清单]    │
└────────────────────────────┘
```

---

# 16. Agent 阶段不强制锁定 Variant

这是 V1.2 的另一个变化。

例如某沙发：

```text
2600 ✓
2800 ✓
3200 ✗
```

用户可能只是：

> 我喜欢这个造型。

此时可以直接加入 Shortlist：

```text
preferredVariantId = null
```

也可以允许用户在卡片上先选：

```text
preferredVariantId = 2800
```

但它只是：

```text
偏好规格
```

不是最终锁定规格。

真正 Variant 锁定发生在：

```text
/selection/{sessionId}
```

最终方案整理阶段。

---

# 17. 搭配参考商品 Reference Item

主体产品可以存在多个 Shortlist。

例如：

```text
沙发 A ♥
沙发 B ♥
```

但后续茶几推荐最好有一个明确搭配方向。

因此增加：

```text
Reference Item / 搭配参考
```

例如：

```text
沙发 A  ♥
沙发 B  ♥  ★ 当前搭配参考
```

含义：

> 当前 Agent 推荐茶几、休闲椅等配套产品时，以沙发 B 的风格、颜色、材质、价格档位作为主要 Anchor。

注意：

```text
Reference ≠ Confirmed
```

用户最终仍然可以选择沙发 A。

---

# 18. Reference 默认规则

如果当前主体品类：

```text
Shortlist = 0
→ 无 Reference

Shortlist = 1
→ 自动设为 Reference

Shortlist > 1
→ 保留当前 Reference
→ 没有时可默认最近加入或排名最高
→ UI 允许手动切换
```

用户也可以说：

```text
“后面先按第二款沙发来搭。”
```

Agent 将对应 Shortlist Item 设置为：

```text
is_reference = true
```

---

# 19. Room-aware Skill 调整

当前：

```text
LivingRoomMatchingSkill
```

不能继续以：

```text
Session 最新 ConfirmedItem
```

作为 Anchor。

V1.2 改为：

```text
当前 Room
+
当前品类的 Reference Shortlist Item
```

逻辑：

```text
roomId
 ↓
找到主体品类 shortlist
 ↓
找到 is_reference = true
 ↓
作为 Companion Skill Anchor
```

如果没有 Reference：

```text
只有一个主体 shortlist
→ 自动使用

多个 shortlist
→ 使用默认 reference
```

未来：

```text
living-room-matching
dining-room-matching
bedroom-matching
```

均使用同一机制。

---

# 20. 新增 agent_shortlist_item

V18 新增：

```sql
CREATE TABLE agent_shortlist_item (
    shortlist_item_id       VARCHAR(64) PRIMARY KEY,
    session_id              VARCHAR(64) NOT NULL,
    room_id                 VARCHAR(64) NOT NULL,
    recommend_item_id       VARCHAR(64),
    rspu_id                 VARCHAR(64) NOT NULL,
    category_code           VARCHAR(32),

    preferred_variant_id    VARCHAR(64),
    is_reference            BOOLEAN NOT NULL DEFAULT FALSE,

    status                  VARCHAR(20) NOT NULL DEFAULT 'active',
    user_note               VARCHAR(512),

    created_at              TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ
);
```

建议状态：

```text
active
removed
```

最终确定后不需要把 Shortlist 改成 confirmed 状态。

Confirmed 使用独立：

```text
agent_confirmed_item
```

记录。

---

# 21. Shortlist 唯一性建议

同一 Session、同一 Room 下，同一个 RSPU 不应重复创建多个活动 Shortlist。

建议：

```sql
CREATE UNIQUE INDEX uk_agent_shortlist_active_product
ON agent_shortlist_item(session_id, room_id, rspu_id)
WHERE status = 'active';
```

如果用户改变偏好 Variant：

```text
UPDATE preferred_variant_id
```

而不是再创建一条重复记录。

---

# 22. Reference 唯一性

同一：

```text
Session
+
Room
+
Category
```

最多一个活动的 Reference。

建议：

```sql
CREATE UNIQUE INDEX uk_agent_shortlist_reference
ON agent_shortlist_item(session_id, room_id, category_code)
WHERE status = 'active' AND is_reference = true;
```

切换 Reference 时采用事务：

```text
旧 reference = false
↓
新 reference = true
```

---

# 23. agent_confirmed_item 调整

V1.2 中 `agent_confirmed_item` 仍然保留，但产生时间后移。

建议新增：

```text
shortlist_item_id
room_id
variant_id
```

关系：

```text
ShortlistItem
 ↓
用户最终选择
 ↓
ConfirmedItem
```

最终 Confirmed Item 至少记录：

```text
sessionId
roomId
shortlistItemId
rspuId
variantId
quantity
spec snapshot
```

---

# 24. Shortlist API

新增：

```http
POST /api/v1/agent/sessions/{sessionId}/shortlist
```

请求：

```json
{
  "recommendItemId": "ITEM_xxx",
  "preferredVariantId": null
}
```

返回：

```json
{
  "shortlistItemId": "SHORT_xxx",
  "roomId": "ROOM_xxx",
  "rspuId": "RSPU_xxx",
  "isReference": true
}
```

---

## 24.1 删除 Shortlist

```http
DELETE /api/v1/agent/sessions/{sessionId}/shortlist/{shortlistItemId}
```

语义：

```text
status = removed
```

保留历史，不强制物理删除。

---

## 24.2 更新偏好规格

```http
PUT /api/v1/agent/sessions/{sessionId}/shortlist/{shortlistItemId}
```

例如：

```json
{
  "preferredVariantId": "VARIANT_2800"
}
```

---

## 24.3 设置搭配参考

```http
PUT /api/v1/agent/sessions/{sessionId}/rooms/{roomId}/reference-item
```

请求：

```json
{
  "shortlistItemId": "SHORT_xxx"
}
```

---

# 25. Shortlist 的 Room 来源

加入 Shortlist 时也不能直接使用：

```text
session.activeRoomId
```

正确方式：

```text
recommendItem
 ↓
recommendBatch
 ↓
batch.roomId
 ↓
shortlistItem.roomId
```

原因与 Confirm 相同：

```text
用户切到主卧后
仍可能回头点击旧客厅推荐卡
```

旧卡必须继续进入客厅 Shortlist。

---

# 26. RecommendBatch 房间化

V18 中：

```text
agent_recommend_batch
+ room_id
+ room_context_snapshot
```

依然必须保留。

Snapshot 示例：

```json
{
  "roomId": "ROOM_LIVING",
  "roomType": "LIVING_ROOM",
  "label": "客厅",
  "widthMm": 5200,
  "depthMm": 4300,
  "areaM2": 22.36,
  "effectiveMaxWidthMm": 2800
}
```

---

# 27. Variant 级搜索仍然必须做

Shortlist 并不会降低空间适配要求。

商品进入推荐前仍然需要：

```text
RSPU
 ↓
Variant 级硬过滤
 ↓
至少一个 Variant 可用
 ↓
RSPU 才进入候选
```

例如：

```text
2600 ✓
2800 ✓
3200 ✗
```

推荐卡显示：

```text
可适配规格：
2600 / 2800
```

用户可以先收藏整个产品，最终方案阶段再确定具体 Variant。

---

# 28. 新增 `/selection/{sessionId}`

这是 V1.2 相比 V1.1 最大的页面变化。

新增：

```text
website/pages/selection/[sessionId].vue
```

职责：

> 对 Agent 阶段形成的所有 Shortlist 进行分房间、分品类整理，并完成最终商品决策。

页面不是聊天。

---

# 29. Selection 页面布局

例如：

```text
整屋备选清单                         共 12 件备选

客厅
────────────────────────────────

沙发

┌──────────┐  ┌──────────┐
│ 沙发 A   │  │ 沙发 B   │
│          │  │          │
│ [采用]   │  │ [采用]   │
└──────────┘  └──────────┘

茶几

┌──────────┐  ┌──────────┐  ┌──────────┐
│ 茶几 A   │  │ 茶几 B   │  │ 茶几 C   │
│          │  │          │  │          │
└──────────┘  └──────────┘  └──────────┘

休闲椅

...


餐厅
────────────────────────────────

餐桌
...

[返回继续选品]                    [生成方案]
```

---

# 30. Selection 页面功能

至少支持：

```text
查看产品详情
移出备选
选择最终采用
选择 Variant
设置数量
查看空间适配状态
返回 Agent 继续找
```

真正的：

```text
[确认采用]
```

只在这个页面出现。

---

# 31. 一个品类不强制只选一个商品

不要写死：

```text
一个 category 只能确认一个商品
```

例如：

```text
沙发       通常 1
餐桌       通常 1
床         通常 1

茶几       可能 1~2
休闲椅     可能 1~2
边几       可能 2
餐椅       一种商品数量 ×6
```

因此最终模型应该是：

```text
Category
→ N 个 ConfirmedItem
→ 每个 Item 自己有 quantity
```

第一期可以由前端提示推荐数量，不需要建设复杂规则引擎。

---

# 32. Selection → Confirmed

用户在 Selection 页面点击：

```text
确认采用
```

请求正式 Confirm API。

建议：

```http
POST /api/v1/agent/sessions/{sessionId}/confirm
```

请求：

```json
{
  "shortlistItemId": "SHORT_xxx",
  "variantId": "VARIANT_2800",
  "quantity": 1,
  "idempotencyKey": "..."
}
```

注意：

```text
recommendItemId
```

不再作为前端主要确认入口。

最终确认以：

```text
shortlistItemId
```

为来源。

---

# 33. 最终确认必须锁定 Variant

Shortlist 阶段：

```text
preferredVariantId
```

可以为空。

Confirmed 阶段：

```text
variantId
```

必须非空。

例如：

```text
Shortlist
沙发 A
preferredVariant = null

↓

Selection
选择 2800mm

↓

Confirmed
rspuId = A
variantId = 2800
quantity = 1
```

---

# 34. RSKU 解析

只有 ConfirmedItem 才进入 RSKU 解析。

正确：

```text
ConfirmedItem
  rspuId
  variantId
  quantity
      ↓
AgentRskuResolver
      ↓
该 Variant 可销售 RSKU
      ↓
Supply / Sale Price
```

禁止：

```text
Shortlist
→ 直接生成正式报价
```

也禁止：

```text
仅按 RSPU 找最低价 RSKU
```

---

# 35. Agent 页面价格展示变化

由于同一品类可以同时 Shortlist 多个商品：

```text
沙发 A ¥12,800
沙发 B ¥18,900
```

它们不代表都会购买。

因此 Agent 页不要显示：

```text
整屋当前合计 = 所有 Shortlist 商品价格相加
```

这个数字没有业务意义。

Agent 页可以展示：

```text
喜好清单 6 件
```

可选增强：

```text
沙发备选：¥12,800 ～ ¥18,900
```

但第一期不需要做复杂价格区间计算。

---

# 36. 真正整屋金额何时产生

只有：

```text
Selection
 ↓
Confirmed Item
```

之后才计算：

```text
客厅小计
餐厅小计
主卧小计
整屋合计
```

因此：

```text
Shortlist = 审美/决策候选
Confirmed = 财务计算基础
```

必须严格区分。

---

# 37. Solution 页面

新增 / 保留：

```text
website/pages/solution/[sessionId].vue
```

数据来自：

```text
ConfirmedItem
```

而不是 Shortlist。

按 Room 分组：

```text
客厅
沙发 A 2800 ×1
茶几 B ×1
休闲椅 C ×2

餐厅
餐桌 D ×1
餐椅 E ×6
```

右侧：

```text
客厅    ¥26,200
餐厅    ¥21,600
主卧    ¥21,400
次卧    ¥15,700

整屋合计 ¥84,900
```

---

# 38. 无预算用户在 Solution 页的表现

如果：

```text
budgetMode = UNSET
```

显示：

```text
预算状态
暂未设置

当前最终方案金额
¥84,900
```

用户可以：

```text
返回调整
```

或者重新进入 Agent：

> “有点高，尽量控制在 8 万以内。”

然后：

```text
更新 Global Requirement
→ 推荐更合适替代品
→ 加入 Shortlist
→ Selection 替换
```

---

# 39. Quote 与 Scheme

正式报价只读取：

```text
ConfirmedItem
```

Quote Snapshot：

```json
{
  "roomId": "ROOM_1",
  "rspuId": "RSPU_1",
  "variantId": "VARIANT_1",
  "rskuId": "RSKU_1",
  "quantity": 1,
  "salePrice": 12800
}
```

Scheme：

```text
scheme.analysis_id
→ 来源户型

scheme_item.floor_plan_room_id
→ 具体房间
```

保证：

```text
户型
→ 房间
→ RSPU
→ Variant
→ RSKU
→ Quote
```

全链路可追溯。

---

# 40. V18 数据库最终范围

V18 建议一次性完成：

```text
agent_session
+ floor_plan_analysis_id
+ active_room_id

agent_requirement_version
+ room_id

agent_recommend_batch
+ room_id
+ room_context_snapshot

新增 agent_shortlist_item

agent_confirmed_item
+ shortlist_item_id
+ room_id
+ variant_id

scheme_item
+ floor_plan_room_id
```

注意：

> V18 还未执行前，建议一次设计完整，不要人为拆成多个小迁移。

如果开发时 V18 已经合入其他功能，再按实际仓库水位顺延迁移编号。

---

# 41. 推荐索引

```sql
CREATE INDEX idx_agent_req_session_room_version
ON agent_requirement_version(session_id, room_id, version_no DESC);

CREATE INDEX idx_agent_batch_session_room
ON agent_recommend_batch(session_id, room_id, created_at DESC);

CREATE INDEX idx_agent_shortlist_session_room
ON agent_shortlist_item(session_id, room_id, status);

CREATE UNIQUE INDEX uk_agent_shortlist_active_product
ON agent_shortlist_item(session_id, room_id, rspu_id)
WHERE status = 'active';

CREATE UNIQUE INDEX uk_agent_shortlist_reference
ON agent_shortlist_item(session_id, room_id, category_code)
WHERE status = 'active' AND is_reference = true;

CREATE INDEX idx_agent_confirm_session_room
ON agent_confirmed_item(session_id, room_id, status);

CREATE INDEX idx_scheme_item_floor_room
ON scheme_item(floor_plan_room_id);
```

---

# 42. Agent Graph 调整

Graph 仍然需要解决“先识别目标房间、后持久化需求”。

建议：

```text
START
 ↓
SessionContextNode
 ↓
IntentRequirementInterpretNode
 ↓
RoomResolveNode
 ↓
RequirementApplyNode
 ↓
Router
 ├── Followup
 ├── ProductSearch → Recommend
 ├── CompanionMatch
 ├── Chitchat
 ├── ShortlistHint
 └── ActionHint
```

注意：

```text
Shortlist 的加入/删除/Reference 切换
```

属于确定性业务写操作。

不应交给 LLM 自己直接写数据库。

---

# 43. LLM 与确定性逻辑的边界

LLM 负责：

```text
理解用户自然语言
识别需求修改
识别目标房间
解释推荐理由
理解“按第二款继续搭配”等意图
```

Java 负责：

```text
Requirement Reducer
Room 校验
Shortlist 写入
Reference 唯一性
Variant 适配
Confirmed 写入
RSKU 解析
Quote
Scheme
```

---

# 44. 官网组件调整

建议最终：

```text
website/components/

FloorPlanFlowStepper.vue

PreferenceForm.vue
PreferenceSummary.vue

AgentRoomNavigator.vue
AgentConversation.vue
AgentMessageBubble.vue
AgentProductCard.vue
DemandProfile.vue
ShortlistSummary.vue

SelectionRoomSection.vue
SelectionProductCard.vue

RoomSchemeCard.vue
QuoteSummary.vue
```

V1.1 的：

```text
ConfirmedItemList.vue
```

在 Agent 页替换成：

```text
ShortlistSummary.vue
```

ConfirmedItem 的完整展示主要放到：

```text
selection
solution
```

页面。

---

# 45. API Composable 调整

```text
website/composables/useAgentApi.ts
```

建议包含：

```ts
createSession()
getSession()
savePreferences()
switchRoom()

addShortlist()
removeShortlist()
updateShortlist()
setReferenceItem()

confirmSelection()
cancelConfirmedItem()

generateQuote()
exportScheme()
```

---

# 46. Session Detail 建议输出

为了减少官网多接口拼装：

```json
{
  "session": {},
  "floorPlan": {},
  "activeRoomId": "ROOM_1",

  "globalRequirement": {},
  "roomRequirement": {},

  "rooms": [
    {
      "roomId": "ROOM_1",
      "label": "客厅",
      "areaM2": 22.36,
      "selectionStatus": "IN_PROGRESS",
      "shortlistCount": 6,
      "confirmedCount": 0
    }
  ],

  "shortlistSummary": {
    "total": 6,
    "byCategory": {
      "SF": 2,
      "TB": 3,
      "FS": 1
    }
  },

  "shortlistItems": [],
  "confirmedItems": []
}
```

---

# 47. 房间状态建议

Agent 阶段可动态计算：

```text
NOT_STARTED
IN_PROGRESS
SHORTLIST_READY
```

Selection 后：

```text
CONFIRMED
```

例如左栏：

```text
客厅
✓ 已完成初选

餐厅
初选中 · 4件备选

主卧
未开始
```

---

# 48. 最终用户完整案例

```text
1. 用户上传户型图
2. 系统识别客厅/餐厅/主卧/次卧
3. 用户人工确认
4. 用户选择：
   - 现代意式
   - 暖灰
   - 暂无预算
   - 品质价格均衡
5. 进入客厅 Agent
6. Agent 推荐 4 款沙发
7. 用户把沙发 A、B 都加入喜好清单
8. B 被设为当前搭配参考
9. 用户说：
   “按 B 的感觉看看茶几”
10. Agent 使用 B 作为 Anchor 推荐茶几
11. 用户把茶几 C、D、E 加入清单
12. 客厅初选完成
13. 切换主卧
14. 用户加入两款床
15. 完成所有房间初选
16. 进入 /selection
17. 客厅沙发最终选 A
18. 选择 Variant 2800mm
19. 茶几最终选 D
20. 主卧床最终选择某 1800mm Variant
21. 设置数量
22. 生成 Confirmed Items
23. 进入 /solution
24. 系统按房间汇总最终产品
25. Variant → RSKU
26. 生成正式价格
27. 输出整屋方案
28. 生成 Quote
29. 后续下单
```

---

# 49. 实施顺序 V1.2

## P0：V18 数据模型

完成：

```text
Session ↔ FloorPlan
Requirement ↔ Room
RecommendBatch ↔ Room
Shortlist
Confirmed ↔ Shortlist + Room + Variant
SchemeItem ↔ Room
```

---

## P1：户型与账号链

完成：

```text
普通客户登录
持久 analysisId
开始智能选品
```

---

## P2：整体偏好

完成：

```text
/preferences
Global Requirement
budgetMode
```

---

## P3：官网 Marketing Agent

完成：

```text
三栏
房间导航
需求档案
POST SSE
商品卡
```

但此时商品卡动作必须是：

```text
加入清单
```

不是：

```text
最终确认
```

---

## P4：Shortlist

完成：

```text
add
remove
preferredVariant
Reference
Room scope
Shortlist Summary
```

---

## P5：Variant 空间适配

完成：

```text
Variant-level hard filter
eligibleVariants
width
depth
```

---

## P6：Room-aware Companion Skill

完成：

```text
Reference Shortlist Item
→ Living Room Skill
→ Dining Skill
→ Bedroom Skill
```

---

## P7：Selection 页面

完成：

```text
分房间
分品类
比较候选
最终采用
Variant
Quantity
```

---

## P8：Solution / Quote / Scheme

完成：

```text
Confirmed
→ RSKU
→ Quote
→ Scheme
```

---

## P9：生产与视觉收口

完成：

```text
rooom.vip 现有品牌视觉
SSR
SSE Proxy
响应式
权限
测试
```

---

# 50. MVP 验收标准

- [ ] 用户可以上传并确认户型；
- [ ] confirmed 户型可以进入智能选品；
- [ ] 普通客户可以登录并使用 Agent；
- [ ] 整体偏好通过结构化 UI 保存；
- [ ] 没有预算也可以选品；
- [ ] Agent 按 Room 工作；
- [ ] Global Requirement 和 Room Requirement 隔离；
- [ ] FloorPlan Fact 不被 LLM 修改；
- [ ] 商品尺寸基于 Variant 过滤；
- [ ] 一个 RSPU 只要有适配 Variant 就可以推荐；
- [ ] Agent 商品卡可以“加入清单”；
- [ ] 同一品类可以同时保留多个喜欢商品；
- [ ] Shortlist 与 user_favorite 分离；
- [ ] Shortlist 带 roomId；
- [ ] 历史推荐卡加入清单不会串到当前 activeRoom；
- [ ] 可以设置一个搭配 Reference；
- [ ] Companion Skill 使用当前 Room 的 Reference；
- [ ] Agent 页不错误汇总所有 Shortlist 为整屋总价；
- [ ] `/selection` 可以整理全部备选；
- [ ] 最终确认时必须锁定 Variant；
- [ ] ConfirmedItem 可以追溯 Shortlist / Room / Variant；
- [ ] RSKU 必须基于最终 Variant 解析；
- [ ] `/solution` 只读取最终 ConfirmedItem；
- [ ] 整屋价格只计算最终采用商品；
- [ ] SchemeItem 可以追溯具体 FloorPlan Room；
- [ ] Quote 保留 Room / RSPU / Variant / RSKU；
- [ ] 一个 Session 可以完成完整整屋流程。

---

# 51. 本期仍不做

暂不建设：

```text
Multi-Agent
复杂 Supervisor
3D 自动布置
家具碰撞检测
门窗精确避让
BIM
PostGIS
水电分析
承重墙分析
自动渲染
复杂组合优化器
```

第一阶段重点：

```text
空间正确
推荐真实
喜好可保留
决策不提前
Variant 正确
报价可追溯
```

---

# 52. 最终业务架构

```text
                     rooom.vip
                         │
                         ▼
                    上传户型
                         │
                         ▼
                  识别 / 人工确认
                         │
                         ▼
                    整体偏好
                         │
                         ▼
                Marketing Agent
                         │
                 推荐真实商品
                         │
            ┌────────────┴────────────┐
            │                         │
          不喜欢                    喜欢
            │                         │
         继续推荐                 Shortlist
                                      │
                    ┌─────────────────┴─────────────────┐
                    │                                   │
                 沙发 A                              沙发 B
                    │                                   │
                    └────────────────┬──────────────────┘
                                     │
                              Reference Item
                                     │
                                     ▼
                               推荐配套产品
                                     │
                                     ▼
                              分房间 Shortlist
                                     │
                                     ▼
                                Selection
                          比较 / Variant / 数量
                                     │
                                     ▼
                                Confirmed
                                     │
                                     ▼
                         Variant → RSKU → Supply
                                     │
                         ┌───────────┴───────────┐
                         ▼                       ▼
                       Scheme                   Quote
                         │                       │
                         └───────────┬───────────┘
                                     ▼
                                    Order
```

---

# 53. 最终产品定义

改造完成后，Marketing Agent 不再是：

```text
推荐一个商品
→ 用户立即最终确认
```

而是：

> **帮助用户在真实户型和真实商品数据的约束下，持续发现喜欢的家具，并按房间建立一个可比较、可调整的备选池；用户完成初选后，再进入专门的方案整理阶段确定最终商品、规格和数量，最后形成可报价、可导出、可下单的整屋方案。**

最终业务主线：

```text
户型
→ 整体偏好
→ Agent 推荐
→ 喜好清单
→ 搭配参考
→ 房间初选
→ 备选整理
→ 最终确认
→ 方案
→ 报价
→ 订单
```
