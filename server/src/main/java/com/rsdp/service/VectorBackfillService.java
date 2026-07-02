package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.service.chroma.ChromaDbClient;
import com.rsdp.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.HashMap;
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

    /**
     * 回填指定数量的存量图片向量。
     *
     * <p>只处理对应 RSPU 尚未写入 style_vector 的图片，避免重复调用 embedding API。</p>
     *
     * @param batchSize 本次处理数量
     * @return 处理结果统计
     */
    public BackfillResult backfill(int batchSize) {
        if (batchSize <= 0 || batchSize > 1000) {
            batchSize = 100;
        }

        Page<ImageAssets> pageParam = new Page<>(1, batchSize);
        QueryWrapper<ImageAssets> wrapper = new QueryWrapper<>();
        wrapper.eq("ai_processed", true)
            .isNotNull("rspu_id")
            .isNotNull("storage_path")
            .orderByAsc("created_at");
        List<ImageAssets> images = imageAssetsMapper.selectPage(pageParam, wrapper).getRecords();

        // 批量加载 RSPU，减少 N+1
        Set<String> rspuIds = images.stream()
            .map(ImageAssets::getRspuId)
            .collect(Collectors.toSet());
        Map<String, RspuMaster> rspuMap = rspuIds.isEmpty() ? Map.of() :
            rspuMapper.selectBatchIds(rspuIds).stream()
                .collect(Collectors.toMap(RspuMaster::getRspuId, r -> r));

        int success = 0;
        int failed = 0;
        for (ImageAssets image : images) {
            try {
                RspuMaster rspu = rspuMap.get(image.getRspuId());
                if (processImage(image, rspu)) {
                    success++;
                }
            } catch (Exception e) {
                failed++;
                log.error("回填向量失败，imageId={}", image.getImageId(), e);
            }
        }

        return new BackfillResult(success, failed);
    }

    private boolean processImage(ImageAssets image, RspuMaster rspu) throws Exception {
        if (rspu == null || rspu.getDeletedAt() != null) {
            log.warn("RSPU 不存在或已删除，跳过 imageId={}", image.getImageId());
            return false;
        }

        // 已存在向量则跳过，避免重复调用 API
        if (rspu.getStyleVector() != null && !rspu.getStyleVector().isBlank()) {
            log.debug("RSPU 已存在向量，跳过 imageId={}", image.getImageId());
            return false;
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

        // 更新 RSPU style_vector
        rspu.setStyleVector(objectMapper.writeValueAsString(embedding));
        rspuMapper.updateById(rspu);

        // 写入 ChromaDB
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("rspu_id", rspu.getRspuId());
        metadata.put("category_code", rspu.getCategoryCode());
        metadata.put("positioning_label", rspu.getPositioningLabel());
        metadata.put("color_primary_name", rspu.getColorPrimaryName());
        metadata.put("material_tags", rspu.getMaterialTags());
        metadata.put("scene_tags", rspu.getSceneTags());
        metadata.put("status", rspu.getStatus());
        metadata.put("image_size", image.getFileSize() != null ? image.getFileSize() : 0);

        chromaDbClient.upsert(
            List.of(image.getImageId()),
            List.of(embedding),
            List.of(metadata),
            null
        );

        log.info("存量向量回填完成，imageId={}", image.getImageId());
        return true;
    }

    /**
     * 回填结果统计。
     */
    public record BackfillResult(int successCount, int failedCount) {
    }
}
