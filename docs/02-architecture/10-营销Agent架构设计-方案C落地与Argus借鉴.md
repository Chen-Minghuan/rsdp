# RSDP 营销 Agent 架构设计：方案 C 落地与 Argus 借鉴

> 版本：v1.1 · 日期：2026-09-15 · 状态：**P1 MVP 已实现**（后端 com.rsdp.agent 全量 + 前端页面 + 87 新用例，全量 1593 测试通过）。
> v1.1 变更：纳入架构评审 20 条意见——长期分层（Graph≠Skill≠Tool）、agent_message 与 agent_recommend_item 落表、actor/subject 会话归属、RequirementPatch Reducer、推荐理由结构化 Grounding、尺寸硬约束、Checkpoint 适配层、Session 并发控制、SSE 生产细节。
> 前置文档：[[RSDP_营销Agent_MVP_PRD_V0.1]]、[[08-向量数据库改造方案-pgvector与双Agent检索底座]]。
> Argus（github.com/DevYangJC/Argus）仅作为技术参考来源；本文只吸收其经过验证的设计点。

## 1. 定位与边界

营销 Agent 建立在 RSDP 仓库内，复用 RSDP 的商品主数据、鉴权、定价与订单能力。
业务主链路：

```text
找产品 → 主体产品确认与反馈（LV1：沙发/餐桌/床）
      → 配套产品推荐（LV2：茶几等）
      → 方案搭配（效果图确认与反馈）
      → 一键报价 → 订单成交付款
```

MVP 只做到主体产品确认（PRD M01–M08）。LV2 配套、效果图、报价成交是后续阶段，
数据模型与工具接口已预留，避免二期推翻重来。

## 2. 长期目标架构

```text
                        Web / Customer Portal
                                │
                           SSE / REST
                                │
                    MarketingAgentFacade
                                │
                    ┌───────────▼───────────┐
                    │      SAA Graph        │
                    │   业务流程编排 / HITL   │
                    └───────────┬───────────┘
                                │
            ┌───────────────────┼────────────────────┐
            │                   │                    │
      Requirement          Capability           Recommendation
         Node               Runtime                 Node
                                │
                    ┌───────────┴───────────┐
                    │                       │
                Tool Registry          Skill Registry
                    │                       │
             确定性业务能力            业务策略/操作流程
                    │                       │
            ┌───────▼───────────────────────▼───────┐
            │           RSDP Domain Service          │
            │  ProductQuery / Pricing / Order / Auth │
            └─────────────────┬──────────────────────┘
                              │
                         PostgreSQL
```

### 2.1 关键关系：Graph ≠ Skill ≠ Tool

| 层 | 负责什么 | RSDP 示例 |
|---|---|---|
| Graph | 业务流程 | 需求→确认→检索→推荐→反馈→确认 |
| Skill | 怎么完成某类任务（策略/流程） | 沙发选品、客厅搭配、报价准备 |
| Tool | 具体能力 | search_products、get_product_detail、price_quote |
| Service | 真正业务逻辑 | MarketingProductQueryService、PricingService |
| DB | 业务事实 | 商品、需求版本、确认清单 |

**未来即使增加 30 个 Skill，Graph 主干也不应大改。** Skill 以节点形式挂在 Graph 上
（SkillNode → ReactAgent → Skill → Tools），Graph 决定什么时候干什么，Skill 决定某个专业任务应该怎么干。

### 2.2 Skill 规范（P2 引入，本文先行约定）

- 首选场景：LV2 客厅搭配（living-room-matching）、各品类选品（sofa-selection 等）。
- Skill 元数据：`id / version / description / riskLevel / allowedTools / requiredPermissions / inputSchema / outputSchema / maxSteps`，
  使每次推荐可追溯"用了哪个 skill 哪个版本、哪些 tool、哪个模型、客户是否确认"。
- **权限边界长期坚持：Skill 只能调读工具；写工具（confirm_item / create_quote / create_order）归 Graph + HITL 控制。**
  Skill 提出建议 → Graph → HITL → 用户确认 → Write Tool。
- Tool 接口必须稳定（如 `search_products(criteria)`），检索内部从"关键词"升级到"结构化+向量+RRF"时 Skill 零改动。
- 不上 Multi-Agent：直到出现真正"独立上下文 + 独立目标 + 独立工具 + 互相委派"的多个 Agent，
  才考虑 Supervisor/Subagent/AgentScope（评估门见 §8）。

## 3. 设计原则

1. **真实产品优先**：产品事实只来自工具返回。推荐卡片存 candidate snapshot；推荐理由为结构化
   highlights + evidenceRefs（必须指向 snapshot 字段），不止校验 rspu_id 白名单——防"产品存在但参数被编造"。
2. **客户确认优先**：所有状态推进都是 HITL 节点，模型只能建议。
3. **需求更新走 Patch**：LLM 输出 RequirementPatch（operations: field/operation/value/evidence），
   **Java Reducer 负责真正修改**；不让模型重生成完整档案再做 diff（那是检测模型犯错，不是防止犯错）。
4. **业务状态归业务库，执行状态归框架**：Graph State（OverAllState）只放 ID
   （sessionId/runId/requirementVersionId/batchId/pendingAction）；恢复时按 ID 从业务表重放，
   不从 checkpoint 反序列化业务数据。
5. **聊天档案与运行 checkpoint 分离**：agent_message 是会话业务档案（刷新恢复聊天用它）；
   Graph checkpoint 只是运行时恢复机制，不当业务数据用。
6. **复用现有服务**：检索、定价、订单、权限不重写，Agent 只通过工具接口调用。

### 3.1 数据归属

| 数据 | 存储与访问方式 |
|---|---|
| 商品主数据、实时价格、库存、交期 | RSDP 业务表，经 Domain Service 读取，不走向量库快照 |
| retailPrice（建议零售价） | MVP 可用于预算粗筛与展示，**明确标注"以正式报价为准"**；正式报价 P2 接 PricingService |
| 品牌规范、材质知识、搭配规则 | 文档库 + pgvector（二期知识库，见 §6.4） |
| 聊天消息 | agent_message（业务档案，含 client_message_id 幂等） |
| 需求档案、推荐批次/条目、反馈、确认清单 | RSDP 新业务表（见 §5） |
| Agent 执行状态 | agent_run 只存运行元信息 + checkpoint_id；运行状态本体在框架 checkpoint 存储 |

## 4. 技术栈与方案 C 落地

### 4.1 基线

- Spring Boot 3.4.0 → **3.5.16**；`spring-ai-alibaba-bom:1.1.2.3`
  （dashscope starter + graph-core）。
- 模型沿用 DashScope：qwen3-vl-plus、multimodal-embedding-v1。
  选 SAA 的原因：multimodal-embedding-v1 是 DashScope 私有 API，SAA 有原生支持。

### 4.2 分层

```text
┌─ 编排层：SAA Graph 状态机（PG checkpoint，HITL 中断点）
├─ 工具层：稳定接口的确定性能力（@Tool / 直接 Java 调用）
│     search_products → MarketingProductQueryService（不是裸 Mapper）
├─ 模型层：SAA DashScope ChatModel（新模块专用）
└─ 存储层：PostgreSQL 16 + pgvector（ProductVectorStore 保持自研，见 08 号文）
```

**明确不做**：不接 Spring AI 自带 PgVectorStore；本期不引 AgentScope；**v1 不动现有
VisionService/EmbeddingService**（底座替换为独立任务，避免与营销功能混成一次大改）。

## 5. 数据模型（V10 迁移，八张表）

| 表 | 关键列 | 说明 |
|---|---|---|
| agent_session | session_id PK；**created_by（actor）**；**customer_user_id NULL（subject）**；customer_name；status；current_version_no；**active_run_id NULL** | actor/subject 分离：设计师代录时 actor=设计师、subject=客户；客户注册后回填 customer_user_id 继承代录会话。active_run_id 做并发控制（一会话最多一个 running run，原子认领，冲突 409） |
| agent_message | message_id PK；session_id；run_id；**client_message_id**；role(user/assistant/system)；message_type(text/cards/requirement/notice)；content；metadata JSONB；sequence_no；created_at | 聊天业务档案。**UNIQUE(session_id, client_message_id)**：网络重试不会发起两次 Graph Run，已存在则续传原 run 事件流 |
| agent_requirement_version | version_id PK；session_id；version_no；constraints JSONB；**patch JSONB**；source(extract/followup/manual/feedback)；created_at | patch 记录本版应用的 operations，每次变更可追溯证据 |
| agent_recommend_batch | batch_id PK；session_id；version_no；query_criteria JSONB；created_at | 瘦身，不塞候选 JSONB |
| agent_recommend_item | item_id PK；batch_id；rspu_id；**rank**；rank_score；**snapshot JSONB**；**reason JSONB（highlights+evidenceRefs）**；created_at | "第二款"是稳定实体（recommend_item_id），不是 JSON 数组下标；snapshot 是 Grounding 锚点；为曝光/点击/拒绝/Top1/Top3 确认率分析预留 |
| agent_feedback | feedback_id PK；session_id；**item_id NULL**；batch_id；action(confirm/reject/modify)；parsed JSONB；created_at | 反馈可指向具体推荐条目 |
| agent_confirmed_item | item_id PK；session_id；**recommend_item_id**；rspu_id；spec JSONB；quantity；status；**idempotency_key UNIQUE**；confirmed_by；created_at | 写操作，HITL 后置 + 幂等键 |
| agent_run | run_id PK；session_id；status(running/waiting_human/done/failed)；current_node；**checkpoint_id**；framework_version；started_at；finished_at；error_code | 只存运行元信息，**不存 graph_state** |

SAA 框架 checkpoint 表由框架 Saver 自建（经 AgentCheckpointStore 适配层访问，业务代码不直接依赖框架 Saver 接口——框架此区域近期仍有修复，留兼容层）。

## 6. 主链路 → Graph 状态机映射

```text
需求提取(LLM 输出 Patch → Java Reducer 应用 → 新版本)
   ↓ 缺关键约束（品类/预算/形态/最大宽度）
主动追问（有限次）↩
   ↓ 约束齐备
【HITL-1】用户确认/编辑需求档案
   ↓
产品检索(search_products → MarketingProductQueryService)
   ↓
候选推荐(LLM 输出结构化理由；Java 校验白名单+evidenceRefs；落 batch+items)
   ↓
反馈解析(LLM 输出 Patch + 目标 item；rank→recommend_item_id)
   ├── 修改 → Patch → 重新检索
   └── 【HITL-2】客户确认主体（LV1）→ 写工具落 confirmed_item
        ↓ （P2）
   配套推荐(SkillNode → living-room-matching Skill) → 方案搭配 →【HITL-3】效果图确认
        ↓
   一键报价(PricingService) →【HITL-4】报价确认 → 订单创建 → 支付
```

尺寸是硬约束：MVP 支持 maxWidthMm/minWidthMm 经 rspu_variant 真实过滤
（"≤2.8m" 的沙发场景必须能跑通；不做完整尺寸 DSL）。
反馈修改遵守"保留已确认约束，只改用户明确调整的条件"，由 Patch 模型从机制上保证。

## 7. 从 Argus 吸收的设计点

### 7.1 证据引用与溯源（吸收并落实到数据结构）

- 推荐卡片 = recommend_item.snapshot（产品事实快照，来自工具返回）。
- 推荐理由 = highlights[] + evidenceRefs[]（必须 ∈ snapshot 字段名，Java 校验）。
- 链式 Grounding：Reason → EvidenceRef → Candidate Snapshot → 真实产品。

### 7.2 混合检索与 RRF 融合（接口预留，v2 实现）

- v1 单通道：结构化过滤（品类/风格/材质/颜色/预算/**尺寸**）+ 关键词 + DictAliasService 别名归一。
- v2 加向量召回 + RRF 时，只演进 MarketingProductQueryService 内部，
  `search_products(criteria)` 接口不变，Graph/Skill 零改动。不引入 Elasticsearch。

### 7.3 会话记忆压缩（吸收）

长会话摘要压缩：保留已确认约束与未决问题，写入 agent_session.summary。

### 7.4 知识库（二期）

品牌规范、材质知识、搭配规则文档化入 pgvector，供推荐理由与方案文案引用；
权限沿用 RSDP 群组体系。

### 7.5 不吸收

You.com 外部市场搜索；单轮一次检索限制（按"有限次数+去重+预算控制"设计）。

## 8. 分期路线

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0 底座 | Boot 3.5.16 + SAA 1.1.2.3 接入；现有功能回归 | 现有识别/检索/导入行为不变 |
| P1 MVP | §6 到主体确认；八张表；SSE 会话；Patch/Reducer；结构化理由 | PRD M01–M08 + "≤2.8m" 真实过滤 |
| P2 搭配与报价 | **Skill 体系引入**（LV2 living-room-matching 首选）；正式报价接 PricingService；订单转化接 OrderService | 沙发→茶几→报价→下单全链路 |
| P3 效果图与知识库 | 效果图生成确认环；文档知识库 | 方案效果图确认可用 |

**AgentScope/Multi-Agent 评估门（P2 之后）**：仅当出现真正"独立目标+独立工具+互相委派"的
多 Agent 需求时，用同一批任务做对照（事实准确率、恢复成功率、重试幂等、Token 成本）。

## 9. SSE 协议（v1 即按生产标准）

- 通道：`POST /api/v1/agent/sessions/{id}/messages/stream`，fetch + ReadableStream（EventSource 不支持 POST）。
- 帧：`event: <类型>\ndata: <JSON>\n\n`；事件 data 均含 **runId + seq（单调递增）**，解决乱序/重复/断线续传。
- 事件：meta（首帧）→ node（节点切换）→ token（LLM 增量）→ requirement（档案更新）→ cards（推荐条目，含 snapshot）→ done / error。
- 心跳：15s 无事件发 `: ping` 注释帧，防网关空闲断连。
- 异常：流中途失败发 error 帧后优雅 close。
- 认证：本项目为 HttpOnly Cookie（apiClient withCredentials:true），SSE fetch 显式 `credentials:'include'`。
- 前端：AbortController 取消；断线按 lastSeq 重连；提交消息带 client_message_id（uuid）幂等。

## 10. 风险与对策

| 风险 | 对策 |
|---|---|
| 模型编造产品参数 | snapshot 锚定 + evidenceRefs Java 校验（不止 rspu_id 白名单） |
| 反馈解析误改已确认约束 | Patch 模型：LLM 只能提交 operations，Reducer 校验 field 白名单/operation 类型/evidence 必填 |
| 网络重试重复发起 Run | UNIQUE(session_id, client_message_id)，已存在则续传原 run |
| 双标签页并发改同一需求 | active_run_id 原子认领，冲突 409 SESSION_BUSY |
| 恢复会话状态不一致 | Graph State 只放 ID；业务表重放；checkpoint 只恢复运行位置 |
| 重试造成重复确认/下单 | 写工具幂等键（idempotency_key UNIQUE）；幂等命中校验会话归属 |
| 权限越界 | ProductVisibilityPolicy 集中一处；未授权结果不进模型上下文；客户 DTO 裁剪内部字段 |
| 设计师代录会话归属纠纷 | actor（created_by）/subject（customer_user_id）分离；客户注册后回填继承 |

## 11. v1 实现说明（与设计的偏差与已知边界）

实现于 2026-09-15，技术栈：Boot 3.5.16 + SAA 1.1.2.3（dashscope starter + graph-core）。

**关键实现选择：**
- **run 边界即 HITL 边界**：每轮用户消息跑一次完整 StateGraph 即结束，等待下一条消息/确认点击
  天然构成人工确认点；不做图内 interrupt/resume。checkpoint 以 threadId=runId 经
  AgentCheckpointStore（PostgresSaver 主用、MemorySaver 兜底）留痕，agent_run 只存元信息。
- 意图分流：RequirementPatchNode 后经条件边 → followup / product_search→recommend /
  chitchat / confirm_hint（CONFIRM_ITEM 意图引导用户点"确认这款"按钮，确认动作为显式 HITL）。
- 关键约束齐备判断：categoryCode / budgetMax / sofaForm∨maxWidthMm 三组取二。
- SSE：meta/node/token/requirement/cards/done/error + 15s `: ping` 心跳 + runId/seq；
  stream 接口校验失败返回真实 HTTP 状态（409/404/403），普通接口走 GlobalExceptionHandler
  （HTTP 200 + body code）——两条错误路径前端分别处理，属有意设计。
- 权限：agent:use 经 AgentUsePermissionMigration 启动期幂等授予 ADMIN/EDITOR/DESIGNER；
  ROLE_CUSTOMER 待客户角色体系确定后追加。

**已知边界（v2 处理）：**
- 尺寸硬过滤为 SQL 预取（500 条窗口）+ 内存精筛，totalMatched 是窗口内数量，产品库变大后偏低；
- style/material 用 JSON 文本 like 匹配（MVP 简化），存在子串误命中风险，v2 改 JSONB 查询；
- 异步段（图执行/落库/done 帧）暂无集成测试，单测覆盖同步段；
- 向量召回/RRF 未接入（MarketingProductQueryService 接口已预留，接入时 Graph/前端零改动）。

**已踩坑（勿复踩）：**
- `spring.ai.dashscope.chat.options.model` 必须配**文本模型**（当前 qwen-plus）。
  qwen3-vl-plus 属多模态端点（multimodal-generation），SAA ChatModel 走文本链路调用会 400，
  且节点有降级设计（抽取失败→chitchat、闲聊失败→静态兜底文案），故障表现为"Agent 只会说同一句话"
  而 run 状态仍是 done——排障时先查后端日志的 LLM 调用异常。
- **SSE 异步重分发鉴权**：SseEmitter 完成/超时时容器按 ASYNC 分发重走过滤器链，
  OncePerRequestFilter 默认只跑 REQUEST 分发 → 重分发时匿名身份被 AuthorizationFilter 拒绝
  （Access Denied + response already committed）。修复：JwtAuthenticationFilter 覆写
  `shouldNotFilterAsyncDispatch()=false`（token 在 cookie/header 上重分发仍可读，重复认证幂等）。
  此类问题单测（WebMvcTest addFilters=false）覆盖不到，只能实测发现。
- **jsonb 列裸 LIKE**：`six_dim_tags`/`material_tags` 是 jsonb 类型，PostgreSQL 无
  `jsonb ~~ unknown` 运算符，`wrapper.like("material_tags", x)` 直接 SQL 报错、run 挂在
  product_search。修复：`wrapper.apply("material_tags::text LIKE {0}", "%x%")` 显式转文本
  （单测走 mock 不进真实 SQL，此类问题只能靠实测/集成测试发现）。
- **品类词 ≠ 品类码**：LLM 抽取的 categoryCode 是品类词（沙发/座椅），rspu_master.category_code
  存的是 category_dict 字典码（SF/FS），直接等值必空结果。修复：检索前经
  DictResolverService.resolveCodeByName("category", …)（标准名/英文名/别名）归一，
  未命中保留原文（等值不命中，不静默放宽过滤）。

**测试**：新增 87 用例全部通过（Patch Reducer 15、查询服务 8、可见性 2、路由/齐备判断 18、
确认幂等 7、会话隔离 11、Controller 12、LLM JSON 容错 9、Stream 同步段 5）。
