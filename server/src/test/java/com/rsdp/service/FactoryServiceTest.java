package com.rsdp.service;

import com.rsdp.dto.request.FactoryCreateRequest;
import com.rsdp.dto.request.FactoryLevelCapabilityUpdateRequest;
import com.rsdp.dto.request.FactoryUpdateRequest;
import com.rsdp.dto.response.FactoryResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.FactoryLevelCapability;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FactoryLevelCapabilityMapper;
import com.rsdp.mapper.FactoryMasterMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link FactoryService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class FactoryServiceTest {

    @Mock
    private FactoryMasterMapper factoryMasterMapper;

    @Mock
    private FactoryLevelCapabilityMapper capabilityMapper;

    @Mock
    private DictService dictService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private FactoryService factoryService;

    private List<CategoryDict> factoryLevelDicts() {
        CategoryDict s = new CategoryDict();
        s.setDictType("factory_level");
        s.setDictCode("S");
        s.setDictName("S级");
        CategoryDict a = new CategoryDict();
        a.setDictType("factory_level");
        a.setDictCode("A");
        a.setDictName("A级");
        return List.of(s, a);
    }

    @Test
    void listFactories_shouldReturnActiveFactories() {
        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");
        factory.setStatus("active");

        when(factoryMasterMapper.selectList(any())).thenReturn(List.of(factory));
        when(capabilityMapper.selectList(any())).thenReturn(List.of());

        List<FactoryResponse> result = factoryService.listFactories();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFactoryCode()).isEqualTo("F001");
    }

    @Test
    void createFactory_shouldInsertWhenCodeNotExists() {
        FactoryCreateRequest request = new FactoryCreateRequest();
        request.setFactoryCode("F001");
        request.setFactoryName("测试工厂");
        request.setFactoryLevel("S");
        request.setCertification("[\"ISO9001\"]");
        request.setEngineeringCases("[{\"name\":\"案例1\"}]");

        when(factoryMasterMapper.selectById("F001")).thenReturn(null);
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());

        factoryService.createFactory(request);

        ArgumentCaptor<FactoryMaster> factoryCaptor = ArgumentCaptor.forClass(FactoryMaster.class);
        verify(factoryMasterMapper, times(1)).insert(factoryCaptor.capture());
        assertThat(factoryCaptor.getValue().getCertification()).isEqualTo("[\"ISO9001\"]");
        assertThat(factoryCaptor.getValue().getEngineeringCases()).isEqualTo("[{\"name\":\"案例1\"}]");

        ArgumentCaptor<FactoryLevelCapability> captor = ArgumentCaptor.forClass(FactoryLevelCapability.class);
        verify(capabilityMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getLevelCode()).isEqualTo("S");
        assertThat(captor.getValue().getIsPrimary()).isTrue();
    }

    @Test
    void createFactory_withCapableLevels_shouldInsertAllCapabilities() {
        FactoryCreateRequest request = new FactoryCreateRequest();
        request.setFactoryCode("F001");
        request.setFactoryName("测试工厂");
        request.setFactoryLevel("S");
        request.setCapableLevels(List.of("A"));

        when(factoryMasterMapper.selectById("F001")).thenReturn(null);
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());

        factoryService.createFactory(request);

        verify(factoryMasterMapper, times(1)).insert(any(FactoryMaster.class));
        ArgumentCaptor<FactoryLevelCapability> captor = ArgumentCaptor.forClass(FactoryLevelCapability.class);
        verify(capabilityMapper, times(2)).insert(captor.capture());
        List<FactoryLevelCapability> capabilities = captor.getAllValues();
        assertThat(capabilities)
            .anyMatch(c -> "S".equals(c.getLevelCode()) && Boolean.TRUE.equals(c.getIsPrimary()))
            .anyMatch(c -> "A".equals(c.getLevelCode()) && Boolean.FALSE.equals(c.getIsPrimary()));
    }

    @Test
    void createFactory_shouldThrowWhenCodeExists() {
        FactoryCreateRequest request = new FactoryCreateRequest();
        request.setFactoryCode("F001");

        when(factoryMasterMapper.selectById("F001")).thenReturn(new FactoryMaster());

        assertThatThrownBy(() -> factoryService.createFactory(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("工厂代码已存在");
    }

    @Test
    void getFactory_shouldThrowWhenNotFound() {
        when(factoryMasterMapper.selectById("F999")).thenReturn(null);

        assertThatThrownBy(() -> factoryService.getFactory("F999"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateFactoryLevel_shouldUpdateLevelWithoutChangingCode() {
        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("B007");
        factory.setFactoryName("测试工厂");
        factory.setFactoryLevel("S");

        FactoryLevelCapability oldPrimary = new FactoryLevelCapability();
        oldPrimary.setFactoryCode("B007");
        oldPrimary.setLevelCode("S");
        oldPrimary.setIsPrimary(true);

        when(factoryMasterMapper.selectById("B007")).thenReturn(factory);
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(capabilityMapper.selectList(any())).thenReturn(List.of(oldPrimary));

        factoryService.updateFactoryLevel("B007", "A");

        assertThat(factory.getFactoryLevel()).isEqualTo("A");
        assertThat(factory.getFactoryCode()).isEqualTo("B007");
        verify(factoryMasterMapper).updateById(factory);

        ArgumentCaptor<FactoryLevelCapability> updateCaptor = ArgumentCaptor.forClass(FactoryLevelCapability.class);
        verify(capabilityMapper).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getLevelCode()).isEqualTo("S");
        assertThat(updateCaptor.getValue().getIsPrimary()).isFalse();

        ArgumentCaptor<FactoryLevelCapability> insertCaptor = ArgumentCaptor.forClass(FactoryLevelCapability.class);
        verify(capabilityMapper).insert(insertCaptor.capture());
        assertThat(insertCaptor.getValue().getLevelCode()).isEqualTo("A");
        assertThat(insertCaptor.getValue().getIsPrimary()).isTrue();
    }

    @Test
    void updateCapableLevels_shouldKeepPrimaryLevel() {
        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");
        factory.setFactoryLevel("S");

        when(factoryMasterMapper.selectById("F001")).thenReturn(factory);
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());

        FactoryLevelCapabilityUpdateRequest request = new FactoryLevelCapabilityUpdateRequest();
        request.setCapableLevels(List.of("A"));

        factoryService.updateCapableLevels("F001", request);

        verify(capabilityMapper).delete(any());
        ArgumentCaptor<FactoryLevelCapability> captor = ArgumentCaptor.forClass(FactoryLevelCapability.class);
        verify(capabilityMapper, times(2)).insert(captor.capture());
        List<FactoryLevelCapability> capabilities = captor.getAllValues();
        assertThat(capabilities)
            .anyMatch(c -> "S".equals(c.getLevelCode()) && Boolean.TRUE.equals(c.getIsPrimary()))
            .anyMatch(c -> "A".equals(c.getLevelCode()) && Boolean.FALSE.equals(c.getIsPrimary()));
    }

    @Test
    void getFactoryCapableLevels_shouldFallbackToPrimaryLevelForLegacyData() {
        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryLevel("S");

        when(factoryMasterMapper.selectById("F001")).thenReturn(factory);
        when(capabilityMapper.selectList(any())).thenReturn(List.of());

        List<String> result = factoryService.getFactoryCapableLevels("F001");

        assertThat(result).containsExactly("S");
    }

    @Test
    void getFactoryCapableLevels_shouldReturnEmptyListWhenFactoryNotFound() {
        when(factoryMasterMapper.selectById("F999")).thenReturn(null);

        List<String> result = factoryService.getFactoryCapableLevels("F999");

        assertThat(result).isEmpty();
        verifyNoInteractions(capabilityMapper);
    }

    @Test
    void getFactoryCapableLevels_shouldReturnEmptyListWhenFactoryCodeBlank() {
        List<String> result = factoryService.getFactoryCapableLevels("");

        assertThat(result).isEmpty();
        verifyNoInteractions(factoryMasterMapper);
        verifyNoInteractions(capabilityMapper);
    }

    @Test
    void extendCapability_shouldInsertPrimaryLevelFirstForLegacyFactory() {
        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryLevel("S");

        when(factoryMasterMapper.selectById("F001")).thenReturn(factory);
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(capabilityMapper.selectOne(any())).thenReturn(null);
        when(capabilityMapper.selectCount(any())).thenReturn(0L);

        factoryService.extendCapability("F001", "A");

        ArgumentCaptor<FactoryLevelCapability> captor = ArgumentCaptor.forClass(FactoryLevelCapability.class);
        verify(capabilityMapper, times(2)).insert(captor.capture());
        List<FactoryLevelCapability> capabilities = captor.getAllValues();
        assertThat(capabilities)
            .anyMatch(c -> "S".equals(c.getLevelCode()) && Boolean.TRUE.equals(c.getIsPrimary()))
            .anyMatch(c -> "A".equals(c.getLevelCode()) && Boolean.FALSE.equals(c.getIsPrimary()));
    }

    @Test
    void listFactories_shouldReturnCapabilitiesInBusinessOrder() {
        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");
        factory.setFactoryLevel("A");
        factory.setStatus("active");

        FactoryLevelCapability c = new FactoryLevelCapability();
        c.setFactoryCode("F001");
        c.setLevelCode("C");
        FactoryLevelCapability s = new FactoryLevelCapability();
        s.setFactoryCode("F001");
        s.setLevelCode("S");
        FactoryLevelCapability a = new FactoryLevelCapability();
        a.setFactoryCode("F001");
        a.setLevelCode("A");
        a.setIsPrimary(true);

        when(factoryMasterMapper.selectList(any())).thenReturn(List.of(factory));
        when(capabilityMapper.selectList(any())).thenReturn(List.of(c, s, a));

        List<FactoryResponse> result = factoryService.listFactories();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCapableLevels()).containsExactly("S", "A", "C");
    }

    @Test
    void createFactory_withExtendedFields_shouldPersistAllFields() {
        FactoryCreateRequest request = new FactoryCreateRequest();
        request.setFactoryCode("F001");
        request.setFactoryName("测试工厂");
        request.setFactoryLevel("S");
        request.setFactoryArea(new BigDecimal("5000.50"));
        request.setEmployeeCount(120);
        request.setMonthlyCapacity(3000);
        request.setFoundedYear(2010);
        request.setEquipmentList("[\"CNC\",\"CUTTING\"]");
        request.setFrameWood("OAK");
        request.setSpongeSupplier("东亚海绵");
        request.setQcItems("[\"INCOMING\",\"FINAL\"]");
        request.setQcStaffCount(8);
        request.setLogisticsMethods("[\"SPECIAL\",\"SF\"]");
        request.setDefaultPackaging("[\"CARTON\"]");
        request.setAuditorSignature("张三");
        request.setFactoryImages("[\"img/1.jpg\"]");

        when(factoryMasterMapper.selectById("F001")).thenReturn(null);
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());

        factoryService.createFactory(request);

        ArgumentCaptor<FactoryMaster> captor = ArgumentCaptor.forClass(FactoryMaster.class);
        verify(factoryMasterMapper, times(1)).insert(captor.capture());
        FactoryMaster saved = captor.getValue();
        assertThat(saved.getFactoryArea()).isEqualByComparingTo("5000.50");
        assertThat(saved.getEmployeeCount()).isEqualTo(120);
        assertThat(saved.getMonthlyCapacity()).isEqualTo(3000);
        assertThat(saved.getFoundedYear()).isEqualTo(2010);
        assertThat(saved.getEquipmentList()).isEqualTo("[\"CNC\",\"CUTTING\"]");
        assertThat(saved.getFrameWood()).isEqualTo("OAK");
        assertThat(saved.getQcStaffCount()).isEqualTo(8);
        assertThat(saved.getAuditorSignature()).isEqualTo("张三");
    }

    @Test
    void updateFactory_shouldUpdateNonNullFieldsAndIgnoreNullFields() {
        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("旧名称");
        factory.setFactoryLevel("S");
        factory.setRegion("广东");
        factory.setEmployeeCount(50);

        when(factoryMasterMapper.selectById("F001")).thenReturn(factory);

        FactoryUpdateRequest request = new FactoryUpdateRequest();
        request.setFactoryName("新名称");
        request.setFactoryArea(new BigDecimal("8000"));
        request.setQcStaffCount(10);
        // region 和 employeeCount 不传，应保持原值

        factoryService.updateFactory("F001", request);

        assertThat(factory.getFactoryName()).isEqualTo("新名称");
        assertThat(factory.getFactoryArea()).isEqualByComparingTo("8000");
        assertThat(factory.getQcStaffCount()).isEqualTo(10);
        assertThat(factory.getRegion()).isEqualTo("广东");
        assertThat(factory.getEmployeeCount()).isEqualTo(50);
        verify(factoryMasterMapper).updateById(factory);
        verify(auditLogService).logUpdate(eq("factory_master"), eq("F001"), any(), any(), any());
    }

    @Test
    void updateFactory_shouldThrowWhenFactoryNotFound() {
        when(factoryMasterMapper.selectById("F999")).thenReturn(null);

        FactoryUpdateRequest request = new FactoryUpdateRequest();
        request.setFactoryName("新名称");

        assertThatThrownBy(() -> factoryService.updateFactory("F999", request))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("工厂不存在");
    }

    @Test
    void updateFactory_shouldRejectNegativeEmployeeCount() {
        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");
        factory.setFactoryLevel("S");

        when(factoryMasterMapper.selectById("F001")).thenReturn(factory);

        FactoryUpdateRequest request = new FactoryUpdateRequest();
        request.setEmployeeCount(-1);

        assertThatThrownBy(() -> factoryService.updateFactory("F001", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("员工人数不能为负数");
    }
}
