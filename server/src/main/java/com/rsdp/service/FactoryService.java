package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.request.FactoryCreateRequest;
import com.rsdp.dto.request.FactoryLevelCapabilityUpdateRequest;
import com.rsdp.dto.response.FactoryResponse;
import com.rsdp.entity.FactoryLevelCapability;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FactoryLevelCapabilityMapper;
import com.rsdp.mapper.FactoryMasterMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工厂档案服务。
 */
@Service
@RequiredArgsConstructor
public class FactoryService {

    private final FactoryMasterMapper factoryMasterMapper;
    private final FactoryLevelCapabilityMapper capabilityMapper;
    private final DictService dictService;
    private final AuditLogService auditLogService;

    /**
     * 查询所有有效工厂。
     *
     * @return 工厂列表
     */
    public List<FactoryResponse> listFactories() {
        List<FactoryMaster> factories = factoryMasterMapper.selectList(
            new QueryWrapper<FactoryMaster>()
                .eq("status", "active")
                .isNull("deleted_at")
                .orderByDesc("created_at")
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
    @Transactional
    public void createFactory(FactoryCreateRequest request) {
        if (factoryMasterMapper.selectById(request.getFactoryCode()) != null) {
            throw new BusinessException("工厂代码已存在: " + request.getFactoryCode());
        }

        String primaryLevel = request.getFactoryLevel();
        validateFactoryLevel(primaryLevel);

        Set<String> capableLevels = normalizeCapableLevels(request.getCapableLevels(), primaryLevel);
        capableLevels.forEach(this::validateFactoryLevel);

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode(request.getFactoryCode());
        factory.setFactoryName(request.getFactoryName());
        factory.setFactoryLevel(primaryLevel);
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

        saveCapabilities(factory.getFactoryCode(), primaryLevel, capableLevels);

        auditLogService.logCreate("factory_master", factory.getFactoryCode(), factory, "admin");
    }

    /**
     * 更新工厂主等级。
     *
     * @param factoryCode 工厂代码
     * @param newLevel    新主等级，如 S/A/B/C
     */
    @Transactional
    public void updateFactoryLevel(String factoryCode, String newLevel) {
        FactoryMaster factory = factoryMasterMapper.selectById(factoryCode);
        if (factory == null || factory.getDeletedAt() != null) {
            throw new ResourceNotFoundException("工厂不存在: " + factoryCode);
        }
        if (newLevel == null || newLevel.isBlank()) {
            throw new BusinessException("工厂等级不能为空");
        }
        validateFactoryLevel(newLevel);

        FactoryMaster oldSnapshot = snapshot(factory);
        factory.setFactoryLevel(newLevel);
        factory.setUpdatedAt(LocalDateTime.now());
        factoryMasterMapper.updateById(factory);

        syncPrimaryCapability(factoryCode, newLevel);

        auditLogService.logUpdate("factory_master", factoryCode, oldSnapshot, factory, "admin");
    }

    /**
     * 更新工厂兼做等级列表。
     *
     * @param factoryCode 工厂代码
     * @param request     兼做等级更新请求
     */
    @Transactional
    public void updateCapableLevels(String factoryCode, FactoryLevelCapabilityUpdateRequest request) {
        FactoryMaster factory = factoryMasterMapper.selectById(factoryCode);
        if (factory == null || factory.getDeletedAt() != null) {
            throw new ResourceNotFoundException("工厂不存在: " + factoryCode);
        }

        String primaryLevel = factory.getFactoryLevel();
        Set<String> capableLevels = normalizeCapableLevels(request.getCapableLevels(), primaryLevel);
        capableLevels.forEach(this::validateFactoryLevel);

        saveCapabilities(factoryCode, primaryLevel, capableLevels);
    }

    private Set<String> normalizeCapableLevels(List<String> capableLevels, String primaryLevel) {
        Set<String> levels = new HashSet<>();
        if (capableLevels != null) {
            levels.addAll(capableLevels);
        }
        levels.add(primaryLevel);
        return levels;
    }

    private void saveCapabilities(String factoryCode, String primaryLevel, Set<String> capableLevels) {
        capabilityMapper.delete(
            new QueryWrapper<FactoryLevelCapability>().eq("factory_code", factoryCode)
        );

        LocalDateTime now = LocalDateTime.now();
        for (String level : capableLevels) {
            FactoryLevelCapability capability = new FactoryLevelCapability();
            capability.setFactoryCode(factoryCode);
            capability.setLevelCode(level);
            capability.setIsPrimary(level.equals(primaryLevel));
            capability.setCreatedAt(now);
            capabilityMapper.insert(capability);
        }
    }

    private void syncPrimaryCapability(String factoryCode, String newPrimaryLevel) {
        List<FactoryLevelCapability> capabilities = capabilityMapper.selectList(
            new QueryWrapper<FactoryLevelCapability>().eq("factory_code", factoryCode)
        );

        // 若 capability 表为空（老数据），则把新主等级作为唯一能力写入
        if (capabilities.isEmpty()) {
            FactoryLevelCapability capability = new FactoryLevelCapability();
            capability.setFactoryCode(factoryCode);
            capability.setLevelCode(newPrimaryLevel);
            capability.setIsPrimary(true);
            capability.setCreatedAt(LocalDateTime.now());
            capabilityMapper.insert(capability);
            return;
        }

        for (FactoryLevelCapability capability : capabilities) {
            boolean shouldBePrimary = capability.getLevelCode().equals(newPrimaryLevel);
            if (Boolean.TRUE.equals(capability.getIsPrimary()) != shouldBePrimary) {
                capability.setIsPrimary(shouldBePrimary);
                capabilityMapper.updateById(capability);
            }
        }

        // 确保新主等级一定在 capability 表中
        boolean primaryExists = capabilities.stream()
            .anyMatch(c -> c.getLevelCode().equals(newPrimaryLevel));
        if (!primaryExists) {
            FactoryLevelCapability capability = new FactoryLevelCapability();
            capability.setFactoryCode(factoryCode);
            capability.setLevelCode(newPrimaryLevel);
            capability.setIsPrimary(true);
            capability.setCreatedAt(LocalDateTime.now());
            capabilityMapper.insert(capability);
        }
    }

    private void validateFactoryLevel(String level) {
        boolean exists = dictService.listByType("factory_level").stream()
            .anyMatch(d -> level.equals(d.getDictCode()) || level.equals(d.getDictName()));
        if (!exists) {
            throw new BusinessException("工厂等级不存在: " + level);
        }
    }

    private FactoryMaster snapshot(FactoryMaster source) {
        FactoryMaster copy = new FactoryMaster();
        copy.setFactoryCode(source.getFactoryCode());
        copy.setFactoryName(source.getFactoryName());
        copy.setFactoryLevel(source.getFactoryLevel());
        copy.setHomeCommercialTag(source.getHomeCommercialTag());
        copy.setRegion(source.getRegion());
        copy.setAddress(source.getAddress());
        copy.setContactPerson(source.getContactPerson());
        copy.setContactPhone(source.getContactPhone());
        copy.setNotes(source.getNotes());
        copy.setStatus(source.getStatus());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private FactoryResponse toResponse(FactoryMaster factory) {
        FactoryResponse response = new FactoryResponse();
        response.setFactoryCode(factory.getFactoryCode());
        response.setFactoryName(factory.getFactoryName());
        response.setFactoryLevel(factory.getFactoryLevel());
        response.setCapableLevels(listCapableLevels(factory.getFactoryCode()));
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

    /**
     * 查询工厂的能力等级列表。
     *
     * @param factoryCode 工厂代码
     * @return 能力等级代码列表
     */
    public List<String> getFactoryCapableLevels(String factoryCode) {
        FactoryMaster factory = factoryMasterMapper.selectById(factoryCode);
        if (factory == null || factory.getDeletedAt() != null) {
            throw new ResourceNotFoundException("工厂不存在: " + factoryCode);
        }

        List<String> levels = listCapableLevels(factoryCode);
        // 老数据兼容： capability 表无记录时，默认至少具备主等级能力
        if (levels.isEmpty()) {
            return List.of(factory.getFactoryLevel());
        }
        return levels;
    }

    /**
     * 为工厂扩展一个能力等级。
     *
     * @param factoryCode 工厂代码
     * @param level       等级代码
     */
    @Transactional
    public void extendCapability(String factoryCode, String level) {
        FactoryMaster factory = factoryMasterMapper.selectById(factoryCode);
        if (factory == null || factory.getDeletedAt() != null) {
            throw new ResourceNotFoundException("工厂不存在: " + factoryCode);
        }
        validateFactoryLevel(level);

        FactoryLevelCapability existing = capabilityMapper.selectOne(
            new QueryWrapper<FactoryLevelCapability>()
                .eq("factory_code", factoryCode)
                .eq("level_code", level)
        );
        if (existing != null) {
            return;
        }

        // 老数据兼容：若 capability 表为空，先把工厂主等级写入，避免扩展后丢失主等级
        long capabilityCount = capabilityMapper.selectCount(
            new QueryWrapper<FactoryLevelCapability>().eq("factory_code", factoryCode)
        );
        if (capabilityCount == 0) {
            FactoryLevelCapability primary = new FactoryLevelCapability();
            primary.setFactoryCode(factoryCode);
            primary.setLevelCode(factory.getFactoryLevel());
            primary.setIsPrimary(true);
            primary.setCreatedAt(LocalDateTime.now());
            capabilityMapper.insert(primary);
        }

        FactoryLevelCapability capability = new FactoryLevelCapability();
        capability.setFactoryCode(factoryCode);
        capability.setLevelCode(level);
        capability.setIsPrimary(level.equals(factory.getFactoryLevel()));
        capability.setCreatedAt(LocalDateTime.now());
        capabilityMapper.insert(capability);
    }

    private static final List<String> LEVEL_ORDER = List.of("S", "A", "B", "C");

    private List<String> listCapableLevels(String factoryCode) {
        List<String> levels = capabilityMapper.selectList(
            new QueryWrapper<FactoryLevelCapability>()
                .eq("factory_code", factoryCode)
        ).stream()
            .map(FactoryLevelCapability::getLevelCode)
            .distinct()
            .collect(Collectors.toList());

        // 按业务等级 S > A > B > C 排序，未定义的等级放末尾
        levels.sort((a, b) -> {
            int idxA = LEVEL_ORDER.indexOf(a);
            int idxB = LEVEL_ORDER.indexOf(b);
            if (idxA >= 0 && idxB >= 0) {
                return Integer.compare(idxA, idxB);
            }
            return idxA >= 0 ? -1 : (idxB >= 0 ? 1 : a.compareTo(b));
        });
        return levels;
    }
}
