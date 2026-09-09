package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.service.vector.ExistingVector;
import com.rsdp.service.vector.ProductVectorProfile;
import com.rsdp.service.vector.ProductVectorStore;
import com.rsdp.service.vector.VectorStaleImageException;
import com.rsdp.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 向量回填服务：为存量图片按源图重新生成 embedding 并写入 pgvector。
 *
 * <p>数据来源为最终保存的产品图（image_assets.storage_path），经 EmbeddingService
 * 统一编码（multimodal-embedding-v1，1024 维，cosine）；本服务不重复执行识别/同款审核。
 * 向量随图片内容版本（content_revision）幂等：已存在同配置同版本向量时直接跳过。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorBackfillService {

    private final RspuMapper rspuMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final EmbeddingService embeddingService;
    private final ProductVectorStore productVectorStore;
    private final StorageService storageService;

    /** 单次扫描页大小（游标翻页，避免大页内存压力） */
    private static final int SCAN_PAGE_SIZE = 200;

    /**
     * 回填指定数量的存量图片向量。
     *
     * <p>候选范围：已 AI 处理（ai_processed=true）、已关联 RSPU（rspu_id 非空）、
     * storage_path 非空且未逻辑删除的图片，且所属 RSPU 未软删。幂等跳过：向量库中
     * 已存在当前编码配置（{@link ProductVectorProfile#CURRENT}）且内容版本一致的
     * 向量时不重新编码（跳过不计成功/失败）。</p>
     *
     * <p>分页使用 created_at + image_id 游标：已完成的图片仍满足过滤条件，固定取第一页
     * 会导致每次调用都扫到同一批记录、永远无法推进。游标翻页让每次调用跳过已完成项，
     * 持续向后扫描直到实际处理满 batchSize（成功+失败计数，跳过不计）或候选集耗尽。</p>
     *
     * @param batchSize 本次处理数量（{@code <=0} 或 {@code >1000} 时归一为 100）
     * @return 处理结果统计（successCount / failedCount，跳过不计入）
     */
    public BackfillResult backfill(int batchSize) {
        if (batchSize <= 0 || batchSize > 1000) {
            batchSize = 100;
        }

        int success = 0;
        int failed = 0;
        java.time.LocalDateTime cursorCreatedAt = null;
        String cursorImageId = null;

        while (success + failed < batchSize) {
            QueryWrapper<ImageAssets> wrapper = new QueryWrapper<>();
            wrapper.eq("ai_processed", true)
                .isNotNull("rspu_id")
                .isNotNull("storage_path");
            if (cursorCreatedAt != null) {
                java.time.LocalDateTime cAt = cursorCreatedAt;
                String cId = cursorImageId;
                wrapper.and(w -> w.gt("created_at", cAt)
                    .or(n -> n.eq("created_at", cAt).gt("image_id", cId)));
            }
            wrapper.orderByAsc("created_at").orderByAsc("image_id");
            List<ImageAssets> images = imageAssetsMapper.selectPage(new Page<>(1, SCAN_PAGE_SIZE), wrapper).getRecords();
            if (images.isEmpty()) {
                break;
            }

            // 批量加载 RSPU，减少 N+1；selectBatchIds 已按 @TableLogic 过滤软删记录
            Set<String> rspuIds = images.stream()
                .map(ImageAssets::getRspuId)
                .collect(Collectors.toSet());
            Map<String, RspuMaster> rspuMap = rspuIds.isEmpty() ? Map.of() :
                rspuMapper.selectBatchIds(rspuIds).stream()
                    .collect(Collectors.toMap(RspuMaster::getRspuId, r -> r));

            // 批量查询向量库已存在项，做幂等跳过判断
            List<String> imageIds = images.stream().map(ImageAssets::getImageId).toList();
            Map<String, ExistingVector> existingMap = imageIds.isEmpty() ? Map.of() :
                productVectorStore.findExisting(imageIds);

            for (ImageAssets image : images) {
                if (success + failed >= batchSize) {
                    break;
                }
                try {
                    if (processImage(image, rspuMap.get(image.getRspuId()), existingMap)) {
                        success++;
                    }
                } catch (VectorStaleImageException e) {
                    // 编码期间图片被更新/删除，属正常并发结果：丢弃本次结果，由后续流程重编码
                    log.info("图片在编码期间已更新或删除，跳过向量化，imageId={}", image.getImageId());
                } catch (Exception e) {
                    failed++;
                    log.error("回填向量失败，imageId={}", image.getImageId(), e);
                }
            }

            ImageAssets last = images.get(images.size() - 1);
            cursorCreatedAt = last.getCreatedAt();
            cursorImageId = last.getImageId();
            if (images.size() < SCAN_PAGE_SIZE) {
                break; // 候选集已耗尽
            }
        }

        return new BackfillResult(success, failed);
    }

    /**
     * 处理单张图片：RSPU 缺失或向量已是最新时跳过（不计数）；否则读取源图、
     * 统一编码并写入向量库。
     *
     * @param image       图片记录
     * @param rspu        所属 RSPU（可能为 null，表示不存在或已软删）
     * @param existingMap 本页已向量的存在性查询结果
     * @return true=本次成功编码写入；false=跳过
     * @throws Exception 编码或写入失败
     */
    private boolean processImage(ImageAssets image, RspuMaster rspu, Map<String, ExistingVector> existingMap) throws Exception {
        if (rspu == null) {
            log.warn("RSPU 不存在或已删除，跳过 imageId={}", image.getImageId());
            return false;
        }

        long revision = image.getContentRevision() == null ? 1L : image.getContentRevision();
        ExistingVector existing = existingMap.get(image.getImageId());
        if (existing != null
            && ProductVectorProfile.CURRENT.equals(existing.profileId())
            && existing.sourceRevision() == revision) {
            log.debug("向量已存在且内容版本一致，跳过 imageId={}", image.getImageId());
            return false;
        }

        String objectKey = image.getStoragePath();
        if (objectKey == null || objectKey.isBlank()) {
            log.warn("图片缺少存储路径，跳过 imageId={}", image.getImageId());
            return false;
        }

        EmbeddingService.ImageEmbedding embedding;
        try (InputStream stream = storageService.get(objectKey)) {
            embedding = embeddingService.embedImageWithHash(stream);
        }

        productVectorStore.upsert(image.getImageId(), revision, embedding.inputHash(), embedding.vector());
        log.info("存量图片向量重建完成，imageId={}", image.getImageId());
        return true;
    }

    /**
     * 回填结果统计。
     */
    public record BackfillResult(int successCount, int failedCount) {
    }
}
