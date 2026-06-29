package com.rsdp.service;

import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.ImageAssetsMapper;
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
import static org.mockito.Mockito.when;

/**
 * {@link QuoteService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class QuoteServiceTest {

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private FactoryMasterMapper factoryMasterMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @InjectMocks
    private QuoteService quoteService;

    @Test
    void generateQuote_shouldReturnQuoteWithSummary() {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setRspuId("RSPU-001");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));
        rsku.setLeadTimeDays(25);
        rsku.setMoq(10);

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("中古风");

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        when(rskuSupplyMapper.selectById("RSKU-001")).thenReturn(rsku);
        when(rspuMapper.selectById("RSPU-001")).thenReturn(rspu);
        when(factoryMasterMapper.selectById("F001")).thenReturn(factory);
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of(new ImageAssets()));

        var response = quoteService.generateQuote(List.of("RSKU-001"));

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getSummary().getTotalPrice()).isEqualByComparingTo(new BigDecimal("2500"));
        assertThat(response.getSummary().getItemCount()).isEqualTo(1);
        assertThat(response.getSummary().getFactoryCount()).isEqualTo(1);
        assertThat(response.getSummary().getMaxLeadTimeDays()).isEqualTo(25);
    }

    @Test
    void generateQuote_shouldRejectEmptyList() {
        assertThatThrownBy(() -> quoteService.generateQuote(List.of()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("至少一个");
    }

    @Test
    void generateQuote_shouldThrowWhenRskuNotFound() {
        when(rskuSupplyMapper.selectById("RSKU-NOT-FOUND")).thenReturn(null);

        assertThatThrownBy(() -> quoteService.generateQuote(List.of("RSKU-NOT-FOUND")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已失效或不存在")
            .hasMessageContaining("RSKU-NOT-FOUND");
    }

    @Test
    void generateQuote_shouldCollectAllInvalidRskuIds() {
        when(rskuSupplyMapper.selectById("RSKU-A")).thenReturn(null);

        RskuSupply deletedRsku = new RskuSupply();
        deletedRsku.setRskuId("RSKU-B");
        deletedRsku.setDeletedAt(java.time.LocalDateTime.now());
        when(rskuSupplyMapper.selectById("RSKU-B")).thenReturn(deletedRsku);

        assertThatThrownBy(() -> quoteService.generateQuote(List.of("RSKU-A", "RSKU-B")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("RSKU-A")
            .hasMessageContaining("RSKU-B");
    }
}
