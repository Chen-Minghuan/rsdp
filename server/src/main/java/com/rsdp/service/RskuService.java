package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.request.RskuCreateRequest;
import com.rsdp.dto.response.RskuResponse;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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
    private final AuditLogService auditLogService;

    /**
     * 查询某 RSPU 下的所有 RSKU 报价。
     *
     * @param rspuId RSPU ID
     * @return RSKU 报价列表
     */
    public List<RskuResponse> listByRspu(String rspuId) {
        List<RskuSupply> list = rskuSupplyMapper.selectList(
            new QueryWrapper<RskuSupply>()
                .eq("rspu_id", rspuId)
                .isNull("deleted_at")
                .orderByDesc("created_at")
        );
        return list.stream().map(this::toResponse).collect(Collectors.toList());
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
        return toResponse(rsku);
    }

    /**
     * 查询某工厂的所有 RSKU 报价。
     *
     * @param factoryCode 工厂代码
     * @return RSKU 报价列表
     */
    public List<RskuResponse> listByFactory(String factoryCode) {
        List<RskuSupply> list = rskuSupplyMapper.selectList(
            new QueryWrapper<RskuSupply>()
                .eq("factory_code", factoryCode)
                .isNull("deleted_at")
                .orderByDesc("created_at")
        );
        return list.stream().map(this::toResponse).collect(Collectors.toList());
    }

    /**
     * 为 RSPU 新增工厂报价。
     *
     * @param request 报价请求
     */
    public void createRsku(RskuCreateRequest request) {
        RspuMaster rspu = rspuMapper.selectById(request.getRspuId());
        if (rspu == null || rspu.getDeletedAt() != null) {
            throw new ResourceNotFoundException("产品不存在: " + request.getRspuId());
        }

        FactoryMaster factory = factoryMasterMapper.selectById(request.getFactoryCode());
        if (factory == null || factory.getDeletedAt() != null) {
            throw new ResourceNotFoundException("工厂不存在: " + request.getFactoryCode());
        }

        QueryWrapper<RskuSupply> duplicateQuery = new QueryWrapper<RskuSupply>()
            .eq("rspu_id", request.getRspuId())
            .eq("factory_code", request.getFactoryCode())
            .isNull("deleted_at");
        if (request.getVariantId() != null) {
            duplicateQuery.eq("variant_id", request.getVariantId());
        } else {
            duplicateQuery.isNull("variant_id");
        }
        Long count = rskuSupplyMapper.selectCount(duplicateQuery);
        if (count != null && count > 0) {
            throw new BusinessException("该工厂对该变体已有报价");
        }

        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        rsku.setRspuId(request.getRspuId());
        rsku.setVariantId(request.getVariantId());
        rsku.setFactoryCode(request.getFactoryCode());
        rsku.setFactorySku(request.getFactorySku());
        rsku.setFactoryPrice(request.getFactoryPrice());
        rsku.setPriceBand(resolvePriceBand(request.getFactoryPrice()));
        rsku.setMaterialDescription(request.getMaterialDescription());
        rsku.setLeadTimeDays(request.getLeadTimeDays());
        rsku.setMoq(request.getMoq());
        rsku.setWarrantyYears(request.getWarrantyYears());
        rsku.setShippingFrom(request.getShippingFrom());
        rsku.setDiffNotes(request.getDiffNotes());
        rsku.setQuoteConfidence(request.getQuoteConfidence());
        rsku.setReviewStatus("待复核");
        rsku.setPriceUpdated(LocalDate.now());
        rsku.setCreatedAt(LocalDateTime.now());
        rsku.setUpdatedAt(LocalDateTime.now());
        rskuSupplyMapper.insert(rsku);

        auditLogService.logCreate("rsku_supply", rsku.getRskuId(), rsku, "admin");
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

    private RskuResponse toResponse(RskuSupply rsku) {
        RskuResponse response = new RskuResponse();
        response.setRskuId(rsku.getRskuId());
        response.setRspuId(rsku.getRspuId());
        response.setVariantId(rsku.getVariantId());
        response.setFactoryCode(rsku.getFactoryCode());
        response.setFactorySku(rsku.getFactorySku());
        response.setFactoryPrice(rsku.getFactoryPrice());
        response.setPriceBand(rsku.getPriceBand());
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

        FactoryMaster factory = factoryMasterMapper.selectById(rsku.getFactoryCode());
        if (factory != null) {
            response.setFactoryName(factory.getFactoryName());
        }
        return response;
    }
}
