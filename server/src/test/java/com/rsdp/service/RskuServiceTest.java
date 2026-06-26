package com.rsdp.service;

import com.rsdp.dto.request.RskuCreateRequest;
import com.rsdp.dto.response.RskuResponse;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * {@link RskuService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RskuServiceTest {

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private FactoryMasterMapper factoryMasterMapper;

    @InjectMocks
    private RskuService rskuService;

    @Test
    void listByRspu_shouldReturnRskuList() {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-TEST01");
        rsku.setRspuId("RSPU-TEST01");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));

        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(rsku));
        when(factoryMasterMapper.selectById("F001")).thenReturn(null);

        List<RskuResponse> result = rskuService.listByRspu("RSPU-TEST01");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRskuId()).isEqualTo("RSKU-TEST01");
    }

    @Test
    void createRsku_shouldInsertWhenValid() {
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setFactoryPrice(new BigDecimal("2500"));

        when(rspuMapper.selectById("RSPU-TEST01")).thenReturn(new RspuMaster());
        when(factoryMasterMapper.selectById("F001")).thenReturn(new FactoryMaster());
        when(rskuSupplyMapper.selectCount(any())).thenReturn(0L);

        rskuService.createRsku(request);

        verify(rskuSupplyMapper, times(1)).insert(any(RskuSupply.class));
    }

    @Test
    void createRsku_shouldThrowWhenDuplicate() {
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setFactoryPrice(new BigDecimal("2500"));

        when(rspuMapper.selectById("RSPU-TEST01")).thenReturn(new RspuMaster());
        when(factoryMasterMapper.selectById("F001")).thenReturn(new FactoryMaster());
        when(rskuSupplyMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> rskuService.createRsku(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已有报价");
    }
}
