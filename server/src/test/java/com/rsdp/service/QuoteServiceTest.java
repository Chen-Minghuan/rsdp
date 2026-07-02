package com.rsdp.service;

import com.rsdp.dto.request.QuoteItemRequest;
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
import static org.mockito.ArgumentMatchers.eq;
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

    @Mock
    private FactoryService factoryService;

    @InjectMocks
    private QuoteService quoteService;

    @Test
    void generateQuote_shouldReturnQuoteWithSummary() {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setRspuId("RSPU-001");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));
        rsku.setProductLevel("S");
        rsku.setLeadTimeDays(25);
        rsku.setMoq(10);

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("中古风");

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        ImageAssets image = new ImageAssets();
        image.setImageId("IMG-001");
        image.setRspuId("RSPU-001");

        when(rskuSupplyMapper.selectBatchIds(List.of("RSKU-001"))).thenReturn(List.of(rsku));
        when(rspuMapper.selectBatchIds(List.of("RSPU-001"))).thenReturn(List.of(rspu));
        when(factoryMasterMapper.selectBatchIds(List.of("F001"))).thenReturn(List.of(factory));
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("S", "A"));
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of(image));

        var response = quoteService.generateQuote(List.of(req("RSKU-001", 2)));

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(2);
        assertThat(response.getItems().get(0).getSubtotal()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(response.getSummary().getTotalPrice()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(response.getSummary().getItemCount()).isEqualTo(1);
        assertThat(response.getSummary().getTotalQuantity()).isEqualTo(2);
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
        when(rskuSupplyMapper.selectBatchIds(List.of("RSKU-NOT-FOUND"))).thenReturn(List.of());

        assertThatThrownBy(() -> quoteService.generateQuote(List.of(req("RSKU-NOT-FOUND", 1))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已失效或不存在")
            .hasMessageContaining("RSKU-NOT-FOUND");
    }

    @Test
    void generateQuote_shouldCollectAllInvalidRskuIds() {
        RskuSupply deletedRsku = new RskuSupply();
        deletedRsku.setRskuId("RSKU-B");
        deletedRsku.setDeletedAt(java.time.LocalDateTime.now());
        when(rskuSupplyMapper.selectBatchIds(List.of("RSKU-A", "RSKU-B"))).thenReturn(List.of(deletedRsku));

        assertThatThrownBy(() -> quoteService.generateQuote(List.of(req("RSKU-A", 1), req("RSKU-B", 1))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("RSKU-A")
            .hasMessageContaining("RSKU-B");
    }

    @Test
    void generateQuote_shouldRejectRskuWhenFactoryNotCapable() {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setRspuId("RSPU-001");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));
        rsku.setProductLevel("S");

        when(rskuSupplyMapper.selectBatchIds(List.of("RSKU-001"))).thenReturn(List.of(rsku));
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("A", "B"));

        assertThatThrownBy(() -> quoteService.generateQuote(List.of(req("RSKU-001", 1))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("RSKU-001")
            .hasMessageContaining("未声明");
    }

    @Test
    void generateQuote_shouldMergeDuplicateRskuQuantities() {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setRspuId("RSPU-001");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));
        rsku.setProductLevel("S");

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("中古风");

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        when(rskuSupplyMapper.selectBatchIds(List.of("RSKU-001"))).thenReturn(List.of(rsku));
        when(rspuMapper.selectBatchIds(List.of("RSPU-001"))).thenReturn(List.of(rspu));
        when(factoryMasterMapper.selectBatchIds(List.of("F001"))).thenReturn(List.of(factory));
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("S", "A"));
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());

        var response = quoteService.generateQuote(List.of(req("RSKU-001", 2), req("RSKU-001", 3)));

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(5);
        assertThat(response.getItems().get(0).getSubtotal()).isEqualByComparingTo(new BigDecimal("12500"));
        assertThat(response.getSummary().getTotalQuantity()).isEqualTo(5);
    }

    @Test
    void generateQuote_shouldDefaultNullOrZeroQuantityToOne() {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setRspuId("RSPU-001");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));
        rsku.setProductLevel("S");

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("中古风");

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        when(rskuSupplyMapper.selectBatchIds(List.of("RSKU-001"))).thenReturn(List.of(rsku));
        when(rspuMapper.selectBatchIds(List.of("RSPU-001"))).thenReturn(List.of(rspu));
        when(factoryMasterMapper.selectBatchIds(List.of("F001"))).thenReturn(List.of(factory));
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("S", "A"));
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());

        QuoteItemRequest nullQty = new QuoteItemRequest();
        nullQty.setRskuId("RSKU-001");
        nullQty.setQuantity(null);
        QuoteItemRequest zeroQty = new QuoteItemRequest();
        zeroQty.setRskuId("RSKU-001");
        zeroQty.setQuantity(0);

        var response = quoteService.generateQuote(List.of(nullQty, zeroQty));

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(2);
        assertThat(response.getItems().get(0).getSubtotal()).isEqualByComparingTo(new BigDecimal("5000"));
    }

    private QuoteItemRequest req(String rskuId, int quantity) {
        QuoteItemRequest r = new QuoteItemRequest();
        r.setRskuId(rskuId);
        r.setQuantity(quantity);
        return r;
    }
}
