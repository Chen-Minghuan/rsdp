package com.rsdp.service.vector;

import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.ProductImageEmbedding;
import com.rsdp.exception.ExternalServiceException;
import com.rsdp.mapper.ProductImageEmbeddingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * pgvector 版产品图片向量存储实现。
 *
 * <p>向量主存 PostgreSQL {@code product_image_embedding} 表（vector(1024)），
 * 经 {@link ProductImageEmbeddingMapper} 完成余弦距离检索（{@code <=>}）、
 * 行锁版本核验、幂等 upsert 与批量删除。检索固定过滤当前编码配置、
 * 图片内容版本一致、图片/产品未删除，品类/在售状态在查询时经 JOIN 过滤，
 * 无需像 ChromaDB 那样同步冗余 metadata。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PgvectorProductVectorStore implements ProductVectorStore {

    private final ProductImageEmbeddingMapper mapper;

    /**
     * 相似图片检索。
     *
     * <p>查询向量先经 {@link ProductVectorProfile#validate} 校验维度、有限数值与非零范数，
     * 再以当前编码配置调用 Mapper；存储故障包装为
     * {@link ExternalServiceException}，区别于合法空结果。</p>
     *
     * @param queryVector  查询向量（维度须为 {@link ProductVectorProfile#DIMENSION}）
     * @param limit        返回上限
     * @param categoryCode 品类过滤，null/空 = 不限
     * @param activeOnly   true = 仅在售产品；false = 不限状态
     * @return 命中列表（原始余弦距离升序）
     * @throws IllegalArgumentException    向量校验失败
     * @throws ExternalServiceException    存储故障
     */
    @Override
    public List<VectorHit> search(float[] queryVector, int limit, String categoryCode, boolean activeOnly) {
        ProductVectorProfile.validate(queryVector);
        String category = categoryCode != null && !categoryCode.isBlank() ? categoryCode.trim() : null;
        try {
            return mapper.search(queryVector, limit, ProductVectorProfile.CURRENT, category, activeOnly);
        } catch (Exception e) {
            log.error("pgvector 相似图片检索失败", e);
            throw new ExternalServiceException("向量检索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 写入某张图片在当前编码配置下的向量（幂等，冲突全量覆盖）。
     *
     * <p>短事务内先锁定图片行并核验：行存在、未删除、内容版本仍等于
     * {@code expectedSourceRevision}；不匹配抛出 {@link VectorStaleImageException}
     * （调用方按"丢弃"处理，防旧写覆盖新内容）。</p>
     *
     * @param imageId                图片 ID
     * @param expectedSourceRevision 编码时读取到的图片内容版本
     * @param inputHash              实际送入 embedding API 的字节 SHA-256
     * @param embedding              向量
     * @throws IllegalArgumentException   向量校验失败
     * @throws VectorStaleImageException  图片不存在、已删除或内容版本已变更
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void upsert(String imageId, long expectedSourceRevision, String inputHash, float[] embedding) {
        ProductVectorProfile.validate(embedding);
        ImageAssets row = mapper.lockById(imageId);
        if (row == null) {
            throw new VectorStaleImageException("图片不存在: " + imageId);
        }
        if (row.getDeletedAt() != null) {
            throw new VectorStaleImageException("图片已删除: " + imageId);
        }
        if (row.getContentRevision() == null || row.getContentRevision() != expectedSourceRevision) {
            throw new VectorStaleImageException("图片内容版本已变更: " + imageId);
        }
        ProductImageEmbedding entity = new ProductImageEmbedding();
        entity.setImageId(imageId);
        entity.setProfileId(ProductVectorProfile.CURRENT);
        entity.setSourceRevision(expectedSourceRevision);
        entity.setInputHash(inputHash);
        entity.setEmbedding(embedding);
        mapper.upsert(entity);
    }

    /**
     * 批量读取已存在向量的编码配置标识与内容版本。
     *
     * @param imageIds 图片 ID 列表
     * @return imageId → 配置标识与版本；不存在的不出现在 Map 中；空列表返回空 Map
     */
    @Override
    public Map<String, ExistingVector> findExisting(List<String> imageIds) {
        if (imageIds == null || imageIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return mapper.findExisting(imageIds).stream()
            .collect(Collectors.toMap(ExistingVector::imageId, v -> v, (a, b) -> a));
    }

    /**
     * 按图片 ID 幂等删除。
     *
     * @param imageIds 图片 ID 列表
     * @return 删除行数；空列表返回 0
     */
    @Override
    public int deleteByImageIds(List<String> imageIds) {
        if (imageIds == null || imageIds.isEmpty()) {
            return 0;
        }
        return mapper.deleteByImageIds(imageIds);
    }
}
