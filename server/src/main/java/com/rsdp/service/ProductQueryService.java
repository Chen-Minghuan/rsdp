package com.rsdp.service;

import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.security.datascope.DataScopeHelper;

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
import com.rsdp.entity.FactoryProductCapability;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RspuStyle;
import com.rsdp.entity.RskuSupply;
import com.rsdp.entity.SysUser;
import com.rsdp.event.RspuDeletedEvent;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.AiRecognitionMapper;
import com.rsdp.mapper.FactoryProductCapabilityMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.ProductStyleMatchMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final UserFactoryService userFactoryService;
    private final FactoryProductCapabilityMapper capabilityMapper;
    private final RskuSupplyMapper rskuSupplyMapper;
    private final SysUserMapper sysUserMapper;
    private final DataScopeHelper dataScopeHelper;

    /**
     * 分页查询产品列表。
     *
     * <p>支持工厂管理员视角：
     * <ul>
     *   <li>{@code viewMode=own}：仅返回当前用户关联工厂已录入 RSKU 的产品。</li>
     *   <li>{@code viewMode=full}：在 {@code view_full_catalog=true} 时返回全库中
     *       未被本工厂能力覆盖的产品（始终保留自己已有的产品）。</li>
     * </ul>
     *
     * @param request 查询条件
     * @return 分页结果
     */
    public PageResult<ProductSummaryResponse> listProducts(ProductListRequest request) {
        List<String> userFactoryCodes = resolveUserFactoryCodes(request.getFactoryCode());
        String viewMode = resolveViewMode(request.getViewMode());
        boolean isFullView = "full".equals(viewMode) && isFullViewEligible(userFactoryCodes);

        QueryWrapper<RspuMaster> wrapper = buildListWrapper(request, viewMode);

        if ("own".equals(viewMode)) {
            applyOwnProductFilter(wrapper, userFactoryCodes);
        }

        if (isFullView) {
            // 全库去重视图：先按常规条件查询全部候选，再后过滤去重并手动分页
            wrapper.orderByDesc("created_at");
            List<RspuMaster> candidates = rspuMapper.selectList(wrapper);
            List<RspuMaster> filtered = applyFullViewFilter(candidates, userFactoryCodes);
            return paginateAndSummarize(filtered, request.getPage(), request.getSize());
        }

        Page<RspuMaster> pageParam = new Page<>(request.getPage(), request.getSize());
        wrapper.orderByDesc("created_at");
        Page<RspuMaster> page = rspuMapper.selectPage(pageParam, wrapper);

        List<String> rspuIds = page.getRecords().stream().map(RspuMaster::getRspuId).toList();
        Map<String, String> primaryImageUrlMap = batchPrimaryImageUrls(rspuIds);
        Map<String, List<String>> factoryCodeMap = batchFactoryCodes(rspuIds);

        List<ProductSummaryResponse> rows = page.getRecords().stream()
            .map(rspu -> toSummary(rspu, primaryImageUrlMap, factoryCodeMap))
            .collect(Collectors.toList());

        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), rows);
    }

    private QueryWrapper<RspuMaster> buildListWrapper(ProductListRequest request, String viewMode) {
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
            try {
                String tagJson = objectMapper.writeValueAsString(List.of(request.getMaterialTag().trim()));
                wrapper.apply("material_tags @> {0}::jsonb", tagJson);
            } catch (Exception e) {
                log.warn("材质标签 JSON 序列化失败: {}", request.getMaterialTag(), e);
                wrapper.apply("1 = 0");
            }
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq("status", request.getStatus());
        }
        // 平台运营人员（ADMIN/EDITOR）和设计师可按请求参数筛选复核状态；
        // 其他非运营人员在「全库视图」下只看已确认产品，
        // 在「自己的产品」视图下不过滤复核状态，确保工厂管理员能看到自己录入的待复核产品
        if (SecurityOperatorContext.isPlatformStaff() || SecurityOperatorContext.isCurrentUserDesigner()) {
            if (StringUtils.hasText(request.getReviewStatus())) {
                wrapper.eq("review_status", request.getReviewStatus());
            }
        } else if (!"own".equals(viewMode)) {
            wrapper.eq("review_status", ReviewStatus.APPROVED.getDbValue());
        }
        if (StringUtils.hasText(request.getProductLevel())) {
            wrapper.eq("product_level", request.getProductLevel());
        }
        if (StringUtils.hasText(request.getKeyword())) {
            String keyword = "%" + request.getKeyword().trim() + "%";
            wrapper.and(w -> w.like("category_path", keyword).or().like("rspu_id", keyword));
        }

        return wrapper;
    }

    private List<String> resolveUserFactoryCodes(String requestedFactoryCode) {
        List<String> userFactoryCodes = userFactoryService.getFactoryCodesByUsername(
            SecurityOperatorContext.currentUsername()
        );
        if (!StringUtils.hasText(requestedFactoryCode)) {
            return userFactoryCodes;
        }
        String code = requestedFactoryCode.trim();
        if (!userFactoryCodes.contains(code)) {
            throw new BusinessException("无权查看该工厂数据: " + code);
        }
        return List.of(code);
    }

    private String resolveViewMode(String viewMode) {
        if (!StringUtils.hasText(viewMode)) {
            return "own";
        }
        String mode = viewMode.trim().toLowerCase();
        if ("own".equals(mode) || "full".equals(mode)) {
            return mode;
        }
        return "own";
    }

    private boolean isFullViewEligible(List<String> userFactoryCodes) {
        if (!SecurityOperatorContext.isCurrentUserFactoryAdmin()) {
            return false;
        }
        if (userFactoryCodes.isEmpty()) {
            return false;
        }
        SysUser currentUser = getCurrentSysUser();
        return currentUser != null && Boolean.TRUE.equals(currentUser.getViewFullCatalog());
    }

    private SysUser getCurrentSysUser() {
        String username = SecurityOperatorContext.currentUsername();
        if ("anonymous".equals(username)) {
            return null;
        }
        return sysUserMapper.selectByUsername(username);
    }

    /**
     * 判断当前登录用户是否有权查看指定产品。
     *
     * <p>规则：
     * <ul>
     *   <li>平台运营人员（ADMIN/EDITOR）和设计师始终可见。</li>
     *   <li>已确认（复核通过）产品对所有用户可见。</li>
     *   <li>非确认产品仅对关联工厂管理员可见（通过 RSKU 工厂归属判断）。</li>
     * </ul>
     *
     * @param rspu 产品主档
     * @return 是否可见
     */
    private boolean canViewProduct(RspuMaster rspu) {
        if (SecurityOperatorContext.isPlatformStaff() || SecurityOperatorContext.isCurrentUserDesigner()) {
            return true;
        }
        if (ReviewStatus.APPROVED.getDbValue().equals(rspu.getReviewStatus())) {
            return true;
        }
        List<String> factoryCodes = userFactoryService.getFactoryCodesByUsername(
            SecurityOperatorContext.currentUsername()
        );
        if (factoryCodes.isEmpty()) {
            return false;
        }
        Long count = rskuSupplyMapper.selectCount(
            new QueryWrapper<RskuSupply>()
                .eq("rspu_id", rspu.getRspuId())
                .in("factory_code", factoryCodes)
                .isNull("deleted_at")
        );
        return count != null && count > 0;
    }

    private void applyOwnProductFilter(QueryWrapper<RspuMaster> wrapper, List<String> factoryCodes) {
        if (SecurityOperatorContext.isPlatformStaff()) {
            return;
        }
        if (factoryCodes.isEmpty()) {
            wrapper.apply("1 = 0");
            return;
        }
        List<Object> rspuIdObjs = rskuSupplyMapper.selectObjs(
            new QueryWrapper<RskuSupply>()
                .select("DISTINCT rspu_id")
                .in("factory_code", factoryCodes)
                .isNull("deleted_at")
        );
        List<String> rspuIds = rspuIdObjs.stream()
            .map(Object::toString)
            .distinct()
            .toList();
        if (rspuIds.isEmpty()) {
            wrapper.apply("1 = 0");
        } else {
            wrapper.in("rspu_id", rspuIds);
        }
    }

    private List<RspuMaster> applyFullViewFilter(List<RspuMaster> candidates, List<String> userFactoryCodes) {
        if (candidates.isEmpty() || userFactoryCodes.isEmpty()) {
            return candidates;
        }

        Set<String> ownRspuIds = findOwnRspuIds(userFactoryCodes);
        CapabilityMatcher matcher = buildCapabilityMatcher(userFactoryCodes);

        return candidates.stream()
            .filter(rspu -> ownRspuIds.contains(rspu.getRspuId()) || !matcher.isCovered(rspu))
            .collect(Collectors.toList());
    }

    private Set<String> findOwnRspuIds(List<String> factoryCodes) {
        List<RskuSupply> ownRskus = rskuSupplyMapper.selectList(
            new QueryWrapper<RskuSupply>()
                .in("factory_code", factoryCodes)
                .isNull("deleted_at")
        );
        return ownRskus.stream()
            .map(RskuSupply::getRspuId)
            .collect(Collectors.toSet());
    }

    private CapabilityMatcher buildCapabilityMatcher(List<String> factoryCodes) {
        List<FactoryProductCapability> capabilities = capabilityMapper.selectList(
            new QueryWrapper<FactoryProductCapability>()
                .in("factory_code", factoryCodes)
        );
        return new CapabilityMatcher(capabilities, objectMapper);
    }

    private PageResult<ProductSummaryResponse> paginateAndSummarize(List<RspuMaster> records, long page, long size) {
        long total = records.size();
        long from = (page - 1) * size;
        if (from >= total) {
            return PageResult.of(total, page, size, List.of());
        }
        long to = Math.min(from + size, total);
        List<RspuMaster> pageRecords = records.subList((int) from, (int) to);

        List<String> pageRspuIds = pageRecords.stream().map(RspuMaster::getRspuId).toList();
        Map<String, String> primaryImageUrlMap = batchPrimaryImageUrls(pageRspuIds);
        Map<String, List<String>> factoryCodeMap = batchFactoryCodes(pageRspuIds);

        List<ProductSummaryResponse> rows = pageRecords.stream()
            .map(rspu -> toSummary(rspu, primaryImageUrlMap, factoryCodeMap))
            .collect(Collectors.toList());

        return PageResult.of(total, page, size, rows);
    }

    /**
     * 能力键匹配器。
     *
     * <p>支持三级通配：
     * <ul>
     *   <li>(category, null, null)：覆盖整个品类。</li>
     *   <li>(category, style, null)：覆盖该品类下指定风格。</li>
     *   <li>(category, style, material)：覆盖该品类/风格/材质组合。</li>
     * </ul>
     */
    private class CapabilityMatcher {

        private final Set<String> exactKeys = new HashSet<>();
        private final Set<String> categoryStyleKeys = new HashSet<>();
        private final Set<String> categoryKeys = new HashSet<>();
        private final ObjectMapper mapper;

        CapabilityMatcher(List<FactoryProductCapability> capabilities, ObjectMapper mapper) {
            this.mapper = mapper;
            for (FactoryProductCapability cap : capabilities) {
                String category = cap.getCategoryCode();
                String style = cap.getStyleCode();
                String material = cap.getMaterialCode();
                if (!StringUtils.hasText(category)) {
                    continue;
                }
                boolean hasStyle = StringUtils.hasText(style);
                boolean hasMaterial = StringUtils.hasText(material);
                if (hasStyle && hasMaterial) {
                    exactKeys.add(key(category, style, material));
                } else if (hasStyle) {
                    categoryStyleKeys.add(key(category, style));
                } else {
                    categoryKeys.add(category);
                }
            }
        }

        boolean isCovered(RspuMaster rspu) {
            String category = rspu.getCategoryCode();
            String style = rspu.getPositioningLabel();
            if (!StringUtils.hasText(category)) {
                return false;
            }
            List<String> materials = parseMaterialTags(rspu.getMaterialTags());

            // 品类级覆盖
            if (categoryKeys.contains(category)) {
                return true;
            }
            if (!StringUtils.hasText(style)) {
                return false;
            }
            // 品类+风格级覆盖
            if (categoryStyleKeys.contains(key(category, style))) {
                return true;
            }
            // 精确匹配：任意材质命中
            for (String material : materials) {
                if (exactKeys.contains(key(category, style, material))) {
                    return true;
                }
            }
            return false;
        }

        private List<String> parseMaterialTags(String materialTagsJson) {
            if (!StringUtils.hasText(materialTagsJson)) {
                return List.of();
            }
            try {
                return mapper.readValue(materialTagsJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            } catch (Exception e) {
                return List.of();
            }
        }

        private static String key(String... parts) {
            return String.join(":", parts);
        }
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
     * 批量查询 RSPU 关联的工厂编码列表（用于列表项展示与前端删除权限判断）。
     *
     * @param rspuIds RSPU ID 列表
     * @return RSPU ID -> 工厂编码列表（去重）
     */
    private Map<String, List<String>> batchFactoryCodes(List<String> rspuIds) {
        if (rspuIds == null || rspuIds.isEmpty()) {
            return Map.of();
        }
        List<RskuSupply> rskus = rskuSupplyMapper.selectList(
            new QueryWrapper<RskuSupply>()
                .in("rspu_id", rspuIds)
                .isNull("deleted_at")
        );
        return rskus.stream()
            .collect(Collectors.groupingBy(
                RskuSupply::getRspuId,
                Collectors.mapping(
                    RskuSupply::getFactoryCode,
                    Collectors.collectingAndThen(Collectors.toSet(), ArrayList::new)
                )
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
        if (!canViewProduct(rspu)) {
            throw new ResourceNotFoundException("产品不存在: " + rspuId);
        }

        List<ImageAssets> images = imageAssetsMapper.selectList(
            new QueryWrapper<ImageAssets>().eq("rspu_id", rspuId).orderByDesc("is_primary")
        );
        // AI 识别记录（含原始 OCR、模型输出）仅对平台运营人员返回，其他角色只看解析后的产品标签
        List<AiRecognition> recognitions = SecurityOperatorContext.isPlatformStaff()
            ? aiRecognitionMapper.selectList(
                new QueryWrapper<AiRecognition>().eq("rspu_id", rspuId).orderByDesc("created_at")
            )
            : List.of();
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
        dataScopeHelper.assertCanAccessRspu(rspuId);

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
        dataScopeHelper.assertCanAccessRspu(rspuId);
        if (!dataScopeHelper.isOnlyAssociatedFactoryForRspu(rspuId)) {
            throw new BusinessException("其他工厂已关联该产品，无法删除: " + rspuId);
        }

        RspuMaster oldSnapshot = snapshot(rspu);
        int affected = rspuMapper.deleteById(rspuId);
        if (affected == 0) {
            throw new ResourceNotFoundException("产品不存在或已被删除: " + rspuId);
        }

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

        dataScopeHelper.assertCanAccessRspu(rspuId);

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

    private ProductSummaryResponse toSummary(RspuMaster rspu,
                                             Map<String, String> primaryImageUrlMap,
                                             Map<String, List<String>> factoryCodeMap) {
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
        summary.setFactoryCodes(factoryCodeMap.getOrDefault(rspu.getRspuId(), List.of()));
        return summary;
    }

    private String buildImageUrl(String imageId) {
        return "/api/v1/images/" + imageId;
    }
}
