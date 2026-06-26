package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.request.FactoryCreateRequest;
import com.rsdp.dto.response.FactoryResponse;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FactoryMasterMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 工厂档案服务。
 */
@Service
@RequiredArgsConstructor
public class FactoryService {

    private final FactoryMasterMapper factoryMasterMapper;
    private final AuditLogService auditLogService;

    /**
     * 查询所有有效工厂。
     *
     * @return 工厂列表
     */
    public List<FactoryResponse> listFactories() {
        List<FactoryMaster> factories = factoryMasterMapper.selectList(
            new QueryWrapper<FactoryMaster>().eq("status", "active").isNull("deleted_at").orderByDesc("created_at")
        );
        return factories.stream().map(this::toResponse).collect(Collectors.toList());
    }

    /**
     * 查询工厂详情。
     *
     * @param factoryCode 工厂代码
     * @return 工厂详情
     */
    public FactoryResponse getFactory(String factoryCode) {
        FactoryMaster factory = factoryMasterMapper.selectById(factoryCode);
        if (factory == null || factory.getDeletedAt() != null) {
            throw new ResourceNotFoundException("工厂不存在: " + factoryCode);
        }
        return toResponse(factory);
    }

    /**
     * 创建工厂。
     *
     * @param request 创建请求
     */
    public void createFactory(FactoryCreateRequest request) {
        if (factoryMasterMapper.selectById(request.getFactoryCode()) != null) {
            throw new BusinessException("工厂代码已存在: " + request.getFactoryCode());
        }

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode(request.getFactoryCode());
        factory.setFactoryName(request.getFactoryName());
        factory.setFactoryLevel(request.getFactoryLevel());
        factory.setHomeCommercialTag(request.getHomeCommercialTag());
        factory.setRegion(request.getRegion());
        factory.setAddress(request.getAddress());
        factory.setContactPerson(request.getContactPerson());
        factory.setContactPhone(request.getContactPhone());
        factory.setNotes(request.getNotes());
        factory.setStatus("active");
        factory.setCreatedAt(LocalDateTime.now());
        factory.setUpdatedAt(LocalDateTime.now());
        factoryMasterMapper.insert(factory);

        auditLogService.logCreate("factory_master", factory.getFactoryCode(), factory, "admin");
    }

    private FactoryResponse toResponse(FactoryMaster factory) {
        FactoryResponse response = new FactoryResponse();
        response.setFactoryCode(factory.getFactoryCode());
        response.setFactoryName(factory.getFactoryName());
        response.setFactoryLevel(factory.getFactoryLevel());
        response.setHomeCommercialTag(factory.getHomeCommercialTag());
        response.setRegion(factory.getRegion());
        response.setAddress(factory.getAddress());
        response.setContactPerson(factory.getContactPerson());
        response.setContactPhone(factory.getContactPhone());
        response.setNotes(factory.getNotes());
        response.setStatus(factory.getStatus());
        response.setCreatedAt(factory.getCreatedAt());
        response.setUpdatedAt(factory.getUpdatedAt());
        return response;
    }
}
