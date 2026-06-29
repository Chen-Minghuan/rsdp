package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.common.PageResult;
import com.rsdp.dto.request.ProductListRequest;
import com.rsdp.dto.request.ProductUpdateRequest;
import com.rsdp.dto.response.ProductDetailResponse;
import com.rsdp.dto.response.ProductSummaryResponse;
import com.rsdp.entity.AiRecognition;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RspuStyle;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.AiRecognitionMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 产品查询与复核服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductQueryService {

    private final RspuMapper rspuMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final AiRecognitionMapper aiRecognitionMapper;
    private final RspuStyleMapper rspuStyleMapper;
    private final RspuSceneMapper rspuSceneMapper;
    private final AuditLogService auditLogService;
    private final DictService dictService;
    private final ObjectMapper objectMapper;
    private final RspuRelationService rspuRelationService;

    /**
     * 分页查询产品列表。
     *
     * @param request 查询条件
     * @return 分页结果
     */
    public PageResult<ProductSummaryResponse> listProducts(ProductListRequest request) {
        Page<RspuMaster> pageParam = new Page<>(request.getPage(), request.getSize());
        QueryWrapper<RspuMaster> wrapper = new QueryWrapper<>();
        wrapper.isNull("deleted_at");

        if (StringUtils.hasText(request.getCategoryCode())) {
            wrapper.eq("category_code", request.getCategoryCode());
        }
        if (StringUtils.hasText(request.getPositioningLabel())) {
            List<String> styleRspuIds = rspuStyleMapper.selectList(
                new QueryWrapper<RspuStyle>().eq("style_code", request.getPositioningLabel())
            ).stream().map(RspuStyle::getRspuId).distinct().collect(Collectors.toList());
            if (styleRspuIds.isEmpty()) {
                styleRspuIds.add("__NO_MATCH__");
            }
            wrapper.in("rspu_id", styleRspuIds);
        }
        if (StringUtils.hasText(request.getSceneCode())) {
            List<String> sceneRspuIds = rspuSceneMapper.selectList(
                new QueryWrapper<RspuScene>().eq("scene_code", request.getSceneCode())
            ).stream().map(RspuScene::getRspuId).distinct().collect(Collectors.toList());
            if (sceneRspuIds.isEmpty()) {
                sceneRspuIds.add("__NO_MATCH__");
            }
            wrapper.in("rspu_id", sceneRspuIds);
        }
        if (StringUtils.hasText(request.getMaterialTag())) {
            String tagJson = "[\"" + request.getMaterialTag().trim() + "\"]";
            wrapper.apply("material_tags @> {0}::jsonb", tagJson);
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq("status", request.getStatus());
        }
        if (StringUtils.hasText(request.getReviewStatus())) {
            wrapper.eq("review_status", request.getReviewStatus());
        }
        if (StringUtils.hasText(request.getProductLevel())) {
            wrapper.eq("product_level", request.getProductLevel());
        }
        if (StringUtils.hasText(request.getKeyword())) {
            String keyword = "%" + request.getKeyword().trim() + "%";
            wrapper.and(w -> w.like("category_path", keyword).or().like("rspu_id", keyword));
        }

        wrapper.orderByDesc("created_at");
        Page<RspuMaster> page = rspuMapper.selectPage(pageParam, wrapper);

        List<ProductSummaryResponse> rows = page.getRecords().stream()
            .map(this::toSummary)
            .collect(Collectors.toList());

        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), rows);
    }

    /**
     * 查询产品详情。
     *
     * @param rspuId RSPU ID
     * @return 产品详情
     */
    public ProductDetailResponse getProductDetail(String rspuId) {
        RspuMaster rspu = rspuMapper.selectById(rspuId);
        if (rspu == null || rspu.getDeletedAt() != null) {
            throw new ResourceNotFoundException("产品不存在: " + rspuId);
        }

        List<ImageAssets> images = imageAssetsMapper.selectList(
            new QueryWrapper<ImageAssets>().eq("rspu_id", rspuId).orderByDesc("is_primary")
        );
        List<AiRecognition> recognitions = aiRecognitionMapper.selectList(
            new QueryWrapper<AiRecognition>().eq("rspu_id", rspuId).orderByDesc("created_at")
        );

        ProductDetailResponse response = new ProductDetailResponse();
        response.setRspu(rspu);
        response.setImages(images);
        response.setRecognitions(recognitions);
        response.setOfficialMatches(rspuRelationService.listByAnchor(rspuId));
        response.setMatchedBy(rspuRelationService.listByRelated(rspuId));
        return response;
    }

    /**
     * 复核确认产品。
     *
     * @param rspuId         RSPU ID
     * @param reviewStatus   复核状态
     * @param reviewComment  复核备注
     */
    @Transactional
    public void reviewProduct(String rspuId, String reviewStatus, String reviewComment) {
        RspuMaster rspu = rspuMapper.selectById(rspuId);
        if (rspu == null || rspu.getDeletedAt() != null) {
            throw new ResourceNotFoundException("产品不存在: " + rspuId);
        }

        RspuMaster oldSnapshot = snapshot(rspu);
        rspu.setReviewStatus(reviewStatus);
        rspu.setReviewComment(reviewComment);
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMapper.updateById(rspu);

        auditLogService.logReview("rspu_master", rspuId, oldSnapshot, rspu, "admin");
    }

    /**
     * 更新产品元数据。
     *
     * <p>只更新请求中非 {@code null} 的字段；风格/场景变更会同步维护
     * {@code rspu_style} / {@code rspu_scene} 关联表。
     *
     * @param rspuId  RSPU ID
     * @param request 更新请求
     */
    @Transactional
    public void updateProduct(String rspuId, ProductUpdateRequest request) {
        RspuMaster rspu = rspuMapper.selectById(rspuId);
        if (rspu == null || rspu.getDeletedAt() != null) {
            throw new ResourceNotFoundException("产品不存在: " + rspuId);
        }

        RspuMaster oldSnapshot = snapshot(rspu);
        String oldPositioningLabel = rspu.getPositioningLabel();
        String oldSceneTagsJson = rspu.getSceneTags();

        if (StringUtils.hasText(request.getPositioningLabel())) {
            rspu.setPositioningLabel(request.getPositioningLabel().trim());
        }
        if (request.getColorPrimaryName() != null) {
            rspu.setColorPrimaryName(request.getColorPrimaryName().trim());
        }
        if (request.getColorPrimaryHsv() != null) {
            rspu.setColorPrimaryHsv(writeJson(request.getColorPrimaryHsv()));
        }
        if (request.getMaterialTags() != null) {
            rspu.setMaterialTags(writeJson(request.getMaterialTags()));
        }
        if (request.getSceneTags() != null) {
            rspu.setSceneTags(writeJson(request.getSceneTags()));
        }
        if (request.getSixDimTags() != null) {
            rspu.setSixDimTags(writeJson(request.getSixDimTags()));
        }
        if (request.getReferencePriceBand() != null) {
            rspu.setReferencePriceBand(request.getReferencePriceBand().trim());
        }
        if (StringUtils.hasText(request.getProductLevel())) {
            validateProductLevel(request.getProductLevel().trim());
            rspu.setProductLevel(request.getProductLevel().trim());
        }
        if (request.getWarrantyYears() != null) {
            rspu.setWarrantyYears(request.getWarrantyYears());
        }
        if (request.getKeySpecs() != null) {
            rspu.setKeySpecs(writeJson(request.getKeySpecs()));
        }

        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMapper.updateById(rspu);

        if (StringUtils.hasText(request.getPositioningLabel())
            && !request.getPositioningLabel().trim().equals(oldPositioningLabel)) {
            updatePrimaryStyle(rspuId, request.getPositioningLabel().trim());
        }

        if (request.getSceneTags() != null
            && !writeJson(request.getSceneTags()).equals(oldSceneTagsJson)) {
            updateScenes(rspuId, request.getSceneTags());
        }

        auditLogService.logUpdate("rspu_master", rspuId, oldSnapshot, rspu, "admin");
    }

    private void updatePrimaryStyle(String rspuId, String styleCode) {
        rspuStyleMapper.delete(new QueryWrapper<RspuStyle>().eq("rspu_id", rspuId));
        RspuStyle style = new RspuStyle();
        style.setRspuId(rspuId);
        style.setDictType("style");
        style.setStyleCode(styleCode);
        style.setIsPrimary(true);
        style.setCreatedAt(LocalDateTime.now());
        rspuStyleMapper.insert(style);
    }

    private void updateScenes(String rspuId, List<String> sceneCodes) {
        rspuSceneMapper.delete(new QueryWrapper<RspuScene>().eq("rspu_id", rspuId));
        if (sceneCodes == null || sceneCodes.isEmpty()) {
            return;
        }
        for (String code : sceneCodes) {
            if (!StringUtils.hasText(code)) {
                continue;
            }
            RspuScene scene = new RspuScene();
            scene.setRspuId(rspuId);
            scene.setDictType("scene");
            scene.setSceneCode(code.trim());
            scene.setCreatedAt(LocalDateTime.now());
            rspuSceneMapper.insert(scene);
        }
    }

    private void validateProductLevel(String level) {
        boolean exists = dictService.listByType("factory_level").stream()
            .anyMatch(d -> level.equals(d.getDictCode()) || level.equals(d.getDictName()));
        if (!exists) {
            throw new com.rsdp.exception.BusinessException("产品等级不存在: " + level);
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("JSON 序列化失败: {}", value, e);
            throw new com.rsdp.exception.BusinessException("JSON 序列化失败: " + e.getMessage());
        }
    }

    private RspuMaster snapshot(RspuMaster source) {
        RspuMaster copy = new RspuMaster();
        copy.setRspuId(source.getRspuId());
        copy.setCategoryCode(source.getCategoryCode());
        copy.setCategoryPath(source.getCategoryPath());
        copy.setPositioningLabel(source.getPositioningLabel());
        copy.setColorPrimaryName(source.getColorPrimaryName());
        copy.setColorPrimaryHsv(source.getColorPrimaryHsv());
        copy.setMaterialTags(source.getMaterialTags());
        copy.setSceneTags(source.getSceneTags());
        copy.setSixDimTags(source.getSixDimTags());
        copy.setStatus(source.getStatus());
        copy.setReviewStatus(source.getReviewStatus());
        copy.setReviewComment(source.getReviewComment());
        copy.setAestheticsConfidence(source.getAestheticsConfidence());
        copy.setProductLevel(source.getProductLevel());
        copy.setSourceAgentVersion(source.getSourceAgentVersion());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private ProductSummaryResponse toSummary(RspuMaster rspu) {
        ProductSummaryResponse summary = new ProductSummaryResponse();
        summary.setRspuId(rspu.getRspuId());
        summary.setCategoryCode(rspu.getCategoryCode());
        summary.setCategoryPath(rspu.getCategoryPath());
        summary.setPositioningLabel(rspu.getPositioningLabel());
        summary.setColorPrimaryName(rspu.getColorPrimaryName());
        summary.setStatus(rspu.getStatus());
        summary.setReviewStatus(rspu.getReviewStatus());
        summary.setAestheticsConfidence(rspu.getAestheticsConfidence());
        summary.setProductLevel(rspu.getProductLevel());
        summary.setCreatedAt(rspu.getCreatedAt());
        summary.setUpdatedAt(rspu.getUpdatedAt());

        ImageAssets primaryImage = imageAssetsMapper.selectOne(
            new QueryWrapper<ImageAssets>()
                .eq("rspu_id", rspu.getRspuId())
                .eq("is_primary", true)
                .last("LIMIT 1")
        );
        if (primaryImage != null) {
            summary.setPrimaryImageUrl(buildImageUrl(primaryImage.getImageId()));
        }
        return summary;
    }

    private String buildImageUrl(String imageId) {
        return "/api/v1/images/" + imageId;
    }
}
