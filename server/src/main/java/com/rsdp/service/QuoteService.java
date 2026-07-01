package com.rsdp.service;

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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /**
     * 根据 RSKU ID 列表生成报价单。
     *
     * @param rskuIds RSKU ID 列表
     * @return 报价单
     */
    public QuoteResponse generateQuote(List<String> rskuIds) {
        if (rskuIds == null || rskuIds.isEmpty()) {
            throw new BusinessException("请选择至少一个 RSKU");
        }

        List<String> distinctRskuIds = rskuIds.stream().distinct().toList();

        // 批量查询 RSKU
        List<RskuSupply> rskus = rskuSupplyMapper.selectBatchIds(distinctRskuIds);
        Map<String, RskuSupply> rskuMap = rskus.stream()
            .collect(Collectors.toMap(RskuSupply::getRskuId, r -> r));

        // 批量校验 RSKU 有效性，一次性返回所有失效项
        List<String> invalidRskuIds = distinctRskuIds.stream()
            .filter(id -> {
                RskuSupply rsku = rskuMap.get(id);
                return rsku == null || rsku.getDeletedAt() != null;
            })
            .toList();
        if (!invalidRskuIds.isEmpty()) {
            throw new BusinessException(
                "以下 RSKU 已失效或不存在，请重新选择产品：" + String.join(", ", invalidRskuIds));
        }

        // 批量查询 RSPU、工厂、主图
        Map<String, RspuMaster> rspuMap = batchRspuMap(rskus);
        Map<String, FactoryMaster> factoryMap = batchFactoryMap(rskus);
        Map<String, String> primaryImageUrlMap = batchPrimaryImageUrls(rspuMap.values().stream()
            .map(RspuMaster::getRspuId).toList());

        List<QuoteItemResponse> items = distinctRskuIds.stream()
            .map(id -> buildItem(rskuMap.get(id), rspuMap, factoryMap, primaryImageUrlMap))
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
            .distinct()
            .toList();
        return rspuMapper.selectBatchIds(rspuIds).stream()
            .collect(Collectors.toMap(RspuMaster::getRspuId, r -> r));
    }

    private Map<String, FactoryMaster> batchFactoryMap(List<RskuSupply> rskus) {
        List<String> factoryCodes = rskus.stream()
            .map(RskuSupply::getFactoryCode)
            .distinct()
            .toList();
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

    private QuoteItemResponse buildItem(RskuSupply rsku,
                                        Map<String, RspuMaster> rspuMap,
                                        Map<String, FactoryMaster> factoryMap,
                                        Map<String, String> primaryImageUrlMap) {
        RspuMaster rspu = rspuMap.get(rsku.getRspuId());
        if (rspu == null || rspu.getDeletedAt() != null) {
            throw new ResourceNotFoundException("RSPU 不存在: " + rsku.getRspuId());
        }

        FactoryMaster factory = factoryMap.get(rsku.getFactoryCode());

        QuoteItemResponse item = new QuoteItemResponse();
        item.setRspuId(rspu.getRspuId());
        item.setRspuName(rspu.getPositioningLabel());
        item.setPrimaryImageUrl(primaryImageUrlMap.get(rspu.getRspuId()));

        item.setRskuId(rsku.getRskuId());
        item.setFactoryCode(rsku.getFactoryCode());
        item.setFactoryName(factory != null ? factory.getFactoryName() : null);
        item.setFactorySku(rsku.getFactorySku());
        item.setFactoryPrice(rsku.getFactoryPrice());
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
        int maxLeadTimeDays = 0;
        Set<String> factoryCodes = new HashSet<>();

        for (QuoteItemResponse item : items) {
            if (item.getFactoryPrice() != null) {
                totalPrice = totalPrice.add(item.getFactoryPrice());
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
        summary.setFactoryCount(factoryCodes.size());
        summary.setMaxLeadTimeDays(maxLeadTimeDays);
        return summary;
    }
}
