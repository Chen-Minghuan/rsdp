package com.rsdp.service;

import com.rsdp.dto.request.RskuCreateRequest;
import com.rsdp.dto.request.RskuPriceUpdateRequest;
import com.rsdp.dto.response.RskuResponse;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.PriceHistory;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuVariant;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.PriceHistoryMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

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

    @Mock
    private RspuVariantService rspuVariantService;

    @Mock
    private PriceHistoryMapper priceHistoryMapper;

    @Mock
    private AuditLogService auditLogService;

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
        request.setVariantId("RSPU-TEST01-V001");
        request.setFactoryPrice(new BigDecimal("2500"));

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("RSPU-TEST01-V001");
        variant.setRspuId("RSPU-TEST01");

        when(rspuMapper.selectById("RSPU-TEST01")).thenReturn(new RspuMaster());
        when(factoryMasterMapper.selectById("F001")).thenReturn(new FactoryMaster());
        when(rspuVariantService.findById("RSPU-TEST01-V001")).thenReturn(variant);

        rskuService.createRsku(request);

        verify(rskuSupplyMapper, times(1)).insert(any(RskuSupply.class));
    }

    @Test
    void createRsku_shouldThrowWhenVariantNotBelongToRspu() {
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setVariantId("RSPU-OTHER-V001");
        request.setFactoryPrice(new BigDecimal("2500"));

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("RSPU-OTHER-V001");
        variant.setRspuId("RSPU-OTHER");

        when(rspuMapper.selectById("RSPU-TEST01")).thenReturn(new RspuMaster());
        when(factoryMasterMapper.selectById("F001")).thenReturn(new FactoryMaster());
        when(rspuVariantService.findById("RSPU-OTHER-V001")).thenReturn(variant);

        assertThatThrownBy(() -> rskuService.createRsku(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("变体不属于该产品");
    }

    @Test
    void createRsku_shouldThrowWhenDuplicate() {
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setVariantId("RSPU-TEST01-V001");
        request.setFactoryPrice(new BigDecimal("2500"));

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("RSPU-TEST01-V001");
        variant.setRspuId("RSPU-TEST01");

        when(rspuMapper.selectById("RSPU-TEST01")).thenReturn(new RspuMaster());
        when(factoryMasterMapper.selectById("F001")).thenReturn(new FactoryMaster());
        when(rspuVariantService.findById("RSPU-TEST01-V001")).thenReturn(variant);
        doThrow(new DataIntegrityViolationException("duplicate")).when(rskuSupplyMapper).insert(any(RskuSupply.class));

        assertThatThrownBy(() -> rskuService.createRsku(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已有报价");
    }

    @Test
    void getRsku_shouldReturnDetailWhenValid() {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-TEST01");
        rsku.setRspuId("RSPU-TEST01");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));

        when(rskuSupplyMapper.selectById("RSKU-TEST01")).thenReturn(rsku);
        when(factoryMasterMapper.selectById("F001")).thenReturn(null);

        RskuResponse result = rskuService.getRsku("RSPU-TEST01", "RSKU-TEST01");

        assertThat(result.getRskuId()).isEqualTo("RSKU-TEST01");
    }

    @Test
    void getRsku_shouldThrowWhenRspuMismatch() {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-TEST01");
        rsku.setRspuId("RSPU-OTHER");

        when(rskuSupplyMapper.selectById("RSKU-TEST01")).thenReturn(rsku);

        assertThatThrownBy(() -> rskuService.getRsku("RSPU-TEST01", "RSKU-TEST01"))
            .isInstanceOf(com.rsdp.exception.ResourceNotFoundException.class)
            .hasMessageContaining("不属于");
    }

    @Test
    void listByFactory_shouldReturnRskuList() {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-TEST01");
        rsku.setRspuId("RSPU-TEST01");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));

        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(rsku));
        when(factoryMasterMapper.selectById("F001")).thenReturn(null);

        List<RskuResponse> result = rskuService.listByFactory("F001");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRskuId()).isEqualTo("RSKU-TEST01");
    }

    @Test
    void updateRskuPrice_shouldUpdatePriceAndRecordHistory() {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-TEST01");
        rsku.setRspuId("RSPU-TEST01");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("1200"));

        when(rskuSupplyMapper.selectById("RSKU-TEST01")).thenReturn(rsku);

        rskuService.updateRskuPrice("RSKU-TEST01", new BigDecimal("1500"), "原材料涨价");

        ArgumentCaptor<RskuSupply> rskuCaptor = ArgumentCaptor.forClass(RskuSupply.class);
        verify(rskuSupplyMapper).updateById(rskuCaptor.capture());
        assertThat(rskuCaptor.getValue().getFactoryPrice()).isEqualTo(new BigDecimal("1500"));

        ArgumentCaptor<PriceHistory> historyCaptor = ArgumentCaptor.forClass(PriceHistory.class);
        verify(priceHistoryMapper).insert(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getOldPrice()).isEqualTo(new BigDecimal("1200"));
        assertThat(historyCaptor.getValue().getNewPrice()).isEqualTo(new BigDecimal("1500"));
        assertThat(historyCaptor.getValue().getChangeReason()).isEqualTo("原材料涨价");
    }
}
