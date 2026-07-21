package com.rsdp.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RspuFactoryMappingService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RspuFactoryMappingServiceTest {

    @Mock
    private RspuFactoryMappingMapper mappingMapper;

    @Mock
    private FactoryMasterMapper factoryMasterMapper;

    @Mock
    private FactoryWarehouseMapper warehouseMapper;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private DataScopeHelper dataScopeHelper;

    @InjectMocks
    private RspuFactoryMappingService mappingService;

    @BeforeEach
    void setUp() {
        doNothing().when(dataScopeHelper).assertCanAccessRspu(anyString());
    }

    @Test
    void saveMapping_shouldCreateWhenFactoryExistsAndNoDuplicate() {
        RspuFactoryMappingRequest request = new RspuFactoryMappingRequest();
        request.setRspuId("RSPU-001");
        request.setFactoryCode("F001");
        request.setIsPrimary(true);
        request.setMoq(10);
        request.setBaseLeadTimeDays(30);

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        when(factoryMasterMapper.selectById("F001")).thenReturn(factory);
        when(mappingMapper.selectCount(any())).thenReturn(0L);
        when(mappingMapper.insert(any(RspuFactoryMapping.class))).thenAnswer(inv -> {
            RspuFactoryMapping m = inv.getArgument(0);
            m.setMappingId(1L);
            return 1;
        });

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.currentUsername()).thenReturn("测试用户");

            Long id = mappingService.saveMapping(request);

            assertThat(id).isEqualTo(1L);
        }

        ArgumentCaptor<RspuFactoryMapping> captor = ArgumentCaptor.forClass(RspuFactoryMapping.class);
        verify(mappingMapper, times(1)).insert(captor.capture());
        RspuFactoryMapping saved = captor.getValue();
        assertThat(saved.getRspuId()).isEqualTo("RSPU-001");
        assertThat(saved.getFactoryCode()).isEqualTo("F001");
        assertThat(saved.getIsPrimary()).isTrue();
        assertThat(saved.getMoq()).isEqualTo(10);
    }

    @Test
    void saveMapping_shouldThrowWhenNoDataScopePermission() {
        RspuFactoryMappingRequest request = new RspuFactoryMappingRequest();
        request.setRspuId("RSPU-001");
        request.setFactoryCode("F001");

        doThrow(new BusinessException("只能维护本厂已报价的产品"))
            .when(dataScopeHelper).assertCanAccessRspu("RSPU-001");

        assertThatThrownBy(() -> mappingService.saveMapping(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("只能维护本厂已报价的产品");
    }

    @Test
    void saveMapping_shouldThrowWhenFactoryNotExists() {
        RspuFactoryMappingRequest request = new RspuFactoryMappingRequest();
        request.setRspuId("RSPU-001");
        request.setFactoryCode("F999");

        when(factoryMasterMapper.selectById("F999")).thenReturn(null);

        assertThatThrownBy(() -> mappingService.saveMapping(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("工厂不存在");
    }

    @Test
    void saveMapping_shouldThrowWhenDuplicate() {
        RspuFactoryMappingRequest request = new RspuFactoryMappingRequest();
        request.setRspuId("RSPU-001");
        request.setFactoryCode("F001");

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        when(factoryMasterMapper.selectById("F001")).thenReturn(factory);
        when(mappingMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> mappingService.saveMapping(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已关联此工厂");
    }

    @Test
    void saveMapping_shouldClearOtherPrimary() {
        RspuFactoryMappingRequest request = new RspuFactoryMappingRequest();
        request.setRspuId("RSPU-001");
        request.setFactoryCode("F002");
        request.setIsPrimary(true);

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F002");

        RspuFactoryMapping oldPrimary = new RspuFactoryMapping();
        oldPrimary.setMappingId(9L);
        oldPrimary.setRspuId("RSPU-001");
        oldPrimary.setFactoryCode("F001");
        oldPrimary.setIsPrimary(true);

        when(factoryMasterMapper.selectById("F002")).thenReturn(factory);
        when(mappingMapper.selectCount(any())).thenReturn(0L);
        when(mappingMapper.selectList(any())).thenReturn(List.of(oldPrimary));
        when(mappingMapper.insert(any(RspuFactoryMapping.class))).thenAnswer(inv -> {
            RspuFactoryMapping m = inv.getArgument(0);
            m.setMappingId(2L);
            return 1;
        });

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.currentUsername()).thenReturn("测试用户");

            mappingService.saveMapping(request);
        }

        ArgumentCaptor<RspuFactoryMapping> captor = ArgumentCaptor.forClass(RspuFactoryMapping.class);
        verify(mappingMapper, times(1)).updateById(captor.capture());
        assertThat(captor.getValue().getIsPrimary()).isFalse();
    }

    @Test
    void saveMapping_shouldValidateWarehouseBelongsToFactory() {
        RspuFactoryMappingRequest request = new RspuFactoryMappingRequest();
        request.setRspuId("RSPU-001");
        request.setFactoryCode("F001");
        request.setShippingWarehouseId("W001");

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");

        FactoryWarehouse warehouse = new FactoryWarehouse();
        warehouse.setWarehouseId("W001");
        warehouse.setFactoryCode("F002");

        when(factoryMasterMapper.selectById("F001")).thenReturn(factory);
        when(warehouseMapper.selectById("W001")).thenReturn(warehouse);

        assertThatThrownBy(() -> mappingService.saveMapping(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("发货仓库不属于该工厂");
    }

    @Test
    void listByRspu_shouldFilterByProvince() {
        FactoryWarehouse warehouse = new FactoryWarehouse();
        warehouse.setWarehouseId("W001");
        warehouse.setFactoryCode("F001");
        warehouse.setProvince("广东");
        warehouse.setCity("佛山");

        RspuFactoryMapping mapping = new RspuFactoryMapping();
        mapping.setMappingId(1L);
        mapping.setRspuId("RSPU-001");
        mapping.setFactoryCode("F001");
        mapping.setShippingWarehouseId("W001");

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        when(mappingMapper.selectActiveByRspuAndProvince("RSPU-001", "广东")).thenReturn(List.of(mapping));
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));
        when(warehouseMapper.selectBatchIds(any())).thenReturn(List.of(warehouse));

        List<RspuFactoryMappingResponse> result = mappingService.listByRspu("RSPU-001", "广东");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProvince()).isEqualTo("广东");
        assertThat(result.get(0).getFactoryName()).isEqualTo("测试工厂");
    }

    @Test
    void listByFactory_shouldReturnMappings() {
        RspuFactoryMapping mapping = new RspuFactoryMapping();
        mapping.setMappingId(1L);
        mapping.setRspuId("RSPU-001");
        mapping.setFactoryCode("F001");

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        when(mappingMapper.selectList(any())).thenReturn(List.of(mapping));
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));

        List<RspuFactoryMappingResponse> result = mappingService.listByFactory("F001");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRspuId()).isEqualTo("RSPU-001");
    }

    @Test
    void deleteMapping_shouldDeleteAndLog() {
        RspuFactoryMapping mapping = new RspuFactoryMapping();
        mapping.setMappingId(1L);

        when(mappingMapper.selectById(1L)).thenReturn(mapping);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUsername()).thenReturn("测试用户");

            mappingService.deleteMapping(1L);
        }

        verify(mappingMapper, times(1)).deleteById(1L);
        verify(auditLogService, times(1)).logDelete(eq("rspu_factory_mapping"), eq("1"), eq(mapping), any());
    }

    @Test
    void deleteMapping_shouldThrowWhenNotFound() {
        when(mappingMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> mappingService.deleteMapping(999L))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
