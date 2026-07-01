package com.rsdp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiLabels;
import com.rsdp.dto.request.SimilarProductRequest;
import com.rsdp.dto.response.SimilarProductResponse;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.exception.ExternalServiceException;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.service.chroma.ChromaDbClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 相似产品检索服务。
 * <p>三层检索：向量召回 → 元数据过滤 → 规则重排。</p>
 *
 * <p>图片查询时，会通过 {@link VisionService} 提取查询图的视觉标签（风格、主色、材质、场景、六维），
 * 与候选 RSPU 的标签做匹配加成，实现更接近“同款判定”的精排。</p>
 *
 * <p>若业务后续出现 inactive/下架等非删除状态，需要在状态变更时同步更新 ChromaDB metadata
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
    private final VisionService visionService;

    /**
     * 以图/以文搜索相似产品。
     *
     * @param request 检索请求
     * @return 相似产品列表（按综合得分降序）
     */
    public List<SimilarProductResponse> searchSimilar(SimilarProductRequest request) {
        byte[] imageBytes = extractImageBytes(request);
        float[] queryEmbedding = buildQueryEmbedding(imageBytes, request);
        AiLabels queryLabels = extractQueryLabels(imageBytes, request);

        int topK = request.getTopK() != null && request.getTopK() > 0 ? request.getTopK() : 20;
        Map<String, Object> where = buildWhereClause(request);

        // 扩大候选池，为去重和重排留出空间
        ChromaDbClient.QueryResult result = chromaDbClient.query(queryEmbedding, topK * 5, where);
        if (result == null || result.getIds() == null || result.getIds().isEmpty()) {
            return Collections.emptyList();
        }

        List<SimilarProductResponse> candidates = aggregateByRspu(result, topK * 5);
        candidates = rerank(candidates, request, queryLabels);

        return candidates.stream()
            .sorted(Comparator.comparing(SimilarProductResponse::getFinalScore).reversed())
            .limit(topK)
            .collect(Collectors.toList());
    }

    private byte[] extractImageBytes(SimilarProductRequest request) {
        if (request.getImage() == null || request.getImage().isEmpty()) {
            return null;
        }
        try (InputStream stream = request.getImage().getInputStream()) {
            return stream.readAllBytes();
        } catch (IOException e) {
            log.error("读取查询图片失败", e);
            throw new IllegalArgumentException("读取查询图片失败", e);
        }
    }

    private float[] buildQueryEmbedding(byte[] imageBytes, SimilarProductRequest request) {
        if (imageBytes != null) {
            return embeddingService.embedImage(new ByteArrayInputStream(imageBytes));
        }
        if (request.getText() != null && !request.getText().isBlank()) {
            return embeddingService.embedText(request.getText().trim());
        }
        throw new IllegalArgumentException("请提供查询图片或文本");
    }

    private AiLabels extractQueryLabels(byte[] imageBytes, SimilarProductRequest request) {
        if (imageBytes == null) {
            return null;
        }
        try {
            AiLabels labels = visionService.recognizeImage(new ByteArrayInputStream(imageBytes));
            log.info("查询图片 AI 标签识别完成，style={}", labels != null ? labels.getStyle() : null);
            return labels;
        } catch (ExternalServiceException e) {
            log.warn("查询图片 AI 标签识别失败，降级为空标签继续检索", e);
            return null;
        }
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

        // 批量查询图片 URL，避免 N+1
        List<String> imageIds = ids.stream().distinct().collect(Collectors.toList());
        Map<String, String> imageUrlMap = imageAssetsMapper.selectBatchIds(imageIds).stream()
            .collect(Collectors.toMap(ImageAssets::getImageId, ImageAssets::getStorageUrl, (a, b) -> a));

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
                response.setMainImageUrl(imageUrlMap.get(imageId));
                response.setFinalScore(score);
                bestByRspu.put(rspuId, response);
            }
        }

        return bestByRspu.values().stream()
            .sorted(Comparator.comparing(SimilarProductResponse::getVectorScore).reversed())
            .limit(limit)
            .collect(Collectors.toList());
    }

    private List<SimilarProductResponse> rerank(List<SimilarProductResponse> candidates,
                                                SimilarProductRequest request,
                                                AiLabels queryLabels) {
        // 批量查询 RSPU 元数据，避免 N+1
        List<String> rspuIds = candidates.stream()
            .map(SimilarProductResponse::getRspuId)
            .distinct()
            .collect(Collectors.toList());
        Map<String, RspuMaster> rspuMap = rspuMapper.selectBatchIds(rspuIds).stream()
            .collect(Collectors.toMap(RspuMaster::getRspuId, r -> r, (a, b) -> a));

        // 按向量分排序后做跨 RSPU 同款去重
        candidates = candidates.stream()
            .sorted(Comparator.comparing(SimilarProductResponse::getVectorScore).reversed())
            .collect(Collectors.toList());
        candidates = deduplicateByFingerprint(candidates, rspuMap);

        for (SimilarProductResponse candidate : candidates) {
            double boost = 0.0;
            List<String> reasons = new ArrayList<>();

            RspuMaster rspu = rspuMap.get(candidate.getRspuId());
            if (rspu != null) {
                candidate.setPositioningLabel(rspu.getPositioningLabel());
                candidate.setAestheticsConfidence(rspu.getAestheticsConfidence());

                // 元数据完整性加分
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

                // 查询图片视觉标签匹配加成
                if (queryLabels != null) {
                    boost += computeLabelBoost(queryLabels, rspu, reasons);
                }
            }

            double finalScore = Math.min(candidate.getVectorScore() + boost, 0.999);
            candidate.setFinalScore(finalScore);
            candidate.setMatchReasons(reasons.isEmpty() ? Collections.singletonList("向量相似") : reasons);
        }
        return candidates;
    }

    /**
     * 按特征指纹跨 RSPU 去重，保留向量分最高的代表条目。
     */
    private List<SimilarProductResponse> deduplicateByFingerprint(List<SimilarProductResponse> candidates,
                                                                  Map<String, RspuMaster> rspuMap) {
        Set<String> seen = new HashSet<>();
        List<SimilarProductResponse> unique = new ArrayList<>();
        for (SimilarProductResponse candidate : candidates) {
            String fingerprint = buildFingerprint(rspuMap.get(candidate.getRspuId()));
            if (seen.add(fingerprint)) {
                unique.add(candidate);
            }
        }
        return unique;
    }

    private String buildFingerprint(RspuMaster rspu) {
        if (rspu == null) {
            return "UNKNOWN";
        }
        String materials = parseJsonArray(rspu.getMaterialTags()).stream()
            .sorted()
            .collect(Collectors.joining(","));
        return Objects.toString(rspu.getCategoryCode(), "") + "|"
            + Objects.toString(rspu.getPositioningLabel(), "") + "|"
            + Objects.toString(rspu.getColorPrimaryName(), "") + "|"
            + materials;
    }

    private double computeLabelBoost(AiLabels queryLabels, RspuMaster rspu, List<String> reasons) {
        double boost = 0.0;

        // 风格一致
        if (queryLabels.getStyle() != null && !queryLabels.getStyle().isBlank()
            && queryLabels.getStyle().equalsIgnoreCase(rspu.getPositioningLabel())) {
            boost += 0.05;
            reasons.add("风格一致：" + rspu.getPositioningLabel());
        }

        // 主色一致
        if (queryLabels.getColorPrimaryName() != null && !queryLabels.getColorPrimaryName().isBlank()
            && queryLabels.getColorPrimaryName().equalsIgnoreCase(rspu.getColorPrimaryName())) {
            boost += 0.04;
            reasons.add("主色一致：" + rspu.getColorPrimaryName());
        }

        // 材质标签交集
        List<String> queryMaterials = normalizeTags(queryLabels.getMaterialTags());
        List<String> rspuMaterials = parseJsonArray(rspu.getMaterialTags());
        int materialOverlap = countOverlap(queryMaterials, rspuMaterials);
        if (materialOverlap > 0) {
            boost += 0.03 * Math.min(materialOverlap, 2);
            reasons.add("材质匹配：" + rspuMaterials.stream().limit(2).collect(Collectors.joining(", ")));
        }

        // 场景标签交集
        List<String> queryScenes = normalizeTags(queryLabels.getSceneTags());
        List<String> rspuScenes = parseJsonArray(rspu.getSceneTags());
        int sceneOverlap = countOverlap(queryScenes, rspuScenes);
        if (sceneOverlap > 0) {
            boost += 0.02 * Math.min(sceneOverlap, 2);
            reasons.add("场景匹配：" + rspuScenes.stream().limit(2).collect(Collectors.joining(", ")));
        }

        // 六维标签维度一致
        Map<String, String> querySixDim = queryLabels.getSixDimTags();
        Map<String, String> rspuSixDim = parseJsonObject(rspu.getSixDimTags());
        if (querySixDim != null && !querySixDim.isEmpty() && !rspuSixDim.isEmpty()) {
            int matched = 0;
            for (Map.Entry<String, String> entry : querySixDim.entrySet()) {
                String rspuValue = rspuSixDim.get(entry.getKey());
                if (rspuValue != null && rspuValue.equalsIgnoreCase(entry.getValue())) {
                    matched++;
                }
            }
            if (matched > 0) {
                boost += 0.01 * Math.min(matched, 6);
                reasons.add("形态匹配 " + matched + " 维");
            }
        }

        return Math.min(boost, 0.20);
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return Collections.emptyList();
        }
        return tags.stream()
            .filter(t -> t != null && !t.isBlank())
            .map(String::trim)
            .collect(Collectors.toList());
    }

    private int countOverlap(List<String> queryTags, List<String> rspuTags) {
        if (queryTags.isEmpty() || rspuTags.isEmpty()) {
            return 0;
        }
        Set<String> querySet = queryTags.stream()
            .map(String::toLowerCase)
            .collect(Collectors.toSet());
        return (int) rspuTags.stream()
            .map(String::toLowerCase)
            .filter(querySet::contains)
            .count();
    }

    private List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<?> list = objectMapper.readValue(json, new TypeReference<List<?>>() {});
            if (list == null) {
                return Collections.emptyList();
            }
            return list.stream()
                .map(Object::toString)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("解析 JSON 数组失败：{}", json);
            return Collections.emptyList();
        }
    }

    private Map<String, String> parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            if (map == null) {
                return Collections.emptyMap();
            }
            return map.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toString(), (a, b) -> a));
        } catch (Exception e) {
            log.warn("解析 JSON 对象失败：{}", json);
            return Collections.emptyMap();
        }
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
