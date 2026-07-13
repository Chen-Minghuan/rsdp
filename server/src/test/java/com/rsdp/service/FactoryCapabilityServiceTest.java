package com.rsdp.service;

import com.rsdp.dto.FactoryCapabilitySource;
import com.rsdp.dto.request.FactoryProductCapabilityCreateRequest;
import com.rsdp.dto.request.FactoryProductCapabilityUpdateRequest;
import com.rsdp.dto.response.FactoryProductCapabilityResponse;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.FactoryProductCapability;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.FactoryProductCapabilityMapper;
import com.rsdp.security.datascope.DataScopeHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.lenient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FactoryCapabilityService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class FactoryCapabilityServiceTest {

    @Mock
    private FactoryProductCapabilityMapper capabilityMapper;

    @Mock
    private FactoryMasterMapper factoryMasterMapper;

    @Mock
    private DataScopeHelper dataScopeHelper;

    private FactoryCapabilityService factoryCapabilityService;

    @BeforeEach
    void setUp() {
        factoryCapabilityService = new FactoryCapabilityService(capabilityMapper, factoryMasterMapper, dataScopeHelper);
        lenient().when(dataScopeHelper.canAccessFactory(any())).thenReturn(true);
    }

    @Test
    void listByFactory_shouldReturnSortedCapabilities() {
        when(factoryMasterMapper.selectById("F001")).thenReturn(new FactoryMaster());
        FactoryProductCapability cap = new FactoryProductCapability();
        cap.setId(1L);
        cap.setFactoryCode("F001");
        cap.setCategoryCode("FS");
        cap.setStyleCode("MC");
        cap.setMaterialCode("PE");
        when(capabilityMapper.selectList(any())).thenReturn(List.of(cap));

        List<FactoryProductCapabilityResponse> result = factoryCapabilityService.listByFactory("F001");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCategoryCode()).isEqualTo("FS");
    }

    @Test
    void create_shouldSaveCapability() {
        when(factoryMasterMapper.selectById("F001")).thenReturn(new FactoryMaster());
        when(capabilityMapper.selectCount(any())).thenReturn(0L);
        when(capabilityMapper.insert(any(FactoryProductCapability.class))).thenAnswer(inv -> {
            FactoryProductCapability c = inv.getArgument(0);
            c.setId(1L);
            return 1;
        });

        FactoryProductCapabilityCreateRequest request = new FactoryProductCapabilityCreateRequest();
        request.setFactoryCode("F001");
        request.setCategoryCode("FS");
        request.setStyleCode("MC");

        FactoryProductCapabilityResponse response = factoryCapabilityService.create(request);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getFactoryCode()).isEqualTo("F001");
        verify(capabilityMapper).insert(any(FactoryProductCapability.class));
    }

    @Test
    void create_shouldThrowWhenFactoryNotExists() {
        when(factoryMasterMapper.selectById("F001")).thenReturn(null);

        FactoryProductCapabilityCreateRequest request = new FactoryProductCapabilityCreateRequest();
        request.setFactoryCode("F001");
        request.setCategoryCode("FS");

        assertThatThrownBy(() -> factoryCapabilityService.create(request))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("工厂不存在");
    }

    @Test
    void update_shouldModifyCapability() {
        FactoryProductCapability existing = new FactoryProductCapability();
        existing.setId(1L);
        existing.setFactoryCode("F001");
        existing.setCategoryCode("FS");

        when(capabilityMapper.selectById(1L)).thenReturn(existing);
        when(capabilityMapper.selectCount(any())).thenReturn(0L);

        FactoryProductCapabilityUpdateRequest request = new FactoryProductCapabilityUpdateRequest();
        request.setCategoryCode("SF");

        FactoryProductCapabilityResponse response = factoryCapabilityService.update(1L, request);

        assertThat(response.getCategoryCode()).isEqualTo("SF");
        verify(capabilityMapper).updateById(any(FactoryProductCapability.class));
    }

    @Test
    void delete_shouldRemoveCapability() {
        FactoryProductCapability existing = new FactoryProductCapability();
        existing.setId(1L);
        when(capabilityMapper.selectById(1L)).thenReturn(existing);

        factoryCapabilityService.delete(1L);

        verify(capabilityMapper).deleteById(1L);
    }

    @Test
    void syncByFactory_shouldReplaceAndReturnCapabilities() {
        when(factoryMasterMapper.selectById("F001")).thenReturn(new FactoryMaster());
        FactoryCapabilitySource source = new FactoryCapabilitySource();
        source.setFactoryCode("F001");
        source.setCategoryCode("FS");
        source.setStyleCode("MC");
        source.setMaterialCode("PE");
        when(capabilityMapper.selectCapabilitySourcesByFactory("F001")).thenReturn(List.of(source));
        when(capabilityMapper.selectList(any())).thenReturn(List.of());

        List<FactoryProductCapabilityResponse> result = factoryCapabilityService.syncByFactory("F001");

        assertThat(result).isEmpty();
        verify(capabilityMapper).deleteByFactoryCode("F001");
        verify(capabilityMapper).insertBatchIgnoreConflict(any());
    }
}
