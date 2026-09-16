package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.response.PublicCollectionDetailResponse;
import com.rsdp.dto.response.PublicCollectionSummaryResponse;
import com.rsdp.dto.response.PublicProductItemResponse;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.ProductCollection;
import com.rsdp.entity.ProductCollectionItem;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuVariant;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.ProductCollectionItemMapper;
import com.rsdp.mapper.ProductCollectionMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuVariantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户端官网公开产品集服务（免登录）：已发布集合列表 / 集合详情。
 *
 * <p>红线同 {@link PublicCatalogService}：产品项仅 RSPU 展示字段与零售参考价，
 * 绝不联查 rsku_supply 工厂报价与工厂信息；只返回 status='active' 的在售产品。</p>
 */
@Service
@RequiredArgsConstructor
public class PublicCollectionService {

    private static final String STATUS_ACTIVE = "active";

    private final ProductCollectionMapper collectionMapper;
    private final ProductCollectionItemMapper itemMapper;
    private final RspuMapper rspuMapper;
    private final RspuVariantMapper rspuVariantMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final ObjectMapper objectMapper;

    /**
     * 已发布产品集列表（按推荐/排序号/创建时间倒序）。
     *
     * @return 已发布集合列表（含封面图与在售产品数）
     */
    public List<PublicCollectionSummaryResponse> listPublished() {
        List<ProductCollection> collections = collectionMapper.selectList(
            new QueryWrapper<ProductCollection>()
                .eq("is_published", true)
                .orderByDesc("is_featured", "sort_order", "created_at"));
        if (collections.isEmpty()) {
            return List.of();
        }

        List<String> collectionIds = collections.stream()
            .map(ProductCollection::getCollectionId).toList();
        Map<String, List<ProductCollectionItem>> itemsByCollection = itemMapper.selectList(
            new QueryWrapper<ProductCollectionItem>()
                .in("collection_id", collectionIds)
                .orderByAsc("collection_id", "sort_order", "id")
        ).stream().collect(Collectors.groupingBy(ProductCollectionItem::getCollectionId,
            LinkedHashMap::new, Collectors.toList()));

        // 集合内仅统计/展示在售产品
        List<String> allRspuIds = itemsByCollection.values().stream()
            .flatMap(List::stream)
            .map(ProductCollectionItem::getRspuId)
            .distinct()
            .toList();
        Map<String, RspuMaster> activeRspuMap = activeRspuMap(allRspuIds);
        Map<String, String> primaryImageMap = batchPrimaryImageUrls(new ArrayList<>(activeRspuMap.keySet()));

        return collections.stream().map(collection -> {
            PublicCollectionSummaryResponse item = toSummary(collection);
            List<ProductCollectionItem> items = itemsByCollection
                .getOrDefault(collection.getCollectionId(), List.of());
            List<ProductCollectionItem> activeItems = items.stream()
                .filter(i -> activeRspuMap.containsKey(i.getRspuId()))
                .toList();
            item.setItemCount(activeItems.size());
            item.setCoverImageUrl(activeItems.isEmpty()
                ? null : primaryImageMap.get(activeItems.get(0).getRspuId()));
            return item;
        }).toList();
    }

    /**
     * 已发布产品集详情（未发布/不存在一律 404）。
     *
     * <p>产品项仅含在售（active）产品，字段口径同公开商品列表。</p>
     *
     * @param collectionId 产品集 ID
     * @return 集合详情（含产品项）
     */
    public PublicCollectionDetailResponse getPublishedDetail(String collectionId) {
        ProductCollection collection = collectionMapper.selectById(collectionId);
        if (collection == null || !Boolean.TRUE.equals(collection.getIsPublished())) {
            throw new ResourceNotFoundException("产品集不存在或未发布");
        }

        List<ProductCollectionItem> items = itemMapper.selectByCollectionId(collectionId);
        List<String> rspuIds = items.stream().map(ProductCollectionItem::getRspuId).distinct().toList();
        Map<String, RspuMaster> activeRspuMap = activeRspuMap(rspuIds);
        Map<String, String> primaryImageMap = batchPrimaryImageUrls(new ArrayList<>(activeRspuMap.keySet()));
        Map<String, Long> variantCountMap = batchVariantCounts(new ArrayList<>(activeRspuMap.keySet()));

        List<PublicProductItemResponse> itemResponses = items.stream()
            .filter(item -> activeRspuMap.containsKey(item.getRspuId()))
            .map(item -> toPublicProductItem(activeRspuMap.get(item.getRspuId()),
                primaryImageMap, variantCountMap))
            .toList();

        PublicCollectionDetailResponse detail = new PublicCollectionDetailResponse();
        copySummary(toSummary(collection), detail);
        detail.setItemCount(itemResponses.size());
        detail.setCoverImageUrl(itemResponses.isEmpty() ? null : itemResponses.get(0).getPrimaryImageUrl());
        detail.setItems(itemResponses);
        return detail;
    }

    private PublicCollectionSummaryResponse toSummary(ProductCollection collection) {
        PublicCollectionSummaryResponse summary = new PublicCollectionSummaryResponse();
        summary.setCollectionId(collection.getCollectionId());
        summary.setName(collection.getName());
        summary.setDescription(collection.getDescription());
        summary.setCategoryCodes(parseStringList(collection.getCategoryCodes()));
        summary.setStyleCodes(parseStringList(collection.getStyleCodes()));
        summary.setTargetSegments(parseStringList(collection.getTargetSegments()));
        return summary;
    }

    private void copySummary(PublicCollectionSummaryResponse source, PublicCollectionDetailResponse target) {
        target.setCollectionId(source.getCollectionId());
        target.setName(source.getName());
        target.setDescription(source.getDescription());
        target.setCategoryCodes(source.getCategoryCodes());
        target.setStyleCodes(source.getStyleCodes());
        target.setTargetSegments(source.getTargetSegments());
    }

    /**
     * 批量查询在售产品（rspu_master 软删由 @TableLogic 自动过滤）。
     *
     * @param rspuIds 产品 ID 列表
     * @return rspuId → 在售产品实体
     */
    private Map<String, RspuMaster> activeRspuMap(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        return rspuMapper.selectList(new QueryWrapper<RspuMaster>()
            .in("rspu_id", rspuIds)
            .eq("status", STATUS_ACTIVE)
        ).stream().collect(Collectors.toMap(RspuMaster::getRspuId, r -> r, (a, b) -> a));
    }

    private PublicProductItemResponse toPublicProductItem(RspuMaster rspu,
            Map<String, String> primaryImageMap, Map<String, Long> variantCountMap) {
        PublicProductItemResponse item = new PublicProductItemResponse();
        item.setRspuId(rspu.getRspuId());
        item.setRspuCode(rspu.getRspuCode());
        item.setProductName(rspu.getProductName());
        item.setCategoryCode(rspu.getCategoryCode());
        item.setCategoryPath(rspu.getCategoryPath());
        item.setPositioningLabel(rspu.getPositioningLabel());
        item.setColorPrimaryName(rspu.getColorPrimaryName());
        item.setMaterialTags(parseStringList(rspu.getMaterialTags()));
        item.setRetailPrice(rspu.getRetailPrice());
        item.setPrimaryImageUrl(primaryImageMap.get(rspu.getRspuId()));
        item.setVariantCount(variantCountMap.getOrDefault(rspu.getRspuId(), 0L).intValue());
        item.setCreatedAt(rspu.getCreatedAt());
        return item;
    }

    private Map<String, String> batchPrimaryImageUrls(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        List<ImageAssets> images = imageAssetsMapper.selectList(new QueryWrapper<ImageAssets>()
            .in("rspu_id", rspuIds)
            .eq("is_primary", true)
            .orderByDesc("created_at"));
        Map<String, String> result = new HashMap<>();
        for (ImageAssets image : images) {
            result.putIfAbsent(image.getRspuId(), imageUrl(image.getImageId()));
        }
        return result;
    }

    private Map<String, Long> batchVariantCounts(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = rspuVariantMapper.selectMaps(new QueryWrapper<RspuVariant>()
            .select("rspu_id", "COUNT(*) AS cnt")
            .in("rspu_id", rspuIds)
            .groupBy("rspu_id"));
        Map<String, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            result.put((String) row.get("rspu_id"), ((Number) row.get("cnt")).longValue());
        }
        return result;
    }

    private String imageUrl(String imageId) {
        return StringUtils.hasText(imageId) ? "/api/v1/images/" + imageId : null;
    }

    private List<String> parseStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
