package com.rsdp.service;

import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.security.datascope.DataScopeHelper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.request.RskuCreateRequest;
import com.rsdp.dto.response.RskuResponse;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.PriceHistory;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuVariant;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.PriceHistoryMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * RSKU 报价服务。
 */
@Service
@RequiredArgsConstructor
public class RskuService {

    private final RskuSupplyMapper rskuSupplyMapper;
    private final RspuMapper rspuMapper;
    private final FactoryMasterMapper factoryMasterMapper;
    private final FactoryService factoryService;
    private final DictService dictService;
    private final RspuVariantService rspuVariantService;
    private final PriceHistoryMapper priceHistoryMapper;
    private final AuditLogService auditLogService;
    private final DataScopeHelper dataScopeHelper;

    /**
     * 查询某 RSPU 下的所有 RSKU 报价。
     *
     * @param rspuId RSPU ID
     * @return RSKU 报价列表
     */
    public List<RskuResponse> listByRspu(String rspuId) {
        QueryWrapper<RskuSupply> wrapper = new QueryWrapper<RskuSupply>()
            .eq("rspu_id", rspuId)
            .orderByDesc("created_at");
        dataScopeHelper.applyRskuScope(wrapper);
        List<RskuSupply> list = rskuSupplyMapper.selectList(wrapper);
        return toResponses(list);
    }

    /**
     * 查询单个 RSKU 报价详情。
     *
     * @param rspuId RSPU ID
     * @param rskuId RSKU ID
     * @return RSKU 报价详情
     */
    public RskuResponse getRsku(String rspuId, String rskuId) {
        RskuSupply rsku = rskuSupplyMapper.selectById(rskuId);
        if (rsku == null || rsku.getDeletedAt() != null) {
            throw new ResourceNotFoundException("RSKU 不存在: " + rskuId);
        }
        if (!rspuId.equals(rsku.getRspuId())) {
            throw new ResourceNotFoundException("RSKU 不属于该产品: " + rskuId);
        }
        if (!dataScopeHelper.canAccessRskuFactory(rsku.getFactoryCode())) {
            throw new ResourceNotFoundException("RSKU 不存在: " + rskuId);
        }
        return toResponses(List.of(rsku)).get(0);
    }

    /**
     * 查询某工厂的所有 RSKU 报价。
     *
     * @param factoryCode 工厂代码
     * @return RSKU 报价列表
     */
    public List<RskuResponse> listByFactory(String factoryCode) {
        if (!dataScopeHelper.canAccessRskuFactory(factoryCode)) {
            return List.of();
        }
        List<RskuSupply> list = rskuSupplyMapper.selectList(
            new QueryWrapper<RskuSupply>()
                .eq("factory_code", factoryCode)
                .orderByDesc("created_at")
        );
        return toResponses(list);
    }

    /**
     * 为 RSPU 新增工厂报价。
     *
     * @param request 报价请求
     * @return 新创建的 RSKU ID
     */
    @Transactional
    public String createRsku(RskuCreateRequest request) {
        RspuMaster rspu = rspuMapper.selectById(request.getRspuId());
        if (rspu == null || rspu.getDeletedAt() != null) {
            throw new ResourceNotFoundException("产品不存在: " + request.getRspuId());
        }

        FactoryMaster factory = factoryMasterMapper.selectById(request.getFactoryCode());
        if (factory == null || factory.getDeletedAt() != null) {
            throw new ResourceNotFoundException("工厂不存在: " + request.getFactoryCode());
        }
        if (!dataScopeHelper.canAccessRskuFactory(request.getFactoryCode())) {
            throw new BusinessException("无权为该工厂录入报价: " + request.getFactoryCode());
        }

        RspuVariant variant = rspuVariantService.findById(request.getVariantId());
        if (!request.getRspuId().equals(variant.getRspuId())) {
            throw new BusinessException("变体不属于该产品: " + request.getVariantId());
        }

        String productLevel = resolveProductLevel(request, rspu, variant);
        validateProductLevel(productLevel);

        List<String> capableLevels = factoryService.getFactoryCapableLevels(request.getFactoryCode());
        if (!capableLevels.contains(productLevel)) {
            if (Boolean.TRUE.equals(request.getAutoExtendCapability())) {
                factoryService.extendCapability(request.getFactoryCode(), productLevel);
            } else {
                throw new BusinessException(
                    String.format("工厂 %s 未声明 %s 级能力，无法录入该等级产品报价。请先更新工厂能力等级或开启自动扩展。",
                        request.getFactoryCode(), productLevel));
            }
        }

        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        rsku.setRspuId(request.getRspuId());
        rsku.setVariantId(request.getVariantId());
        rsku.setFactoryCode(request.getFactoryCode());
        rsku.setFactorySku(request.getFactorySku());
        rsku.setFactoryPrice(request.getFactoryPrice());
        rsku.setPriceBand(resolvePriceBand(request.getFactoryPrice()));
        rsku.setProductLevel(productLevel);
        rsku.setMaterialCode(request.getMaterialCode());
        rsku.setMaterialDescription(request.getMaterialDescription());
        rsku.setLeadTimeDays(request.getLeadTimeDays());
        rsku.setMoq(request.getMoq());
        rsku.setWarrantyYears(request.getWarrantyYears());
        rsku.setShippingFrom(request.getShippingFrom());
        rsku.setShippingWarehouseId(request.getShippingWarehouseId());
        rsku.setDiffNotes(request.getDiffNotes());
        rsku.setQuoteConfidence(request.getQuoteConfidence());
        rsku.setReviewStatus("待复核");
        rsku.setPriceUpdated(LocalDate.now());
        rsku.setCreatedAt(LocalDateTime.now());
        rsku.setUpdatedAt(LocalDateTime.now());

        try {
            rskuSupplyMapper.insert(rsku);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException("该工厂对该变体已有报价");
        }

        auditLogService.logCreate("rsku_supply", rsku.getRskuId(), rsku, SecurityOperatorContext.currentUsername());
        return rsku.getRskuId();
    }

    private String resolveProductLevel(RskuCreateRequest request, RspuMaster rspu, RspuVariant variant) {
        if (StringUtils.hasText(request.getProductLevel())) {
            return request.getProductLevel().trim();
        }
        if (StringUtils.hasText(variant.getProductLevel())) {
            return variant.getProductLevel();
        }
        if (StringUtils.hasText(rspu.getProductLevel())) {
            return rspu.getProductLevel();
        }
        throw new BusinessException("请为产品或变体设置产品等级，或在报价中指定产品等级");
    }

    private void validateProductLevel(String level) {
        boolean exists = dictService.listByType("factory_level").stream()
            .anyMatch(d -> level.equals(d.getDictCode()));
        if (!exists) {
            throw new BusinessException("产品等级不存在: " + level);
        }
    }

    /**
     * 软删除 RSKU 报价。
     *
     * @param rskuId RSKU ID
     */
    @Transactional
    public void deleteRsku(String rskuId) {
        RskuSupply rsku = rskuSupplyMapper.selectById(rskuId);
        if (rsku == null || rsku.getDeletedAt() != null) {
            throw new ResourceNotFoundException("RSKU 不存在: " + rskuId);
        }
        if (!dataScopeHelper.canAccessRskuFactory(rsku.getFactoryCode())) {
            throw new ResourceNotFoundException("RSKU 不存在: " + rskuId);
        }

        RskuSupply oldSnapshot = snapshot(rsku);
        rsku.setDeletedAt(LocalDateTime.now());
        rsku.setUpdatedAt(LocalDateTime.now());
        rskuSupplyMapper.updateById(rsku);

        auditLogService.logDelete("rsku_supply", rskuId, oldSnapshot, SecurityOperatorContext.currentUsername());
    }

    /**
     * 更新 RSKU 出厂价，并记录价格历史。
     *
     * @param rspuId      RSPU ID
     * @param rskuId      RSKU ID
     * @param newPrice    新价格
     * @param changeReason 变更原因
     * @throws ResourceNotFoundException 当 RSKU 不存在或不属于该 RSPU 时
     * @throws BusinessException         当价格非法时
     */
    @Transactional
    public void updateRskuPrice(String rspuId, String rskuId, BigDecimal newPrice, String changeReason) {
        RskuSupply rsku = rskuSupplyMapper.selectById(rskuId);
        if (rsku == null || rsku.getDeletedAt() != null) {
            throw new ResourceNotFoundException("RSKU 不存在: " + rskuId);
        }
        if (!rspuId.equals(rsku.getRspuId())) {
            throw new ResourceNotFoundException("RSKU 不属于该产品: " + rskuId);
        }
        if (!dataScopeHelper.canAccessRskuFactory(rsku.getFactoryCode())) {
            throw new ResourceNotFoundException("RSKU 不存在: " + rskuId);
        }
        if (newPrice == null || newPrice.compareTo(java.math.BigDecimal.ZERO) < 0) {
            throw new BusinessException("价格不能为负数");
        }

        RskuSupply oldSnapshot = snapshot(rsku);
        BigDecimal oldPrice = rsku.getFactoryPrice();

        rsku.setFactoryPrice(newPrice);
        rsku.setPriceBand(resolvePriceBand(newPrice));
        rsku.setPriceUpdated(LocalDate.now());
        rsku.setUpdatedAt(LocalDateTime.now());
        rskuSupplyMapper.updateById(rsku);

        PriceHistory history = new PriceHistory();
        history.setRskuId(rskuId);
        history.setOldPrice(oldPrice);
        history.setNewPrice(newPrice);
        history.setChangedBy(SecurityOperatorContext.currentUsername());
        history.setChangeReason(changeReason);
        history.setCreatedAt(LocalDateTime.now());
        priceHistoryMapper.insert(history);

        auditLogService.logUpdate("rsku_supply", rskuId, oldSnapshot, rsku, SecurityOperatorContext.currentUsername());
    }

    private RskuSupply snapshot(RskuSupply source) {
        RskuSupply copy = new RskuSupply();
        copy.setRskuId(source.getRskuId());
        copy.setRspuId(source.getRspuId());
        copy.setVariantId(source.getVariantId());
        copy.setFactoryCode(source.getFactoryCode());
        copy.setFactorySku(source.getFactorySku());
        copy.setFactoryPrice(source.getFactoryPrice());
        copy.setPriceBand(source.getPriceBand());
        copy.setProductLevel(source.getProductLevel());
        copy.setMaterialCode(source.getMaterialCode());
        copy.setMaterialDescription(source.getMaterialDescription());
        copy.setLeadTimeDays(source.getLeadTimeDays());
        copy.setMoq(source.getMoq());
        copy.setWarrantyYears(source.getWarrantyYears());
        copy.setShippingFrom(source.getShippingFrom());
        copy.setDiffNotes(source.getDiffNotes());
        copy.setQuoteConfidence(source.getQuoteConfidence());
        copy.setReviewStatus(source.getReviewStatus());
        copy.setPriceUpdated(source.getPriceUpdated());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private String resolvePriceBand(BigDecimal price) {
        if (price == null) {
            return "unknown";
        }
        if (price.compareTo(new BigDecimal("1000")) < 0) {
            return "low";
        } else if (price.compareTo(new BigDecimal("5000")) < 0) {
            return "mid";
        } else {
            return "high";
        }
    }

    private List<RskuResponse> toResponses(List<RskuSupply> rskus) {
        List<String> factoryCodes = rskus.stream()
            .map(RskuSupply::getFactoryCode)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
        Map<String, FactoryMaster> factoryMap = factoryCodes.isEmpty()
            ? Map.of()
            : factoryMasterMapper.selectBatchIds(factoryCodes).stream()
                .collect(Collectors.toMap(FactoryMaster::getFactoryCode, f -> f));
        Map<String, List<String>> capabilityMap = factoryCodes.isEmpty()
            ? Map.of()
            : factoryCodes.stream()
                .collect(Collectors.toMap(
                    code -> code,
                    factoryService::getFactoryCapableLevels
                ));

        return rskus.stream()
            .map(rsku -> toResponse(rsku, factoryMap, capabilityMap))
            .collect(Collectors.toList());
    }

    private RskuResponse toResponse(RskuSupply rsku,
                                    Map<String, FactoryMaster> factoryMap,
                                    Map<String, List<String>> capabilityMap) {
        RskuResponse response = new RskuResponse();
        response.setRskuId(rsku.getRskuId());
        response.setRspuId(rsku.getRspuId());
        response.setVariantId(rsku.getVariantId());
        response.setFactoryCode(rsku.getFactoryCode());
        response.setFactorySku(rsku.getFactorySku());
        response.setFactoryPrice(rsku.getFactoryPrice());
        response.setPriceBand(rsku.getPriceBand());
        response.setProductLevel(rsku.getProductLevel());
        response.setMaterialCode(rsku.getMaterialCode());
        response.setMaterialDescription(rsku.getMaterialDescription());
        response.setLeadTimeDays(rsku.getLeadTimeDays());
        response.setMoq(rsku.getMoq());
        response.setWarrantyYears(rsku.getWarrantyYears());
        response.setShippingFrom(rsku.getShippingFrom());
        response.setDiffNotes(rsku.getDiffNotes());
        response.setQuoteConfidence(rsku.getQuoteConfidence());
        response.setReviewStatus(rsku.getReviewStatus());
        response.setPriceUpdated(rsku.getPriceUpdated());
        response.setCreatedAt(rsku.getCreatedAt());
        response.setUpdatedAt(rsku.getUpdatedAt());

        FactoryMaster factory = factoryMap.get(rsku.getFactoryCode());
        if (factory != null) {
            response.setFactoryName(factory.getFactoryName());
        }
        response.setFactoryCapableLevels(capabilityMap.getOrDefault(rsku.getFactoryCode(), List.of()));
        return response;
    }
}
