# 官网端营销 Agent 实施拆解

> 版本：v1.2 · 日期：2026-10-05 · 状态：W1/W2/W3 已完成（W4 游客模式暂缓）
> 前置文档：`docs/RSDP_营销Agent_MVP_PRD_V0.1.md`、`docs/02-architecture/10-营销Agent架构设计-方案C落地与Argus借鉴.md`、`docs/08-roadmap/RSDP 户型驱动营销 Agent 架构设计.md`
> 决策记录：① 官网端先开放设计师（DESIGNER 已有 agent:use 权限，后端零改动）；② 游客匿名模式后期再做（需签名会话凭证 + 额度限流，暂缓）；③ W2 数据模型改造含 scheme_item.floor_plan_room_id。

## 背景

管理端（web/）营销 Agent 已到 P2（对话选品 + 配套推荐 + 报价快照 + 方案导出）。本方案把 Agent 能力搬到官网（website/，Nuxt 3 SSR），并按户型驱动设计完成"上传户型 → 确认空间 → 按房间选品 → 整屋方案"闭环。

## 场景与降级规则

- **场景 A（口头描述）**：无户型，纯对话选品。任何会话都可走此路径。
- **场景 B（户型驱动）**：会话绑定 confirmed 户型后出现房间维度，约束 = 全屋偏好 + 房间需求 + 空间硬约束。
- **降级铁律**：`agent_session.floor_plan_analysis_id` 可空；无户型时硬约束层为空，体验与场景 A 完全一致。户型不是使用 Agent 的门槛。
- **效果图**：P3 可选增强，不在本方案范围。

## 阶段拆解

### W1 官网设计师版对话选品（场景 A 上官网）✅ 2026-10-03 完成

后端零架构改动（DESIGNER 已被 AgentUsePermissionMigration 授予 agent:use，官网登录态为 HttpOnly JWT Cookie 同源携带）。

| # | 任务 | 交付物 |
|---|---|---|
| 1 | 类型与 API 封装 | `website/types/agent.ts`、`useAgentApi.ts`（REST）、`useAgentChat.ts`（状态 + SSE 流，fetch + ReadableStream，seq 去重、AbortController、client_message_id 幂等） |
| 2 | `/agent` 页面 | 会话列表 / 消息流（文本气泡、思考过程折叠、推荐卡片、报价卡片、方案卡片）/ 需求档案 + 已确认清单侧栏 |
| 3 | 入口与守卫 | SiteHeader 设计师可见入口；非设计师访问跳 `/designer/login?redirect=/agent` |
| 4 | 验收 | 设计师官网内完成"描述需求 → 推荐 → 确认 → 报价 → 导出方案"闭环；`vue-tsc` + `pnpm build` 通过 |

### W2 户型绑定（后端核心，管理端/官网共用）✅ 2026-10-04 完成

| # | 任务 | 说明 |
|---|---|---|
| 1 | Flyway 新 V 脚本 | `agent_session` + `floor_plan_analysis_id`、`active_room_id`；`agent_requirement_version` + `room_id`；`agent_recommend_batch` + `room_id`；`agent_confirmed_item` 关联房间；`scheme_item` + `floor_plan_room_id`；同步 schema/13_agent.sql + reset_db.sql + 实体对账 |
| 2 | FloorPlanContextService + FloorPlanContextNode | 不调 LLM；注入 confirmed 房间事实；户型事实不进 RequirementPatch（Agent 不可改） |
| 3 | EffectiveConstraintComposer | 全局 + 房间 + 空间硬约束三层合并；优先级：空间硬约束 > 用户更严约束 > 偏好 > 推测；ProductSearchCriteria 扩展 maxDepthMm/roomType/roomAreaM2 |
| 4 | Graph 改造 | RoomSelectNode；activeRoom 切换不新建会话 |
| 5 | 会话创建扩展 | CreateSessionRequest + floorPlanAnalysisId；校验户型 confirmed + 归属本人 |
| 6 | Grounding 扩展 | 推荐理由可引用空间事实；推荐批次/确认条目追溯 room_id |
| 7 | 验收 | 户型驱动文档 §34 的 11 条验收标准 |

W2 落地补充：V19 迁移（原 V18，因远程 V18__drop_review_status 占位顺延）+ schema/reset_db 同步（`make check-migration-sync` 零差异）；`RequirementScope` 按字段拆分全局/房间作用域（风格/预算/备注全局，品类/材质/颜色/尺寸等按房间），合并有效视图经 SSE `requirement` 事件下发；空间硬约束 = 开间/进深各预留 600mm 过道（`EffectiveConstraintComposer`，只收紧不放宽）；`SWITCH_ROOM` 意图 + `RoomSelectNode` 切换房间不新建会话；新端点 `POST /sessions/{id}/rooms/{roomId}/activate`；确认条目/报价行追溯 roomId、方案导出透传 `floorPlanRoomId`。Agent 域 193 例 + 全量后端 2201 例全绿，web/website 类型检查与构建通过。

### W3 官网户型驱动体验（场景 B 上官网）✅ 2026-10-05 完成

| # | 任务 | 说明 |
|---|---|---|
| 1 | ai-match → Agent 直通 | 确认户型后"进入智能选品"→ 创建绑定会话 → 跳 /agent；设计师"我的户型"历史同样可发起 |
| 2 | 空间卡片首页 | 会话内展示房间列表（名称/面积/尺寸），点选或对话设定 activeRoom |
| 3 | 房间级体验 | 需求面板按房间分 scope、确认清单按房间分组、房间切换器 |
| 4 | 整屋收官 | "完成选品并生成方案"→ scheme 按房间分组 |

W3 落地补充：全部官网前端改动（后端零改动）。直通 = ai-match 确认户型后按钮 + 我的户型 confirmed 卡片 + /agent 新建会话下拉三入口；房间切换条 `AgentRoomBar` 点选 activate，与对话 SWITCH_ROOM 同源；报价卡片行项按房间分组（QuoteLine.roomId）+「生成整屋方案」文案；确认清单/需求档案按房间分组与徽标。管理端方案详情按户型房间分组展示未做（现有空间分区是产品 spaceTag 维度，与户型房间实例不同），留待后续单独设计。

### W4 游客匿名 Agent（暂缓）

签名会话凭证（仿 PublicFloorPlanCadService 24h token）+ ProductVisibilityPolicy 收紧（仅已确认上架商品）+ 额度限流（轮次/每 IP 会话数/SSE 并发）。等 W1–W3 真实使用数据出来后重新评估优先级。

## 风险与注意

- 官网报价卡片仅售价口径（agent_quote 本无成本字段），设计师端可见性沿用 ProductVisibilityPolicy。
- SSE 在官网走同源相对路径（nitro devProxy / 生产同域部署），避免跨域；官网暂未纳入 docker-compose 生产编排，部署方式需另行补齐。
- W2 改结构必须遵守 AGENTS.md 数据库同步约定（schema/ + reset_db.sql + V 增量 + check-migration-sync）。
