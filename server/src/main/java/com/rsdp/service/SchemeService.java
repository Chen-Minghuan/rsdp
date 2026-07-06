package com.rsdp.service;

import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.security.datascope.DataScopeHelper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.request.QuoteItemRequest;
import com.rsdp.dto.request.SchemeCreateRequest;
import com.rsdp.dto.request.SchemeItemRequest;
import com.rsdp.dto.request.SchemeUpdateRequest;
import com.rsdp.dto.response.PriceChangeResponse;
import com.rsdp.dto.response.QuoteResponse;
import com.rsdp.dto.response.SchemeItemResponse;
import com.rsdp.dto.response.SchemeResponse;
import com.rsdp.dto.response.SchemeSummaryResponse;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.entity.Scheme;
import com.rsdp.entity.SchemeItem;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.SchemeItemMapper;
import com.rsdp.mapper.SchemeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 搭配方案服务。
 */
@Service
@RequiredArgsConstructor
public class SchemeService {

    private final SchemeMapper schemeMapper;
    private final SchemeItemMapper schemeItemMapper;
    private final RskuSupplyMapper rskuSupplyMapper;
    private final RspuMapper rspuMapper;
    private final FactoryMasterMapper factoryMasterMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final QuoteService quoteService;
    private final DataScopeHelper dataScopeHelper;

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
        if (!StringUtils.hasText(request.getSchemeName())) {
            throw new BusinessException("方案名称不能为空");
        }

        String schemeId = "SCHEME-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

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
            if (rsku == null || rsku.getDeletedAt() != null) {
                throw new ResourceNotFoundException("RSKU 不存在: " + itemRequest.getRskuId());
            }
            if (!rsku.getRspuId().equals(itemRequest.getRspuId())) {
                throw new BusinessException(
                    "RSKU 与 RSPU 不匹配: " + itemRequest.getRskuId());
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
            schemeItem.setCreatedAt(LocalDateTime.now());
            schemeItems.add(schemeItem);
        }

        // 同一创建人下不允许存在同名活动方案
        assertSchemeNameUnique(request.getSchemeName().trim(), SecurityOperatorContext.currentUsername(), null);

        // 先写入主表，再写入子表，避免外键约束异常
        Scheme scheme = new Scheme();
        scheme.setSchemeId(schemeId);
        scheme.setSchemeName(request.getSchemeName().trim());
        scheme.setRoomType(request.getRoomType());
        scheme.setBudgetLimit(request.getBudgetLimit());
        scheme.setTotalPrice(totalPrice);
        scheme.setFactoryCount(factoryCodes.size());
        scheme.setMaxLeadTimeDays(maxLeadTimeDays);
        scheme.setItemCount(distinctItems.size());
        scheme.setStatus("active");
        scheme.setCreatedBy(SecurityOperatorContext.currentUsername());
        scheme.setCreatedAt(LocalDateTime.now());
        schemeMapper.insert(scheme);

        schemeItemMapper.insertBatchSafe(schemeItems);

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
        if (!StringUtils.hasText(request.getSchemeName())) {
            throw new BusinessException("方案名称不能为空");
        }

        Scheme scheme = schemeMapper.selectById(schemeId);
        if (scheme == null || scheme.getDeletedAt() != null) {
            throw new ResourceNotFoundException("方案不存在: " + schemeId);
        }
        assertSchemeOwnerOrAdmin(scheme);

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
            if (rsku == null || rsku.getDeletedAt() != null) {
                throw new ResourceNotFoundException("RSKU 不存在: " + itemRequest.getRskuId());
            }
            if (!rsku.getRspuId().equals(itemRequest.getRspuId())) {
                throw new BusinessException(
                    "RSKU 与 RSPU 不匹配: " + itemRequest.getRskuId());
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
            schemeItem.setCreatedAt(LocalDateTime.now());
            schemeItems.add(schemeItem);
        }

        // 同一创建人下不允许存在同名活动方案（排除当前方案自身）
        assertSchemeNameUnique(request.getSchemeName().trim(), scheme.getCreatedBy(), schemeId);

        // 物理删除旧子项
        schemeItemMapper.delete(
            new QueryWrapper<SchemeItem>().eq("scheme_id", schemeId)
        );

        scheme.setSchemeName(request.getSchemeName().trim());
        scheme.setRoomType(request.getRoomType());
        scheme.setBudgetLimit(request.getBudgetLimit());
        scheme.setTotalPrice(totalPrice);
        scheme.setFactoryCount(factoryCodes.size());
        scheme.setMaxLeadTimeDays(maxLeadTimeDays);
        scheme.setItemCount(distinctItems.size());
        scheme.setUpdatedAt(LocalDateTime.now());
        schemeMapper.updateById(scheme);

        schemeItemMapper.insertBatchSafe(schemeItems);

        return getSchemeDetail(schemeId);
    }

    private void assertSchemeNameUnique(String schemeName, String owner, String excludeSchemeId) {
        QueryWrapper<Scheme> wrapper = new QueryWrapper<Scheme>()
            .eq("scheme_name", schemeName)
            .eq("status", "active")
            .eq("created_by", owner);
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
        String currentUser = SecurityOperatorContext.currentUsername();
        if (scheme.getCreatedBy() == null || !scheme.getCreatedBy().equals(currentUser)) {
            throw new BusinessException("无权操作该方案");
        }
    }

    /**
     * 查询方案列表。
     *
     * @return 方案摘要列表
     */
    public List<SchemeSummaryResponse> listSchemes() {
        List<Scheme> schemes = schemeMapper.selectList(
            new QueryWrapper<Scheme>()
                .eq("status", "active")
                .orderByDesc("created_at")
        );

        return schemes.stream().map(s -> {
            SchemeSummaryResponse summary = new SchemeSummaryResponse();
            summary.setSchemeId(s.getSchemeId());
            summary.setSchemeName(s.getSchemeName());
            summary.setItemCount(s.getItemCount());
            summary.setTotalPrice(s.getTotalPrice());
            summary.setCreatedBy(s.getCreatedBy());
            summary.setCreatedAt(s.getCreatedAt());
            return summary;
        }).collect(Collectors.toList());
    }

    /**
     * 查询方案详情。
     *
     * @param schemeId 方案 ID
     * @return 方案详情
     */
    public SchemeResponse getSchemeDetail(String schemeId) {
        Scheme scheme = schemeMapper.selectById(schemeId);
        if (scheme == null || scheme.getDeletedAt() != null) {
            throw new ResourceNotFoundException("方案不存在: " + schemeId);
        }

        List<SchemeItem> items = schemeItemMapper.selectList(
            new QueryWrapper<SchemeItem>()
                .eq("scheme_id", schemeId)
                .orderByAsc("sort_order")
        );

        List<String> rspuIds = items.stream().map(SchemeItem::getRspuId).distinct().toList();
        List<String> rskuIds = items.stream().map(SchemeItem::getRskuId).distinct().toList();
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

        SchemeResponse response = new SchemeResponse();
        response.setSchemeId(scheme.getSchemeId());
        response.setSchemeName(scheme.getSchemeName());
        response.setRoomType(scheme.getRoomType());
        response.setBudgetLimit(scheme.getBudgetLimit());
        response.setTotalPrice(scheme.getTotalPrice());
        response.setFactoryCount(scheme.getFactoryCount());
        response.setMaxLeadTimeDays(scheme.getMaxLeadTimeDays());
        response.setItemCount(scheme.getItemCount());
        response.setStatus(scheme.getStatus());
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
        if (scheme == null || scheme.getDeletedAt() != null) {
            throw new ResourceNotFoundException("方案不存在: " + schemeId);
        }
        assertSchemeOwnerOrAdmin(scheme);
        scheme.setStatus("deleted");
        scheme.setDeletedAt(LocalDateTime.now());
        scheme.setUpdatedAt(LocalDateTime.now());
        schemeMapper.updateById(scheme);
    }

    /**
     * 根据方案生成报价单。
     *
     * @param schemeId 方案 ID
     * @return 报价单
     */
    public QuoteResponse generateQuote(String schemeId) {
        Scheme scheme = schemeMapper.selectById(schemeId);
        if (scheme == null || scheme.getDeletedAt() != null) {
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

        QuoteResponse quote = quoteService.generateQuote(quoteItems);

        // 快照模式：对比方案保存时的价格与当前最新价格
        List<PriceChangeResponse> priceChanges = accessibleItems.stream()
            .map(item -> {
                RskuSupply currentRsku = rskuSupplyMapper.selectById(item.getRskuId());
                if (currentRsku == null) {
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
        response.setFactoryPrice(item.getFactoryPrice());
        int quantity = item.getQuantity() != null && item.getQuantity() > 0 ? item.getQuantity() : 1;
        response.setQuantity(quantity);
        if (item.getFactoryPrice() != null) {
            response.setSubtotal(item.getFactoryPrice().multiply(BigDecimal.valueOf(quantity)));
        }
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
