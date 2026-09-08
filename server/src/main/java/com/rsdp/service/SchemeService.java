package com.rsdp.service;

import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.security.datascope.DataScopeHelper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.common.PageResult;
import com.rsdp.dto.request.CopyFromTemplateRequest;
import com.rsdp.dto.request.QuoteItemRequest;
import com.rsdp.dto.request.SaveCanvasLayoutRequest;
import com.rsdp.dto.request.SchemeCreateRequest;
import com.rsdp.dto.request.SchemeItemReorderRequest;
import com.rsdp.dto.request.SchemeItemRequest;
import com.rsdp.dto.request.SchemeShareRequest;
import com.rsdp.dto.request.SchemeTemplateRequest;
import com.rsdp.dto.request.SchemeUpdateRequest;
import com.rsdp.dto.response.CopyFromTemplateResponse;
import com.rsdp.dto.response.PriceChangeResponse;
import com.rsdp.dto.response.QuoteItemResponse;
import com.rsdp.dto.response.QuoteResponse;
import com.rsdp.dto.response.SchemeItemResponse;
import com.rsdp.dto.response.SchemeResponse;
import com.rsdp.dto.response.SchemeSummaryResponse;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.Project;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RskuSupply;
import com.rsdp.entity.Scheme;
import com.rsdp.entity.SchemeItem;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.CategoryDictMapper;
import com.rsdp.mapper.DesignOrderMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.SchemeItemMapper;
import com.rsdp.mapper.SchemeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.rsdp.util.IdGenerator;

/**
 * 搭配方案服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SchemeService {

    /** 单个方案允许的最大明细项数量（性能与安全阈值）。 */
    public static final int MAX_SCHEME_ITEMS = 50;

    private final SchemeMapper schemeMapper;
    private final SchemeItemMapper schemeItemMapper;
    private final DesignOrderMapper designOrderMapper;
    private final RspuSceneMapper rspuSceneMapper;
    private final CategoryDictMapper categoryDictMapper;
    private final RskuSupplyMapper rskuSupplyMapper;
    private final RspuMapper rspuMapper;
    private final FactoryMasterMapper factoryMasterMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final QuoteService quoteService;
    private final DataScopeHelper dataScopeHelper;
    private final ProjectService projectService;
    private final TemplateTagService templateTagService;
    private final SchemeSalePriceService schemeSalePriceService;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    /**
     * 创建搭配方案。
     *
     * @param request 创建请求
     * @return 方案详情
     */
    @Transactional
    public SchemeResponse createScheme(SchemeCreateRequest request) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException("方案项不能为空");
        }
        if (request.getItems().size() > MAX_SCHEME_ITEMS) {
            throw new BusinessException("方案项数量不能超过 " + MAX_SCHEME_ITEMS + " 个");
        }
        if (!StringUtils.hasText(request.getSchemeName())) {
            throw new BusinessException("方案名称不能为空");
        }

        String schemeId = IdGenerator.schemeId();

        // 按 rskuId 去重并聚合数量，保留第一次出现的顺序
        Map<String, SchemeItemRequest> uniqueItemMap = new java.util.LinkedHashMap<>();
        Map<String, Integer> quantityMap = new java.util.LinkedHashMap<>();
        for (SchemeItemRequest item : request.getItems()) {
            if (item.getRskuId() == null) {
                continue;
            }
            int quantity = item.getQuantity() != null && item.getQuantity() > 0
                ? item.getQuantity()
                : 1;
            uniqueItemMap.putIfAbsent(item.getRskuId(), item);
            quantityMap.merge(item.getRskuId(), quantity, Integer::sum);
        }
        List<SchemeItemRequest> distinctItems = new java.util.ArrayList<>(uniqueItemMap.values());
        if (distinctItems.isEmpty()) {
            throw new BusinessException("没有有效的方案项");
        }

        BigDecimal totalPrice = BigDecimal.ZERO;
        int maxLeadTimeDays = 0;
        Set<String> factoryCodes = new HashSet<>();

        // 先校验并汇总，避免写入脏数据
        List<SchemeItem> schemeItems = new java.util.ArrayList<>();
        for (int i = 0; i < distinctItems.size(); i++) {
            SchemeItemRequest itemRequest = distinctItems.get(i);
            RskuSupply rsku = rskuSupplyMapper.selectById(itemRequest.getRskuId());
            if (rsku == null) {
                throw new ResourceNotFoundException("RSKU 不存在: " + itemRequest.getRskuId());
            }
            if (!rsku.getRspuId().equals(itemRequest.getRspuId())) {
                throw new BusinessException(
                    "RSKU 与 RSPU 不匹配: " + itemRequest.getRskuId());
            }
            if (!dataScopeHelper.canAccessFactory(rsku.getFactoryCode())) {
                throw new BusinessException("无权使用该工厂报价: " + rsku.getFactoryCode());
            }

            int quantity = quantityMap.getOrDefault(itemRequest.getRskuId(), 1);

            if (rsku.getFactoryPrice() != null) {
                totalPrice = totalPrice.add(rsku.getFactoryPrice().multiply(BigDecimal.valueOf(quantity)));
            }
            if (rsku.getLeadTimeDays() != null && rsku.getLeadTimeDays() > maxLeadTimeDays) {
                maxLeadTimeDays = rsku.getLeadTimeDays();
            }
            if (rsku.getFactoryCode() != null) {
                factoryCodes.add(rsku.getFactoryCode());
            }

            SchemeItem schemeItem = new SchemeItem();
            schemeItem.setSchemeId(schemeId);
            schemeItem.setRspuId(itemRequest.getRspuId());
            schemeItem.setRskuId(itemRequest.getRskuId());
            schemeItem.setFactoryCode(rsku.getFactoryCode());
            schemeItem.setFactoryPrice(rsku.getFactoryPrice());
            schemeItem.setLeadTimeDays(rsku.getLeadTimeDays());
            schemeItem.setMoq(rsku.getMoq());
            schemeItem.setQuantity(quantity);
            schemeItem.setSortOrder(itemRequest.getSortOrder() != null ? itemRequest.getSortOrder() : i);
            schemeItem.setSpaceTag(StringUtils.hasText(itemRequest.getSpaceTag())
                ? itemRequest.getSpaceTag().trim() : null);
            schemeItem.setCreatedAt(LocalDateTime.now());
            schemeItems.add(schemeItem);
        }

        // 同一项目/用户下不允许存在同名活动方案
        assertSchemeNameUnique(request.getSchemeName().trim(), request.getProjectId(),
            SecurityOperatorContext.currentUsername(), null);

        // 先写入主表，再写入子表，避免外键约束异常
        Scheme scheme = new Scheme();
        scheme.setSchemeId(schemeId);
        scheme.setSchemeName(request.getSchemeName().trim());
        scheme.setRoomType(request.getRoomType());
        if (StringUtils.hasText(request.getProjectId())) {
            Project project = projectService.getAccessibleProject(request.getProjectId());
            scheme.setProjectId(project.getProjectId());
        }
        scheme.setBudgetLimit(request.getBudgetLimit());
        scheme.setTotalPrice(totalPrice);
        scheme.setFactoryCount(factoryCodes.size());
        scheme.setMaxLeadTimeDays(maxLeadTimeDays);
        scheme.setItemCount(distinctItems.size());
        scheme.setStatus("active");
        scheme.setCreatedBy(currentUsername());
        scheme.setCreatedAt(LocalDateTime.now());
        schemeMapper.insert(scheme);

        schemeItemMapper.insertBatchSafe(schemeItems);

        auditLogService.logCreate("scheme", schemeId, scheme, scheme.getCreatedBy());
        return getSchemeDetail(schemeId);
    }

    /**
     * 更新搭配方案。
     * 采用"先删除旧子项、再写入新子项"的简单策略，保证幂等且避免脏数据。
     *
     * @param schemeId 方案 ID
     * @param request  更新请求
     * @return 更新后的方案详情
     */
    @Transactional
    public SchemeResponse updateScheme(String schemeId, SchemeUpdateRequest request) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException("方案项不能为空");
        }
        if (request.getItems().size() > MAX_SCHEME_ITEMS) {
            throw new BusinessException("方案项数量不能超过 " + MAX_SCHEME_ITEMS + " 个");
        }
        if (!StringUtils.hasText(request.getSchemeName())) {
            throw new BusinessException("方案名称不能为空");
        }

        Scheme scheme = schemeMapper.selectById(schemeId);
        if (scheme == null) {
            throw new ResourceNotFoundException("方案不存在: " + schemeId);
        }
        assertSchemeOwnerOrAdmin(scheme);
        Scheme oldSnapshot = snapshot(scheme);

        // 按 rskuId 去重并聚合数量，保留第一次出现的顺序
        Map<String, SchemeItemRequest> uniqueItemMap = new java.util.LinkedHashMap<>();
        Map<String, Integer> quantityMap = new java.util.LinkedHashMap<>();
        for (SchemeItemRequest item : request.getItems()) {
            if (item.getRskuId() == null) {
                continue;
            }
            int quantity = item.getQuantity() != null && item.getQuantity() > 0
                ? item.getQuantity()
                : 1;
            uniqueItemMap.putIfAbsent(item.getRskuId(), item);
            quantityMap.merge(item.getRskuId(), quantity, Integer::sum);
        }
        List<SchemeItemRequest> distinctItems = new java.util.ArrayList<>(uniqueItemMap.values());
        if (distinctItems.isEmpty()) {
            throw new BusinessException("没有有效的方案项");
        }

        BigDecimal totalPrice = BigDecimal.ZERO;
        int maxLeadTimeDays = 0;
        Set<String> factoryCodes = new HashSet<>();

        List<SchemeItem> schemeItems = new java.util.ArrayList<>();
        for (int i = 0; i < distinctItems.size(); i++) {
            SchemeItemRequest itemRequest = distinctItems.get(i);
            RskuSupply rsku = rskuSupplyMapper.selectById(itemRequest.getRskuId());
            if (rsku == null) {
                throw new ResourceNotFoundException("RSKU 不存在: " + itemRequest.getRskuId());
            }
            if (!rsku.getRspuId().equals(itemRequest.getRspuId())) {
                throw new BusinessException(
                    "RSKU 与 RSPU 不匹配: " + itemRequest.getRskuId());
            }
            if (!dataScopeHelper.canAccessFactory(rsku.getFactoryCode())) {
                throw new BusinessException("无权使用该工厂报价: " + rsku.getFactoryCode());
            }

            int quantity = quantityMap.getOrDefault(itemRequest.getRskuId(), 1);

            if (rsku.getFactoryPrice() != null) {
                totalPrice = totalPrice.add(rsku.getFactoryPrice().multiply(BigDecimal.valueOf(quantity)));
            }
            if (rsku.getLeadTimeDays() != null && rsku.getLeadTimeDays() > maxLeadTimeDays) {
                maxLeadTimeDays = rsku.getLeadTimeDays();
            }
            if (rsku.getFactoryCode() != null) {
                factoryCodes.add(rsku.getFactoryCode());
            }

            SchemeItem schemeItem = new SchemeItem();
            schemeItem.setSchemeId(schemeId);
            schemeItem.setRspuId(itemRequest.getRspuId());
            schemeItem.setRskuId(itemRequest.getRskuId());
            schemeItem.setFactoryCode(rsku.getFactoryCode());
            schemeItem.setFactoryPrice(rsku.getFactoryPrice());
            schemeItem.setLeadTimeDays(rsku.getLeadTimeDays());
            schemeItem.setMoq(rsku.getMoq());
            schemeItem.setQuantity(quantity);
            schemeItem.setSortOrder(itemRequest.getSortOrder() != null ? itemRequest.getSortOrder() : i);
            schemeItem.setSpaceTag(StringUtils.hasText(itemRequest.getSpaceTag())
                ? itemRequest.getSpaceTag().trim() : null);
            schemeItem.setCreatedAt(LocalDateTime.now());
            schemeItems.add(schemeItem);
        }

        // 同一项目/用户下不允许存在同名活动方案（排除当前方案自身）
        String effectiveProjectId = StringUtils.hasText(request.getProjectId())
            ? request.getProjectId()
            : scheme.getProjectId();
        assertSchemeNameUnique(request.getSchemeName().trim(), effectiveProjectId,
            scheme.getCreatedBy(), schemeId);

        // 物理删除旧子项
        schemeItemMapper.delete(
            new QueryWrapper<SchemeItem>().eq("scheme_id", schemeId)
        );
        // 联动清理画布布局（V41）：旧明细被整体替换、scheme_item_id 全部失效，剔除失效摆位键
        pruneCanvasLayout(scheme, Collections.emptySet());

        scheme.setSchemeName(request.getSchemeName().trim());
        scheme.setRoomType(request.getRoomType());
        if (StringUtils.hasText(request.getProjectId())) {
            Project project = projectService.getAccessibleProject(request.getProjectId());
            scheme.setProjectId(project.getProjectId());
        }
        scheme.setBudgetLimit(request.getBudgetLimit());
        scheme.setTotalPrice(totalPrice);
        scheme.setFactoryCount(factoryCodes.size());
        scheme.setMaxLeadTimeDays(maxLeadTimeDays);
        scheme.setItemCount(distinctItems.size());
        scheme.setUpdatedAt(LocalDateTime.now());
        schemeMapper.updateById(scheme);
        auditLogService.logUpdate("scheme", schemeId, oldSnapshot, scheme, currentUsername());

        schemeItemMapper.insertBatchSafe(schemeItems);

        return getSchemeDetail(schemeId);
    }

    /**
     * 校验同一项目/用户下是否存在同名活动方案。
     *
     * <p>存在项目 ID 时按项目维度去重；无项目 ID 时按创建人维度去重。</p>
     *
     * @param schemeName      方案名称
     * @param projectId       所属项目 ID（可为空）
     * @param owner           创建人用户名
     * @param excludeSchemeId 需要排除的方案 ID（更新时排除自身）
     */
    private void assertSchemeNameUnique(String schemeName, String projectId, String owner, String excludeSchemeId) {
        QueryWrapper<Scheme> wrapper = new QueryWrapper<Scheme>()
            .eq("scheme_name", schemeName)
            .eq("status", "active");
        if (StringUtils.hasText(projectId)) {
            wrapper.eq("project_id", projectId);
        } else {
            wrapper.eq("created_by", owner);
        }
        if (excludeSchemeId != null) {
            wrapper.ne("scheme_id", excludeSchemeId);
        }
        Long count = schemeMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException("已存在同名方案：" + schemeName);
        }
    }

    private void assertSchemeOwnerOrAdmin(Scheme scheme) {
        if (SecurityOperatorContext.isCurrentUserAdmin()) {
            return;
        }
        String currentUser = currentUsername();
        if (scheme.getCreatedBy() == null || !scheme.getCreatedBy().equals(currentUser)) {
            throw new BusinessException("无权操作该方案");
        }
    }

    private String currentUsername() {
        String username = SecurityOperatorContext.currentUsername();
        return StringUtils.hasText(username) ? username : "unknown";
    }

    private Scheme snapshot(Scheme source) {
        Scheme copy = new Scheme();
        copy.setSchemeId(source.getSchemeId());
        copy.setSchemeName(source.getSchemeName());
        copy.setRoomType(source.getRoomType());
        copy.setProjectId(source.getProjectId());
        copy.setBudgetLimit(source.getBudgetLimit());
        copy.setTotalPrice(source.getTotalPrice());
        copy.setFactoryCount(source.getFactoryCount());
        copy.setMaxLeadTimeDays(source.getMaxLeadTimeDays());
        copy.setItemCount(source.getItemCount());
        copy.setStatus(source.getStatus());
        copy.setIsTemplate(source.getIsTemplate());
        copy.setTemplateTags(source.getTemplateTags());
        copy.setCanvasLayout(source.getCanvasLayout());
        copy.setShareEnabled(source.getShareEnabled());
        copy.setShareExpireAt(source.getShareExpireAt());
        copy.setCreatedBy(source.getCreatedBy());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.setDeletedAt(source.getDeletedAt());
        return copy;
    }

    /**
     * 分页查询方案列表（支持模板筛选与标签筛选）。
     *
     * @param isTemplate 是否仅查模板（可选）
     * @param tag        模板标签筛选（可选）
     * @param page       页码（从 1 开始）
     * @param size       每页条数
     * @return 方案摘要分页结果
     */
    public PageResult<SchemeSummaryResponse> listSchemes(Boolean isTemplate, String tag, long page, long size) {
        QueryWrapper<Scheme> wrapper = new QueryWrapper<Scheme>()
            .eq("status", "active")
            .orderByDesc("created_at");
        if (isTemplate != null) {
            wrapper.eq("is_template", isTemplate);
        }
        if (StringUtils.hasText(tag)) {
            // template_tags 为 JSON 数组字符串，按带引号的标签精确片段匹配
            wrapper.like("template_tags", "\"" + tag + "\"");
        }
        Page<Scheme> result = schemeMapper.selectPage(Page.of(page, size), wrapper);
        // 售价合计（销售价口径改造，方式 A）：批量实时换算，避免逐方案循环单查
        Map<String, BigDecimal> saleTotals = schemeSalePriceService.batchTotalSalePrices(
            result.getRecords().stream().map(Scheme::getSchemeId).toList());
        List<SchemeSummaryResponse> rows = result.getRecords().stream()
            .map(s -> toSummary(s, saleTotals.getOrDefault(s.getSchemeId(), BigDecimal.ZERO)))
            .collect(Collectors.toList());
        return PageResult.of(result.getTotal(), page, size, rows);
    }

    private SchemeSummaryResponse toSummary(Scheme s, BigDecimal totalSalePrice) {
        SchemeSummaryResponse summary = new SchemeSummaryResponse();
        summary.setSchemeId(s.getSchemeId());
        summary.setSchemeName(s.getSchemeName());
        summary.setItemCount(s.getItemCount());
        // 成本口径总价仅平台员工可见；其他角色用 totalSalePrice（销售价合计，全角色可见）
        summary.setTotalPrice(SecurityOperatorContext.isPlatformStaff() ? s.getTotalPrice() : null);
        summary.setTotalSalePrice(totalSalePrice);
        summary.setCreatedBy(s.getCreatedBy());
        summary.setCreatedAt(s.getCreatedAt());
        summary.setDeletedAt(s.getDeletedAt());
        summary.setIsTemplate(s.getIsTemplate());
        summary.setTemplateTags(fromJson(s.getTemplateTags()));
        return summary;
    }

    /**
     * 查询方案详情。
     *
     * @param schemeId 方案 ID
     * @return 方案详情
     */
    public SchemeResponse getSchemeDetail(String schemeId) {
        Scheme scheme = schemeMapper.selectById(schemeId);
        if (scheme == null) {
            throw new ResourceNotFoundException("方案不存在: " + schemeId);
        }

        List<SchemeItem> items = schemeItemMapper.selectList(
            new QueryWrapper<SchemeItem>()
                .eq("scheme_id", schemeId)
                .orderByAsc("sort_order")
        );

        List<String> rspuIds = items.stream().map(SchemeItem::getRspuId).distinct().toList();
        List<String> rskuIds = items.stream().map(SchemeItem::getRskuId).distinct().toList();
        // 售价合计口径与 scheme.total_price 一致（含全部明细），在数据范围过滤前保留全量明细
        List<SchemeItem> allItems = items;
        // 数据权限过滤：只返回当前用户可见工厂的项
        items = items.stream()
            .filter(item -> dataScopeHelper.canAccessRskuFactory(item.getFactoryCode()))
            .collect(Collectors.toList());

        List<String> factoryCodes = items.stream()
            .map(SchemeItem::getFactoryCode)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();

        Map<String, RspuMaster> rspuMap = batchRspuMap(rspuIds);
        Map<String, RskuSupply> rskuMap = batchRskuMap(rskuIds);
        Map<String, FactoryMaster> factoryMap = batchFactoryMap(factoryCodes);
        Map<String, String> primaryImageUrlMap = batchPrimaryImageUrls(rspuIds);

        List<SchemeItemResponse> itemResponses = items.stream()
            .map(item -> buildItemResponse(item, rspuMap, rskuMap, factoryMap, primaryImageUrlMap))
            .collect(Collectors.toList());

        // 空间分区标签（阶段 9 画布空间分区 / 方案 A）：scheme_item.space_tag 覆盖优先，
        // 空则回退产品 rspu_scene 首场景码；显示名批量查场景字典，码已删时原样返回码
        Map<String, String> derivedSpaceCodes = batchSpaceTagCodes(rspuIds);
        Map<Long, String> overrideCodes = items.stream()
            .filter(item -> StringUtils.hasText(item.getSpaceTag()))
            .collect(Collectors.toMap(SchemeItem::getSchemeItemId, SchemeItem::getSpaceTag, (a, b) -> a));
        List<String> effectiveCodes = itemResponses.stream()
            .map(item -> {
                String code = overrideCodes.get(item.getSchemeItemId());
                return StringUtils.hasText(code) ? code : derivedSpaceCodes.get(item.getRspuId());
            })
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
        Map<String, String> sceneNames = batchSceneNames(effectiveCodes);
        itemResponses.forEach(item -> {
            String code = overrideCodes.get(item.getSchemeItemId());
            if (!StringUtils.hasText(code)) {
                code = derivedSpaceCodes.get(item.getRspuId());
            }
            item.setSpaceTag(StringUtils.hasText(code) ? code : null);
            item.setSpaceTagName(StringUtils.hasText(code) ? sceneNames.getOrDefault(code, code) : null);
            item.setSpaceTagOverridden(overrideCodes.containsKey(item.getSchemeItemId()));
        });

        SchemeResponse response = new SchemeResponse();
        response.setSchemeId(scheme.getSchemeId());
        response.setSchemeName(scheme.getSchemeName());
        response.setRoomType(scheme.getRoomType());
        response.setBudgetLimit(scheme.getBudgetLimit());
        // 成本口径总价仅平台员工可见；销售价合计（方式 A 响应层实时换算）全角色可见
        response.setTotalPrice(SecurityOperatorContext.isPlatformStaff() ? scheme.getTotalPrice() : null);
        response.setTotalSalePrice(
            schemeSalePriceService.sumItemsSalePrice(allItems, rspuMap, rskuMap));
        response.setFactoryCount(scheme.getFactoryCount());
        response.setMaxLeadTimeDays(scheme.getMaxLeadTimeDays());
        response.setItemCount(scheme.getItemCount());
        response.setStatus(scheme.getStatus());
        response.setProjectId(scheme.getProjectId());
        response.setIsTemplate(scheme.getIsTemplate());
        response.setTemplateTags(fromJson(scheme.getTemplateTags()));
        response.setCanvasLayout(scheme.getCanvasLayout());
        response.setShareEnabled(scheme.getShareEnabled());
        response.setShareExpireAt(scheme.getShareExpireAt());
        response.setCreatedBy(scheme.getCreatedBy());
        response.setCreatedAt(scheme.getCreatedAt());
        response.setItems(itemResponses);
        return response;
    }

    /**
     * 软删除方案。
     *
     * @param schemeId 方案 ID
     */
    @Transactional
    public void deleteScheme(String schemeId) {
        Scheme scheme = schemeMapper.selectById(schemeId);
        if (scheme == null) {
            throw new ResourceNotFoundException("方案不存在: " + schemeId);
        }
        assertSchemeOwnerOrAdmin(scheme);
        Scheme oldSnapshot = snapshot(scheme);
        int affected = schemeMapper.deleteById(schemeId);
        if (affected == 0) {
            throw new ResourceNotFoundException("方案不存在或已被删除: " + schemeId);
        }
        // 级联软删除方案明细（@TableLogic → UPDATE deleted_at），避免残留孤儿记录
        schemeItemMapper.delete(new QueryWrapper<SchemeItem>().eq("scheme_id", schemeId));
        auditLogService.logDelete("scheme", schemeId, oldSnapshot, currentUsername());
    }

    /**
     * 分页查询回收站中的方案（已软删除，绕过 @TableLogic 自动过滤）。
     *
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     * @return 方案摘要分页结果（含 deletedAt）
     */
    public PageResult<SchemeSummaryResponse> listDeletedSchemes(long page, long size) {
        Page<Scheme> result = schemeMapper.selectDeletedPage(Page.of(page, size));
        // 售价合计口径与活动列表一致：批量实时换算，避免逐方案循环单查
        Map<String, BigDecimal> saleTotals = schemeSalePriceService.batchTotalSalePrices(
            result.getRecords().stream().map(Scheme::getSchemeId).toList());
        List<SchemeSummaryResponse> rows = result.getRecords().stream()
            .map(s -> toSummary(s, saleTotals.getOrDefault(s.getSchemeId(), BigDecimal.ZERO)))
            .collect(Collectors.toList());
        return PageResult.of(result.getTotal(), page, size, rows);
    }

    /**
     * 彻底删除方案（物理删除）：先物理删除全部明细（含软删），再物理删除方案行，同事务。
     *
     * <p>前置约束：
     * ① 方案必须已软删除（回收站中），未软删的先走删除入口；
     * ② 被订单（design_order.scheme_id，含软删订单）引用时拒绝——订单是业务凭证；
     * ③ 仅方案创建人或 ADMIN（与软删除一致的归属校验）。
     * 项目内方案同样允许：project_id 仅逻辑关联，project 表无外键指向 scheme。</p>
     *
     * @param schemeId 方案 ID
     * @param operator 操作人用户名（审计日志）
     */
    @Transactional
    public void purgeScheme(String schemeId, String operator) {
        // selectById 受 @TableLogic 影响查不到软删行，须用含软删的自定义查询
        Scheme scheme = schemeMapper.selectAnyById(schemeId);
        if (scheme == null) {
            throw new ResourceNotFoundException("方案不存在: " + schemeId);
        }
        assertSchemeOwnerOrAdmin(scheme);
        if (scheme.getDeletedAt() == null) {
            throw new BusinessException("仅回收站中的方案可彻底删除，请先执行删除: " + schemeId);
        }
        long orderRefs = designOrderMapper.countSchemeRefsAny(schemeId);
        if (orderRefs > 0) {
            throw new BusinessException("方案已生成订单，属于业务凭证，不能彻底删除: " + schemeId);
        }

        Scheme oldSnapshot = snapshot(scheme);
        schemeItemMapper.physicalDeleteBySchemeId(schemeId);
        schemeMapper.physicalDeleteById(schemeId);
        auditLogService.logDelete("scheme", schemeId, oldSnapshot, operator);
        log.info("方案已彻底删除（物理），schemeId={}，operator={}", schemeId, operator);
    }

    /**
     * 方案明细拖拽排序（阶段 9）：按给定顺序重写 sort_order。
     *
     * <p>itemIds 必须是该方案全部明细的完整列表，否则报错。
     * 可选的 spaceTags（明细 ID → 场景字典码）在同一事务内更新 scheme_item.space_tag
     * （排序 + 空间覆盖要么都成功要么整体回滚）：值为 {@code null} 表示清除覆盖恢复跟随产品；
     * 键不出现时不动该列；覆盖码不强制校验字典存在（允许先拖入后建字典的兜底）。</p>
     *
     * @param schemeId 方案 ID
     * @param request  排序请求（itemIds 按新顺序排列；spaceTags 可选空间覆盖）
     * @return 更新后的方案详情
     */
    @Transactional
    public SchemeResponse reorderItems(String schemeId, SchemeItemReorderRequest request) {
        Scheme scheme = schemeMapper.selectById(schemeId);
        if (scheme == null) {
            throw new ResourceNotFoundException("方案不存在: " + schemeId);
        }
        assertSchemeOwnerOrAdmin(scheme);

        List<Long> itemIds = request.getItemIds();
        List<SchemeItem> items = schemeItemMapper.selectList(
            new QueryWrapper<SchemeItem>().eq("scheme_id", schemeId));
        Set<Long> existingIds = items.stream().map(SchemeItem::getSchemeItemId).collect(Collectors.toSet());
        if (itemIds.size() != existingIds.size()
            || !existingIds.containsAll(itemIds)
            || new HashSet<>(itemIds).size() != itemIds.size()) {
            throw new BusinessException("排序列表必须与方案全部明细一致（完整且不重复）");
        }

        Map<Long, String> spaceTags = request.getSpaceTags();
        if (spaceTags != null && !spaceTags.isEmpty() && !existingIds.containsAll(spaceTags.keySet())) {
            throw new BusinessException("空间覆盖标签包含不属于本方案的明细");
        }

        Scheme oldSnapshot = snapshot(scheme);
        int order = 1;
        for (Long itemId : itemIds) {
            UpdateWrapper<SchemeItem> update = new UpdateWrapper<SchemeItem>()
                .eq("scheme_item_id", itemId)
                .set("sort_order", order++);
            if (spaceTags != null && spaceTags.containsKey(itemId)) {
                String spaceTag = spaceTags.get(itemId);
                update.set("space_tag", StringUtils.hasText(spaceTag) ? spaceTag.trim() : null);
            }
            schemeItemMapper.update(null, update);
        }
        scheme.setUpdatedAt(LocalDateTime.now());
        schemeMapper.updateById(scheme);
        auditLogService.logUpdate("scheme", schemeId, oldSnapshot, scheme, currentUsername());
        return getSchemeDetail(schemeId);
    }

    /**
     * 保存方案画布布局（搭配画布，V41）：将前端画布摆位持久化到 scheme.canvas_layout。
     *
     * <p>layout 的每个 key 必须是该方案现存（未软删）的 scheme_item_id 字符串，
     * 否则整体报错不落库；layout 为 {@code null} 或空 Map 时清空画布布局。</p>
     *
     * @param schemeId 方案 ID
     * @param layout   画布布局（明细 ID 字符串 → 位置/缩放/层级；可空 = 清空）
     * @param operator 操作人用户名（审计日志）
     * @return 更新后的方案详情
     */
    @Transactional
    public SchemeResponse saveCanvasLayout(String schemeId,
                                           Map<String, SaveCanvasLayoutRequest.CanvasPosition> layout,
                                           String operator) {
        Scheme scheme = schemeMapper.selectById(schemeId);
        if (scheme == null) {
            throw new ResourceNotFoundException("方案不存在: " + schemeId);
        }
        assertSchemeOwnerOrAdmin(scheme);
        Scheme oldSnapshot = snapshot(scheme);

        if (layout == null || layout.isEmpty()) {
            // 清空画布布局
            scheme.setCanvasLayout(null);
        } else {
            List<SchemeItem> items = schemeItemMapper.selectList(
                new QueryWrapper<SchemeItem>().eq("scheme_id", schemeId));
            Set<String> existingIds = items.stream()
                .map(item -> String.valueOf(item.getSchemeItemId()))
                .collect(Collectors.toSet());
            if (!existingIds.containsAll(layout.keySet())) {
                throw new BusinessException("画布布局包含不属于本方案的明细");
            }
            scheme.setCanvasLayout(toJson(layout));
        }
        scheme.setUpdatedAt(LocalDateTime.now());
        schemeMapper.updateById(scheme);
        auditLogService.logUpdate("scheme", schemeId, oldSnapshot, scheme, operator);
        return getSchemeDetail(schemeId);
    }

    /**
     * 清理画布布局中的失效摆位键（V41 联动清理）：剔除不在存活明细 ID 集合中的 key，
     * 清理后为空则置 {@code null}；存量脏数据无法解析时防御性清空。
     *
     * @param scheme       方案实体（仅修改内存对象，由调用方负责落库）
     * @param validItemIds 存活明细 ID 集合（scheme_item_id 字符串）
     */
    private void pruneCanvasLayout(Scheme scheme, Set<String> validItemIds) {
        if (!StringUtils.hasText(scheme.getCanvasLayout())) {
            return;
        }
        try {
            Map<String, Object> layout = objectMapper.readValue(
                scheme.getCanvasLayout(), new TypeReference<Map<String, Object>>() {
                });
            boolean changed = layout.keySet().removeIf(key -> !validItemIds.contains(key));
            if (changed) {
                scheme.setCanvasLayout(layout.isEmpty() ? null : toJson(layout));
            }
        } catch (JsonProcessingException e) {
            // 存量脏数据防御：无法解析的布局直接清空，避免脏数据长期残留
            scheme.setCanvasLayout(null);
        }
    }

    /**
     * 设置方案分享开关（V42）。仅方案创建人或 ADMIN。
     *
     * <p>开启时按 expireDays 计算 share_expire_at（为空=永久有效）；关闭时清空过期时间。
     * 字段语义与项目分享（ProjectService.updateShare）保持一致。</p>
     *
     * @param schemeId 方案 ID
     * @param request  分享请求（开关 + 有效期天数，空=永久）
     * @return 更新后的方案详情
     */
    @Transactional
    public SchemeResponse updateSchemeShare(String schemeId, SchemeShareRequest request) {
        Scheme scheme = schemeMapper.selectById(schemeId);
        if (scheme == null) {
            throw new ResourceNotFoundException("方案不存在: " + schemeId);
        }
        assertSchemeOwnerOrAdmin(scheme);
        Scheme oldSnapshot = snapshot(scheme);

        scheme.setShareEnabled(request.getShareEnabled());
        if (Boolean.TRUE.equals(request.getShareEnabled()) && request.getExpireDays() != null) {
            scheme.setShareExpireAt(LocalDateTime.now().plusDays(request.getExpireDays()));
        } else {
            scheme.setShareExpireAt(null);
        }
        scheme.setUpdatedAt(LocalDateTime.now());
        schemeMapper.updateById(scheme);
        auditLogService.logUpdate("scheme", schemeId, oldSnapshot, scheme, currentUsername());
        return getSchemeDetail(schemeId);
    }

    /**
     * 设为/取消方案模板。
     *
     * @param schemeId 方案 ID
     * @param request  模板设置请求
     * @return 更新后的方案详情
     */
    @Transactional
    public SchemeResponse setTemplate(String schemeId, SchemeTemplateRequest request) {
        Scheme scheme = schemeMapper.selectById(schemeId);
        if (scheme == null) {
            throw new ResourceNotFoundException("方案不存在: " + schemeId);
        }
        assertSchemeOwnerOrAdmin(scheme);
        Scheme oldSnapshot = snapshot(scheme);

        // 设为模板时校验标签必须存在于受控标签字典（阶段 6）
        if (Boolean.TRUE.equals(request.getIsTemplate())) {
            templateTagService.validateTagNames(request.getTemplateTags());
        }

        scheme.setIsTemplate(request.getIsTemplate());
        scheme.setTemplateTags(Boolean.TRUE.equals(request.getIsTemplate())
            ? toJson(request.getTemplateTags())
            : null);
        scheme.setUpdatedAt(LocalDateTime.now());
        schemeMapper.updateById(scheme);
        auditLogService.logUpdate("scheme", schemeId, oldSnapshot, scheme, currentUsername());
        return getSchemeDetail(schemeId);
    }

    /**
     * 套用模板创建新方案：复制方案项并取 RSKU 当前最新价，模板自身不被修改。
     *
     * <p>复制内容包括数量、排序与空间覆盖标签（space_tag，模板价值 = 复用空间布局）。</p>
     *
     * @param schemeId 模板方案 ID
     * @param request  套用请求（目标项目 + 可选新方案名）
     * @return 新方案详情与价格变动对比
     */
    @Transactional
    public CopyFromTemplateResponse copyFromTemplate(String schemeId, CopyFromTemplateRequest request) {
        Scheme template = schemeMapper.selectById(schemeId);
        if (template == null) {
            throw new ResourceNotFoundException("方案不存在: " + schemeId);
        }
        if (!Boolean.TRUE.equals(template.getIsTemplate())) {
            throw new BusinessException("该方案不是模板，无法套用");
        }
        Project project = projectService.getAccessibleProject(request.getProjectId());

        List<SchemeItem> templateItems = schemeItemMapper.selectList(
            new QueryWrapper<SchemeItem>()
                .eq("scheme_id", schemeId)
                .orderByAsc("sort_order")
        );
        if (templateItems.isEmpty()) {
            throw new BusinessException("模板方案没有可套用的方案项");
        }

        String newSchemeId = IdGenerator.schemeId();
        BigDecimal totalPrice = BigDecimal.ZERO;
        int maxLeadTimeDays = 0;
        Set<String> factoryCodes = new HashSet<>();
        List<SchemeItem> newItems = new java.util.ArrayList<>();
        List<PriceChangeResponse> priceChanges = new java.util.ArrayList<>();
        List<String> skippedRskuIds = new java.util.ArrayList<>();

        int sortOrder = 0;
        for (SchemeItem templateItem : templateItems) {
            RskuSupply rsku = rskuSupplyMapper.selectById(templateItem.getRskuId());
            if (rsku == null) {
                skippedRskuIds.add(templateItem.getRskuId());
                continue;
            }
            if (!dataScopeHelper.canAccessFactory(rsku.getFactoryCode())) {
                skippedRskuIds.add(templateItem.getRskuId());
                continue;
            }
            int quantity = templateItem.getQuantity() != null && templateItem.getQuantity() > 0
                ? templateItem.getQuantity()
                : 1;

            // 价格快照对比：模板保存价 vs 当前最新价。
            // 出厂价变动明细（含金额）受出厂价可见性约束：设计师等无权限角色不返回变动条目，
            // 防止经 oldPrice/newPrice 旁路泄露出厂价（canAccessFactory 对设计师恒 true，不能用作价格判据）
            if (templateItem.getFactoryPrice() != null && rsku.getFactoryPrice() != null
                && templateItem.getFactoryPrice().compareTo(rsku.getFactoryPrice()) != 0
                && dataScopeHelper.canViewFactoryPrice(rsku.getFactoryCode())) {
                RspuMaster rspu = rspuMapper.selectById(templateItem.getRspuId());
                PriceChangeResponse change = new PriceChangeResponse();
                change.setRspuId(templateItem.getRspuId());
                change.setRspuName(rspu != null ? rspu.getPositioningLabel() : null);
                change.setRskuId(templateItem.getRskuId());
                change.setOldPrice(templateItem.getFactoryPrice());
                change.setNewPrice(rsku.getFactoryPrice());
                priceChanges.add(change);
            }

            if (rsku.getFactoryPrice() != null) {
                totalPrice = totalPrice.add(rsku.getFactoryPrice().multiply(BigDecimal.valueOf(quantity)));
            }
            if (rsku.getLeadTimeDays() != null && rsku.getLeadTimeDays() > maxLeadTimeDays) {
                maxLeadTimeDays = rsku.getLeadTimeDays();
            }
            if (rsku.getFactoryCode() != null) {
                factoryCodes.add(rsku.getFactoryCode());
            }

            SchemeItem item = new SchemeItem();
            item.setSchemeId(newSchemeId);
            item.setRspuId(templateItem.getRspuId());
            item.setRskuId(templateItem.getRskuId());
            item.setFactoryCode(rsku.getFactoryCode());
            item.setFactoryPrice(rsku.getFactoryPrice());
            item.setLeadTimeDays(rsku.getLeadTimeDays());
            item.setMoq(rsku.getMoq());
            item.setQuantity(quantity);
            item.setSortOrder(sortOrder++);
            // 复制空间覆盖标签：模板价值 = 复用空间布局（B3）
            item.setSpaceTag(templateItem.getSpaceTag());
            item.setCreatedAt(LocalDateTime.now());
            newItems.add(item);
        }
        if (newItems.isEmpty()) {
            throw new BusinessException("模板中的 RSKU 均已失效，无法套用");
        }

        String baseName = StringUtils.hasText(request.getSchemeName())
            ? request.getSchemeName().trim()
            : template.getSchemeName() + "-套用";
        String newName = resolveUniqueSchemeName(baseName, project.getProjectId(),
            SecurityOperatorContext.currentUsername());

        Scheme scheme = new Scheme();
        scheme.setSchemeId(newSchemeId);
        scheme.setSchemeName(newName);
        scheme.setRoomType(template.getRoomType());
        scheme.setBudgetLimit(template.getBudgetLimit());
        scheme.setTotalPrice(totalPrice);
        scheme.setFactoryCount(factoryCodes.size());
        scheme.setMaxLeadTimeDays(maxLeadTimeDays);
        scheme.setItemCount(newItems.size());
        scheme.setStatus("active");
        scheme.setProjectId(project.getProjectId());
        scheme.setIsTemplate(false);
        scheme.setCreatedBy(currentUsername());
        scheme.setCreatedAt(LocalDateTime.now());
        schemeMapper.insert(scheme);

        schemeItemMapper.insertBatchSafe(newItems);
        auditLogService.logCreate("scheme", newSchemeId, scheme, scheme.getCreatedBy());

        CopyFromTemplateResponse response = new CopyFromTemplateResponse();
        response.setScheme(getSchemeDetail(newSchemeId));
        response.setPriceChanges(priceChanges);
        response.setSkippedRskuIds(skippedRskuIds);
        return response;
    }

    /**
     * 在指定项目/用户范围内生成不重复的方案名称。
     *
     * @param baseName 基础名称
     * @param projectId 所属项目 ID（可为空）
     * @param owner     创建人用户名
     * @return 可用的方案名称
     */
    private String resolveUniqueSchemeName(String baseName, String projectId, String owner) {
        String name = baseName;
        int suffix = 1;
        while (true) {
            QueryWrapper<Scheme> wrapper = new QueryWrapper<Scheme>()
                .eq("scheme_name", name)
                .eq("status", "active");
            if (StringUtils.hasText(projectId)) {
                wrapper.eq("project_id", projectId);
            } else {
                wrapper.eq("created_by", owner);
            }
            Long count = schemeMapper.selectCount(wrapper);
            if (count == null || count == 0) {
                return name;
            }
            name = baseName + "-" + (++suffix);
        }
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

    /**
     * 根据方案生成报价单（成本核价口径）。
     *
     * @param schemeId 方案 ID
     * @return 报价单
     */
    public QuoteResponse generateQuote(String schemeId) {
        return generateQuote(schemeId, null);
    }

    /**
     * 根据方案生成报价单。
     *
     * <p>报价项附方案空间信息（spaceTag/spaceTagName：方案明细覆盖码优先，
     * 回退产品场景推导），供前端按空间分组展示与导出空间列。</p>
     *
     * @param schemeId 方案 ID
     * @param mode     报价口径（可空，默认成本核价；sale=销售报价，透传给报价服务）
     * @return 报价单
     */
    public QuoteResponse generateQuote(String schemeId, String mode) {
        Scheme scheme = schemeMapper.selectById(schemeId);
        if (scheme == null) {
            throw new ResourceNotFoundException("方案不存在: " + schemeId);
        }

        List<SchemeItem> items = schemeItemMapper.selectList(
            new QueryWrapper<SchemeItem>().eq("scheme_id", schemeId)
        );

        // 数据权限过滤：无权限的 RSKU 不进入报价单
        List<SchemeItem> accessibleItems = items.stream()
            .filter(item -> dataScopeHelper.canAccessRskuFactory(item.getFactoryCode()))
            .collect(Collectors.toList());

        List<QuoteItemRequest> quoteItems = accessibleItems.stream()
            .map(item -> {
                QuoteItemRequest req = new QuoteItemRequest();
                req.setRskuId(item.getRskuId());
                int quantity = item.getQuantity() != null && item.getQuantity() > 0
                    ? item.getQuantity()
                    : 1;
                req.setQuantity(quantity);
                return req;
            })
            .collect(Collectors.toList());

        QuoteResponse quote = quoteService.generateQuote(quoteItems, mode);

        // 空间标签（方案 A 步骤 6）：报价项按方案明细附空间信息（覆盖码优先，回退产品场景推导）
        attachSpaceTags(accessibleItems, quote.getItems());

        // 快照模式：对比方案保存时的价格与当前最新价格。
        // 出厂价可见性约束：无权限角色（设计师等）不返回变动条目，防止经 oldPrice/newPrice 旁路泄露
        List<PriceChangeResponse> priceChanges = accessibleItems.stream()
            .map(item -> {
                RskuSupply currentRsku = rskuSupplyMapper.selectById(item.getRskuId());
                if (currentRsku == null) {
                    return null;
                }
                if (!dataScopeHelper.canViewFactoryPrice(currentRsku.getFactoryCode())) {
                    return null;
                }
                BigDecimal oldPrice = item.getFactoryPrice();
                BigDecimal newPrice = currentRsku.getFactoryPrice();
                if (oldPrice == null || newPrice == null || oldPrice.compareTo(newPrice) == 0) {
                    return null;
                }
                RspuMaster rspu = rspuMapper.selectById(item.getRspuId());
                PriceChangeResponse change = new PriceChangeResponse();
                change.setRspuId(item.getRspuId());
                change.setRspuName(rspu != null ? rspu.getPositioningLabel() : null);
                change.setRskuId(item.getRskuId());
                change.setOldPrice(oldPrice);
                change.setNewPrice(newPrice);
                return change;
            })
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toList());

        quote.setPriceChanges(priceChanges);
        return quote;
    }

    /**
     * 给方案语境的报价项附空间信息（方案 A 步骤 6）：按方案明细生效码
     * （space_tag 覆盖优先，回退产品 rspu_scene 首场景码）匹配 rspuId+rskuId 填充
     * spaceTag/spaceTagName；独立报价构建器（无方案语境）不调用，字段保持 null。
     *
     * @param schemeItems 方案明细（已按数据权限过滤）
     * @param quoteItems  报价项
     */
    private void attachSpaceTags(List<SchemeItem> schemeItems, List<QuoteItemResponse> quoteItems) {
        if (quoteItems == null || quoteItems.isEmpty() || schemeItems.isEmpty()) {
            return;
        }
        List<String> rspuIds = schemeItems.stream().map(SchemeItem::getRspuId).distinct().toList();
        Map<String, String> derivedCodes = batchSpaceTagCodes(rspuIds);
        // 键：rspuId|rskuId → 生效空间码
        Map<String, String> codeByItem = new HashMap<>();
        for (SchemeItem item : schemeItems) {
            String code = StringUtils.hasText(item.getSpaceTag())
                ? item.getSpaceTag()
                : derivedCodes.get(item.getRspuId());
            if (StringUtils.hasText(code)) {
                codeByItem.putIfAbsent(item.getRspuId() + "|" + item.getRskuId(), code);
            }
        }
        Map<String, String> sceneNames = batchSceneNames(
            codeByItem.values().stream().distinct().toList());
        for (QuoteItemResponse quoteItem : quoteItems) {
            String code = codeByItem.get(quoteItem.getRspuId() + "|" + quoteItem.getRskuId());
            if (StringUtils.hasText(code)) {
                quoteItem.setSpaceTag(code);
                quoteItem.setSpaceTagName(sceneNames.getOrDefault(code, code));
            }
        }
    }

    private SchemeItemResponse buildItemResponse(SchemeItem item,
                                                 Map<String, RspuMaster> rspuMap,
                                                 Map<String, RskuSupply> rskuMap,
                                                 Map<String, FactoryMaster> factoryMap,
                                                 Map<String, String> primaryImageUrlMap) {
        RspuMaster rspu = rspuMap.get(item.getRspuId());
        FactoryMaster factory = item.getFactoryCode() != null
            ? factoryMap.get(item.getFactoryCode())
            : null;
        RskuSupply rsku = rskuMap.get(item.getRskuId());

        SchemeItemResponse response = new SchemeItemResponse();
        response.setSchemeItemId(item.getSchemeItemId());
        response.setRspuId(item.getRspuId());
        response.setRspuName(rspu != null ? rspu.getPositioningLabel() : null);
        response.setPrimaryImageUrl(primaryImageUrlMap.get(item.getRspuId()));
        response.setRskuId(item.getRskuId());
        response.setFactoryCode(item.getFactoryCode());
        response.setFactoryName(factory != null ? factory.getFactoryName() : null);
        response.setFactorySku(rsku != null ? rsku.getFactorySku() : null);
        // 出厂价按角色掩码：仅平台运营人员与本厂管理员可见；掩码时小计同步隐藏
        boolean canViewPrice = dataScopeHelper.canViewFactoryPrice(item.getFactoryCode());
        response.setFactoryPrice(canViewPrice ? item.getFactoryPrice() : null);
        int quantity = item.getQuantity() != null && item.getQuantity() > 0 ? item.getQuantity() : 1;
        response.setQuantity(quantity);
        if (canViewPrice && item.getFactoryPrice() != null) {
            response.setSubtotal(item.getFactoryPrice().multiply(BigDecimal.valueOf(quantity)));
        }
        // 标准售价全角色可见（设计师端方案明细按售价查看，与出厂价掩码互不影响）
        response.setSalePrice(schemeSalePriceService.salePriceOf(rspu, rsku));
        response.setLeadTimeDays(item.getLeadTimeDays());
        response.setMoq(item.getMoq());
        response.setSortOrder(item.getSortOrder());
        return response;
    }

    private Map<String, RspuMaster> batchRspuMap(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        return rspuMapper.selectList(
            new QueryWrapper<RspuMaster>().in("rspu_id", rspuIds)
        ).stream().collect(Collectors.toMap(RspuMaster::getRspuId, r -> r));
    }

    /**
     * 批量获取 RSPU 的推导空间码：取每个 RSPU 的首个场景字典码（无标签则不出现）。
     *
     * @param rspuIds RSPU ID 列表
     * @return rspuId → 首个场景字典码
     */
    private Map<String, String> batchSpaceTagCodes(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        List<RspuScene> scenes = rspuSceneMapper.selectList(
            new QueryWrapper<RspuScene>()
                .in("rspu_id", rspuIds)
                .orderByAsc("scene_code"));
        Map<String, String> result = new HashMap<>();
        for (RspuScene scene : scenes) {
            result.putIfAbsent(scene.getRspuId(), scene.getSceneCode());
        }
        return result;
    }

    /**
     * 批量查询场景字典名称（category_dict dict_type=scene）。
     *
     * @param codes 场景字典码列表
     * @return 场景码 → 场景名称映射
     */
    private Map<String, String> batchSceneNames(List<String> codes) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        return categoryDictMapper.selectList(
                new QueryWrapper<CategoryDict>()
                    .eq("dict_type", "scene")
                    .in("dict_code", codes))
            .stream().collect(Collectors.toMap(
                CategoryDict::getDictCode,
                CategoryDict::getDictName,
                (a, b) -> a));
    }

    private Map<String, RskuSupply> batchRskuMap(List<String> rskuIds) {
        if (rskuIds.isEmpty()) {
            return Map.of();
        }
        return rskuSupplyMapper.selectList(
            new QueryWrapper<RskuSupply>().in("rsku_id", rskuIds)
        ).stream().collect(Collectors.toMap(RskuSupply::getRskuId, r -> r));
    }

    private Map<String, FactoryMaster> batchFactoryMap(List<String> factoryCodes) {
        if (factoryCodes.isEmpty()) {
            return Map.of();
        }
        return factoryMasterMapper.selectList(
            new QueryWrapper<FactoryMaster>().in("factory_code", factoryCodes)
        ).stream().collect(Collectors.toMap(FactoryMaster::getFactoryCode, f -> f));
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
}
