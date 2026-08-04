package com.rsdp.service;

import com.rsdp.dto.request.QuoteItemRequest;
import com.rsdp.dto.response.QuoteItemResponse;
import com.rsdp.dto.response.QuoteResponse;
import com.rsdp.dto.response.QuoteSummaryResponse;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.security.datascope.DataScopeHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 报价单服务。
 */
@Service
@RequiredArgsConstructor
public class QuoteService {

    private final RskuSupplyMapper rskuSupplyMapper;
    private final RspuMapper rspuMapper;
    private final FactoryMasterMapper factoryMasterMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final FactoryService factoryService;
    private final DataScopeHelper dataScopeHelper;

    /**
     * 根据 RSKU ID 及数量列表生成报价单。
     *
     * @param quoteItems 报价单项请求列表
     * @return 报价单
     */
    public QuoteResponse generateQuote(List<QuoteItemRequest> quoteItems) {
        if (quoteItems == null || quoteItems.isEmpty()) {
            throw new BusinessException("请选择至少一个 RSKU");
        }

        // 按 rskuId 聚合数量，保留第一次出现的顺序
        List<QuoteItemRequest> mergedItems = new ArrayList<>();
        Map<String, Integer> quantityMap = new java.util.LinkedHashMap<>();
        for (QuoteItemRequest item : quoteItems) {
            if (item.getRskuId() == null || item.getRskuId().isBlank()) {
                continue;
            }
            int quantity = item.getQuantity() != null && item.getQuantity() > 0 ? item.getQuantity() : 1;
            quantityMap.merge(item.getRskuId(), quantity, Integer::sum);
        }
        quantityMap.forEach((rskuId, quantity) -> {
            QuoteItemRequest merged = new QuoteItemRequest();
            merged.setRskuId(rskuId);
            merged.setQuantity(quantity);
            mergedItems.add(merged);
        });

        if (mergedItems.isEmpty()) {
            throw new BusinessException("请选择至少一个有效的 RSKU");
        }

        List<String> distinctRskuIds = mergedItems.stream()
            .map(QuoteItemRequest::getRskuId)
            .toList();

        // 批量查询 RSKU
        List<RskuSupply> rskus = rskuSupplyMapper.selectBatchIds(distinctRskuIds);
        Map<String, RskuSupply> rskuMap = rskus.stream()
            .collect(Collectors.toMap(RskuSupply::getRskuId, r -> r));

        // 批量校验 RSKU 有效性，一次性返回所有失效项
        List<String> invalidRskuIds = distinctRskuIds.stream()
            .filter(id -> {
                RskuSupply rsku = rskuMap.get(id);
                return rsku == null;
            })
            .toList();
        if (!invalidRskuIds.isEmpty()) {
            throw new BusinessException(
                "以下 RSKU 已失效或不存在，请重新选择产品：" + String.join(", ", invalidRskuIds));
        }

        // 数据权限校验：当前用户必须能访问每个 RSKU 的工厂
        List<String> deniedRskuIds = rskus.stream()
            .filter(rsku -> !dataScopeHelper.canAccessRskuFactory(rsku.getFactoryCode()))
            .map(RskuSupply::getRskuId)
            .toList();
        if (!deniedRskuIds.isEmpty()) {
            throw new BusinessException("无权访问以下 RSKU 的工厂数据：" + String.join(", ", deniedRskuIds));
        }

        // 批量查询 RSPU、工厂、主图
        Map<String, RspuMaster> rspuMap = batchRspuMap(rskus);
        Map<String, FactoryMaster> factoryMap = batchFactoryMap(rskus);

        // 校验每个 RSKU 所属工厂是否具备对应产品等级能力
        validateFactoryCapabilities(rskus);

        Map<String, String> primaryImageUrlMap = batchPrimaryImageUrls(rspuMap.values().stream()
            .map(RspuMaster::getRspuId).toList());

        List<QuoteItemResponse> items = mergedItems.stream()
            .map(item -> buildItem(item, rskuMap.get(item.getRskuId()), rspuMap, factoryMap, primaryImageUrlMap))
            .collect(Collectors.toList());

        QuoteSummaryResponse summary = computeSummary(items);

        QuoteResponse response = new QuoteResponse();
        response.setItems(items);
        response.setSummary(summary);
        return response;
    }

    private Map<String, RspuMaster> batchRspuMap(List<RskuSupply> rskus) {
        List<String> rspuIds = rskus.stream()
            .map(RskuSupply::getRspuId)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        return rspuMapper.selectBatchIds(rspuIds).stream()
            .collect(Collectors.toMap(RspuMaster::getRspuId, r -> r));
    }

    private void validateFactoryCapabilities(List<RskuSupply> rskus) {
        List<String> factoryCodes = rskus.stream()
            .map(RskuSupply::getFactoryCode)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
        // 按 distinct 工厂编码批量查询能力等级，避免同工厂重复查询（N+1）
        Map<String, List<String>> capableLevelsMap = factoryCodes.isEmpty()
            ? Map.of()
            : factoryService.batchListCapableLevels(factoryCodes);

        List<String> mismatched = rskus.stream()
            .filter(rsku -> {
                String productLevel = rsku.getProductLevel();
                if (productLevel == null || productLevel.isBlank()) {
                    return false;
                }
                List<String> capableLevels = capableLevelsMap.getOrDefault(rsku.getFactoryCode(), List.of());
                return !capableLevels.contains(productLevel);
            })
            .map(RskuSupply::getRskuId)
            .toList();

        if (!mismatched.isEmpty()) {
            throw new BusinessException(
                "以下 RSKU 所属工厂未声明对应产品等级能力，请重新选择：" + String.join(", ", mismatched));
        }
    }

    private Map<String, FactoryMaster> batchFactoryMap(List<RskuSupply> rskus) {
        List<String> factoryCodes = rskus.stream()
            .map(RskuSupply::getFactoryCode)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
        if (factoryCodes.isEmpty()) {
            return Map.of();
        }
        return factoryMasterMapper.selectBatchIds(factoryCodes).stream()
            .collect(Collectors.toMap(FactoryMaster::getFactoryCode, f -> f));
    }

    private Map<String, String> batchPrimaryImageUrls(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        List<ImageAssets> images = imageAssetsMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ImageAssets>()
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

    private QuoteItemResponse buildItem(QuoteItemRequest quoteItem,
                                        RskuSupply rsku,
                                        Map<String, RspuMaster> rspuMap,
                                        Map<String, FactoryMaster> factoryMap,
                                        Map<String, String> primaryImageUrlMap) {
        RspuMaster rspu = rspuMap.get(rsku.getRspuId());
        if (rspu == null) {
            throw new ResourceNotFoundException("RSPU 不存在: " + rsku.getRspuId());
        }

        FactoryMaster factory = factoryMap.get(rsku.getFactoryCode());
        int quantity = quoteItem.getQuantity() != null && quoteItem.getQuantity() > 0
            ? quoteItem.getQuantity()
            : 1;
        // 出厂价按角色掩码：仅平台运营人员与本厂管理员可见；掩码时小计同步隐藏，避免经小计/总价泄露
        boolean canViewPrice = dataScopeHelper.canViewFactoryPrice(rsku.getFactoryCode());
        BigDecimal subtotal = canViewPrice && rsku.getFactoryPrice() != null
            ? rsku.getFactoryPrice().multiply(BigDecimal.valueOf(quantity))
            : null;

        QuoteItemResponse item = new QuoteItemResponse();
        item.setRspuId(rspu.getRspuId());
        item.setRspuName(rspu.getPositioningLabel());
        item.setPrimaryImageUrl(primaryImageUrlMap.get(rspu.getRspuId()));

        item.setRskuId(rsku.getRskuId());
        item.setFactoryCode(rsku.getFactoryCode());
        item.setFactoryName(factory != null ? factory.getFactoryName() : null);
        item.setFactorySku(rsku.getFactorySku());
        item.setFactoryPrice(canViewPrice ? rsku.getFactoryPrice() : null);
        item.setQuantity(quantity);
        item.setSubtotal(subtotal);
        item.setPriceBand(rsku.getPriceBand());
        item.setMaterialDescription(rsku.getMaterialDescription());
        item.setLeadTimeDays(rsku.getLeadTimeDays());
        item.setMoq(rsku.getMoq());
        item.setWarrantyYears(rsku.getWarrantyYears());
        item.setShippingFrom(rsku.getShippingFrom());
        item.setDiffNotes(rsku.getDiffNotes());
        return item;
    }

    private QuoteSummaryResponse computeSummary(List<QuoteItemResponse> items) {
        BigDecimal totalPrice = BigDecimal.ZERO;
        int totalQuantity = 0;
        int maxLeadTimeDays = 0;
        Set<String> factoryCodes = new HashSet<>();

        for (QuoteItemResponse item : items) {
            if (item.getSubtotal() != null) {
                totalPrice = totalPrice.add(item.getSubtotal());
            }
            if (item.getQuantity() != null) {
                totalQuantity += item.getQuantity();
            }
            if (item.getLeadTimeDays() != null && item.getLeadTimeDays() > maxLeadTimeDays) {
                maxLeadTimeDays = item.getLeadTimeDays();
            }
            if (item.getFactoryCode() != null) {
                factoryCodes.add(item.getFactoryCode());
            }
        }

        QuoteSummaryResponse summary = new QuoteSummaryResponse();
        summary.setTotalPrice(totalPrice);
        summary.setItemCount(items.size());
        summary.setTotalQuantity(totalQuantity);
        summary.setFactoryCount(factoryCodes.size());
        summary.setMaxLeadTimeDays(maxLeadTimeDays);
        return summary;
    }
}
