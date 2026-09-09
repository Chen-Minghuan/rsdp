package com.rsdp.service.vector;

import java.util.List;
import java.util.Map;

/**
 * 产品图片向量存储接口（P0 最小面）：检索、写入、存在性查询、删除。
 *
 * <p>只围绕现有图片业务，不接受模型生成的 SQL，不暴露存储内部结果格式；
 * 权限、商品聚合与业务重排仍由上层 Service 负责。实现为 pgvector
 * （{@code PgvectorProductVectorStore}）。所有向量均为
 * {@link ProductVectorProfile#CURRENT} 编码配置下的 1024 维浮点向量。</p>
 */
public interface ProductVectorStore {

    /**
     * 相似图片检索。
     *
     * <p>固定过滤当前编码配置、图片内容版本一致、图片/产品未删除；
     * 结果按原始余弦距离升序（HNSW 索引）。分数换算
     * {@code clamp(1 - distance / 2, 0, 1)} 由调用方负责，语义不变。</p>
     *
     * @param queryVector  查询向量（维度须为 {@link ProductVectorProfile#DIMENSION}）
     * @param limit        返回上限（调用方按候选倍数放大后传入）
     * @param categoryCode 品类过滤，null/空 = 不限
     * @param activeOnly   true = 仅在售产品（检索语义）；false = 不限状态（疑似同款检测语义）
     * @return 命中列表；合法空结果返回空列表
     * @throws com.rsdp.exception.ExternalServiceException 存储故障（区别于合法空结果）
     */
    List<VectorHit> search(float[] queryVector, int limit, String categoryCode, boolean activeOnly);

    /**
     * 写入某张图片在当前编码配置下的向量（幂等，冲突全量覆盖）。
     *
     * <p>实现内在短事务中锁定图片行并核验：内容版本仍等于
     * {@code expectedSourceRevision}、图片未删除；不匹配抛出
     * {@link VectorStaleImageException}（调用方按"丢弃"处理）。
     * 写入前校验维度、有限数值与非零范数。</p>
     *
     * @param imageId               图片 ID
     * @param expectedSourceRevision 编码时读取到的图片内容版本
     * @param inputHash             实际送入 embedding API 的字节 SHA-256
     * @param embedding             向量
     * @throws IllegalArgumentException   向量校验失败
     * @throws VectorStaleImageException  图片已更新或删除
     */
    void upsert(String imageId, long expectedSourceRevision, String inputHash, float[] embedding);

    /**
     * 批量读取已存在向量的编码配置标识与内容版本。
     *
     * @param imageIds 图片 ID 列表
     * @return imageId → 配置标识与版本；不存在的不出现在 Map 中
     */
    Map<String, ExistingVector> findExisting(List<String> imageIds);

    /**
     * 按图片 ID 幂等删除。
     *
     * @param imageIds 图片 ID 列表
     * @return 删除行数
     */
    int deleteByImageIds(List<String> imageIds);
}
