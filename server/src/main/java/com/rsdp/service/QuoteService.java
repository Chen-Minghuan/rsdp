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
 * 报价单服务：支持两种价格口径——成本核价（cost，内部，出厂价 + 权限掩码）与
 * 销售报价（sale，对客户，标准售价计价，售价低于成本仅警告不拦截）。
 */
@Service
@RequiredArgsConstructor
public class QuoteService {

    /** 报价口径：成本核价（内部）。 */
    public static final String MODE_COST = "cost";
    /** 报价口径：销售报价（对客户）。 */
    public static final String MODE_SALE = "sale";

    private final RskuSupplyMapper rskuSupplyMapper;
    private final RspuMapper rspuMapper;
    private final FactoryMasterMapper factoryMasterMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final FactoryService factoryService;
    private final DataScopeHelper dataScopeHelper;
    private final PricingService pricingService;

    /**
     * 解析报价口径：空值回退成本核价（向后兼容），非法值抛业务异常。
     *
     * @param mode 原始口径参数（可空）
     * @return {@link #MODE_COST} 或 {@link #MODE_SALE}
     */
    public static String resolveMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return MODE_COST;
        }
        if (MODE_COST.equals(mode) || MODE_SALE.equals(mode)) {
            return mode;
        }
        throw new BusinessException("非法报价口径: " + mode + "（支持 cost/sale）");
    }

    /**
     * 根据 RSKU ID 及数量列表生成报价单（成本核价口径）。
     *
     * @param quoteItems 报价单项请求列表
     * @return 报价单
     */
    public QuoteResponse generateQuote(List<QuoteItemRequest> quoteItems) {
        return generateQuote(quoteItems, null);
    }

    /**
     * 根据 RSKU ID 及数量列表生成报价单。
     *
     * <p>口径 cost：出厂价 + 权限掩码（维持原行为）；口径 sale：单价取标准售价
     * （{@link PricingService#resolveSalePrice}，RSPU 建议销售价优先，否则成本 × 全局加价倍率），
     * 未定价 RSKU 整单拦截报错；有出厂价查看权限时每项附成本与毛利（仅内部），
     * 无权限则绝不返回成本字段。</p>
     *
     * @param quoteItems 报价单项请求列表
     * @param mode       报价口径（可空，默认 cost）
     * @return 报价单
     */
    public QuoteResponse generateQuote(List<QuoteItemRequest> quoteItems, String mode) {
        String resolvedMode = resolveMode(mode);
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
            .map(item -> buildItem(item, rskuMap.get(item.getRskuId()), rspuMap, factoryMap, primaryImageUrlMap, resolvedMode))
            .collect(Collectors.toList());

        QuoteSummaryResponse summary = computeSummary(items, resolvedMode);

        QuoteResponse response = new QuoteResponse();
        response.setItems(items);
        response.setSummary(summary);
        response.setPriceWarning(buildPriceWarning(items, resolvedMode));
        return response;
    }

    /**
     * 汇总售价低于成本的明细为口径级警告（仅 sale 口径；清库存场景提示，无则返回 {@code null}）。
     *
     * @param items 报价项
     * @param mode  报价口径
     * @return 警告文案或 {@code null}
     */
    private static String buildPriceWarning(List<QuoteItemResponse> items, String mode) {
        if (!MODE_SALE.equals(mode)) {
            return null;
        }
        List<String> names = items.stream()
            .filter(QuoteItemResponse::isBelowCost)
            .map(item -> StringUtils.hasText(item.getRspuName()) ? item.getRspuName() : item.getRspuId())
            .distinct()
            .toList();
        if (names.isEmpty()) {
            return null;
        }
        return "以下产品售价低于成本：" + String.join("、", names) + "（清库存场景请确认）";
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
                                        Map<String, String> primaryImageUrlMap,
                                        String mode) {
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
        boolean saleMode = MODE_SALE.equals(mode);

        // 售价口径：标准售价（建议销售价优先，否则成本 × 全局加价倍率），未定价整单拦截
        BigDecimal salePrice = null;
        BigDecimal subtotal;
        if (saleMode) {
            salePrice = pricingService.resolveSalePrice(rspu, rsku);
            if (salePrice == null) {
                throw new BusinessException("产品未定价（无建议销售价且无出厂价）: " + rsku.getRspuId());
            }
            subtotal = salePrice.multiply(BigDecimal.valueOf(quantity))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        } else {
            subtotal = canViewPrice && rsku.getFactoryPrice() != null
                ? rsku.getFactoryPrice().multiply(BigDecimal.valueOf(quantity))
                : null;
        }

        QuoteItemResponse item = new QuoteItemResponse();
        item.setRspuId(rspu.getRspuId());
        item.setRspuName(rspu.getPositioningLabel());
        item.setProductName(rspu.getProductName());
        item.setPrimaryImageUrl(primaryImageUrlMap.get(rspu.getRspuId()));

        item.setRskuId(rsku.getRskuId());
        item.setFactoryCode(rsku.getFactoryCode());
        item.setFactoryName(factory != null ? factory.getFactoryName() : null);
        item.setFactorySku(rsku.getFactorySku());
        // 出厂价=成本：cost 口径按角色掩码返回；sale（对客户）口径绝不返回，避免成本泄露
        item.setFactoryPrice(!saleMode && canViewPrice ? rsku.getFactoryPrice() : null);
        item.setQuantity(quantity);
        item.setSubtotal(subtotal);
        if (saleMode) {
            item.setSalePrice(salePrice);
            item.setBelowCost(PricingService.isBelowCost(salePrice, rsku.getFactoryPrice()));
            // sale 口径为对客户报价单：不返回成本/毛利字段（costPrice/marginAmount 恒为 null）
        }
        item.setPriceBand(rsku.getPriceBand());
        item.setMaterialDescription(rsku.getMaterialDescription());
        item.setLeadTimeDays(rsku.getLeadTimeDays());
        item.setMoq(rsku.getMoq());
        item.setWarrantyYears(rsku.getWarrantyYears());
        item.setShippingFrom(rsku.getShippingFrom());
        item.setDiffNotes(rsku.getDiffNotes());
        return item;
    }

    private QuoteSummaryResponse computeSummary(List<QuoteItemResponse> items, String mode) {
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

        // sale 口径为对客户报价单：不汇总成本/毛利（totalCost/totalMargin 恒为 null）
        return summary;
    }
}
