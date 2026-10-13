package com.rsdp.dto.response;

/**
 * 手画布局保存响应。
 *
 * @param analysisId 分析批次 ID
 * @param layoutRevision 保存后的新版本
 */
public record ManualFloorPlanLayoutSaveResponse(String analysisId, long layoutRevision) {
}
