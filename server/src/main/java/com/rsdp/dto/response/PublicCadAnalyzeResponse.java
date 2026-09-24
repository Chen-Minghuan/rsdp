package com.rsdp.dto.response;

/**
 * 官网游客 CAD 异步分析创建响应。
 *
 * @param analysisId  分析批次 ID
 * @param taskId      异步任务 ID
 * @param accessToken 仅用于该分析记录的短期访问凭证
 */
public record PublicCadAnalyzeResponse(String analysisId, String taskId, String accessToken) {
}
