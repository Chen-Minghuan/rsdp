package com.rsdp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.SimilarProductRequest;
import com.rsdp.dto.response.SimilarProductResponse;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.service.chroma.ChromaDbClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 相似产品检索服务。
 * <p>三层检索：向量召回 → 元数据过滤 → 规则重排。</p>
 *
 * <p>TODO: 当前规则重排仅基于元数据完整性与请求参数做简单加分；积累数据后应引入 AI 视觉标签
 * （风格、主色、材质、场景）的对比匹配，实现更接近“同款判定”的精排逻辑。</p>
 *
 * <p>TODO: 若业务后续出现 inactive/下架等非删除状态，需要在状态变更时同步更新 ChromaDB metadata
 * 中的 status 字段，或在检索后二次校验 PostgreSQL 状态，避免返回已下架商品。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final EmbeddingService embeddingService;
    private final ChromaDbClient chromaDbClient;
    private final RspuMapper rspuMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final ObjectMapper objectMapper;

    /**
     * 以图/以文搜索相似产品。
     *
     * @param request 检索请求
     * @return 相似产品列表（按综合得分降序）
     */
    public List<SimilarProductResponse> searchSimilar(SimilarProductRequest request) {
        float[] queryEmbedding = buildQueryEmbedding(request);

        int topK = request.getTopK() != null && request.getTopK() > 0 ? request.getTopK() : 20;
        Map<String, Object> where = buildWhereClause(request);

        ChromaDbClient.QueryResult result = chromaDbClient.query(queryEmbedding, topK * 3, where);
        if (result == null || result.getIds() == null || result.getIds().isEmpty()) {
            return Collections.emptyList();
        }

        List<SimilarProductResponse> candidates = aggregateByRspu(result, topK * 3);
        candidates = rerank(candidates, request);

        return candidates.stream()
            .sorted(Comparator.comparing(SimilarProductResponse::getFinalScore).reversed())
            .limit(topK)
            .collect(Collectors.toList());
    }

    private float[] buildQueryEmbedding(SimilarProductRequest request) {
        if (request.getImage() != null && !request.getImage().isEmpty()) {
            try (InputStream stream = request.getImage().getInputStream()) {
                return embeddingService.embedImage(stream);
            } catch (IOException e) {
                log.error("读取查询图片失败", e);
                throw new RuntimeException("读取查询图片失败", e);
            }
        }
        if (request.getText() != null && !request.getText().isBlank()) {
            return embeddingService.embedText(request.getText().trim());
        }
        throw new IllegalArgumentException("请提供查询图片或文本");
    }

    private Map<String, Object> buildWhereClause(SimilarProductRequest request) {
        List<Map<String, Object>> conditions = new ArrayList<>();
        conditions.add(Map.of("status", "active"));

        if (request.getCategoryCode() != null && !request.getCategoryCode().isBlank()) {
            conditions.add(Map.of("category_code", request.getCategoryCode().trim()));
        }
        if (request.getPositioningLabel() != null && !request.getPositioningLabel().isBlank()) {
            conditions.add(Map.of("positioning_label", request.getPositioningLabel().trim()));
        }

        if (conditions.size() == 1) {
            return conditions.get(0);
        }

        Map<String, Object> where = new HashMap<>();
        where.put("$and", conditions);
        return where;
    }

    private List<SimilarProductResponse> aggregateByRspu(ChromaDbClient.QueryResult result, int limit) {
        List<List<String>> idsList = result.getIds();
        List<List<Double>> distancesList = result.getDistances();
        List<List<Map<String, Object>>> metadatasList = result.getMetadatas();

        if (idsList == null || idsList.isEmpty()
            || distancesList == null || distancesList.isEmpty()
            || metadatasList == null || metadatasList.isEmpty()) {
            return Collections.emptyList();
        }

        // 取第一批查询结果（单查询向量只有一批）
        List<String> ids = idsList.get(0);
        List<Double> distances = distancesList.get(0);
        List<Map<String, Object>> metadatas = metadatasList.get(0);

        if (ids == null || distances == null || metadatas == null
            || ids.size() != distances.size() || ids.size() != metadatas.size()) {
            return Collections.emptyList();
        }

        // 按 RSPU 聚合，取相似度最高的图片
        Map<String, SimilarProductResponse> bestByRspu = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            String imageId = ids.get(i);
            double distance = distances.get(i);
            Map<String, Object> metadata = metadatas.get(i);
            String rspuId = metadata != null ? (String) metadata.get("rspu_id") : null;
            if (rspuId == null || rspuId.isBlank()) {
                continue;
            }

            // ChromaDB cosine distance 范围 [0, 2]，映射到 [0, 1]
            double score = Math.max(0.0, Math.min(1.0, 1.0 - distance / 2.0));
            SimilarProductResponse existing = bestByRspu.get(rspuId);
            if (existing == null || score > existing.getVectorScore()) {
                SimilarProductResponse response = new SimilarProductResponse();
                response.setRspuId(rspuId);
                response.setCategoryCode((String) metadata.get("category_code"));
                response.setPositioningLabel((String) metadata.get("positioning_label"));
                response.setVectorScore(score);
                response.setMainImageUrl(resolveImageUrl(imageId));
                response.setFinalScore(score);
                bestByRspu.put(rspuId, response);
            }
        }

        return bestByRspu.values().stream()
            .sorted(Comparator.comparing(SimilarProductResponse::getVectorScore).reversed())
            .limit(limit)
            .collect(Collectors.toList());
    }

    private String resolveImageUrl(String imageId) {
        ImageAssets image = imageAssetsMapper.selectById(imageId);
        return image != null ? image.getStorageUrl() : null;
    }

    private List<SimilarProductResponse> rerank(List<SimilarProductResponse> candidates, SimilarProductRequest request) {
        for (SimilarProductResponse candidate : candidates) {
            double boost = 0.0;
            List<String> reasons = new ArrayList<>();

            RspuMaster rspu = rspuMapper.selectById(candidate.getRspuId());
            if (rspu != null) {
                candidate.setPositioningLabel(rspu.getPositioningLabel());
                if (rspu.getColorPrimaryName() != null && !rspu.getColorPrimaryName().isBlank()) {
                    boost += 0.01;
                    reasons.add("包含主色信息");
                }
                if (hasNonEmptyJsonArray(rspu.getMaterialTags())) {
                    boost += 0.02;
                    reasons.add("包含材质标签");
                }
                if (hasNonEmptyJsonArray(rspu.getSceneTags())) {
                    boost += 0.01;
                    reasons.add("包含场景标签");
                }
                if (request.getCategoryCode() != null && !request.getCategoryCode().isBlank()
                    && request.getCategoryCode().equals(rspu.getCategoryCode())) {
                    boost += 0.03;
                    reasons.add("类别一致");
                }
                if (request.getPositioningLabel() != null && !request.getPositioningLabel().isBlank()
                    && request.getPositioningLabel().equals(rspu.getPositioningLabel())) {
                    boost += 0.03;
                    reasons.add("风格/定位一致");
                }
            }

            double finalScore = Math.min(candidate.getVectorScore() + boost, 0.999);
            candidate.setFinalScore(finalScore);
            candidate.setMatchReasons(reasons.isEmpty() ? Collections.singletonList("向量相似") : reasons);
        }
        return candidates;
    }

    private boolean hasNonEmptyJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            List<?> list = objectMapper.readValue(json, new TypeReference<List<?>>() {});
            return list != null && !list.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
