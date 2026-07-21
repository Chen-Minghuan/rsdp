package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.request.RspuFactoryMappingRequest;
import com.rsdp.dto.response.RspuFactoryMappingResponse;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.FactoryWarehouse;
import com.rsdp.entity.RspuFactoryMapping;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.FactoryWarehouseMapper;
import com.rsdp.mapper.RspuFactoryMappingMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.security.datascope.DataScopeHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RSPU-工厂多对多关联服务。
 */
@Service
@RequiredArgsConstructor
public class RspuFactoryMappingService {

    private final RspuFactoryMappingMapper mappingMapper;
    private final FactoryMasterMapper factoryMasterMapper;
    private final FactoryWarehouseMapper warehouseMapper;
    private final AuditLogService auditLogService;
    private final DataScopeHelper dataScopeHelper;

    /**
     * 创建或更新 RSPU-工厂关联。
     *
     * @param request 关联请求
     * @return 关联 ID
     */
    @Transactional
    public Long saveMapping(RspuFactoryMappingRequest request) {
        dataScopeHelper.assertCanAccessRspu(request.getRspuId());
        validateRequest(request);

        RspuFactoryMapping mapping;
        boolean isCreate = request.getMappingId() == null;
        if (isCreate) {
            // 同一 RSPU+工厂只能有一条
            Long exists = mappingMapper.selectCount(
                new QueryWrapper<RspuFactoryMapping>()
                    .eq("rspu_id", request.getRspuId())
                    .eq("factory_code", request.getFactoryCode())
            );
            if (exists != null && exists > 0) {
                throw new BusinessException("该款式已关联此工厂，请勿重复关联");
            }
            mapping = new RspuFactoryMapping();
            mapping.setCreatedAt(LocalDateTime.now());
        } else {
            mapping = mappingMapper.selectById(request.getMappingId());
            if (mapping == null) {
                throw new ResourceNotFoundException("关联记录不存在: " + request.getMappingId());
            }
        }

        mapping.setRspuId(request.getRspuId());
        mapping.setFactoryCode(request.getFactoryCode());
        mapping.setIsPrimary(request.getIsPrimary() != null ? request.getIsPrimary() : false);
        mapping.setShippingWarehouseId(request.getShippingWarehouseId());
        mapping.setMoq(request.getMoq());
        mapping.setBaseLeadTimeDays(request.getBaseLeadTimeDays());
        mapping.setStatus(StringUtils.hasText(request.getStatus()) ? request.getStatus() : "active");
        mapping.setNotes(request.getNotes());
        mapping.setCreatedBy(SecurityOperatorContext.currentUserId());
        mapping.setUpdatedAt(LocalDateTime.now());

        // 如果设为主供，取消该 RSPU 其他主供
        if (Boolean.TRUE.equals(mapping.getIsPrimary())) {
            clearOtherPrimary(request.getRspuId(), isCreate ? null : request.getMappingId());
        }

        if (isCreate) {
            mappingMapper.insert(mapping);
            auditLogService.logCreate("rspu_factory_mapping", String.valueOf(mapping.getMappingId()), mapping,
                SecurityOperatorContext.currentUsername());
        } else {
            RspuFactoryMapping old = mappingMapper.selectById(mapping.getMappingId());
            mappingMapper.updateById(mapping);
            auditLogService.logUpdate("rspu_factory_mapping", String.valueOf(mapping.getMappingId()), old, mapping,
                SecurityOperatorContext.currentUsername());
        }
        return mapping.getMappingId();
    }

    /**
     * 查询某 RSPU 的所有工厂关联（可按发货地省份筛选）。
     *
     * @param rspuId   RSPU ID
     * @param province 发货省份，null 表示不限
     * @return 关联列表
     */
    public List<RspuFactoryMappingResponse> listByRspu(String rspuId, String province) {
        List<RspuFactoryMapping> mappings = mappingMapper.selectActiveByRspuAndProvince(rspuId, province);
        return enrichAndConvert(mappings);
    }

    /**
     * 查询某工厂关联的所有 RSPU。
     *
     * @param factoryCode 工厂代码
     * @return 关联列表
     */
    public List<RspuFactoryMappingResponse> listByFactory(String factoryCode) {
        List<RspuFactoryMapping> mappings = mappingMapper.selectList(
            new QueryWrapper<RspuFactoryMapping>()
                .eq("factory_code", factoryCode)
                .orderByDesc("created_at")
        );
        return enrichAndConvert(mappings);
    }

    /**
     * 删除关联。
     *
     * @param mappingId 关联 ID
     */
    @Transactional
    public void deleteMapping(Long mappingId) {
        RspuFactoryMapping mapping = mappingMapper.selectById(mappingId);
        if (mapping == null) {
            throw new ResourceNotFoundException("关联记录不存在: " + mappingId);
        }
        dataScopeHelper.assertCanAccessRspu(mapping.getRspuId());
        mappingMapper.deleteById(mappingId);
        auditLogService.logDelete("rspu_factory_mapping", String.valueOf(mappingId), mapping,
            SecurityOperatorContext.currentUsername());
    }

    private void validateRequest(RspuFactoryMappingRequest request) {
        FactoryMaster factory = factoryMasterMapper.selectById(request.getFactoryCode());
        if (factory == null) {
            throw new BusinessException("工厂不存在: " + request.getFactoryCode());
        }
        if (StringUtils.hasText(request.getShippingWarehouseId())) {
            FactoryWarehouse warehouse = warehouseMapper.selectById(request.getShippingWarehouseId());
            if (warehouse == null) {
                throw new BusinessException("发货仓库不存在: " + request.getShippingWarehouseId());
            }
            if (!request.getFactoryCode().equals(warehouse.getFactoryCode())) {
                throw new BusinessException("发货仓库不属于该工厂");
            }
        }
    }

    private void clearOtherPrimary(String rspuId, Long excludeMappingId) {
        List<RspuFactoryMapping> primaries = mappingMapper.selectList(
            new QueryWrapper<RspuFactoryMapping>()
                .eq("rspu_id", rspuId)
                .eq("is_primary", true)
        );
        for (RspuFactoryMapping m : primaries) {
            if (excludeMappingId != null && excludeMappingId.equals(m.getMappingId())) {
                continue;
            }
            m.setIsPrimary(false);
            m.setUpdatedAt(LocalDateTime.now());
            mappingMapper.updateById(m);
        }
    }

    private List<RspuFactoryMappingResponse> enrichAndConvert(List<RspuFactoryMapping> mappings) {
        Set<String> factoryCodes = mappings.stream()
            .map(RspuFactoryMapping::getFactoryCode)
            .collect(Collectors.toSet());
        Set<String> warehouseIds = mappings.stream()
            .map(RspuFactoryMapping::getShippingWarehouseId)
            .filter(StringUtils::hasText)
            .collect(Collectors.toSet());

        Map<String, FactoryMaster> factoryMap = factoryCodes.isEmpty() ? new java.util.HashMap<>()
            : factoryMasterMapper.selectBatchIds(factoryCodes).stream()
                .collect(Collectors.toMap(FactoryMaster::getFactoryCode, f -> f, (a, b) -> a, java.util.HashMap::new));
        Map<String, FactoryWarehouse> warehouseMap = warehouseIds.isEmpty() ? new java.util.HashMap<>()
            : warehouseMapper.selectBatchIds(warehouseIds).stream()
                .collect(Collectors.toMap(FactoryWarehouse::getWarehouseId, w -> w, (a, b) -> a, java.util.HashMap::new));

        return mappings.stream()
            .map(m -> toResponse(m, factoryMap.get(m.getFactoryCode()), warehouseMap.get(m.getShippingWarehouseId())))
            .toList();
    }

    private RspuFactoryMappingResponse toResponse(RspuFactoryMapping mapping,
                                                   FactoryMaster factory,
                                                   FactoryWarehouse warehouse) {
        RspuFactoryMappingResponse response = new RspuFactoryMappingResponse();
        response.setMappingId(mapping.getMappingId());
        response.setRspuId(mapping.getRspuId());
        response.setFactoryCode(mapping.getFactoryCode());
        response.setFactoryName(factory != null ? factory.getFactoryName() : null);
        response.setFactoryLevel(factory != null ? factory.getFactoryLevel() : null);
        response.setIsPrimary(mapping.getIsPrimary());
        response.setShippingWarehouseId(mapping.getShippingWarehouseId());
        response.setWarehouseName(warehouse != null ? warehouse.getWarehouseName() : null);
        response.setProvince(warehouse != null ? warehouse.getProvince() : null);
        response.setCity(warehouse != null ? warehouse.getCity() : null);
        response.setMoq(mapping.getMoq());
        response.setBaseLeadTimeDays(mapping.getBaseLeadTimeDays());
        response.setStatus(mapping.getStatus());
        response.setNotes(mapping.getNotes());
        response.setCreatedAt(mapping.getCreatedAt());
        response.setUpdatedAt(mapping.getUpdatedAt());
        return response;
    }
}
