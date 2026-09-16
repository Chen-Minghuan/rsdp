/**
 * AI 推荐候选状态（后端落库为小写）。
 */
export type SchemeCandidateStatus = 'pending' | 'accepted' | 'rejected'

/**
 * AI 推荐候选项（对应后端 SchemeCandidateResponse）。
 */
export interface SchemeCandidate {
  candidateId: string
  /** 推荐请求 ID（来源上下文） */
  recommendRequestId: string
  rspuId: string
  rspuName?: string
  primaryImageUrl?: string
  rskuId?: string
  /** AI 推荐得分 */
  score?: number
  /** AI 推荐理由 */
  aiReason?: string
  /** 匹配因子（JSONB，结构由 AI 侧决定） */
  matchFactors?: Record<string, unknown>
  status: string
  createdBy?: string
  createdAt?: string
  updatedAt?: string
}

/**
 * 创建 AI 推荐候选请求。
 */
export interface SchemeCandidateCreateRequest {
  recommendRequestId: string
  rspuId: string
  rskuId?: string
  score: number
  aiReason?: string
  matchFactors?: Record<string, unknown>
}

/**
 * 批量创建请求（candidates 逐项同创建请求）。
 */
export interface SchemeCandidateBatchCreateRequest {
  recommendRequestId: string
  candidates: SchemeCandidateCreateRequest[]
}

/**
 * 更新候选请求（接受/拒绝即 { status: 'accepted' | 'rejected' }）。
 */
export interface SchemeCandidateUpdateRequest {
  rskuId?: string
  score?: number
  aiReason?: string
  matchFactors?: Record<string, unknown>
  status?: SchemeCandidateStatus
}
