package com.rsdp.service.vector;

/**
 * 相似图片检索命中项。
 *
 * @param imageId  图片 ID
 * @param rspuId   所属 RSPU ID
 * @param distance 原始余弦距离（pgvector {@code <=>}，范围 [0,2]，升序排列）
 */
public record VectorHit(String imageId, String rspuId, double distance) {
}
