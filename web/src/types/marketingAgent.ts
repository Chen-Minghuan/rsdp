/**
 * 营销选品 Agent 类型定义（与后端 API 契约一一对应）。
 */

/** 选品会话。 */
export interface AgentSession {
  sessionId: string
  /** 设计师代录时的客户名（未代录为 null） */
  customerName: string | null
  status: 'active' | 'closed'
  currentVersionNo: number
  summary: string | null
  createdAt: string
  updatedAt: string
}

export type AgentMessageRole = 'user' | 'assistant' | 'system'
export type AgentMessageType = 'text' | 'cards' | 'requirement' | 'notice'

/** 会话消息。messageType=cards 时 metadata.items 为 RecommendItem[]。 */
export interface AgentMessage {
  messageId: string
  role: AgentMessageRole
  messageType: AgentMessageType
  content: string
  metadata: Record<string, unknown> | null
  sequenceNo: number
  createdAt: string
}

/** 需求档案（版本化，SSE requirement 事件全量刷新）。 */
export interface RequirementProfile {
  versionNo: number
  constraints: RequirementConstraints
  source: string
}

/** 需求约束字段（均可选，随对话逐步补全）。 */
export interface RequirementConstraints {
  categoryCode?: string
  categoryName?: string
  style?: string
  material?: string
  color?: string
  budgetMax?: number
  maxWidthMm?: number
  minWidthMm?: number
  sofaForm?: string
  areaM2?: number
  note?: string
}

/** 推荐卡片上的产品快照（推荐时刻的展示口径）。 */
export interface ProductSnapshot {
  productName: string
  categoryPath?: string
  primaryImageUrl?: string
  colorPrimaryName?: string
  material?: string
  sizeText?: string
  retailPrice?: number
}

/** 推荐理由单条亮点。 */
export interface RecommendHighlight {
  text: string
  evidenceRefs: string[]
}

/** 推荐卡片项。 */
export interface RecommendItem {
  itemId: string
  batchId: string
  rspuId: string
  rank: number
  snapshot: ProductSnapshot
  reason: { highlights: RecommendHighlight[] } | null
}

/** 已确认的主体产品。 */
export interface ConfirmedItem {
  itemId: string
  rspuId: string
  productName?: string
  spec: Record<string, unknown> | null
  quantity: number
  status: string
  createdAt: string
}

/** 会话详情（GET /v1/agent/sessions/{sessionId}）。 */
export interface AgentSessionDetail {
  session: AgentSession
  messages: AgentMessage[]
  requirement: RequirementProfile | null
  confirmedItems: ConfirmedItem[]
}

/** 创建会话请求（设计师代录时填客户名）。 */
export interface CreateAgentSessionRequest {
  customerName?: string
}

/** 发送消息请求（SSE 流式）。 */
export interface SendAgentMessageRequest {
  clientMessageId: string
  content: string
}

/** 确认主体产品请求。 */
export interface ConfirmAgentItemRequest {
  recommendItemId: string
  quantity: number
  idempotencyKey: string
}

// ==================== SSE 事件（data 均含 runId/seq，seq 单调递增） ====================

export interface AgentStreamEventBase {
  runId: string
  seq: number
}

export interface AgentStreamMetaEvent extends AgentStreamEventBase {
  sessionId: string
}

export interface AgentStreamNodeEvent extends AgentStreamEventBase {
  node: string
  /** 节点展示文案，如「正在理解需求」「正在检索产品」 */
  label: string
}

export interface AgentStreamTokenEvent extends AgentStreamEventBase {
  text: string
}

export interface AgentStreamRequirementEvent extends AgentStreamEventBase {
  profile: RequirementProfile
}

export interface AgentStreamCardsEvent extends AgentStreamEventBase {
  batchId: string
  items: RecommendItem[]
}

export interface AgentStreamDoneEvent extends AgentStreamEventBase {
  messageId: string
}

export interface AgentStreamErrorEvent extends AgentStreamEventBase {
  code: string
  message: string
}
