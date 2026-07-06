package com.rsdp.service;

import com.rsdp.security.SecurityOperatorContext;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.common.PageResult;
import com.rsdp.common.ReviewStatus;
import com.rsdp.dto.request.ProductListRequest;
import com.rsdp.dto.request.ProductUpdateRequest;
import com.rsdp.dto.response.ProductDetailResponse;
import com.rsdp.dto.response.ProductStyleMatchResponse;
import com.rsdp.dto.response.ProductSummaryResponse;
import com.rsdp.entity.AiRecognition;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RspuStyle;
import com.rsdp.event.RspuDeletedEvent;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.AiRecognitionMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.ProductStyleMatchMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ProductStyleMatchMapper productStyleMatchMapper;
    private final AuditLogService auditLogService;
    private final DictService dictService;
    private final ObjectMapper objectMapper;
    private final RspuRelationService rspuRelationService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 分页查询产品列表。
     *
     * @param request 查询条件
     * @return 分页结果
     */
    public PageResult<ProductSummaryResponse> listProducts(ProductListRequest request) {
        Page<RspuMaster> pageParam = new Page<>(request.getPage(), request.getSize());
        QueryWrapper<RspuMaster> wrapper = new QueryWrapper<>();

        if (StringUtils.hasText(request.getCategoryCode())) {
            wrapper.eq("category_code", request.getCategoryCode());
        }
        if (StringUtils.hasText(request.getPositioningLabel())) {
            wrapper.exists(
                "SELECT 1 FROM rspu_style s WHERE s.rspu_id = rspu_master.rspu_id AND s.style_code = {0}",
                request.getPositioningLabel().trim()
            );
        }
        if (StringUtils.hasText(request.getSceneCode())) {
            wrapper.exists(
                "SELECT 1 FROM rspu_scene s WHERE s.rspu_id = rspu_master.rspu_id AND s.scene_code = {0}",
                request.getSceneCode().trim()
            );
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
        // 非管理员默认只展示已审核通过的产品
        if (!SecurityOperatorContext.isCurrentUserAdmin()) {
            wrapper.eq("review_status", ReviewStatus.APPROVED.getDbValue());
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

        Map<String, String> primaryImageUrlMap = batchPrimaryImageUrls(
            page.getRecords().stream().map(RspuMaster::getRspuId).toList()
        );

        List<ProductSummaryResponse> rows = page.getRecords().stream()
            .map(rspu -> toSummary(rspu, primaryImageUrlMap))
            .collect(Collectors.toList());

        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), rows);
    }

    private Map<String, String> batchPrimaryImageUrls(List<String> rspuIds) {
        if (rspuIds == null || rspuIds.isEmpty()) {
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
                img -> buildImageUrl(img.getImageId()),
                (a, b) -> a
            ));
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
        if (!SecurityOperatorContext.isCurrentUserAdmin()
            && !ReviewStatus.APPROVED.getDbValue().equals(rspu.getReviewStatus())) {
            throw new ResourceNotFoundException("产品不存在: " + rspuId);
        }

        List<ImageAssets> images = imageAssetsMapper.selectList(
            new QueryWrapper<ImageAssets>().eq("rspu_id", rspuId).orderByDesc("is_primary")
        );
        List<AiRecognition> recognitions = aiRecognitionMapper.selectList(
            new QueryWrapper<AiRecognition>().eq("rspu_id", rspuId).orderByDesc("created_at")
        );
        List<ProductStyleMatchResponse> styleMatches = listStyleMatches(rspuId);

        ProductDetailResponse response = new ProductDetailResponse();
        response.setRspu(rspu);
        response.setImages(images);
        response.setRecognitions(recognitions);
        response.setStyleMatches(styleMatches);
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

        auditLogService.logReview("rspu_master", rspuId, oldSnapshot, rspu, SecurityOperatorContext.currentUsername());
    }

    /**
     * 软删除产品。
     *
     * <p>数据库软删除和审计日志在事务内完成；ChromaDB 向量清理通过
     * {@link RspuDeletedEvent} 异步解耦执行，避免外部 IO 拖长事务。</p>
     *
     * @param rspuId RSPU ID
     */
    @Transactional
    public void deleteProduct(String rspuId) {
        RspuMaster rspu = rspuMapper.selectById(rspuId);
        if (rspu == null || rspu.getDeletedAt() != null) {
            throw new ResourceNotFoundException("产品不存在: " + rspuId);
        }

        RspuMaster oldSnapshot = snapshot(rspu);
        rspu.setDeletedAt(LocalDateTime.now());
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMapper.updateById(rspu);

        auditLogService.logDelete("rspu_master", rspuId, oldSnapshot, SecurityOperatorContext.currentUsername());

        publishRspuDeletedEvent(rspuId);
    }

    private void publishRspuDeletedEvent(String rspuId) {
        List<ImageAssets> images = imageAssetsMapper.selectList(
            new QueryWrapper<ImageAssets>().eq("rspu_id", rspuId)
        );
        List<String> imageIds = images.stream()
            .map(ImageAssets::getImageId)
            .toList();
        eventPublisher.publishEvent(new RspuDeletedEvent(rspuId, imageIds));
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

        String styleCode = null;
        if (StringUtils.hasText(request.getPositioningLabel())) {
            styleCode = request.getPositioningLabel().trim().toUpperCase();
            validateDictCode("style", styleCode);
            rspu.setPositioningLabel(styleCode);
        }
        if (request.getColorPrimaryName() != null) {
            rspu.setColorPrimaryName(request.getColorPrimaryName().trim());
        }
        if (request.getColorPrimaryHsv() != null) {
            rspu.setColorPrimaryHsv(writeJson(request.getColorPrimaryHsv()));
        }
        if (request.getMaterialTags() != null) {
            List<String> materialCodes = normalizeCodes(request.getMaterialTags());
            validateDictCodes("material", materialCodes);
            rspu.setMaterialTags(writeJson(materialCodes));
        }
        List<String> sceneCodes = null;
        if (request.getSceneTags() != null) {
            sceneCodes = normalizeCodes(request.getSceneTags());
            validateDictCodes("scene", sceneCodes);
            rspu.setSceneTags(writeJson(sceneCodes));
        }
        if (request.getSixDimTags() != null) {
            rspu.setSixDimTags(writeJson(request.getSixDimTags()));
        }
        if (request.getReferencePriceBand() != null) {
            rspu.setReferencePriceBand(request.getReferencePriceBand().trim());
        }
        if (StringUtils.hasText(request.getProductLevel())) {
            String productLevel = request.getProductLevel().trim().toUpperCase();
            validateProductLevel(productLevel);
            rspu.setProductLevel(productLevel);
        }
        if (request.getWarrantyYears() != null) {
            rspu.setWarrantyYears(request.getWarrantyYears());
        }
        if (request.getKeySpecs() != null) {
            rspu.setKeySpecs(writeJson(request.getKeySpecs()));
        }

        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMapper.updateById(rspu);

        if (StringUtils.hasText(styleCode)
            && !styleCode.equals(oldPositioningLabel)) {
            updatePrimaryStyle(rspuId, styleCode);
        }

        if (sceneCodes != null
            && !writeJson(sceneCodes).equals(oldSceneTagsJson)) {
            updateScenes(rspuId, sceneCodes);
        }

        auditLogService.logUpdate("rspu_master", rspuId, oldSnapshot, rspu, SecurityOperatorContext.currentUsername());
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
        validateDictCode("factory_level", level);
    }

    private void validateDictCode(String dictType, String dictCode) {
        boolean exists = dictService.listByType(dictType).stream()
            .anyMatch(d -> dictCode.equals(d.getDictCode()));
        if (!exists) {
            throw new BusinessException(dictErrorMessage(dictType, dictCode));
        }
    }

    private void validateDictCodes(String dictType, List<String> dictCodes) {
        if (dictCodes == null || dictCodes.isEmpty()) {
            return;
        }
        List<String> validCodes = dictService.listByType(dictType).stream()
            .map(CategoryDict::getDictCode)
            .toList();
        for (String code : dictCodes) {
            if (!StringUtils.hasText(code)) {
                continue;
            }
            if (!validCodes.contains(code.trim())) {
                throw new BusinessException(dictErrorMessage(dictType, code.trim()));
            }
        }
    }

    private List<String> normalizeCodes(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        return codes.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .map(String::toUpperCase)
            .toList();
    }

    private String dictErrorMessage(String dictType, String dictCode) {
        return switch (dictType) {
            case "style" -> "风格不存在: " + dictCode;
            case "scene" -> "场景标签不存在: " + dictCode;
            case "material" -> "材质标签不存在: " + dictCode;
            case "factory_level" -> "产品等级不存在: " + dictCode;
            case "category" -> "品类不存在: " + dictCode;
            default -> "字典项不存在: " + dictType + "=" + dictCode;
        };
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

    private List<ProductStyleMatchResponse> listStyleMatches(String rspuId) {
        var entities = productStyleMatchMapper.selectByRspuId(rspuId);
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        return entities.stream().map(entity -> {
            ProductStyleMatchResponse r = new ProductStyleMatchResponse();
            r.setMatchId(entity.getMatchId());
            r.setRspuId(entity.getRspuId());
            r.setStyleCode(entity.getStyleCode());
            r.setStyleName(resolveStyleName(entity.getStyleCode()));
            r.setOverallScore(entity.getOverallScore());
            r.setConfidence(entity.getConfidence());
            r.setElementMatch(entity.getElementMatch());
            r.setFormulaScores(entity.getFormulaScores());
            r.setCreatedAt(entity.getCreatedAt());
            r.setUpdatedAt(entity.getUpdatedAt());
            return r;
        }).collect(Collectors.toList());
    }

    private String resolveStyleName(String styleCode) {
        if (!StringUtils.hasText(styleCode)) {
            return styleCode;
        }
        return dictService.listByType("style").stream()
            .filter(d -> styleCode.equals(d.getDictCode()))
            .findFirst()
            .map(com.rsdp.entity.CategoryDict::getDictName)
            .orElse(styleCode);
    }

    private ProductSummaryResponse toSummary(RspuMaster rspu, Map<String, String> primaryImageUrlMap) {
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
        summary.setPrimaryImageUrl(primaryImageUrlMap.get(rspu.getRspuId()));
        return summary;
    }

    private String buildImageUrl(String imageId) {
        return "/api/v1/images/" + imageId;
    }
}
