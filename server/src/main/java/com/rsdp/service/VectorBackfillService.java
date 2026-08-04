package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.service.chroma.ChromaDbClient;
import com.rsdp.service.chroma.ChromaMetadataBuilder;
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
 * 向量回填服务：为存量图片生成 embedding 并写入 ChromaDB。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorBackfillService {

    private final RspuMapper rspuMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final EmbeddingService embeddingService;
    private final ChromaDbClient chromaDbClient;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    /** 单次扫描页大小（游标翻页，避免大页内存压力） */
    private static final int SCAN_PAGE_SIZE = 200;

    /**
     * 回填指定数量的存量图片向量。
     *
     * <p>对应 RSPU 已写入 style_vector 且 ChromaDB 中向量存在的图片会跳过，避免重复调用
     * embedding API；PG 有向量但 ChromaDB 缺失的记录会直接复用存量向量补偿回填。</p>
     *
     * <p>分页使用 created_at + image_id 游标：已完成的图片仍满足过滤条件，固定取第一页
     * 会导致每次调用都扫到同一批记录、永远无法推进。游标翻页让每次调用跳过已完成项，
     * 持续向后扫描直到实际处理满 batchSize（成功+失败计数，跳过不计）或候选集耗尽。</p>
     *
     * @param batchSize 本次处理数量
     * @return 处理结果统计
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

            // 批量加载 RSPU，减少 N+1
            Set<String> rspuIds = images.stream()
                .map(ImageAssets::getRspuId)
                .collect(Collectors.toSet());
            Map<String, RspuMaster> rspuMap = rspuIds.isEmpty() ? Map.of() :
                rspuMapper.selectBatchIds(rspuIds).stream()
                    .collect(Collectors.toMap(RspuMaster::getRspuId, r -> r));

            // 批量检查「PG 已有向量」的图片在 ChromaDB 中是否真实存在，修复 PG 有/Chroma 缺的死区
            Set<String> chromaExistingIds = queryChromaExistingIds(images, rspuMap);

            for (ImageAssets image : images) {
                if (success + failed >= batchSize) {
                    break;
                }
                try {
                    RspuMaster rspu = rspuMap.get(image.getRspuId());
                    if (processImage(image, rspu, chromaExistingIds)) {
                        success++;
                    }
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
     * 批量查询 PG 已有向量的图片在 ChromaDB 中的现存 ID；查询失败时按「全部缺失」处理，
     * 走存量向量补偿 upsert（幂等且无需重新调用 embedding API），保证自愈。
     */
    private Set<String> queryChromaExistingIds(List<ImageAssets> images, Map<String, RspuMaster> rspuMap) {
        List<String> candidateIds = images.stream()
            .filter(image -> {
                RspuMaster rspu = rspuMap.get(image.getRspuId());
                return rspu != null && rspu.getStyleVector() != null && !rspu.getStyleVector().isBlank();
            })
            .map(ImageAssets::getImageId)
            .toList();
        if (candidateIds.isEmpty()) {
            return Set.of();
        }
        try {
            return chromaDbClient.getExistingIds(candidateIds);
        } catch (Exception e) {
            log.warn("批量检查 ChromaDB 现存向量失败，按缺失处理并走补偿回填: {}", e.getMessage());
            return Set.of();
        }
    }

    private boolean processImage(ImageAssets image, RspuMaster rspu, Set<String> chromaExistingIds) throws Exception {
        if (rspu == null) {
            log.warn("RSPU 不存在或已删除，跳过 imageId={}", image.getImageId());
            return false;
        }

        String existingVector = rspu.getStyleVector();
        if (existingVector != null && !existingVector.isBlank()) {
            // ChromaDB 中向量真实存在才跳过，避免重复调用 API
            if (chromaExistingIds.contains(image.getImageId())) {
                log.debug("RSPU 已存在向量，跳过 imageId={}", image.getImageId());
                return false;
            }
            // PG 有向量但 ChromaDB 缺失：直接复用存量向量补偿回填，无需重新调用 embedding API
            float[] embedding = objectMapper.readValue(existingVector, float[].class);
            Map<String, Object> metadata = ChromaMetadataBuilder.buildProductMetadata(rspu,
                image.getFileSize() != null ? image.getFileSize() : 0);
            chromaDbClient.upsert(
                List.of(image.getImageId()),
                List.of(embedding),
                List.of(metadata),
                null
            );
            log.info("ChromaDB 缺失向量补偿回填完成，imageId={}", image.getImageId());
            return true;
        }

        String objectKey = image.getStoragePath();
        if (objectKey == null || objectKey.isBlank()) {
            log.warn("图片缺少存储路径，跳过 imageId={}", image.getImageId());
            return false;
        }

        float[] embedding;
        try (InputStream stream = storageService.get(objectKey)) {
            embedding = embeddingService.embedImage(stream);
        }

        // 先写 ChromaDB，成功后再更新 PG，避免 PG 已改但向量库缺失导致不一致
        Map<String, Object> metadata = ChromaMetadataBuilder.buildProductMetadata(rspu,
            image.getFileSize() != null ? image.getFileSize() : 0);
        chromaDbClient.upsert(
            List.of(image.getImageId()),
            List.of(embedding),
            List.of(metadata),
            null
        );

        // ChromaDB 写入成功后，再更新 RSPU style_vector
        rspu.setStyleVector(objectMapper.writeValueAsString(embedding));
        try {
            rspuMapper.updateById(rspu);
        } catch (Exception e) {
            // PG 写入失败时补偿删除 ChromaDB 中刚写入的向量，避免孤儿记录
            try {
                chromaDbClient.delete(List.of(image.getImageId()));
            } catch (Exception compensateEx) {
                log.error("补偿删除 ChromaDB 向量失败，imageId={}", image.getImageId(), compensateEx);
            }
            throw e;
        }

        log.info("存量向量回填完成，imageId={}", image.getImageId());
        return true;
    }

    /**
     * 回填结果统计。
     */
    public record BackfillResult(int successCount, int failedCount) {
    }
}
