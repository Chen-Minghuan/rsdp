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
import com.rsdp.dto.response.ProductBatchDeleteResponse;
import com.rsdp.dto.response.ProductDetailResponse;
import com.rsdp.dto.response.ProductStatusCountsResponse;
import com.rsdp.dto.response.ProductStyleMatchResponse;
import com.rsdp.dto.response.ProductSummaryResponse;
import com.rsdp.dto.response.SchemeRefInfo;
import com.rsdp.entity.AiRecognition;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuRelation;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RspuStyle;
import com.rsdp.entity.RspuVariant;
import com.rsdp.entity.RspuFactoryMapping;
import com.rsdp.entity.RspuPriceSummary;
import com.rsdp.entity.RskuSupply;
import com.rsdp.entity.SysUser;
import com.rsdp.entity.UserFavorite;
import com.rsdp.event.RspuDeletedEvent;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.AiRecognitionMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.ProductStyleMatchMapper;
import com.rsdp.mapper.ProductPurgeMapper;
import com.rsdp.mapper.RspuFactoryMappingMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuRelationMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import com.rsdp.mapper.RspuVariantMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.mapper.UserFavoriteMapper;
import com.rsdp.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
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
    private final RspuVariantMapper rspuVariantMapper;
    private final RspuRelationMapper rspuRelationMapper;
    private final ProductStyleMatchMapper productStyleMatchMapper;
    private final AuditLogService auditLogService;
    private final DictService dictService;
    private final ObjectMapper objectMapper;
    private final RspuRelationService rspuRelationService;
    private final ApplicationEventPublisher eventPublisher;
    private final UserFactoryService userFactoryService;
    private final RskuSupplyMapper rskuSupplyMapper;
    private final SysUserMapper sysUserMapper;
    private final DataScopeHelper dataScopeHelper;
    private final PlatformTransactionManager transactionManager;
    private final RspuFactoryMappingMapper rspuFactoryMappingMapper;
    private final UserFavoriteMapper userFavoriteMapper;
    private final StorageService storageService;
    private final ProductPurgeMapper productPurgeMapper;
    private final RspuPriceSummaryService rspuPriceSummaryService;

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
        if ("recycled".equals(request.getStatusTab())) {
            return listRecycledProducts(request);
        }
        List<String> userFactoryCodes = resolveUserFactoryCodes(request.getFactoryCode());
        String viewMode = resolveViewMode(request.getViewMode());
        boolean isFullView = "full".equals(viewMode) && isFullViewEligible(userFactoryCodes);

        QueryWrapper<RspuMaster> wrapper = buildListWrapper(request, viewMode);

        if ("own".equals(viewMode)) {
            applyOwnProductFilter(wrapper, userFactoryCodes);
        }

        if (isFullView) {
            // 全库去重视图：自有 RSKU / 工厂能力覆盖条件下推到 SQL，由分页插件在库内分页，
            // 不再 selectList 全量候选进 JVM 后手工过滤分页（见 applyFullViewFilter）
            applyFullViewFilter(wrapper, userFactoryCodes);
        }

        Page<RspuMaster> pageParam = new Page<>(request.getPage(), request.getSize());
        wrapper.orderByDesc("created_at");
        Page<RspuMaster> page = rspuMapper.selectPage(pageParam, wrapper);

        List<String> rspuIds = page.getRecords().stream().map(RspuMaster::getRspuId).toList();
        Map<String, String> primaryImageUrlMap = batchPrimaryImageUrls(rspuIds);
        Map<String, List<String>> factoryCodeMap = batchFactoryCodes(rspuIds);
        // 当页最低价与报价数取自价格投影表（V44），不再批量查 RSKU 实体解密聚合。
        // 最低出厂价仅平台运营人员可见（toSummary 响应层掩码），非平台员工不发起查询，
        // 与出厂价泄露封堵口径一致；报价数全角色可见，故掩码只影响 minPriceMap。
        Map<String, RspuPriceSummary> priceSummaryMap = SecurityOperatorContext.isPlatformStaff()
            ? rspuPriceSummaryService.batchSummaries(rspuIds)
            : Map.of();
        Map<String, BigDecimal> minPriceMap = priceSummaryMap.entrySet().stream()
            .filter(e -> e.getValue().getMinFactoryPrice() != null)
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getMinFactoryPrice()));
        Map<String, Long> rskuCountMap = priceSummaryMap.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> (long) e.getValue().getActiveRskuCount(),
                (a, b) -> a));

        List<ProductSummaryResponse> rows = page.getRecords().stream()
            .map(rspu -> toSummary(rspu, primaryImageUrlMap, factoryCodeMap, minPriceMap, rskuCountMap))
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
        applySixDimFilter(wrapper, "A", request.getDimA());
        applySixDimFilter(wrapper, "B", request.getDimB());
        applySixDimFilter(wrapper, "C", request.getDimC());
        applySixDimFilter(wrapper, "D", request.getDimD());
        applySixDimFilter(wrapper, "F", request.getDimF());
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
        if (StringUtils.hasText(request.getRspuCode())) {
            wrapper.like("rspu_code", "%" + request.getRspuCode().trim() + "%");
        }
        if (StringUtils.hasText(request.getSupplierCode())) {
            wrapper.exists(
                "SELECT 1 FROM rsku_supply r WHERE r.rspu_id = rspu_master.rspu_id"
                    + " AND r.deleted_at IS NULL AND r.factory_code LIKE {0}",
                "%" + request.getSupplierCode().trim() + "%"
            );
        }
        if (StringUtils.hasText(request.getCreatedFrom())) {
            wrapper.ge("created_at", LocalDate.parse(request.getCreatedFrom().trim()).atStartOfDay());
        }
        if (StringUtils.hasText(request.getCreatedTo())) {
            wrapper.le("created_at", LocalDate.parse(request.getCreatedTo().trim()).atTime(LocalTime.MAX));
        }
        applyStatusTab(wrapper, request.getStatusTab());
        applyPrimaryImageFilter(wrapper, request.getHasPrimaryImage());
        if (StringUtils.hasText(request.getKeyword())) {
            String keyword = "%" + request.getKeyword().trim() + "%";
            wrapper.and(w -> w.like("category_path", keyword).or().like("rspu_id", keyword));
        }

        return wrapper;
    }

    /**
     * 六维标签筛选：six_dim_tags JSONB 包含 {"A":"字典码"}，命中 GIN 索引 idx_rspu_six_dim_gin。
     * 筛选值为带品类前缀的字典码（如 SF-一字型），存量自由文本不匹配属预期。
     * E 维（表面材质）不枚举，通过材质标签筛选代替，不在此处理。
     *
     * @param wrapper 查询构造器
     * @param dimKey 维度键（A/B/C/D/F）
     * @param value 字典码筛选值，空则忽略
     */
    private void applySixDimFilter(QueryWrapper<RspuMaster> wrapper, String dimKey, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        try {
            String dimJson = objectMapper.writeValueAsString(Map.of(dimKey, value.trim()));
            wrapper.apply("six_dim_tags @> {0}::jsonb", dimJson);
        } catch (Exception e) {
            log.warn("六维标签 JSON 序列化失败: dim={}, value={}", dimKey, value, e);
            wrapper.apply("1 = 0");
        }
    }

    /**
     * 商城状态页签条件：onSale=出售中、warehouse=仓库中、soldOut=已售罄（恒空）。
     * recycled 不走常规 wrapper，由 {@link #listRecycledProducts} 单独处理。
     */
    private void applyStatusTab(QueryWrapper<RspuMaster> wrapper, String statusTab) {
        if (!StringUtils.hasText(statusTab)) {
            return;
        }
        switch (statusTab.trim()) {
            case "onSale" -> wrapper.eq("status", "active");
            case "warehouse" -> wrapper.ne("status", "active");
            case "soldOut" -> wrapper.apply("1 = 0");
            default -> { /* 未知页签不追加条件 */ }
        }
    }

    /**
     * 主图资产筛选：按主图存在性过滤（EXISTS / NOT EXISTS 子查询）。
     *
     * @param wrapper  查询构造器
     * @param hasImage true=仅有主图，false=仅无主图，null 不过滤
     */
    private void applyPrimaryImageFilter(QueryWrapper<RspuMaster> wrapper, Boolean hasImage) {
        if (hasImage == null) {
            return;
        }
        String subquery = "SELECT 1 FROM image_assets ia WHERE ia.rspu_id = rspu_master.rspu_id"
            + " AND ia.deleted_at IS NULL AND ia.is_primary = TRUE";
        if (hasImage) {
            wrapper.exists(subquery);
        } else {
            wrapper.notExists(subquery);
        }
    }
    /**
     * 回收站分页查询（已软删除的 RSPU，绕过 @TableLogic 自动过滤）。
     * 已删除产品的 RSKU/图片多已级联软删，最低出厂价与工厂代码可能为空。
     */
    private PageResult<ProductSummaryResponse> listRecycledProducts(ProductListRequest request) {
        Page<RspuMaster> page = rspuMapper.selectRecycledPage(new Page<>(request.getPage(), request.getSize()));
        List<String> rspuIds = page.getRecords().stream().map(RspuMaster::getRspuId).toList();
        Map<String, String> primaryImageUrlMap = batchPrimaryImageUrls(rspuIds);
        List<ProductSummaryResponse> rows = page.getRecords().stream()
            .map(rspu -> toSummary(rspu, primaryImageUrlMap, Map.of(), Map.of(), Map.of()))
            .collect(Collectors.toList());
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), rows);
    }

    /**
     * 商城商品列表状态页签统计。
     *
     * <p>出售中/仓库中复用列表过滤条件（不含 statusTab 自身）；已售罄当前无业务概念恒为 0；
     * 回收站为全库软删除总数（不叠加搜索条件）。
     *
     * @param request 查询条件（statusTab 忽略）
     * @return 各页签数量
     */
    public ProductStatusCountsResponse statusCounts(ProductListRequest request) {
        List<String> userFactoryCodes = resolveUserFactoryCodes(request.getFactoryCode());
        String viewMode = resolveViewMode(request.getViewMode());

        QueryWrapper<RspuMaster> base = buildListWrapper(request, viewMode);
        if ("own".equals(viewMode)) {
            applyOwnProductFilter(base, userFactoryCodes);
        }

        QueryWrapper<RspuMaster> onSale = base.clone();
        onSale.eq("status", "active");
        QueryWrapper<RspuMaster> inWarehouse = base.clone();
        inWarehouse.ne("status", "active");

        return new ProductStatusCountsResponse(
            rspuMapper.selectCount(onSale),
            rspuMapper.selectCount(inWarehouse),
            0L,
            rspuMapper.selectRecycledCount()
        );
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

    /**
     * 全库去重视图过滤（条件下推 SQL，配合分页插件在库内分页）。
     *
     * <p>保留两类产品，其余（被本工厂能力覆盖且非自有）剔除：
     * <ul>
     *   <li>自有产品：存在本工厂任一未删除 RSKU（EXISTS rsku_supply）；</li>
     *   <li>未被能力覆盖的产品（NOT EXISTS factory_product_capability），覆盖判定为三级通配：
     *     <ul>
     *       <li>品类级：能力行 style_code 为空即覆盖整个品类（material_code 忽略，与历史 Java 实现一致）；</li>
     *       <li>品类+风格级：style_code 命中且 material_code 为空；</li>
     *       <li>精确级：style_code 命中且 material_code 命中 material_tags JSONB 数组任一元素。</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * <p>边界语义与历史 Java 实现（CapabilityMatcher）对齐：能力行 category_code 为空（NULL/空白）
     * 时该行不参与覆盖；产品 positioning_label 为 NULL 时风格级/精确级比较不成立（SQL NULL 比较
     * 结果为 UNKNOWN，与 Java hasText 判空后跳过一致）；material_tags 非法 JSON 在 Java 实现中
     * 视为空数组，SQL 中 JSONB 列类型保证存储即合法 JSON，非数组值 @> 不命中，结果一致。</p>
     *
     * @param wrapper      查询构造器
     * @param factoryCodes 当前用户关联的工厂编码（full 视图入口已保证非空，见 isFullViewEligible）
     */
    private void applyFullViewFilter(QueryWrapper<RspuMaster> wrapper, List<String> factoryCodes) {
        String factoryIn = toSqlInList(factoryCodes);
        String coveredByCapability =
            "SELECT 1 FROM factory_product_capability cap"
                + " WHERE cap.factory_code IN (" + factoryIn + ")"
                + " AND NULLIF(BTRIM(cap.category_code), '') IS NOT NULL"
                + " AND cap.category_code = rspu_master.category_code"
                + " AND (NULLIF(BTRIM(cap.style_code), '') IS NULL"
                + " OR (cap.style_code = rspu_master.positioning_label"
                + " AND (NULLIF(BTRIM(cap.material_code), '') IS NULL"
                + " OR rspu_master.material_tags @> jsonb_build_array(cap.material_code))))";
        wrapper.and(w -> w
            .exists("SELECT 1 FROM rsku_supply rs WHERE rs.rspu_id = rspu_master.rspu_id"
                + " AND rs.factory_code IN (" + factoryIn + ") AND rs.deleted_at IS NULL")
            .or()
            .notExists(coveredByCapability));
    }

    /**
     * 拼接 SQL IN 片段（单引号成对转义）。仅用于工厂编码等来自数据归属的内部编码，
     * 不用于用户自由文本。
     *
     * @param values 编码列表（非空）
     * @return 形如 {@code 'A001', 'A002'} 的 IN 片段
     */
    private String toSqlInList(List<String> values) {
        return values.stream()
            .map(v -> "'" + v.replace("'", "''") + "'")
            .collect(Collectors.joining(", "));
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
     * <p>只取 rspu_id/factory_code 两列，避免整实体映射触发 factory_price 解密。</p>
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
                .select("rspu_id", "factory_code")
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
        if (rspu == null) {
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
        response.setStyleCodes(listStyleCodes(rspuId, rspu.getPositioningLabel()));
        response.setOfficialMatches(rspuRelationService.listByAnchor(rspuId));
        response.setMatchedBy(rspuRelationService.listByRelated(rspuId));
        return response;
    }

    /**
     * 查询产品的风格字典码列表（主风格在前）。
     *
     * <p>无关联记录的老数据回退为 positioningLabel 单值。</p>
     */
    private List<String> listStyleCodes(String rspuId, String positioningLabel) {
        List<RspuStyle> styles = rspuStyleMapper.selectList(new QueryWrapper<RspuStyle>()
            .eq("rspu_id", rspuId).orderByDesc("is_primary"));
        if (styles.isEmpty()) {
            return StringUtils.hasText(positioningLabel) ? List.of(positioningLabel) : List.of();
        }
        return styles.stream().map(RspuStyle::getStyleCode).toList();
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
        if (ReviewStatus.fromDbValue(reviewStatus) == null) {
            throw new BusinessException("无效的复核状态: " + reviewStatus + "，仅支持 待复核/已确认/存疑");
        }
        RspuMaster rspu = rspuMapper.selectById(rspuId);
        if (rspu == null) {
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
     * <p>数据库软删除和审计日志在事务内完成；pgvector 向量清理通过
     * {@link RspuDeletedEvent} 异步解耦执行，避免外部 IO 拖长事务。</p>
     *
     * @param rspuId RSPU ID
     */
    @Transactional
    public void deleteProduct(String rspuId) {
        RspuMaster rspu = rspuMapper.selectById(rspuId);
        if (rspu == null) {
            throw new ResourceNotFoundException("产品不存在: " + rspuId);
        }
        dataScopeHelper.assertCanAccessRspu(rspuId);
        if (!dataScopeHelper.isOnlyAssociatedFactoryForRspu(rspuId)) {
            throw new BusinessException("其他工厂已关联该产品，无法删除: " + rspuId);
        }

        RspuMaster oldSnapshot = snapshot(rspu);
        // 先收集图片 ID 用于 pgvector 向量清理（级联软删后图片将查询不可见）
        List<String> imageIds = imageAssetsMapper.selectList(
            new QueryWrapper<ImageAssets>().eq("rspu_id", rspuId)
        ).stream().map(ImageAssets::getImageId).toList();

        int affected = rspuMapper.deleteById(rspuId);
        if (affected == 0) {
            throw new ResourceNotFoundException("产品不存在或已被删除: " + rspuId);
        }

        cascadeDeleteAssociations(rspuId);

        auditLogService.logDelete("rspu_master", rspuId, oldSnapshot, SecurityOperatorContext.currentUsername());

        eventPublisher.publishEvent(new RspuDeletedEvent(rspuId, imageIds));
    }

    /**
     * 批量软删除产品。
     *
     * <p>每个产品在独立事务中删除（复用 {@link #deleteProduct} 的归属校验、
     * 级联清理、审计与向量事件），单个失败不影响其他产品，失败明细逐个返回。</p>
     *
     * @param rspuIds 待删除的 RSPU ID 列表
     * @return 删除结果（成功数 + 失败明细）
     */
    public ProductBatchDeleteResponse batchDeleteProducts(List<String> rspuIds) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        List<ProductBatchDeleteResponse.Failure> failures = new ArrayList<>();
        int deletedCount = 0;
        for (String rspuId : rspuIds) {
            if (!StringUtils.hasText(rspuId)) {
                failures.add(new ProductBatchDeleteResponse.Failure(rspuId, "RSPU ID 不能为空"));
                continue;
            }
            try {
                transactionTemplate.executeWithoutResult(status -> deleteProduct(rspuId.trim()));
                deletedCount++;
            } catch (Exception e) {
                log.warn("批量删除产品失败: rspuId={}, reason={}", rspuId, e.getMessage());
                failures.add(new ProductBatchDeleteResponse.Failure(rspuId, e.getMessage()));
            }
        }
        return new ProductBatchDeleteResponse(deletedCount, failures.size(), failures);
    }

    /**
     * 级联删除 RSPU 关联数据。
     *
     * <p>变体、报价、图片、搭配关系（双向）随产品一并软删除；
     * 风格、场景为无软删除语义的纯关联行，直接物理删除。</p>
     *
     * @param rspuId RSPU ID
     */
    private void cascadeDeleteAssociations(String rspuId) {
        rspuVariantMapper.delete(new QueryWrapper<RspuVariant>().eq("rspu_id", rspuId));
        rskuSupplyMapper.delete(new QueryWrapper<RskuSupply>().eq("rspu_id", rspuId));
        // RSKU 级联软删后重算价格投影（归 0/NULL），回收站还原时 restoreProduct 会再次重算
        rspuPriceSummaryService.recalculate(rspuId);
        imageAssetsMapper.delete(new QueryWrapper<ImageAssets>().eq("rspu_id", rspuId));
        rspuRelationMapper.delete(new QueryWrapper<RspuRelation>()
            .and(w -> w.eq("anchor_rspu_id", rspuId).or().eq("related_rspu_id", rspuId)));
        rspuStyleMapper.delete(new QueryWrapper<RspuStyle>().eq("rspu_id", rspuId));
        rspuSceneMapper.delete(new QueryWrapper<RspuScene>().eq("rspu_id", rspuId));
    }

    /**
     * 从回收站恢复产品（连带恢复级联软删的变体/报价/图片/搭配关系）。
     *
     * <p>注意：风格、场景关联在删除时已物理清除，无法恢复——恢复后需重新触发
     * AI 识别或手工补充。图片文件本体与 pgvector 向量在软删时已清理/保留情况
     * 不变（向量已清，恢复后如需以图搜图可走向量回填）。</p>
     *
     * @param rspuId RSPU ID
     */
    @Transactional
    public void restoreProduct(String rspuId) {
        RspuMaster existing = rspuMapper.selectAnyById(rspuId);
        if (existing == null) {
            throw new ResourceNotFoundException("产品不存在: " + rspuId);
        }
        dataScopeHelper.assertCanAccessRspu(rspuId);
        if (existing.getDeletedAt() == null) {
            throw new BusinessException("产品未被删除，无需恢复: " + rspuId);
        }

        rspuMapper.restoreById(rspuId);
        rspuVariantMapper.restoreByRspuId(rspuId);
        rskuSupplyMapper.restoreByRspuId(rspuId);
        // RSKU 级联还原后重算价格投影
        rspuPriceSummaryService.recalculate(rspuId);
        imageAssetsMapper.restoreByRspuId(rspuId);
        rspuRelationMapper.restoreByRspuId(rspuId);

        auditLogService.logUpdate("rspu_master", rspuId, existing,
            Map.of("action", "restore_from_recycle_bin"), SecurityOperatorContext.currentUsername());
        log.info("产品已从回收站恢复，rspuId={}", rspuId);
    }

    /**
     * 彻底删除回收站中的产品：物理删除主表与全部关联行，
     * 事务提交后清理存储文件与 pgvector 残留向量（幂等补刀）。
     *
     * <p>仅限已软删除（回收站中）的产品；正常产品须先软删除。风格/场景关联、
     * 工厂映射、风格匹配结果、收藏条目一并物理清除；订单/方案明细为快照语义保留。</p>
     *
     * @param rspuId RSPU ID
     */
    @Transactional
    public void permanentDeleteProduct(String rspuId) {
        RspuMaster existing = rspuMapper.selectAnyById(rspuId);
        if (existing == null) {
            throw new ResourceNotFoundException("产品不存在: " + rspuId);
        }
        if (existing.getDeletedAt() == null) {
            throw new BusinessException("仅回收站中的产品可彻底删除，请先执行删除: " + rspuId);
        }

        // 先收集图片（含软删）的存储对象键与向量 ID，供提交后清理
        List<ImageAssets> images = imageAssetsMapper.selectAnyByRspuId(rspuId);
        List<String> objectKeys = images.stream()
            .map(ImageAssets::getStoragePath).filter(StringUtils::hasText).toList();
        List<String> imageIds = images.stream().map(ImageAssets::getImageId).toList();

        // 方案明细是业务凭证，被引用时禁止彻底删除（含软删引用——外键不看 deleted_at）；
        // 拦截提示列出具体引用方案，软删方案引导到方案回收站彻底删除
        long schemeRefs = productPurgeMapper.countSchemeItemRefs(rspuId);
        if (schemeRefs > 0) {
            List<SchemeRefInfo> refSchemes = productPurgeMapper.listSchemeRefsByRspu(rspuId);
            StringBuilder detail = new StringBuilder();
            boolean hasInUse = false;
            for (SchemeRefInfo ref : refSchemes) {
                if (detail.length() > 0) {
                    detail.append("、");
                }
                if (Boolean.TRUE.equals(ref.getInUse())) {
                    hasInUse = true;
                    detail.append("「").append(ref.getSchemeName()).append("」（使用中）");
                } else {
                    detail.append("「").append(ref.getSchemeName()).append("」（已删除，可在方案回收站彻底删除）");
                }
            }
            throw new BusinessException("产品仍被方案明细引用，无法彻底删除。引用方案：" + detail
                + (hasInUse ? "；使用中方案请先删除方案或移除明细" : "")
                + ": " + rspuId);
        }

        // 按外键依赖顺序物理删除关联行（风格/场景关联在软删时已清除，此处幂等补刀）：
        // ① 导入历史置空引用（保留导入记录本身）
        productPurgeMapper.nullifyImportRowRspuRefs(rspuId);
        productPurgeMapper.nullifyImportRowVariantRefs(rspuId);
        // ② AI 识别记录（解除对 image_assets 的引用）
        productPurgeMapper.deleteAiRecognitions(rspuId);
        // ③ 无实体的弱引用表
        productPurgeMapper.deleteMatchingFeedback(rspuId);
        productPurgeMapper.deleteCollectionItems(rspuId);
        productPurgeMapper.deleteSchemeCandidates(rspuId);
        // ④ 变体/RSKU 的子表
        productPurgeMapper.deleteFactoryVariantCapacity(rspuId);
        productPurgeMapper.deletePriceHistory(rspuId);
        // ⑤ 图片（引用 RSKU/变体）→ RSKU（引用变体）→ 变体
        imageAssetsMapper.physicalDeleteByRspuId(rspuId);
        rskuSupplyMapper.physicalDeleteByRspuId(rspuId);
        rspuVariantMapper.physicalDeleteByRspuId(rspuId);
        // ⑥ RSPU 的其余直接子表
        rspuRelationMapper.physicalDeleteByRspuId(rspuId);
        rspuStyleMapper.delete(new QueryWrapper<RspuStyle>().eq("rspu_id", rspuId));
        rspuSceneMapper.delete(new QueryWrapper<RspuScene>().eq("rspu_id", rspuId));
        productStyleMatchMapper.delete(new QueryWrapper<com.rsdp.entity.ProductStyleMatch>().eq("rspu_id", rspuId));
        rspuFactoryMappingMapper.delete(new QueryWrapper<RspuFactoryMapping>().eq("rspu_id", rspuId));
        userFavoriteMapper.delete(new QueryWrapper<UserFavorite>().eq("rspu_id", rspuId));
        productPurgeMapper.deleteVariantCodeCounter(rspuId);
        // 价格投影行（引用 rspu_master，须先于主表删除）
        productPurgeMapper.deletePriceSummary(rspuId);
        // ⑦ 主表
        rspuMapper.physicalDeleteById(rspuId);

        auditLogService.logDelete("rspu_master", rspuId, existing, SecurityOperatorContext.currentUsername());
        log.info("产品已彻底删除（物理），rspuId={}，清理图片文件 {} 个", rspuId, objectKeys.size());

        // 事务提交后清理外部资源：存储文件逐个删（失败仅记日志），向量走既有删除事件
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    for (String objectKey : objectKeys) {
                        try {
                            storageService.delete(objectKey);
                        } catch (Exception e) {
                            log.warn("彻底删除：清理图片文件失败，objectKey={}", objectKey, e);
                        }
                    }
                }
            });
        }
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
        if (rspu == null) {
            throw new ResourceNotFoundException("产品不存在: " + rspuId);
        }

        dataScopeHelper.assertCanAccessRspu(rspuId);

        RspuMaster oldSnapshot = snapshot(rspu);
        String oldPositioningLabel = rspu.getPositioningLabel();
        String oldSceneTagsJson = rspu.getSceneTags();

        List<String> styleCodes = null;
        if (request.getStyleCodes() != null && !request.getStyleCodes().isEmpty()) {
            // 多风格：首值为主风格写 positioning_label，全量重写 rspu_style
            styleCodes = normalizeCodes(request.getStyleCodes());
            validateDictCodes("style", styleCodes);
            rspu.setPositioningLabel(styleCodes.get(0));
        }
        String styleCode = null;
        if (styleCodes == null && StringUtils.hasText(request.getPositioningLabel())) {
            styleCode = request.getPositioningLabel().trim().toUpperCase();
            validateDictCode("style", styleCode);
            rspu.setPositioningLabel(styleCode);
        }
        if (request.getColorPrimaryName() != null) {
            rspu.setColorPrimaryName(request.getColorPrimaryName().trim());
        }
        if (request.getProductName() != null) {
            rspu.setProductName(StringUtils.hasText(request.getProductName())
                ? request.getProductName().trim()
                : null);
        }
        if (request.getDescription() != null) {
            rspu.setDescription(StringUtils.hasText(request.getDescription())
                ? request.getDescription().trim()
                : null);
        }
        if (request.getColorPrimaryHsv() != null) {
            rspu.setColorPrimaryHsv(writeJson(request.getColorPrimaryHsv()));
        }
        if (request.getMaterialTags() != null) {
            List<String> materialCodes = normalizeCodes(request.getMaterialTags());
            validateDictCodes("material", materialCodes);
            rspu.setMaterialTags(writeJson(materialCodes));
        }
        if (request.getFabricTags() != null) {
            List<String> fabricCodes = normalizeCodes(request.getFabricTags());
            validateDictCodes("fabric", fabricCodes);
            rspu.setFabricTags(writeJson(fabricCodes));
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
        if (request.getRetailPrice() != null) {
            rspu.setRetailPrice(request.getRetailPrice());
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
        if (StringUtils.hasText(request.getStatus())) {
            String status = request.getStatus().trim().toLowerCase();
            if (!"active".equals(status) && !"inactive".equals(status)) {
                throw new BusinessException("非法的销售状态: " + request.getStatus());
            }
            rspu.setStatus(status);
        }

        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMapper.updateById(rspu);

        if (styleCodes != null) {
            updateStyles(rspuId, styleCodes);
        } else if (StringUtils.hasText(styleCode)
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

    /**
     * 全量重写产品风格关联：第一个为主风格，其余为辅风格（自动去重）。
     *
     * @param rspuId     RSPU ID
     * @param styleCodes 风格字典码列表（非空，首值主风格）
     */
    private void updateStyles(String rspuId, List<String> styleCodes) {
        rspuStyleMapper.delete(new QueryWrapper<RspuStyle>().eq("rspu_id", rspuId));
        java.util.Set<String> seen = new java.util.HashSet<>();
        boolean first = true;
        for (String code : styleCodes) {
            if (!StringUtils.hasText(code) || !seen.add(code)) {
                continue;
            }
            RspuStyle style = new RspuStyle();
            style.setRspuId(rspuId);
            style.setDictType("style");
            style.setStyleCode(code);
            style.setIsPrimary(first);
            style.setCreatedAt(LocalDateTime.now());
            rspuStyleMapper.insert(style);
            first = false;
        }
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
                                             Map<String, List<String>> factoryCodeMap,
                                             Map<String, BigDecimal> minPriceMap,
                                             Map<String, Long> rskuCountMap) {
        ProductSummaryResponse summary = new ProductSummaryResponse();
        summary.setRspuId(rspu.getRspuId());
        summary.setRspuCode(rspu.getRspuCode());
        summary.setProductName(rspu.getProductName());
        summary.setCategoryCode(rspu.getCategoryCode());
        summary.setCategoryPath(rspu.getCategoryPath());
        summary.setPositioningLabel(rspu.getPositioningLabel());
        summary.setProductName(rspu.getProductName());
        summary.setColorPrimaryName(rspu.getColorPrimaryName());
        summary.setStatus(rspu.getStatus());
        summary.setReviewStatus(rspu.getReviewStatus());
        summary.setAestheticsConfidence(rspu.getAestheticsConfidence());
        summary.setProductLevel(rspu.getProductLevel());
        // 最低出厂价仅平台运营人员可见。
        // 注意：该值是跨厂聚合的最低价，无法归属单一工厂做 canViewFactoryPrice 逐厂判断，
        // 且 FACTORY_ADMIN 看到的可能是友商价格，因此对工厂角色同样掩码；
        // 工厂查看本厂报价走 RSKU 详情（已有按厂掩码）。
        summary.setMinFactoryPrice(
            SecurityOperatorContext.isPlatformStaff()
                ? minPriceMap.get(rspu.getRspuId())
                : null);
        summary.setRetailPrice(rspu.getRetailPrice());
        summary.setCreatedAt(rspu.getCreatedAt());
        summary.setUpdatedAt(rspu.getUpdatedAt());
        summary.setPrimaryImageUrl(primaryImageUrlMap.get(rspu.getRspuId()));
        summary.setFactoryCodes(factoryCodeMap.getOrDefault(rspu.getRspuId(), List.of()));
        summary.setRskuCount(rskuCountMap.getOrDefault(rspu.getRspuId(), 0L));
        return summary;
    }

    private String buildImageUrl(String imageId) {
        return "/api/v1/images/" + imageId;
    }
}
