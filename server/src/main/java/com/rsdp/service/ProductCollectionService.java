package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.ProductCollectionCreateRequest;
import com.rsdp.dto.request.ProductCollectionUpdateRequest;
import com.rsdp.dto.response.ProductCollectionItemResponse;
import com.rsdp.dto.response.ProductCollectionResponse;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.ProductCollection;
import com.rsdp.entity.ProductCollectionItem;
import com.rsdp.entity.RspuMaster;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.ProductCollectionItemMapper;
import com.rsdp.mapper.ProductCollectionMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.security.SecurityOperatorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 产品集服务。
 */
@Service
@RequiredArgsConstructor
public class ProductCollectionService {

    private final ProductCollectionMapper collectionMapper;
    private final ProductCollectionItemMapper itemMapper;
    private final RspuMapper rspuMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final ObjectMapper objectMapper;

    /**
     * 查询产品集列表。
     *
     * @param status 状态筛选（可选）
     * @return 产品集响应列表
     */
    public List<ProductCollectionResponse> list(String status) {
        QueryWrapper<ProductCollection> wrapper = new QueryWrapper<ProductCollection>()
            .orderByDesc("is_featured", "sort_order", "created_at");
        if (StringUtils.hasText(status)) {
            wrapper.eq("status", status);
        }
        return collectionMapper.selectList(wrapper).stream()
            .map(this::toSummaryResponse)
            .toList();
    }

    /**
     * 查询产品集详情。
     *
     * @param collectionId 产品集 ID
     * @return 产品集详情响应
     */
    public ProductCollectionResponse getDetail(String collectionId) {
        ProductCollection collection = collectionMapper.selectById(collectionId);
        if (collection == null) {
            throw new ResourceNotFoundException("产品集不存在: " + collectionId);
        }
        return enrichItems(toSummaryResponse(collection));
    }

    /**
     * 创建产品集。
     *
     * @param request 创建请求
     * @return 创建后的产品集详情
     */
    @Transactional
    public ProductCollectionResponse create(ProductCollectionCreateRequest request) {
        assertCollectionCodeUnique(request.getCollectionCode(), null);

        ProductCollection collection = new ProductCollection();
        collection.setCollectionId(UUID.randomUUID().toString());
        collection.setCollectionCode(request.getCollectionCode());
        collection.setName(request.getName());
        collection.setDescription(request.getDescription());
        collection.setCategoryCodes(toJson(request.getCategoryCodes()));
        collection.setStyleCodes(toJson(request.getStyleCodes()));
        collection.setTargetSegments(toJson(request.getTargetSegments()));
        collection.setIsFeatured(request.getIsFeatured() != null ? request.getIsFeatured() : false);
        collection.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
        collection.setStatus("ACTIVE");
        String createdBy = SecurityOperatorContext.currentUserId();
        if (!StringUtils.hasText(createdBy)) {
            throw new BusinessException("无法获取当前用户 ID");
        }
        collection.setCreatedBy(createdBy);
        collection.setCreatedAt(LocalDateTime.now());
        collection.setUpdatedAt(LocalDateTime.now());
        collectionMapper.insert(collection);

        saveItems(collection.getCollectionId(), request.getRspuIds());
        return enrichItems(toSummaryResponse(collection));
    }

    /**
     * 更新产品集。
     *
     * @param collectionId 产品集 ID
     * @param request      更新请求
     * @return 更新后的产品集详情
     */
    @Transactional
    public ProductCollectionResponse update(String collectionId, ProductCollectionUpdateRequest request) {
        ProductCollection collection = collectionMapper.selectById(collectionId);
        if (collection == null) {
            throw new ResourceNotFoundException("产品集不存在: " + collectionId);
        }
        assertCollectionCodeUnique(request.getCollectionCode(), collectionId);

        if (StringUtils.hasText(request.getCollectionCode())) {
            collection.setCollectionCode(request.getCollectionCode());
        }
        if (StringUtils.hasText(request.getName())) {
            collection.setName(request.getName());
        }
        if (request.getDescription() != null) {
            collection.setDescription(request.getDescription());
        }
        if (request.getCategoryCodes() != null) {
            collection.setCategoryCodes(toJson(request.getCategoryCodes()));
        }
        if (request.getStyleCodes() != null) {
            collection.setStyleCodes(toJson(request.getStyleCodes()));
        }
        if (request.getTargetSegments() != null) {
            collection.setTargetSegments(toJson(request.getTargetSegments()));
        }
        if (request.getIsFeatured() != null) {
            collection.setIsFeatured(request.getIsFeatured());
        }
        if (request.getSortOrder() != null) {
            collection.setSortOrder(request.getSortOrder());
        }
        if (StringUtils.hasText(request.getStatus())) {
            collection.setStatus(request.getStatus());
        }
        collection.setUpdatedAt(LocalDateTime.now());
        collectionMapper.updateById(collection);

        if (request.getRspuIds() != null) {
            saveItems(collectionId, request.getRspuIds());
        }
        return enrichItems(toSummaryResponse(collection));
    }

    /**
     * 删除产品集。
     *
     * @param collectionId 产品集 ID
     */
    @Transactional
    public void delete(String collectionId) {
        ProductCollection collection = collectionMapper.selectById(collectionId);
        if (collection == null) {
            throw new ResourceNotFoundException("产品集不存在: " + collectionId);
        }
        itemMapper.deleteByCollectionId(collectionId);
        collectionMapper.deleteById(collectionId);
    }

    private void assertCollectionCodeUnique(String collectionCode, String excludeId) {
        if (!StringUtils.hasText(collectionCode)) {
            return;
        }
        QueryWrapper<ProductCollection> wrapper = new QueryWrapper<ProductCollection>()
            .eq("collection_code", collectionCode);
        if (excludeId != null) {
            wrapper.ne("collection_id", excludeId);
        }
        Long count = collectionMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException("产品集编码已存在: " + collectionCode);
        }
    }

    private void saveItems(String collectionId, List<String> rspuIds) {
        itemMapper.deleteByCollectionId(collectionId);
        if (rspuIds == null || rspuIds.isEmpty()) {
            return;
        }
        List<ProductCollectionItem> items = new java.util.ArrayList<>();
        int order = 0;
        for (String rspuId : rspuIds) {
            if (!StringUtils.hasText(rspuId)) {
                continue;
            }
            ProductCollectionItem item = new ProductCollectionItem();
            item.setCollectionId(collectionId);
            item.setRspuId(rspuId);
            item.setSortOrder(order++);
            item.setCreatedAt(LocalDateTime.now());
            items.add(item);
        }
        if (!items.isEmpty()) {
            itemMapper.insertBatch(items);
        }
    }

    private ProductCollectionResponse enrichItems(ProductCollectionResponse response) {
        List<ProductCollectionItem> items = itemMapper.selectByCollectionId(response.getCollectionId());
        if (items.isEmpty()) {
            response.setItems(Collections.emptyList());
            response.setItemCount(0);
            return response;
        }

        List<String> rspuIds = items.stream().map(ProductCollectionItem::getRspuId).distinct().toList();
        Map<String, RspuMaster> rspuMap = rspuMapper.selectList(
            new QueryWrapper<RspuMaster>().in("rspu_id", rspuIds)
        ).stream().collect(Collectors.toMap(RspuMaster::getRspuId, r -> r, (a, b) -> a));

        Map<String, String> primaryImageUrlMap = batchPrimaryImageUrls(rspuIds);

        List<ProductCollectionItemResponse> itemResponses = items.stream()
            .map(item -> {
                RspuMaster rspu = rspuMap.get(item.getRspuId());
                ProductCollectionItemResponse ir = new ProductCollectionItemResponse();
                ir.setId(item.getId());
                ir.setRspuId(item.getRspuId());
                ir.setRspuName(rspu != null ? rspu.getPositioningLabel() : null);
                ir.setPrimaryImageUrl(primaryImageUrlMap.get(item.getRspuId()));
                ir.setSortOrder(item.getSortOrder());
                return ir;
            })
            .toList();
        response.setItems(itemResponses);
        response.setItemCount(itemResponses.size());
        return response;
    }

    private Map<String, String> batchPrimaryImageUrls(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        List<ImageAssets> images = imageAssetsMapper.selectList(
            new QueryWrapper<ImageAssets>()
                .in("rspu_id", rspuIds)
                .eq("is_primary", true)
        );
        return images.stream()
            .collect(Collectors.toMap(
                ImageAssets::getRspuId,
                img -> "/api/v1/images/" + img.getImageId(),
                (a, b) -> a
            ));
    }

    private ProductCollectionResponse toSummaryResponse(ProductCollection collection) {
        ProductCollectionResponse response = new ProductCollectionResponse();
        response.setCollectionId(collection.getCollectionId());
        response.setCollectionCode(collection.getCollectionCode());
        response.setName(collection.getName());
        response.setDescription(collection.getDescription());
        response.setCategoryCodes(fromJson(collection.getCategoryCodes()));
        response.setStyleCodes(fromJson(collection.getStyleCodes()));
        response.setTargetSegments(fromJson(collection.getTargetSegments()));
        response.setIsFeatured(collection.getIsFeatured());
        response.setSortOrder(collection.getSortOrder());
        response.setStatus(collection.getStatus());
        response.setCreatedBy(collection.getCreatedBy());
        response.setCreatedAt(collection.getCreatedAt());
        response.setUpdatedAt(collection.getUpdatedAt());
        return response;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException("JSON 序列化失败: " + e.getMessage());
        }
    }

    private List<String> fromJson(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new BusinessException("JSON 反序列化失败: " + e.getMessage());
        }
    }
}
