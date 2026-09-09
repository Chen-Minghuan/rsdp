package com.rsdp.service.vector;

/**
 * 已存在向量的配置标识与内容版本（幂等判断用）。
 *
 * @param imageId        图片 ID
 * @param profileId      编码配置标识
 * @param sourceRevision 生成时的图片内容版本
 */
public record ExistingVector(String imageId, String profileId, long sourceRevision) {
}
