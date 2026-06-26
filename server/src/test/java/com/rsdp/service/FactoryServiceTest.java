package com.rsdp.service;

import com.rsdp.dto.request.FactoryCreateRequest;
import com.rsdp.dto.response.FactoryResponse;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FactoryMasterMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    private AuditLogService auditLogService;

    @InjectMocks
    private FactoryService factoryService;

    @Test
    void listFactories_shouldReturnActiveFactories() {
        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");
        factory.setStatus("active");

        when(factoryMasterMapper.selectList(any())).thenReturn(List.of(factory));

        List<FactoryResponse> result = factoryService.listFactories();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFactoryCode()).isEqualTo("F001");
    }

    @Test
    void createFactory_shouldInsertWhenCodeNotExists() {
        FactoryCreateRequest request = new FactoryCreateRequest();
        request.setFactoryCode("F001");
        request.setFactoryName("测试工厂");
        request.setFactoryLevel("A");

        when(factoryMasterMapper.selectById("F001")).thenReturn(null);

        factoryService.createFactory(request);

        verify(factoryMasterMapper, times(1)).insert(any(FactoryMaster.class));
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
}
