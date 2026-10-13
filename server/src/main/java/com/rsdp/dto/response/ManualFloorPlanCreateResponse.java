package com.rsdp.dto.response;

/**
 * 无图纸手画布局建单响应。
 *
 * @param analysisId 分析批次 ID
 * @param status 当前状态（draft）
 * @param layoutRevision 初始布局版本
 */
public record ManualFloorPlanCreateResponse(String analysisId, String status, long layoutRevision) {
}
