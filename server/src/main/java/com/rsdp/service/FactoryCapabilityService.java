package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.exception.BusinessException;
import com.rsdp.security.datascope.DataScopeHelper;
import com.rsdp.dto.FactoryCapabilitySource;
import com.rsdp.dto.request.FactoryProductCapabilityCreateRequest;
import com.rsdp.dto.request.FactoryProductCapabilityUpdateRequest;
import com.rsdp.dto.response.FactoryProductCapabilityResponse;
import com.rsdp.entity.FactoryProductCapability;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.FactoryProductCapabilityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工厂产品能力档案服务。
 *
 * <p>能力行支持从工厂已有 RSKU 自动同步，也支持管理员手工增删改。</p>
 */
@Service
@RequiredArgsConstructor
public class FactoryCapabilityService {

    private final FactoryProductCapabilityMapper capabilityMapper;
    private final FactoryMasterMapper factoryMasterMapper;
    private final DataScopeHelper dataScopeHelper;

    /**
     * 查询指定工厂的能力档案列表。
     *
     * @param factoryCode 工厂编码
     * @return 能力行响应列表
     */
    public List<FactoryProductCapabilityResponse> listByFactory(String factoryCode) {
        assertFactoryExists(factoryCode);
        return capabilityMapper.selectList(
            new QueryWrapper<FactoryProductCapability>()
                .eq("factory_code", factoryCode)
                .orderByAsc("category_code", "style_code", "material_code")
        ).stream().map(this::toResponse).toList();
    }

    /**
     * 查询单条能力档案。
     *
     * @param id 能力档案 ID
     * @return 能力行响应
     */
    public FactoryProductCapabilityResponse getById(Long id) {
        FactoryProductCapability cap = capabilityMapper.selectById(id);
        if (cap == null) {
            throw new ResourceNotFoundException("能力档案不存在: " + id);
        }
        return toResponse(cap);
    }

    /**
     * 手工创建能力档案。
     *
     * @param request 创建请求
     * @return 创建后的能力行响应
     */
    @Transactional
    public FactoryProductCapabilityResponse create(FactoryProductCapabilityCreateRequest request) {
        assertFactoryExists(request.getFactoryCode());
        assertCanMaintainCapability(request.getFactoryCode());
        assertUnique(request.getFactoryCode(), request.getCategoryCode(),
            request.getStyleCode(), request.getMaterialCode(), null);

        FactoryProductCapability cap = new FactoryProductCapability();
        cap.setFactoryCode(request.getFactoryCode());
        cap.setCategoryCode(request.getCategoryCode());
        cap.setStyleCode(request.getStyleCode());
        cap.setMaterialCode(request.getMaterialCode());
        cap.setCreatedAt(LocalDateTime.now());
        cap.setUpdatedAt(LocalDateTime.now());
        capabilityMapper.insert(cap);
        return toResponse(cap);
    }

    /**
     * 更新能力档案。
     *
     * @param id      能力档案 ID
     * @param request 更新请求
     * @return 更新后的能力行响应
     */
    @Transactional
    public FactoryProductCapabilityResponse update(Long id, FactoryProductCapabilityUpdateRequest request) {
        FactoryProductCapability cap = capabilityMapper.selectById(id);
        if (cap == null) {
            throw new ResourceNotFoundException("能力档案不存在: " + id);
        }
        assertCanMaintainCapability(cap.getFactoryCode());
        assertUnique(cap.getFactoryCode(), request.getCategoryCode(),
            request.getStyleCode(), request.getMaterialCode(), id);

        cap.setCategoryCode(request.getCategoryCode());
        cap.setStyleCode(request.getStyleCode());
        cap.setMaterialCode(request.getMaterialCode());
        cap.setUpdatedAt(LocalDateTime.now());
        capabilityMapper.updateById(cap);
        return toResponse(cap);
    }

    /**
     * 删除能力档案。
     *
     * @param id 能力档案 ID
     */
    @Transactional
    public void delete(Long id) {
        FactoryProductCapability cap = capabilityMapper.selectById(id);
        if (cap == null) {
            throw new ResourceNotFoundException("能力档案不存在: " + id);
        }
        assertCanMaintainCapability(cap.getFactoryCode());
        capabilityMapper.deleteById(id);
    }

    /**
     * 手动同步指定工厂的能力档案。
     *
     * <p>先清空旧记录，再根据当前有效 RSKU 重新生成。</p>
     *
     * @param factoryCode 工厂编码
     * @return 同步后的能力档案列表
     */
    @Transactional
    public List<FactoryProductCapabilityResponse> syncByFactory(String factoryCode) {
        assertFactoryExists(factoryCode);
        assertCanMaintainCapability(factoryCode);

        List<FactoryCapabilitySource> sources = capabilityMapper.selectCapabilitySourcesByFactory(factoryCode);
        List<FactoryProductCapability> capabilities = sources.stream()
            .map(src -> {
                FactoryProductCapability cap = new FactoryProductCapability();
                cap.setFactoryCode(src.getFactoryCode());
                cap.setCategoryCode(src.getCategoryCode());
                cap.setStyleCode(src.getStyleCode());
                cap.setMaterialCode(src.getMaterialCode());
                cap.setCreatedAt(LocalDateTime.now());
                cap.setUpdatedAt(LocalDateTime.now());
                return cap;
            })
            .distinct()
            .toList();

        capabilityMapper.deleteByFactoryCode(factoryCode);
        if (!capabilities.isEmpty()) {
            capabilityMapper.insertBatchIgnoreConflict(capabilities);
        }

        return listByFactory(factoryCode);
    }

    private void assertFactoryExists(String factoryCode) {
        if (factoryMasterMapper.selectById(factoryCode) == null) {
            throw new ResourceNotFoundException("工厂不存在: " + factoryCode);
        }
    }

    private void assertCanMaintainCapability(String factoryCode) {
        if (!dataScopeHelper.canAccessFactory(factoryCode)) {
            throw new BusinessException("无权维护该工厂能力档案: " + factoryCode);
        }
    }

    private void assertUnique(String factoryCode, String categoryCode,
                              String styleCode, String materialCode, Long excludeId) {
        QueryWrapper<FactoryProductCapability> wrapper = new QueryWrapper<FactoryProductCapability>()
            .eq("factory_code", factoryCode)
            .eq("category_code", categoryCode);
        if (styleCode != null) {
            wrapper.eq("style_code", styleCode);
        } else {
            wrapper.isNull("style_code");
        }
        if (materialCode != null) {
            wrapper.eq("material_code", materialCode);
        } else {
            wrapper.isNull("material_code");
        }
        if (excludeId != null) {
            wrapper.ne("id", excludeId);
        }
        Long count = capabilityMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException("已存在相同的能力档案组合");
        }
    }

    private FactoryProductCapabilityResponse toResponse(FactoryProductCapability cap) {
        FactoryProductCapabilityResponse response = new FactoryProductCapabilityResponse();
        response.setId(cap.getId());
        response.setFactoryCode(cap.getFactoryCode());
        response.setCategoryCode(cap.getCategoryCode());
        response.setStyleCode(cap.getStyleCode());
        response.setMaterialCode(cap.getMaterialCode());
        response.setCreatedAt(cap.getCreatedAt());
        response.setUpdatedAt(cap.getUpdatedAt());
        return response;
    }
}
