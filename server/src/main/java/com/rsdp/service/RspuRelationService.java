package com.rsdp.service;

import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.security.datascope.DataScopeHelper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.request.RspuRelationCreateRequest;
import com.rsdp.dto.request.RspuRelationUpdateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.response.RspuRelationResponse;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuRelation;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuRelationMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import com.rsdp.util.IdGenerator;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RSPU 产品间关系服务，管理原厂搭配、AI 确认搭配等关系。
 */
@Service
@RequiredArgsConstructor
public class RspuRelationService {

    private final RspuRelationMapper relationMapper;
    private final RspuMapper rspuMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final RskuSupplyMapper rskuSupplyMapper;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final DataScopeHelper dataScopeHelper;

    private static final int MAX_LIST_SIZE = 1000;

    /**
     * 查询某产品作为锚点的有效搭配关系。
     *
     * @param anchorRspuId 锚点产品 ID
     * @return 搭配关系列表
     */
    public List<RspuRelationResponse> listByAnchor(String anchorRspuId) {
        validateRspuExists(anchorRspuId);
        List<RspuRelation> relations = relationMapper.selectList(
            new QueryWrapper<RspuRelation>()
                .eq("anchor_rspu_id", anchorRspuId)
                .eq("status", "active")
                .orderByAsc("sort_order", "created_at")
                .last("LIMIT " + MAX_LIST_SIZE)
        );
        return toResponses(relations, RspuRelation::getRelatedRspuId);
    }

    /**
     * 查询某产品作为搭配产品的有效反向关系。
     *
     * @param relatedRspuId 搭配产品 ID
     * @return 被搭配关系列表
     */
    public List<RspuRelationResponse> listByRelated(String relatedRspuId) {
        validateRspuExists(relatedRspuId);
        List<RspuRelation> relations = relationMapper.selectList(
            new QueryWrapper<RspuRelation>()
                .eq("related_rspu_id", relatedRspuId)
                .eq("status", "active")
                .orderByAsc("sort_order", "created_at")
                .last("LIMIT " + MAX_LIST_SIZE)
        );
        return toResponses(relations, RspuRelation::getAnchorRspuId);
    }

    /**
     * 为某产品创建搭配关系。
     *
     * @param anchorRspuId 锚点产品 ID
     * @param request      创建请求
     */
    @Transactional
    public void createRelation(String anchorRspuId, RspuRelationCreateRequest request) {
        validateRspuExists(anchorRspuId);
        validateRspuExists(request.getRelatedRspuId());
        dataScopeHelper.assertCanAccessRspu(anchorRspuId);
        if (anchorRspuId.equals(request.getRelatedRspuId())) {
            throw new BusinessException("产品不能与自己建立搭配关系");
        }
        validateRelationType(request.getRelationType());

        RspuRelation existing = relationMapper.selectOne(
            new QueryWrapper<RspuRelation>()
                .eq("anchor_rspu_id", anchorRspuId)
                .eq("related_rspu_id", request.getRelatedRspuId())
        );
        if (existing != null) {
            throw new BusinessException("搭配关系已存在");
        }

        RspuRelation relation = new RspuRelation();
        relation.setRelationId(IdGenerator.relationId());
        relation.setAnchorRspuId(anchorRspuId);
        relation.setRelatedRspuId(request.getRelatedRspuId());
        relation.setRelationType(StringUtils.hasText(request.getRelationType())
            ? request.getRelationType().trim()
            : "official");
        relation.setReason(request.getReason());
        relation.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
        relation.setStatus("active");
        relation.setCreatedBy(SecurityOperatorContext.currentUsername());
        relation.setCreatedAt(LocalDateTime.now());
        relation.setUpdatedAt(LocalDateTime.now());

        relationMapper.insert(relation);
        auditLogService.logCreate("rspu_relation", relation.getRelationId(), relation, SecurityOperatorContext.currentUsername());
    }

    /**
     * 更新搭配关系。
     *
     * @param anchorRspuId 锚点产品 ID
     * @param relationId   关系 ID
     * @param request      更新请求
     */
    @Transactional
    public void updateRelation(String anchorRspuId, String relationId, RspuRelationUpdateRequest request) {
        RspuRelation relation = relationMapper.selectById(relationId);
        if (relation == null
            || !anchorRspuId.equals(relation.getAnchorRspuId())) {
            throw new ResourceNotFoundException("搭配关系不存在: " + relationId);
        }
        dataScopeHelper.assertCanAccessRspu(anchorRspuId);

        RspuRelation oldSnapshot = snapshot(relation);
        if (StringUtils.hasText(request.getRelationType())) {
            validateRelationType(request.getRelationType());
            relation.setRelationType(request.getRelationType().trim());
        }
        if (request.getReason() != null) {
            relation.setReason(request.getReason());
        }
        if (request.getSortOrder() != null) {
            relation.setSortOrder(request.getSortOrder());
        }
        if (StringUtils.hasText(request.getStatus())) {
            relation.setStatus(request.getStatus().trim());
        }
        relation.setUpdatedAt(LocalDateTime.now());

        relationMapper.updateById(relation);
        auditLogService.logUpdate("rspu_relation", relationId, oldSnapshot, relation, SecurityOperatorContext.currentUsername());
    }

    /**
     * 软删除搭配关系。
     *
     * @param anchorRspuId 锚点产品 ID
     * @param relationId   关系 ID
     */
    @Transactional
    public void deleteRelation(String anchorRspuId, String relationId) {
        RspuRelation relation = relationMapper.selectById(relationId);
        if (relation == null
            || !anchorRspuId.equals(relation.getAnchorRspuId())) {
            throw new ResourceNotFoundException("搭配关系不存在: " + relationId);
        }
        dataScopeHelper.assertCanAccessRspu(anchorRspuId);

        RspuRelation oldSnapshot = snapshot(relation);
        int affected = relationMapper.deleteById(relationId);
        if (affected == 0) {
            throw new ResourceNotFoundException("搭配关系不存在或已被删除: " + relationId);
        }
        auditLogService.logDelete("rspu_relation", relationId, oldSnapshot, SecurityOperatorContext.currentUsername());
    }

    private void validateRspuExists(String rspuId) {
        RspuMaster rspu = rspuMapper.selectById(rspuId);
        if (rspu == null) {
            throw new ResourceNotFoundException("产品不存在: " + rspuId);
        }
    }

    private void validateRelationType(String type) {
        if (!StringUtils.hasText(type)) {
            return;
        }
        if (!List.of("official", "ai_verified", "exclude").contains(type.trim())) {
            throw new BusinessException("无效的关系类型: " + type);
        }
    }

    private List<RspuRelationResponse> toResponses(List<RspuRelation> relations,
                                                   Function<RspuRelation, String> targetRspuIdExtractor) {
        if (relations.isEmpty()) {
            return List.of();
        }

        List<String> targetRspuIds = relations.stream()
            .map(targetRspuIdExtractor)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();

        Map<String, RspuMaster> rspuMap = targetRspuIds.isEmpty()
            ? Map.of()
            : rspuMapper.selectBatchIds(targetRspuIds).stream()
                .collect(Collectors.toMap(RspuMaster::getRspuId, r -> r));
        Map<String, String> imageUrlMap = batchPrimaryImageUrls(targetRspuIds);
        Map<String, BigDecimal> minPriceMap = batchMinPrices(targetRspuIds);

        return relations.stream()
            .map(r -> toResponse(r, targetRspuIdExtractor.apply(r), rspuMap, imageUrlMap, minPriceMap))
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toList());
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

    private Map<String, BigDecimal> batchMinPrices(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        List<RskuSupply> rskus = rskuSupplyMapper.selectList(
            new QueryWrapper<RskuSupply>()
                .in("rspu_id", rspuIds)
        );
        // 数据权限过滤：只取当前用户可见工厂的 RSKU 最低价
        rskus = rskus.stream()
            .filter(rsku -> dataScopeHelper.canAccessRskuFactory(rsku.getFactoryCode()))
            .collect(Collectors.toList());
        return rskus.stream()
            .filter(r -> r.getFactoryPrice() != null)
            .collect(Collectors.groupingBy(
                RskuSupply::getRspuId,
                Collectors.mapping(
                    RskuSupply::getFactoryPrice,
                    Collectors.minBy(Comparator.naturalOrder())
                )
            ))
            .entrySet().stream()
            .filter(e -> e.getValue().isPresent())
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }

    private RspuRelationResponse toResponse(RspuRelation relation, String targetRspuId,
                                            Map<String, RspuMaster> rspuMap,
                                            Map<String, String> imageUrlMap,
                                            Map<String, BigDecimal> minPriceMap) {
        RspuMaster target = rspuMap.get(targetRspuId);
        // 目标产品已软删除时，不在搭配列表中展示
        if (target == null) {
            return null;
        }

        RspuRelationResponse response = new RspuRelationResponse();
        response.setRelationId(relation.getRelationId());
        response.setAnchorRspuId(relation.getAnchorRspuId());
        response.setRelatedRspuId(relation.getRelatedRspuId());
        response.setRelationType(relation.getRelationType());
        response.setReason(relation.getReason());
        response.setSortOrder(relation.getSortOrder());
        response.setStatus(relation.getStatus());
        response.setCreatedAt(relation.getCreatedAt());
        response.setUpdatedAt(relation.getUpdatedAt());
        response.setTargetRspuId(targetRspuId);

        response.setTargetDisplayName(buildDisplayName(target));
        response.setTargetCategoryPath(formatCategoryPath(target.getCategoryPath()));
        response.setTargetImageUrl(imageUrlMap.get(targetRspuId));
        response.setTargetMinPrice(minPriceMap.get(targetRspuId));
        return response;
    }

    private String buildDisplayName(RspuMaster rspu) {
        String categoryPath = formatCategoryPath(rspu.getCategoryPath());
        if (StringUtils.hasText(categoryPath)) {
            return categoryPath;
        }
        return rspu.getRspuId();
    }

    private String formatCategoryPath(String categoryPathJson) {
        if (!StringUtils.hasText(categoryPathJson)) {
            return null;
        }
        try {
            List<String> path = objectMapper.readValue(
                categoryPathJson,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
            );
            return String.join(" / ", path);
        } catch (Exception e) {
            return categoryPathJson;
        }
    }

    private RspuRelation snapshot(RspuRelation source) {
        RspuRelation copy = new RspuRelation();
        copy.setRelationId(source.getRelationId());
        copy.setAnchorRspuId(source.getAnchorRspuId());
        copy.setRelatedRspuId(source.getRelatedRspuId());
        copy.setRelationType(source.getRelationType());
        copy.setReason(source.getReason());
        copy.setSortOrder(source.getSortOrder());
        copy.setStatus(source.getStatus());
        copy.setCreatedBy(source.getCreatedBy());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.setDeletedAt(source.getDeletedAt());
        return copy;
    }
}
